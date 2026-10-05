import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api'

/*
 * The Team screen with the CardBox link on. The team lives on CardBox (cardbox.club); /api/cardbox/team/* reads and
 * changes the open store's team as the signed-in person, and CardBox decides what they may do. It only ever shows
 * people on this store's team: new people join by email invite and accept on cardbox.club with a new or existing
 * CardBox account (it doesn't have to use the invited email), which is then their login here too.
 */

type StoreRole = 'store_manager' | 'store_employee'
interface Member {
  user_id: string; email: string; display_name: string; avatar_url: string; title: string
  role: StoreRole; role_label: string; status: 'active' | 'disabled'
  joined_at: string | null; added_by: string | null; last_login_at: string | null; invited_email: string
  is_self: boolean; can_edit: boolean; can_change_role: boolean; can_disable: boolean; can_remove: boolean
}
interface Invite {
  id: string; email: string; name: string; role: StoreRole; role_label: string
  status: 'pending' | 'expired'; invited_by: string | null; created_at: string; expires_at: string
  last_sent_at: string | null; sent_count: number; email_status: 'pending' | 'sending' | 'sent' | 'failed' | 'unavailable'
  can_manage: boolean
}
interface TeamEvent { id: string; action: string; actor: string; target: string | null; role_label: string | null; detail: string; created_at: string }
interface Team {
  store: { id: string; name: string }
  assignable_roles: { role: StoreRole; label: string }[]
  members: Member[]; invites: Invite[]; events: TeamEvent[]
  invite_lifetime_days: number; email_enabled: boolean
}
interface CatalogRole { role: string; label: string; description: string }
interface InviteResult { invite: Invite; invite_url: string }

const ROLE_HELP: Record<StoreRole, string> = {
  store_manager: 'Runs the store: trades, inventory, settings and the team.',
  store_employee: 'Works the counter: trades and inventory.',
}

const day = (iso: string | null) => iso ? new Date(iso).toLocaleDateString([], { dateStyle: 'medium' }) : '—'

function ago(iso: string | null) {
  if (!iso) return 'Never'
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60000)
  if (minutes < 2) return 'Just now'
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} h ago`
  const days = Math.round(hours / 24)
  return days < 30 ? `${days} d ago` : day(iso)
}

function initials(name: string) {
  const parts = name.replace(/@.*/, '').split(/[\s._-]+/).filter(Boolean)
  return ((parts[0]?.[0] ?? '?') + (parts[1]?.[0] ?? '')).toUpperCase()
}

const VERBS: Record<string, (e: TeamEvent) => string> = {
  role_granted: e => `gave ${e.target} ${e.role_label}`,
  role_removed: e => `removed ${e.role_label} from ${e.target}`,
  invite_sent: e => `invited ${e.target} as ${e.role_label}`,
  invite_resent: e => `re-sent the invite to ${e.target}`,
  invite_revoked: e => `withdrew the invite for ${e.target}`,
  invite_accepted: e => `joined as ${e.role_label}`,
  member_role_changed: e => `made ${e.target} ${e.role_label}`,
  member_disabled: e => `disabled ${e.target}`,
  member_enabled: e => `re-enabled ${e.target}`,
  member_removed: e => `removed ${e.target} from the team`,
  member_updated: e => `updated ${e.target}`,
}

function eventText(e: TeamEvent) {
  return `${e.actor} ${(VERBS[e.action] ?? (x => x.action.replace(/_/g, ' ')))(e)}`
}

/** A native modal dialog: focus is trapped, Escape closes it, and the page behind is inert. */
function Dialog({ title, onClose, children, wide }: { title: string; onClose: () => void; children: React.ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = ref.current
    if (dialog && !dialog.open) dialog.showModal()
    return () => dialog?.close()
  }, [])
  return (
    <dialog ref={ref} className={`modal${wide ? ' wide' : ''}`} onCancel={e => { e.preventDefault(); onClose() }}
      onClick={e => { if (e.target === ref.current) onClose() }} aria-labelledby="modal-title">
      <div className="modal-head">
        <h2 id="modal-title">{title}</h2>
        <button type="button" className="icon-button" aria-label="Close" onClick={onClose}>✕</button>
      </div>
      {children}
    </dialog>
  )
}

/** A row's "more actions" menu. */
function RowMenu({ label, items }: { label: string; items: { label: string; danger?: boolean; onSelect: () => void }[] }) {
  // Fixed to the viewport, so the table's scroll box never clips it; it opens upward near the bottom of the screen.
  const [open, setOpen] = useState<React.CSSProperties | null>(null)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const close = (e: Event) => {
      if (e instanceof KeyboardEvent ? e.key === 'Escape' : e.type !== 'mousedown' || !ref.current?.contains(e.target as Node)) setOpen(null)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', close)
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    return () => {
      document.removeEventListener('mousedown', close); document.removeEventListener('keydown', close)
      window.removeEventListener('scroll', close, true); window.removeEventListener('resize', close)
    }
  }, [open])
  function toggle(button: HTMLButtonElement) {
    if (open) { setOpen(null); return }
    const r = button.getBoundingClientRect()
    const right = window.innerWidth - r.right
    setOpen(r.bottom + 160 > window.innerHeight ? { bottom: window.innerHeight - r.top + 4, right } : { top: r.bottom + 4, right })
  }
  if (items.length === 0) return null
  return (
    <div className="menu" ref={ref}>
      <button type="button" className="icon-button menu-button" aria-label={label} aria-haspopup="menu" aria-expanded={open !== null}
        onClick={e => toggle(e.currentTarget)}>⋯</button>
      {open && (
        <ul role="menu" className="menu-list" style={open}>
          {items.map(item => (
            <li key={item.label} role="none">
              <button type="button" role="menuitem" className={item.danger ? 'danger' : ''}
                onClick={() => { setOpen(null); item.onSelect() }}>{item.label}</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function CopyLink({ url }: { url: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <div className="copy-link">
      <input readOnly value={url} aria-label="Invite link" onFocus={e => e.target.select()} />
      <button type="button" className="small secondary" onClick={() => navigator.clipboard?.writeText(url).then(() => setCopied(true))}>
        {copied ? 'Copied' : 'Copy link'}
      </button>
    </div>
  )
}

type Modal =
  | { kind: 'invite' }
  | { kind: 'sent'; results: { email: string; url?: string; error?: string }[] }
  | { kind: 'edit'; member: Member }
  | { kind: 'remove'; member: Member }
  | { kind: 'link'; email: string; url: string }

export function TeamAdmin({ onChange, store }: { onChange: () => Promise<void>; store?: string }) {
  const [team, setTeam] = useState<Team | null>(null)
  const [catalog, setCatalog] = useState<CatalogRole[]>([])
  const [tab, setTab] = useState<'members' | 'invites' | 'activity'>('members')
  const [filter, setFilter] = useState('')
  const [show, setShow] = useState<'all' | 'active' | 'disabled' | StoreRole>('all')
  const [modal, setModal] = useState<Modal | null>(null)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const query = store ? `?store=${encodeURIComponent(store)}` : ''

  const load = useCallback(() => api<Team>(`/api/cardbox/team${query}`).then(setTeam), [query])
  useEffect(() => {
    load().catch(e => setError(e.message))
    api<{ roles: CatalogRole[] }>('/api/cardbox/role-catalog').then(r => setCatalog(r.roles)).catch(() => setCatalog([]))
  }, [load])

  async function run<T>(request: Promise<T>, done: string, self = false): Promise<T | null> {
    try {
      const result = await request
      await load()
      setMessage(done); setError('')
      if (self) await onChange()
      return result
    } catch (e) { setError((e as Error).message); setMessage(''); return null }
  }

  const members = team?.members ?? []
  const invites = team?.invites ?? []
  const counts = {
    active: members.filter(m => m.status === 'active').length,
    managers: members.filter(m => m.status === 'active' && m.role === 'store_manager').length,
    disabled: members.filter(m => m.status === 'disabled').length,
  }
  const canInvite = (team?.assignable_roles.length ?? 0) > 0
  const term = filter.trim().toLowerCase()
  const shown = members.filter(m =>
    (show === 'all' || m.status === show || m.role === show)
    && (!term || [m.display_name, m.email, m.title, m.invited_email].some(v => v?.toLowerCase().includes(term))))

  const memberPath = (m: Member) => `/api/cardbox/team/members/${encodeURIComponent(m.user_id)}${query}`
  const setDisabled = (m: Member, disabled: boolean) => run(api(memberPath(m), { method: 'PATCH', body: { disabled } }),
    disabled ? `${m.display_name} is disabled and can no longer sign in to this store.` : `${m.display_name} can sign in to this store again.`)
  const resend = async (i: Invite) => {
    const result = await run(api<InviteResult>(`/api/cardbox/team/invites/${encodeURIComponent(i.id)}/resend${query}`, { method: 'POST', body: {} }),
      team?.email_enabled ? `Invite re-sent to ${i.email}. The old link no longer works.` : `New invite link made for ${i.email}.`)
    if (result) setModal({ kind: 'link', email: i.email, url: result.invite_url })
  }
  const revoke = (i: Invite) => {
    if (confirm(`Withdraw the invite for ${i.email}? Its link will stop working.`))
      run(api(`/api/cardbox/team/invites/${encodeURIComponent(i.id)}${query}`, { method: 'DELETE', body: {} }), `Invite for ${i.email} withdrawn.`)
  }

  if (!team) return <>{error ? <p className="error">{error}</p> : <p className="muted">Loading the team…</p>}</>

  return (
    <div className="team">
      <div className="page-head">
        <div>
          <h1>Team</h1>
          <p className="lede">The people who work at <strong>{team.store.name}</strong>. Everyone signs in with their own CardBox account.</p>
        </div>
        {canInvite && <button type="button" onClick={() => setModal({ kind: 'invite' })}>Invite people</button>}
      </div>

      {error && <p className="error" role="alert">{error}</p>}
      {message && <p className="notice" role="status">{message}</p>}

      <div className="stats">
        <div className="stat"><span>Active members</span><strong>{counts.active}</strong></div>
        <div className="stat"><span>Managers</span><strong>{counts.managers}</strong></div>
        <div className="stat"><span>Pending invites</span><strong>{invites.filter(i => i.status === 'pending').length}</strong></div>
        <div className="stat"><span>Disabled</span><strong>{counts.disabled}</strong></div>
      </div>

      <div className="seg wide team-tabs" role="tablist" aria-label="Team sections">
        {([['members', `Members (${members.length})`], ['invites', `Invitations (${invites.length})`], ['activity', 'Activity']] as const).map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={tab === key} aria-pressed={tab === key} onClick={() => setTab(key)}>{label}</button>
        ))}
      </div>

      {tab === 'members' && (
        <>
          <div className="toolbar" style={{ marginTop: 14 }}>
            <input placeholder="Search by name, email or title" aria-label="Search the team" value={filter} onChange={e => setFilter(e.target.value)} />
            <select aria-label="Show" value={show} onChange={e => setShow(e.target.value as typeof show)}>
              <option value="all">Everyone</option>
              <option value="active">Active</option>
              <option value="disabled">Disabled</option>
              <option value="store_manager">Managers</option>
              <option value="store_employee">Employees</option>
            </select>
          </div>
          <div className="table-wrap"><table className="grid team-grid">
            <thead><tr><th>Member</th><th>Role</th><th>Status</th><th>Last signed in</th><th>Joined</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>{shown.map(m => {
              const items = [
                ...(m.can_edit ? [{ label: m.can_change_role ? 'Edit details and role' : 'Edit details', onSelect: () => setModal({ kind: 'edit', member: m }) }] : []),
                ...(m.can_disable ? [m.status === 'active'
                  ? { label: 'Disable access', onSelect: () => { if (confirm(`Disable ${m.display_name}? They stay on the team but can't sign in to ${team.store.name} until you re-enable them.`)) setDisabled(m, true) } }
                  : { label: 'Re-enable access', onSelect: () => setDisabled(m, false) }] : []),
                ...(m.can_remove ? [{ label: 'Remove from team', danger: true, onSelect: () => setModal({ kind: 'remove', member: m }) }] : []),
              ]
              return (
                <tr key={m.user_id} className={m.status === 'disabled' ? 'removed' : ''}>
                  <td>
                    <div className="person">
                      {m.avatar_url ? <img className="avatar" src={m.avatar_url} alt="" /> : <span className="avatar" aria-hidden="true">{initials(m.display_name)}</span>}
                      <div>
                        <strong>{m.display_name}</strong>{m.is_self && <span className="muted"> (you)</span>}
                        <div className="muted small">{m.email}{m.title && <> · {m.title}</>}</div>
                        {m.invited_email && m.invited_email.toLowerCase() !== m.email.toLowerCase() &&
                          <div className="muted small">Invited as {m.invited_email}</div>}
                      </div>
                    </div>
                  </td>
                  <td><span className={`badge ${m.role === 'store_manager' ? 'accent' : ''}`}>{m.role_label}</span></td>
                  <td><span className={`badge ${m.status === 'active' ? 'ok' : 'off'}`}>{m.status === 'active' ? 'Active' : 'Disabled'}</span></td>
                  <td className="small">{ago(m.last_login_at)}</td>
                  <td className="small">{day(m.joined_at)}{m.added_by && <div className="muted small">by {m.added_by}</div>}</td>
                  <td className="r"><RowMenu label={`Actions for ${m.display_name}`} items={items} /></td>
                </tr>
              )
            })}</tbody>
          </table></div>
          {members.length > 0 && shown.length === 0 && <p className="empty">No one matches.</p>}
          {members.length === 0 && <p className="empty">No one is on this team yet. Invite your first team member.</p>}
        </>
      )}

      {tab === 'invites' && (
        invites.length === 0
          ? <p className="empty">No open invitations.{canInvite && <> <button type="button" className="link" onClick={() => setModal({ kind: 'invite' })}>Invite someone</button></>}</p>
          : <div className="table-wrap" style={{ marginTop: 14 }}><table className="grid">
            <thead><tr><th>Invited</th><th>Role</th><th>Status</th><th>Sent</th><th>Expires</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>{invites.map(i => (
              <tr key={i.id}>
                <td><strong>{i.name || i.email}</strong>{i.name && <div className="muted small">{i.email}</div>}
                  {i.invited_by && <div className="muted small">by {i.invited_by}</div>}</td>
                <td><span className="badge">{i.role_label}</span></td>
                <td>
                  {i.status === 'expired' ? <span className="badge off">Expired</span>
                    : i.email_status === 'failed' ? <span className="badge warn" title="The email couldn't be delivered. Resend it, or copy the link and send it yourself.">Email failed</span>
                    : i.email_status === 'unavailable' ? <span className="badge warn" title="Email sending is off. Share the link yourself.">Share the link</span>
                    : i.email_status === 'sent' ? <span className="badge ok">Emailed</span>
                    : <span className="badge">Sending</span>}
                </td>
                <td className="small">{ago(i.last_sent_at ?? i.created_at)}{i.sent_count > 1 && <div className="muted small">{i.sent_count} times</div>}</td>
                <td className="small">{day(i.expires_at)}</td>
                <td className="r">{i.can_manage && <div className="row-actions" style={{ justifyContent: 'flex-end' }}>
                  <button type="button" className="small secondary" onClick={() => resend(i)}>{i.status === 'expired' ? 'Renew' : 'Resend'}</button>
                  <button type="button" className="link" onClick={() => revoke(i)}>Withdraw</button>
                </div>}</td>
              </tr>
            ))}</tbody>
          </table></div>
      )}

      {tab === 'activity' && (
        team.events.length === 0 ? <p className="empty">No changes yet.</p> :
          <ul className="plain activity">{team.events.map(e => (
            <li key={e.id}><span>{eventText(e)}</span>
              {e.detail && <span className="muted small"> · {e.detail}</span>}
              <span className="muted small when">{new Date(e.created_at).toLocaleString()}</span></li>
          ))}</ul>
      )}

      {catalog.length > 0 && (
        <details style={{ marginTop: 24 }}>
          <summary>What each role can do</summary>
          <dl>{catalog.filter(c => c.role.startsWith('store_')).map(c => <div key={c.role}><dt><strong>{c.label}</strong></dt><dd className="muted">{c.description}</dd></div>)}</dl>
          <p className="muted small">Platform roles and everyone's CardBox accounts are managed on cardbox.club.</p>
        </details>
      )}

      {modal?.kind === 'invite' && <InviteDialog team={team} query={query} onClose={() => setModal(null)}
        onSent={async results => { await load().catch(() => undefined); setTab('invites'); setModal({ kind: 'sent', results }) }} />}
      {modal?.kind === 'sent' && (
        <Dialog title="Invitations" onClose={() => setModal(null)} wide>
          <ul className="plain sent-list">{modal.results.map(r => (
            <li key={r.email}>
              <strong>{r.email}</strong>
              {r.error ? <p className="error small">{r.error}</p> : <>
                <p className="muted small">{team.email_enabled ? 'Invite emailed. You can also share this link directly:' : 'Email sending is off, so share this link with them:'}</p>
                {r.url && <CopyLink url={r.url} />}
              </>}
            </li>
          ))}</ul>
          <div className="actions"><button type="button" onClick={() => setModal(null)}>Done</button></div>
        </Dialog>
      )}
      {modal?.kind === 'link' && (
        <Dialog title={`New link for ${modal.email}`} onClose={() => setModal(null)}>
          <p className="muted">{team.email_enabled ? 'We emailed this new link. Any earlier link has stopped working.' : 'Share this link with them. Any earlier link has stopped working.'}</p>
          <CopyLink url={modal.url} />
          <div className="actions"><button type="button" onClick={() => setModal(null)}>Done</button></div>
        </Dialog>
      )}
      {modal?.kind === 'edit' && <EditDialog member={modal.member} team={team} onClose={() => setModal(null)}
        onSave={async change => {
          const done = await run(api(memberPath(modal.member), { method: 'PATCH', body: change }), `${modal.member.display_name} updated.`, modal.member.is_self)
          if (done) setModal(null)
        }} />}
      {modal?.kind === 'remove' && (
        <Dialog title={`Remove ${modal.member.display_name}?`} onClose={() => setModal(null)}>
          <p>{modal.member.display_name} will lose access to {team.store.name} right away. Their CardBox account, collection and the
            trades they recorded stay as they are.</p>
          <p className="muted small">To pause access instead, use Disable access: they keep their place and you can turn it back on.</p>
          <div className="actions">
            <button type="button" className="danger" onClick={async () => {
              const done = await run(api(memberPath(modal.member), { method: 'DELETE', body: {} }), `${modal.member.display_name} was removed from the team.`)
              if (done) setModal(null)
            }}>Remove from team</button>
            <button type="button" className="secondary" onClick={() => setModal(null)}>Cancel</button>
          </div>
        </Dialog>
      )}
    </div>
  )
}

function InviteDialog({ team, query, onClose, onSent }: {
  team: Team; query: string; onClose: () => void; onSent: (results: { email: string; url?: string; error?: string }[]) => Promise<void>
}) {
  const [emails, setEmails] = useState('')
  const [name, setName] = useState('')
  const [role, setRole] = useState<StoreRole>(team.assignable_roles.some(r => r.role === 'store_employee') ? 'store_employee' : team.assignable_roles[0].role)
  const [busy, setBusy] = useState(false)
  const list = [...new Set(emails.split(/[\s,;]+/).map(e => e.trim().toLowerCase()).filter(Boolean))]
  const invalid = list.filter(e => !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e))

  async function send(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    const results: { email: string; url?: string; error?: string }[] = []
    for (const email of list) {
      try {
        const r = await api<InviteResult>(`/api/cardbox/team/invites${query}`, { method: 'POST', body: { email, role, name: list.length === 1 ? name.trim() : '' } })
        results.push({ email, url: r.invite_url })
      } catch (err) { results.push({ email, error: (err as Error).message }) }
    }
    setBusy(false)
    await onSent(results)
  }

  return (
    <Dialog title={`Invite people to ${team.store.name}`} onClose={onClose}>
      <form onSubmit={send}>
        <label>Email addresses
          <textarea required rows={3} value={emails} onChange={e => setEmails(e.target.value)} placeholder="sam@example.com, alex@example.com" />
          <span className="muted small hint">One or more, separated by commas or new lines.</span>
        </label>
        {invalid.length > 0 && <p className="error small">Check {invalid.join(', ')}.</p>}
        {list.length <= 1 && <label>Name (optional)<input value={name} maxLength={120} onChange={e => setName(e.target.value)} placeholder="Used in the invitation email" /></label>}
        <fieldset className="role-pick">
          <legend>Role</legend>
          {team.assignable_roles.map(r => (
            <label key={r.role} className={`role-option${role === r.role ? ' on' : ''}`}>
              <input type="radio" name="role" value={r.role} checked={role === r.role} onChange={() => setRole(r.role)} />
              <span><strong>{r.label}</strong><span className="muted small">{ROLE_HELP[r.role]}</span></span>
            </label>
          ))}
        </fieldset>
        <p className="muted small">They'll get an email with a link that works for {team.invite_lifetime_days} days. They can create a CardBox
          account or sign in with one they already have, even if it uses a different email. That account is how they sign in here.</p>
        <div className="actions">
          <button type="submit" disabled={busy || list.length === 0 || invalid.length > 0}>
            {busy ? 'Sending…' : list.length > 1 ? `Send ${list.length} invites` : 'Send invite'}
          </button>
          <button type="button" className="secondary" onClick={onClose}>Cancel</button>
        </div>
      </form>
    </Dialog>
  )
}

function EditDialog({ member, team, onClose, onSave }: {
  member: Member; team: Team; onClose: () => void; onSave: (change: { title?: string; role?: StoreRole }) => Promise<void>
}) {
  const [title, setTitle] = useState(member.title)
  const [role, setRole] = useState<StoreRole>(member.role)
  const [busy, setBusy] = useState(false)
  const change: { title?: string; role?: StoreRole } = {}
  if (title.trim() !== member.title) change.title = title.trim()
  if (role !== member.role) change.role = role
  return (
    <Dialog title={`Edit ${member.display_name}`} onClose={onClose}>
      <form onSubmit={async e => { e.preventDefault(); setBusy(true); await onSave(change); setBusy(false) }}>
        <p className="muted small">{member.email}. Their name and email come from their CardBox account, which they manage themselves.</p>
        <label>Job title<input value={title} maxLength={80} onChange={e => setTitle(e.target.value)} placeholder="e.g. Weekend lead" /></label>
        {member.can_change_role && (
          <fieldset className="role-pick">
            <legend>Role</legend>
            {team.assignable_roles.map(r => (
              <label key={r.role} className={`role-option${role === r.role ? ' on' : ''}`}>
                <input type="radio" name="edit-role" value={r.role} checked={role === r.role} onChange={() => setRole(r.role)} />
                <span><strong>{r.label}</strong><span className="muted small">{ROLE_HELP[r.role]}</span></span>
              </label>
            ))}
          </fieldset>
        )}
        <div className="actions">
          <button type="submit" disabled={busy || Object.keys(change).length === 0}>{busy ? 'Saving…' : 'Save changes'}</button>
          <button type="button" className="secondary" onClick={onClose}>Cancel</button>
        </div>
      </form>
    </Dialog>
  )
}

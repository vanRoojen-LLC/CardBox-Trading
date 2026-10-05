import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../api'
import { CardBoxStores } from './CardBoxStores'
import SupportReports from '../SupportReports'

interface AdminStore {
  id: string; name: string; planStatus: string; trialEndsAt: string; createdAt: string; entitled: boolean
  website: string; phone: string; contactEmail: string
  people: number; owners: string | null; locations: number; trades: number; lastTradeAt: string | null; cards: number
}
interface Membership {
  id: string; name: string; email: string; role: 'owner' | 'staff'; storeId: string; store: string
  joined: boolean; removed: boolean; createdAt: string; lastUsedAt: string | null
}

const PLANS = ['trial', 'active', 'past_due', 'canceled'] as const
const day = (iso: string | null) => iso ? new Date(iso).toLocaleDateString([], { dateStyle: 'medium' }) : '—'
const isoDay = (iso: string) => iso.slice(0, 10)

/** The platform owner's console: every store and every person, with the changes support needs. */
export default function Admin({ me, onChange }: { me: { email: string; cardbox: boolean }; onChange: () => Promise<void> }) {
  if (me.cardbox) return (
    <section>
      <h1>Platform admin</h1>
      <p className="lede">Every store on CardBox, with its Trading plan. Stores are created and renamed on CardBox for both sites.</p>
      <CardBoxStores />
      <p className="muted small" style={{ marginTop: 20 }}>Each store's team is on its Team page. Platform owner roles and everyone's
        CardBox accounts are managed on <a href="https://cardbox.club/roles" target="_blank" rel="noreferrer">cardbox.club</a>.</p>
      <SupportReports />
    </section>
  )
  return <><LocalAdmin onChange={onChange} /><SupportReports /></>
}

function LocalAdmin({ onChange }: { onChange: () => Promise<void> }) {
  const [stores, setStores] = useState<AdminStore[]>([])
  const [people, setPeople] = useState<Membership[]>([])
  const [open, setOpen] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  const loadPeople = useCallback(() => api<Membership[]>('/api/admin/users').then(setPeople), [])
  useEffect(() => {
    Promise.all([api<AdminStore[]>('/api/admin/stores').then(setStores), loadPeople()]).catch(e => setError(e.message))
  }, [loadPeople])

  async function run(request: Promise<unknown>, done: string) {
    try {
      await request
      await Promise.all([api<AdminStore[]>('/api/admin/stores').then(setStores), loadPeople()])
      setMessage(done); setError('')
      // Changes to Toby's own store or membership show in the top bar too.
      await onChange()
    } catch (e) { setError((e as Error).message); setMessage('') }
  }
  const saveStore = (s: AdminStore, change: Partial<{ name: string; planStatus: string; trialEndsAt: string }>, done: string) =>
    run(api(`/api/admin/stores/${s.id}`, { method: 'PUT', body: change }), done)
  const saveMember = (m: Membership, change: { role?: string; removed?: boolean }, done: string) =>
    run(api(`/api/admin/users/${m.id}`, { method: 'PUT', body: change }), done)

  const term = filter.trim().toLowerCase()
  const shownStores = useMemo(() => stores.filter(s => !term || s.name.toLowerCase().includes(term)
    || people.some(p => p.storeId === s.id && (p.email.includes(term) || p.name.toLowerCase().includes(term)))), [stores, people, term])
  const logins = new Set(people.filter(p => !p.removed).map(p => p.email)).size

  return (
    <section>
      <h1>Platform admin</h1>
      <p className="lede">Every store on CardBox Trading. Only you can see this.</p>
      <div className="stats">
        <div className="stat"><span>Stores</span><strong>{stores.length}</strong></div>
        <div className="stat"><span>Paying or in trial</span><strong>{stores.filter(s => s.entitled).length}</strong></div>
        <div className="stat"><span>People</span><strong>{logins}</strong></div>
      </div>
      <div className="toolbar">
        <input placeholder="Find a store, person or email" aria-label="Find a store, person or email" value={filter} onChange={e => setFilter(e.target.value)} />
      </div>
      {message && <p className="notice">{message}</p>}
      {error && <p className="error">{error}</p>}
      <div className="table-wrap">
        <table className="grid">
          <thead><tr><th>Store</th><th>Owners</th><th>Plan</th><th>Trial ends</th><th className="r">People</th><th className="r">Locations</th><th className="r">Trades</th><th className="r">Cards</th><th>Created</th><th /></tr></thead>
          <tbody>
            {shownStores.map(s => (
              <StoreRows key={s.id} store={s} open={open === s.id} onToggle={() => setOpen(open === s.id ? null : s.id)}
                people={people.filter(p => p.storeId === s.id)} onSave={saveStore} onMember={saveMember}
                onAdd={(body, done) => run(api(`/api/admin/stores/${s.id}/members`, { method: 'POST', body }), done)} />
            ))}
          </tbody>
        </table>
        {stores.length > 0 && shownStores.length === 0 && <p className="empty">No store matches.</p>}
      </div>
    </section>
  )
}

function StoreRows({ store: s, open, onToggle, people, onSave, onMember, onAdd }: {
  store: AdminStore; open: boolean; onToggle: () => void; people: Membership[]
  onSave: (s: AdminStore, change: Partial<{ name: string; planStatus: string; trialEndsAt: string }>, done: string) => void
  onMember: (m: Membership, change: { role?: string; removed?: boolean }, done: string) => void
  onAdd: (body: { name: string; email: string; role: string }, done: string) => void
}) {
  const [name, setName] = useState(s.name)
  const [form, setForm] = useState({ name: '', email: '', role: 'staff' })
  return (
    <>
      <tr className="clickable" onClick={onToggle}>
        <td><strong>{s.name}</strong>{!s.entitled && <div className="error small">Locked</div>}</td>
        <td>{s.owners ?? <span className="error">No owner</span>}</td>
        <td onClick={e => e.stopPropagation()}>
          <select aria-label={`Plan for ${s.name}`} value={s.planStatus}
            onChange={e => onSave(s, { planStatus: e.target.value }, `${s.name} is now ${e.target.value.replace('_', ' ')}.`)}>
            {PLANS.map(p => <option key={p} value={p}>{p.replace('_', ' ')}</option>)}
          </select>
        </td>
        <td onClick={e => e.stopPropagation()}>
          <input type="date" aria-label={`Trial end for ${s.name}`} value={isoDay(s.trialEndsAt)}
            onChange={e => e.target.value && onSave(s, { trialEndsAt: e.target.value }, `${s.name}'s trial now ends ${e.target.value}.`)} />
        </td>
        <td className="r">{s.people}</td><td className="r">{s.locations}</td><td className="r">{s.trades}</td><td className="r">{s.cards}</td>
        <td className="num">{day(s.createdAt)}</td>
        <td className="r"><button className="link" onClick={e => { e.stopPropagation(); onToggle() }}>{open ? 'Close' : 'Manage'}</button></td>
      </tr>
      {open && (
        <tr className="move-row"><td colSpan={10}>
          <div className="admin-store">
            <form className="inline-form" onSubmit={e => { e.preventDefault(); onSave(s, { name }, `Renamed to ${name}.`) }}>
              <label>Store name<input required maxLength={120} value={name} onChange={e => setName(e.target.value)} /></label>
              <button type="submit" className="small secondary" disabled={name.trim() === s.name}>Rename</button>
              <span className="muted small">{[s.website, s.phone, s.contactEmail].filter(Boolean).join(' · ') || 'No contact details yet'}
                {s.lastTradeAt && <> · last trade {day(s.lastTradeAt)}</>}</span>
            </form>
            <table className="grid">
              <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Last signed in</th><th /></tr></thead>
              <tbody>{people.map(p => (
                <tr key={p.id} className={p.removed ? 'removed' : undefined}>
                  <td>{p.name}{!p.joined && <div className="muted small">Hasn't signed in yet</div>}{p.removed && <div className="muted small">Removed</div>}</td>
                  <td>{p.email}</td>
                  <td><select aria-label={`Role for ${p.name}`} value={p.role} disabled={p.removed}
                    onChange={e => onMember(p, { role: e.target.value }, `${p.name} is now ${e.target.value} of ${s.name}.`)}>
                    <option value="owner">Owner</option><option value="staff">Staff</option></select></td>
                  <td className="num">{day(p.lastUsedAt)}</td>
                  <td className="r"><button className="link" onClick={() => onMember(p, { removed: !p.removed },
                    p.removed ? `${p.name} restored to ${s.name}.` : `${p.name} removed from ${s.name}.`)}>{p.removed ? 'Restore' : 'Remove'}</button></td>
                </tr>
              ))}</tbody>
            </table>
            <form className="inline-form" onSubmit={e => { e.preventDefault(); onAdd(form, `${form.name} added to ${s.name}.`); setForm({ name: '', email: '', role: 'staff' }) }}>
              <label>Name<input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
              <label>Email<input type="email" required value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label>
              <label>Role<select value={form.role} onChange={e => setForm({ ...form, role: e.target.value })}>
                <option value="staff">Staff</option><option value="owner">Owner</option></select></label>
              <button type="submit" className="small">Add to store</button>
            </form>
          </div>
        </td></tr>
      )}
    </>
  )
}

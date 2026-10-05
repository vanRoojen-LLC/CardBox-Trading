import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, type Me } from '../api'
import { TeamAdmin } from './TeamAdmin'

interface Member { id: string; name: string; email: string; role: 'owner' | 'staff'; joined: boolean }

/** The people on the store. Owners add people, make them owners or staff, and remove them. */
export default function Staff({ me, onChange }: { me: Me; onChange: () => Promise<void> }) {
  if (me.cardbox) return <CardBoxTeam me={me} onChange={onChange} />
  return <LocalStaff me={me} onChange={onChange} />
}

/** The store's team on CardBox. A platform owner can open another store's team from the Admin tab (?store=). */
function CardBoxTeam({ me, onChange }: { me: Me; onChange: () => Promise<void> }) {
  const [params] = useSearchParams()
  const store = me.admin ? params.get('store') ?? undefined : undefined
  return <section><TeamAdmin key={store ?? ''} store={store} onChange={onChange} /></section>
}

function LocalStaff({ me, onChange }: { me: Me; onChange: () => Promise<void> }) {
  const [staff, setStaff] = useState<Member[]>([])
  const [form, setForm] = useState({ name: '', email: '', role: 'staff' })
  const [error, setError] = useState('')
  useEffect(() => { api<Member[]>('/api/app/staff').then(setStaff).catch(e => setError(e.message)) }, [])
  const owner = me.role === 'owner'
  const owners = staff.filter(s => s.role === 'owner').length

  async function run(request: Promise<Member[]>, self = false) {
    try {
      setStaff(await request); setError('')
      // Changing your own role changes what this screen lets you do.
      if (self) await onChange()
      return true
    } catch (err) { setError((err as Error).message); return false }
  }
  async function add(e: React.FormEvent) {
    e.preventDefault()
    if (await run(api<Member[]>('/api/app/staff', { method: 'POST', body: form }))) setForm({ name: '', email: '', role: 'staff' })
  }
  const setRole = (m: Member, role: string) =>
    run(api<Member[]>(`/api/app/staff/${m.id}`, { method: 'PUT', body: { role } }), m.email === me.email)
  const remove = (m: Member) => {
    if (confirm(`Remove ${m.name} from ${me.store}? They will no longer be able to sign in to it.`))
      run(api<Member[]>(`/api/app/staff/${m.id}/remove`, { method: 'POST', body: {} }))
  }

  return (
    <section>
      <h1>Team</h1>
      <p className="lede">Owners can change rates, store details, locations and the team. Staff take trades.</p>
      <div className="table-wrap"><table className="grid">
        <thead><tr><th>Name</th><th>Email</th><th>Role</th>{owner && <th><span className="sr-only">Actions</span></th>}</tr></thead>
        <tbody>{staff.map(s => {
          const lastOwner = s.role === 'owner' && owners <= 1
          return (
            <tr key={s.id}>
              <td>{s.name}{s.email === me.email && <span className="muted"> (you)</span>}
                {!s.joined && <div className="muted small">Hasn't signed in yet</div>}</td>
              <td>{s.email}</td>
              <td>{owner ? (
                <select aria-label={`Role for ${s.name}`} value={s.role} disabled={lastOwner}
                  title={lastOwner ? 'A store needs at least one owner' : undefined}
                  onChange={e => setRole(s, e.target.value)}>
                  <option value="owner">Owner</option><option value="staff">Staff</option>
                </select>
              ) : <span style={{ textTransform: 'capitalize' }}>{s.role}</span>}</td>
              {owner && <td className="r">{s.email !== me.email && !lastOwner &&
                <button className="link" onClick={() => remove(s)}>Remove</button>}</td>}
            </tr>
          )
        })}</tbody>
      </table></div>
      {error && <p className="error">{error}</p>}
      {owner && (
        <form className="panel narrow" onSubmit={add} style={{ marginTop: 20 }}>
          <h2>Add someone</h2>
          <label>Name<input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>Email<input type="email" required value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label>
          <label>Role
            <select value={form.role} onChange={e => setForm({ ...form, role: e.target.value })}>
              <option value="staff">Staff</option><option value="owner">Owner</option>
            </select>
          </label>
          <p className="muted">They sign in with their own CardBox login using this email, and join your store the first time they do.</p>
          <button type="submit">Add to team</button>
        </form>
      )}
    </section>
  )
}

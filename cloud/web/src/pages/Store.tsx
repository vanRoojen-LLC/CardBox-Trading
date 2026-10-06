import { useState } from 'react'
import { api, type Me, type StoreInfo, type StoreLocation } from '../api'
import StorageEditor from './StorageEditor'

type LocationForm = { name: string; address: string; phone: string }
const blank: LocationForm = { name: '', address: '', phone: '' }

/** Store details and locations. Everyone can see them; owners change them. */
export default function Store({ me, store, onSaved }: { me: Me; store: StoreInfo | null; onSaved: (store: StoreInfo) => void }) {
  if (!store) return <p className="muted">Loading…</p>
  return <StoreSettings me={me} store={store} onSaved={onSaved} />
}

const profileOf = (s: StoreInfo) => ({ name: s.name, website: s.website, phone: s.phone, contactEmail: s.contactEmail })

function StoreSettings({ me, store, onSaved }: { me: Me; store: StoreInfo; onSaved: (store: StoreInfo) => void }) {
  const owner = me.role === 'owner'
  const [profile, setProfile] = useState(() => profileOf(store))
  const [editing, setEditing] = useState<string | null>(null)
  const [locationForm, setLocationForm] = useState<LocationForm>(blank)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  // One change at a time: a second click while saving would add the same location twice.
  const [busy, setBusy] = useState(false)

  async function send(request: () => Promise<StoreInfo>, done: string) {
    if (busy) return false
    setBusy(true)
    try {
      onSaved(await request())
      setMessage(done); setError('')
      return true
    } catch (e) { setError((e as Error).message); setMessage(''); return false }
    finally { setBusy(false) }
  }
  function saveProfile(e: React.FormEvent) {
    e.preventDefault()
    // Show the details as the server stored them (the website gains https://).
    send(() => api<StoreInfo>('/api/app/store', { method: 'PUT', body: profile }).then(saved => { setProfile(profileOf(saved)); return saved }),
      'Store details saved.')
  }
  async function saveLocation(e: React.FormEvent) {
    e.preventDefault()
    const request = () => editing === 'new'
      ? api<StoreInfo>('/api/app/locations', { method: 'POST', body: locationForm })
      : api<StoreInfo>(`/api/app/locations/${editing}`, { method: 'PUT', body: locationForm })
    if (await send(request, editing === 'new' ? `${locationForm.name} added.` : 'Location saved.')) setEditing(null)
  }
  const setArchived = (l: StoreLocation, archived: boolean) => {
    if (archived && !confirm(`Close ${l.name}? Registers there stop taking trades at it. Its past trades and stock keep their location, and you can reopen it later.`)) return
    send(() => api<StoreInfo>(`/api/app/locations/${l.id}`, { method: 'PUT', body: { name: l.name, address: l.address, phone: l.phone, archived } }),
      archived ? `${l.name} closed. Its past trades keep their location.` : `${l.name} reopened.`)
  }
  const edit = (l: StoreLocation | null) => {
    setEditing(l ? l.id : 'new')
    setLocationForm(l ? { name: l.name, address: l.address, phone: l.phone } : blank)
  }
  const openCount = store.locations.filter(l => !l.archived).length
  // Opens in place of the row being edited (or under the list for a new one), so it's next to what it changes.
  const locationEditor = owner && editing !== null && (
    <form onSubmit={saveLocation} className="location-form">
      <h2>{editing === 'new' ? 'New location' : 'Edit location'}</h2>
      <label>Name<input required maxLength={80} autoFocus value={locationForm.name} onChange={e => setLocationForm({ ...locationForm, name: e.target.value })} /></label>
      <label>Address<input maxLength={300} value={locationForm.address} onChange={e => setLocationForm({ ...locationForm, address: e.target.value })} /></label>
      <label>Phone<input type="tel" maxLength={40} value={locationForm.phone} onChange={e => setLocationForm({ ...locationForm, phone: e.target.value })} /></label>
      <p className="actions"><button type="submit" disabled={busy}>{busy ? 'Saving…' : editing === 'new' ? 'Add location' : 'Save location'}</button>{' '}
        <button type="button" className="secondary" onClick={() => setEditing(null)}>Cancel</button></p>
    </form>
  )
  const field = (key: keyof typeof profile, label: string, type = 'text', extra: object = {}) => (
    <label>{label}<input type={type} disabled={!owner} value={profile[key]} onChange={e => setProfile({ ...profile, [key]: e.target.value })} {...extra} /></label>
  )

  return (
    <section>
      <h1>Store</h1>
      <p className="lede">{owner ? 'Your store’s details and locations.' : 'Only a store owner can change these.'}</p>
      {message && <p className="notice">{message}</p>}
      {error && <p className="error">{error}</p>}
      <div className="settings">
        <form className="panel" onSubmit={saveProfile}>
          <h2>Details</h2>
          {field('name', 'Store name', 'text', { required: true, maxLength: 120,
            ...(store.canRename ? {} : { disabled: true, title: 'Only the platform owner can rename a store on CardBox' }) })}
          {owner && me.cardbox && store.onCardBox && <p className="muted small">
            {store.canRename ? 'Renaming changes it on cardbox.club too. Sync and links go by the store, not its name.'
              : 'The name is shared with cardbox.club. Ask the platform owner to rename it.'}</p>}
          {field('website', 'Website', 'text', { placeholder: 'example.com', inputMode: 'url' })}
          {field('phone', 'Phone', 'tel', { inputMode: 'tel' })}
          {field('contactEmail', 'Contact email', 'email')}
          {owner && <button type="submit" disabled={busy}>Save details</button>}
        </form>

        <div className="panel">
          <h2>Locations</h2>
          <p className="muted small">Every trade is tagged to the location it was taken at. Each register picks its location from the top bar once a store has more than one.</p>
          <ul className="location-list">
            {store.locations.map(l => editing === l.id ? <li key={l.id}>{locationEditor}</li> : (
              <li key={l.id} className={l.archived ? 'archived' : undefined}>
                <div>
                  <strong>{l.name}</strong>{l.archived && <span className="muted"> · closed</span>}
                  {(l.address || l.phone) && <div className="muted small">{[l.address, l.phone].filter(Boolean).join(' · ')}</div>}
                </div>
                {owner && (
                  <div className="row-actions">
                    <button className="link" disabled={busy} onClick={() => edit(l)} aria-label={`Edit ${l.name}`}>Edit</button>
                    {l.archived
                      ? <button className="link" disabled={busy} onClick={() => setArchived(l, false)} aria-label={`Reopen ${l.name}`}>Reopen</button>
                      : openCount > 1 && <button className="link" disabled={busy} onClick={() => setArchived(l, true)} aria-label={`Close ${l.name}`}>Close</button>}
                  </div>
                )}
              </li>
            ))}
          </ul>
          {owner && editing === null && <button className="secondary small" onClick={() => edit(null)}>Add location</button>}
          {owner && editing === 'new' && locationEditor}
        </div>
      </div>
      <StorageEditor owner={owner} locations={store.locations} />
    </section>
  )
}

import { useCallback, useEffect, useState } from 'react'
import { api, CONDITIONS, type StoreLocation } from '../api'
import { flatTree, pathText, type PathPart, type Spot } from '../storage'

export interface ClubLink {
  id: string; collectionName: string; linkedBy: string; linkedByEmail: string
  state: 'active' | 'paused'; pausedReason: string | null
  locationId: string; location: string; storageId: string | null; path: PathPart[]; defaultCondition: string
  items: number; notMatched: number; cards: number; lastSyncedAt: string | null
}
interface Unmatched { itemId: string; name: string; set: string; number: string; game: string; quantity: number; reason: string }

const PAUSED: Record<string, string> = {
  role_revoked: 'the person who linked it no longer has a role at this store',
  collection_deleted: 'the collection was deleted on CardBox',
}

/**
 * CardBox collections that staff scan on cardbox.club and sync into this store's inventory. Their cards are
 * changed on CardBox; here an owner chooses where they sit, and what happens to them when a link ends.
 */
export default function ClubCollections({ owner, locations, spots, onChange }: {
  owner: boolean; locations: StoreLocation[]; spots: Spot[]; onChange: () => void
}) {
  const [links, setLinks] = useState<ClubLink[]>([])
  const [error, setError] = useState('')
  const load = useCallback(() => api<ClubLink[]>('/api/app/club-links').then(setLinks).catch(e => setError(e.message)), [])
  useEffect(() => { load() }, [load])
  async function send(request: Promise<ClubLink[]>) {
    try { setLinks(await request); setError(''); onChange() } catch (e) { setError((e as Error).message) }
  }
  if (links.length === 0 && !error) return null
  return (
    <div className="panel club-links">
      <h2>CardBox collections</h2>
      <p className="muted small">Cards scanned into these collections on cardbox.club show up here and stay in step. Change the cards themselves on CardBox.</p>
      {error && <p className="error">{error}</p>}
      <ul className="plain">
        {links.map(link => <LinkRow key={link.id} link={link} owner={owner} locations={locations} spots={spots}
          onTarget={body => send(api<ClubLink[]>(`/api/app/club-links/${link.id}`, { method: 'PUT', body }))}
          onEnd={cards => send(api<ClubLink[]>(`/api/app/club-links/${link.id}/end`, { method: 'POST', body: { cards } }))} />)}
      </ul>
    </div>
  )
}

function LinkRow({ link, owner, locations, spots, onTarget, onEnd }: {
  link: ClubLink; owner: boolean; locations: StoreLocation[]; spots: Spot[]
  onTarget: (body: { locationId: string; storageId: string | null; defaultCondition: string }) => void
  onEnd: (cards: 'keep' | 'remove') => void
}) {
  const [editing, setEditing] = useState(false)
  const [ending, setEnding] = useState(false)
  const [target, setTarget] = useState({ locationId: link.locationId, storageId: link.storageId ?? '', defaultCondition: link.defaultCondition })
  const [unmatched, setUnmatched] = useState<Unmatched[] | null>(null)
  const open = locations.filter(l => !l.archived)
  const where = `${link.location}${link.path.length ? ' › ' + pathText(link.path) : ', not put away'}`
  const choice = (
    <span className="row-actions">
      <button className="small" onClick={() => onEnd('keep')}>Keep {link.cards} as store stock</button>
      <button className="small secondary" onClick={() => onEnd('remove')}>Remove from inventory</button>
    </span>
  )
  return (
    <li>
      <div className="panel-head">
        <div>
          <strong>{link.collectionName}</strong> <span className="chip">{link.state === 'paused' ? 'Paused' : 'Syncing'}</span>
          <div className="muted small">
            {link.cards} card{link.cards === 1 ? '' : 's'} in {where} · linked by {link.linkedBy || link.linkedByEmail}
            {link.lastSyncedAt && <> · updated {new Date(link.lastSyncedAt).toLocaleString()}</>}
          </div>
          {link.notMatched > 0 && (
            <div className="muted small">
              {link.notMatched} card{link.notMatched === 1 ? '' : 's'} couldn't go into inventory.{' '}
              <button className="link" onClick={() => unmatched ? setUnmatched(null)
                : api<Unmatched[]>(`/api/app/club-links/${link.id}/not-matched`).then(setUnmatched)}>{unmatched ? 'Hide' : 'Show'}</button>
            </div>
          )}
        </div>
        {owner && link.state === 'active' && (
          <span className="row-actions">
            <button className="link" onClick={() => { setEditing(!editing); setEnding(false) }}>{editing ? 'Cancel' : 'Change'}</button>
            <button className="link" onClick={() => { setEnding(!ending); setEditing(false) }}>{ending ? 'Cancel' : 'Stop syncing'}</button>
          </span>
        )}
      </div>
      {link.state === 'paused' && (
        <p className="banner">Sync is paused because {PAUSED[link.pausedReason ?? ''] ?? 'CardBox paused it'}.
          {owner ? ' Choose what happens to its cards: ' : ' A store owner decides what happens to its cards.'}{owner && choice}</p>
      )}
      {ending && <p>Stop syncing {link.collectionName}? {choice}</p>}
      {unmatched && (
        <ul className="plain small">
          {unmatched.map(u => <li key={u.itemId}>{u.name || 'Unnamed card'} <span className="muted">{u.set.toUpperCase()} #{u.number} · {u.reason}</span></li>)}
        </ul>
      )}
      {editing && (
        <form className="move-form" onSubmit={e => { e.preventDefault(); setEditing(false); onTarget({ ...target, storageId: target.storageId || null }) }}>
          {open.length > 1 && (
            <label>Location
              <select value={target.locationId} onChange={e => setTarget({ ...target, locationId: e.target.value, storageId: '' })}>
                {open.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
              </select>
            </label>
          )}
          <label>Put in
            <select value={target.storageId} onChange={e => setTarget({ ...target, storageId: e.target.value })}>
              <option value="">Not put away</option>
              {flatTree(spots, target.locationId).map(s => <option key={s.id} value={s.id}>{'  '.repeat(s.depth)}{s.label} {s.name}</option>)}
            </select>
          </label>
          <label>Condition
            <select value={target.defaultCondition} onChange={e => setTarget({ ...target, defaultCondition: e.target.value })}>
              {CONDITIONS.map(c => <option key={c}>{c}</option>)}
            </select>
          </label>
          <button type="submit" className="small">Save</button>
        </form>
      )}
    </li>
  )
}

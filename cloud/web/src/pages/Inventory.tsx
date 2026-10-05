import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, CONDITIONS, FINISHES, money, type Card, type Money, type StoreLocation } from '../api'
import { flatTree, pathText, type PathPart, type Spot } from '../storage'
import SearchIcon from '../SearchIcon'
import ClubCollections from './ClubCollections'

interface Item {
  id: string; locationId: string; location: string; storageId: string | null; cardId: string
  name: string; set: string; number: string; rarity: string; finish: string; condition: string; quantity: number
  image: string | null; market: Money; path: PathPart[]
  /** Set on lines synced from a CardBox collection, which are changed on CardBox. */
  clubLinkId: string | null; clubCollection: string | null
}
interface Page { items: Item[]; more: boolean; cards: number; lines: number }

/** "none" is "not put away yet"; '' is anywhere. */
type Where = string

function SpotOptions({ spots, locationId }: { spots: Spot[]; locationId: string }) {
  return <>{flatTree(spots, locationId).map(s =>
    <option key={s.id} value={s.id}>{'  '.repeat(s.depth)}{s.label} {s.name}</option>)}</>
}

/** Stock on hand: find it, count it, put it away, and add cards that came in some other way than a trade. */
export default function Inventory({ locations, registerLocationId, owner }: { locations: StoreLocation[]; registerLocationId: string | null; owner: boolean }) {
  const open = locations.filter(l => !l.archived)
  const several = locations.length > 1
  const [q, setQ] = useState('')
  const [locationId, setLocationId] = useState(several ? registerLocationId ?? '' : '')
  const [where, setWhere] = useState<Where>('')
  const [page, setPage] = useState<Page | null>(null)
  const [spots, setSpots] = useState<Spot[]>([])
  const [moving, setMoving] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => { api<Spot[]>('/api/app/storage').then(setSpots).catch(e => setError(e.message)) }, [])
  const load = useCallback(() => {
    const params = new URLSearchParams()
    if (q.trim()) params.set('q', q.trim())
    if (locationId) params.set('location', locationId)
    if (where) params.set('storage', where)
    return api<Page>(`/api/app/inventory?${params}`).then(p => { setPage(p); setError('') }).catch(e => setError(e.message))
  }, [q, locationId, where])
  useEffect(() => { const t = setTimeout(load, 250); return () => clearTimeout(t) }, [load])

  async function change(request: Promise<unknown>) {
    try { await request; await load() } catch (e) { setError((e as Error).message) }
  }
  const setQuantity = (item: Item, quantity: number) => {
    if (quantity === 0 && !confirm(`Remove ${item.name} (${item.condition}) from inventory?`)) return
    change(api(`/api/app/inventory/${item.id}`, { method: 'PUT', body: { quantity } }))
  }
  // A filter for a spot only makes sense inside its location.
  const filterLocation = locationId || (open.length === 1 ? open[0].id : '')

  return (
    <section>
      <div className="page-head">
        <h1>Inventory</h1>
        <button className={adding ? 'secondary' : ''} onClick={() => setAdding(!adding)}>{adding ? 'Done adding' : 'Add cards'}</button>
      </div>
      <ClubCollections owner={owner} locations={locations} spots={spots} onChange={load} />
      {adding && <AddCards locations={open} spots={spots} defaultLocation={filterLocation || open[0]?.id || ''} onAdded={load} />}
      <div className="toolbar">
        <input placeholder="Find in stock: name, set or number" aria-label="Find in stock" value={q} onChange={e => setQ(e.target.value)} />
        {several && (
          <select aria-label="Location" value={locationId} onChange={e => { setLocationId(e.target.value); setWhere('') }}>
            <option value="">All locations</option>
            {locations.map(l => <option key={l.id} value={l.id}>{l.name}{l.archived ? ' (closed)' : ''}</option>)}
          </select>
        )}
        <select aria-label="Where" value={where} onChange={e => setWhere(e.target.value)}>
          <option value="">Anywhere</option>
          <option value="none">Not put away yet</option>
          {filterLocation && <SpotOptions spots={spots} locationId={filterLocation} />}
        </select>
      </div>
      {error && <p className="error">{error}</p>}
      {page && <p className="muted">{page.cards} card{page.cards === 1 ? '' : 's'} in {page.lines} line{page.lines === 1 ? '' : 's'}{page.more ? ' (showing the first 500; narrow the search to see more)' : ''}</p>}
      <div className="table-wrap">
        <table className="grid inventory">
          <thead><tr><th>Card</th><th>Finish</th><th>Cond.</th><th>Where</th><th className="r">Market each</th><th className="r">Qty</th><th><span className="sr-only">Actions</span></th></tr></thead>
          <tbody>
            {page?.items.map(item => (
              <InventoryRow key={item.id} item={item} several={several} locations={open} spots={spots}
                moving={moving === item.id} onMove={() => setMoving(moving === item.id ? null : item.id)}
                onQuantity={n => setQuantity(item, n)}
                onMoved={body => { setMoving(null); change(api(`/api/app/inventory/${item.id}/move`, { method: 'POST', body })) }} />
            ))}
          </tbody>
        </table>
        {page && page.items.length === 0 && <p className="empty">{q || where ? 'Nothing matches.' : 'No stock yet. Cards from saved trades show up here.'}</p>}
      </div>
    </section>
  )
}

function InventoryRow({ item, several, locations, spots, moving, onMove, onQuantity, onMoved }: {
  item: Item; several: boolean; locations: StoreLocation[]; spots: Spot[]; moving: boolean
  onMove: () => void; onQuantity: (n: number) => void; onMoved: (body: { locationId: string; storageId: string | null; quantity: number }) => void
}) {
  const [target, setTarget] = useState({ locationId: item.locationId, storageId: '', quantity: item.quantity })
  return (
    <>
      <tr>
        <td><strong>{item.name}</strong><div className="muted small">{item.set.toUpperCase()} #{item.number}
          {item.clubCollection && <> · <span title="Synced from this CardBox collection; change it on CardBox">From CardBox: {item.clubCollection}</span></>}</div></td>
        <td style={{ textTransform: 'capitalize' }}>{item.finish}</td>
        <td>{item.condition}</td>
        <td>{several && <div className="muted small">{item.location}</div>}
          {item.path.length ? pathText(item.path) : <span className="muted">Not put away</span>}</td>
        <td className="r">{money(item.market)}</td>
        <td className="r">
          {item.clubLinkId ? item.quantity : (
            <span className="stepper">
              <button aria-label="One fewer" onClick={() => onQuantity(item.quantity - 1)}>−</button>
              <span>{item.quantity}</span>
              <button aria-label="One more" onClick={() => onQuantity(item.quantity + 1)}>+</button>
            </span>
          )}
        </td>
        <td className="r">{!item.clubLinkId && <button className="link" onClick={onMove}>{moving ? 'Cancel' : 'Move'}</button>}</td>
      </tr>
      {moving && (
        <tr className="move-row"><td colSpan={7}>
          <form className="move-form" onSubmit={e => { e.preventDefault(); onMoved({ ...target, storageId: target.storageId || null }) }}>
            {several && (
              <label>Location
                <select value={target.locationId} onChange={e => setTarget({ ...target, locationId: e.target.value, storageId: '' })}>
                  {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
                </select>
              </label>
            )}
            <label>Put in
              <select autoFocus value={target.storageId} onChange={e => setTarget({ ...target, storageId: e.target.value })}>
                <option value="">Not put away</option>
                <SpotOptions spots={spots} locationId={target.locationId} />
              </select>
            </label>
            <label>How many
              <input type="number" min={1} max={item.quantity} value={target.quantity}
                onChange={e => setTarget({ ...target, quantity: Number(e.target.value) })} />
            </label>
            <button type="submit" className="small">Move {target.quantity}</button>
          </form>
        </td></tr>
      )}
    </>
  )
}

/** Finds a card in the catalog and adds copies of it straight to a location or storage spot. */
function AddCards({ locations, spots, defaultLocation, onAdded }: {
  locations: StoreLocation[]; spots: Spot[]; defaultLocation: string; onAdded: () => void
}) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Card[]>([])
  const [card, setCard] = useState<Card | null>(null)
  const [form, setForm] = useState({ finish: 'normal', condition: 'NM', quantity: 1, locationId: defaultLocation, storageId: '' })
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  useEffect(() => {
    if (query.trim().length < 2) return
    const t = setTimeout(() => api<Card[]>(`/api/app/cards?q=${encodeURIComponent(query.trim())}`)
      .then(setResults).catch(e => setError(e.message)), 250)
    return () => clearTimeout(t)
  }, [query])
  const finishes = useMemo(() => card ? FINISHES.filter(f => f.price(card) != null) : [], [card])

  function pick(c: Card) {
    setCard(c); setResults([]); setQuery('')
    const available = FINISHES.filter(f => f.price(c) != null)
    setForm(f => ({ ...f, finish: available.some(x => x.key === f.finish) ? f.finish : available[0]?.key ?? 'normal' }))
  }
  async function add(e: React.FormEvent) {
    e.preventDefault()
    if (!card) return
    try {
      await api('/api/app/inventory', { method: 'POST', body: { cardId: card.id, ...form, storageId: form.storageId || null } })
      setMessage(`Added ${form.quantity} × ${card.name}.`); setError('')
      setCard(null); setForm(f => ({ ...f, quantity: 1 }))
      onAdded()
    } catch (err) { setError((err as Error).message); setMessage('') }
  }

  return (
    <div className="panel add-cards">
      <div className="search">
        <SearchIcon />
        <input autoFocus placeholder="Find a card to add" aria-label="Find a card to add" value={query} onChange={e => setQuery(e.target.value)} />
      </div>
      {query.trim().length >= 2 && results.length > 0 && (
        <ul className="pick-list">
          {results.slice(0, 12).map(c => (
            <li key={c.id}><button type="button" className="secondary" onClick={() => pick(c)}>
              <strong>{c.name}</strong><span className="muted">{c.set.toUpperCase()} #{c.number} · {c.setName}</span>
              <span className="price">{money(c.usd)}</span>
            </button></li>
          ))}
        </ul>
      )}
      {card && (
        <form className="add-form" onSubmit={add}>
          <div className="picked"><strong>{card.name}</strong> <span className="muted">{card.set.toUpperCase()} #{card.number}</span></div>
          <label>Finish<select value={form.finish} onChange={e => setForm({ ...form, finish: e.target.value })}>
            {finishes.map(f => <option key={f.key} value={f.key}>{f.label}</option>)}</select></label>
          <label>Condition<select value={form.condition} onChange={e => setForm({ ...form, condition: e.target.value })}>
            {CONDITIONS.map(c => <option key={c}>{c}</option>)}</select></label>
          <label>Qty<input type="number" min={1} max={9999} value={form.quantity} onChange={e => setForm({ ...form, quantity: Number(e.target.value) })} /></label>
          {locations.length > 1 && (
            <label>Location<select value={form.locationId} onChange={e => setForm({ ...form, locationId: e.target.value, storageId: '' })}>
              {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}</select></label>
          )}
          <label>Put in<select value={form.storageId} onChange={e => setForm({ ...form, storageId: e.target.value })}>
            <option value="">Not put away</option><SpotOptions spots={spots} locationId={form.locationId} /></select></label>
          <button type="submit">Add to inventory</button>
        </form>
      )}
      {message && <p className="notice">{message}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  )
}

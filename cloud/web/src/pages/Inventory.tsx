import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { aborted, api, CONDITIONS, FINISHES, money, type Card, type Money, type StoreLocation } from '../api'
import { flatTree, pathOf, pathText, placeText, subtreeCounts, type PathPart, type Spot } from '../storage'
import SearchIcon from '../SearchIcon'
import OtherGames, { GameSwitch, type Game } from '../OtherGames'
import { useGames } from '../games'
import { FACETS, TREATMENTS, ruleText, titleCase, valueLabel, type Conditions, type Facets } from '../cardDetails'
import { ColorPips, FacetMenu, PriceMenu } from '../cardFilters'
import ClubCollections from './ClubCollections'
import CardLightbox, { type Slide } from '../CardLightbox'

interface Item {
  id: string; locationId: string; location: string; storageId: string | null; cardId: string
  name: string; set: string; number: string; rarity: string; finish: string; condition: string; quantity: number
  image: string | null; market: Money; path: PathPart[]
  game: string | null; setName: string | null; year: number | null; typeLine: string | null
  /** WUBRG letters, '' for colorless, null when unknown. */
  colors: string | null; treatments: string | null
  /** Where the store's rules would put a line that isn't put away yet. */
  destinationId: string | null; destination: PathPart[]
  /** Set on lines synced from a CardBox collection: how many there are is changed on CardBox. */
  clubLinkId: string | null; clubCollection: string | null
  /** The import batch the cards came in with (a CardBox upload or scan session), when there is one. */
  batchId: string | null; batchName: string | null
  /** CardBox photos of the cards on a synced line, first shown in the row. */
  scans: Scan[]
}
interface Scan { image: string; quantity: number; details: string | null }
interface Page { items: Item[]; more: boolean; offset: number; cards: number; lines: number; value: Money }
/** A search the store saved by name; everyone on the store sees it. */
interface SavedView { id: string; name: string; filters: Filters; sort: string; dir: string; by: string | null; canRemove: boolean }

/** Every filter as a list of values; single-valued ones (q, location, storage, prices) hold one. */
type Filters = Record<string, string[]>

const PAGE = 100


/**
 * Saved starting points. "To put away" is where synced and traded cards wait; "Not where rules say" is put-away stock
 * outside the spot the store's rules would pick, shown only when there is some.
 */
const VIEWS: { key: string; label: string; storage: string | null; rules?: string }[] = [
  { key: 'none', label: 'To put away', storage: 'none' },
  { key: 'any', label: 'Put away', storage: 'any' },
  { key: 'all', label: 'Everything', storage: null },
  { key: 'misplaced', label: 'Not where rules say', storage: null, rules: 'misplaced' },
]

/** Each column sorts by {@code sort} and filters by the facet {@code filter} (price and spot have their own menus). */
const COLUMNS: { key: string; label: string; right?: boolean; sort?: string; filter?: string }[] = [
  { key: 'card', label: 'Card', sort: 'name', filter: 'type' }, { key: 'set', label: 'Set', sort: 'set', filter: 'set' },
  { key: 'year', label: 'Year', sort: 'year', filter: 'year' }, { key: 'color', label: 'Color', sort: 'color', filter: 'color' },
  { key: 'rarity', label: 'Rarity', sort: 'rarity', filter: 'rarity' }, { key: 'finish', label: 'Finish', sort: 'finish', filter: 'finish' },
  { key: 'condition', label: 'Cond.', sort: 'condition', filter: 'condition' }, { key: 'where', label: 'Where', sort: 'where', filter: 'storage' },
  { key: 'collection', label: 'Collection', sort: 'collection', filter: 'source' }, { key: 'batch', label: 'Batch', sort: 'batch', filter: 'batch' },
  { key: 'market', label: 'Market', right: true, sort: 'market', filter: 'price' }, { key: 'quantity', label: 'Qty', right: true, sort: 'quantity' },
]

function query(filters: Filters, extra: Record<string, string> = {}): string {
  const params = new URLSearchParams()
  for (const [k, values] of Object.entries(filters)) for (const v of values) if (v !== '') params.append(k, v)
  for (const [k, v] of Object.entries(extra)) params.set(k, v)
  return params.toString()
}

function readStored<T>(key: string, fallback: T): T {
  try { const v = localStorage.getItem(key); return v ? JSON.parse(v) as T : fallback } catch { return fallback }
}

function SpotOptions({ spots, locationId }: { spots: Spot[]; locationId: string }) {
  return <>{flatTree(spots, locationId).map(s =>
    <option key={s.id} value={s.id}>{'  '.repeat(s.depth)}{s.name}</option>)}</>
}

/**
 * Where to look, one tier at a time: pick an area, then a spot inside it, and so on down. Each spot shows how many
 * of the cards under the other filters sit in it or anywhere inside it.
 */
function LocationPicker({ spots, locationId, value, counts, onChange }: {
  spots: Spot[]; locationId: string; value: string; counts: Map<string, number>; onChange: (storage: string) => void
}) {
  const byId = new Map(spots.map(s => [s.id, s]))
  const chain: Spot[] = []
  for (let at = byId.get(value); at; at = at.parentId ? byId.get(at.parentId) : undefined) chain.unshift(at)
  const place = chain[0]?.locationId ?? locationId
  const children = (parent: string | null) => spots.filter(s => s.locationId === place && s.parentId === parent)
  const count = (id: string) => counts.get(id) ? ` · ${counts.get(id)!.toLocaleString()}` : ''
  const option = (s: Spot) => <option key={s.id} value={s.id}>{s.name}{count(s.id)}</option>
  const top = place ? children(null) : []
  return (
    <span className="loc-picker" role="group" aria-label="Where">
      <select aria-label="Where" value={chain[0]?.id ?? value} onChange={e => onChange(e.target.value)}>
        <option value="">Anywhere in this view</option>
        <option value="none">Not put away</option>
        <option value="any">Put away anywhere</option>
        {top.length > 0 && <optgroup label="In">{top.map(option)}</optgroup>}
      </select>
      {chain.map((s, i) => children(s.id).length > 0 && (
        <Fragment key={s.id}>
          <span className="sep" aria-hidden>›</span>
          <select aria-label={`Inside ${s.name}`} value={chain[i + 1]?.id ?? s.id} onChange={e => onChange(e.target.value)}>
            <option value={s.id}>All of {s.name}{count(s.id)}</option>
            {children(s.id).map(option)}
          </select>
        </Fragment>
      ))}
    </span>
  )
}


/** Stock on hand: find it by any detail, pick lines (or everything matching), and put them away in bulk. */
export default function Inventory({ locations, registerLocationId, owner }: { locations: StoreLocation[]; registerLocationId: string | null; owner: boolean }) {
  const open = locations.filter(l => !l.archived)
  const several = locations.length > 1
  // Names the games stock is filed under in the filters (re-rendering when the list arrives).
  useGames('app')
  const [view, setView] = useState(() => readStored('inventory.view', 'none'))
  const [filters, setFilters] = useState<Filters>(() => (several && registerLocationId ? { location: [registerLocationId] } : {}) as Filters)
  const [q, setQ] = useState('')
  const [sort, setSort] = useState(() => readStored('inventory.sort', { key: 'name', dir: 'asc' }))
  const [page, setPage] = useState<Page | null>(null)
  const [facets, setFacets] = useState<Facets>({})
  const [spots, setSpots] = useState<Spot[]>([])
  // A selection belongs to the search and filters it was made under; changing them starts a new one.
  const [selection, setSelection] = useState<{ key: string; ids: Set<string>; all: boolean }>({ key: '', ids: new Set(), all: false })
  const [moving, setMoving] = useState<string | null>(null)
  const [picking, setPicking] = useState(false)
  const [adding, setAdding] = useState(false)
  const [loading, setLoading] = useState(false)
  const [misplaced, setMisplaced] = useState<{ lines: number; cards: number } | null>(null)
  const [saved, setSaved] = useState<SavedView[]>([])
  const [savedId, setSavedId] = useState<string | null>(null)
  const [naming, setNaming] = useState<string | null>(null)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const lastClicked = useRef<number | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)

  useEffect(() => { try { localStorage.setItem('inventory.view', JSON.stringify(view)); localStorage.setItem('inventory.sort', JSON.stringify(sort)) } catch { /* private window */ } }, [view, sort])
  useEffect(() => { api<Spot[]>('/api/app/storage').then(setSpots).catch(e => setError(e.message)) }, [])
  useEffect(() => { api<SavedView[]>('/api/app/inventory/views').then(setSaved).catch(() => setSaved([])) }, [])

  // Everything the server filters on: the view's storage, the search and the picked filters.
  const active = useMemo<Filters>(() => {
    const current = VIEWS.find(v => v.key === view)
    const storage = filters.storage?.length ? filters.storage : current?.storage ? [current.storage] : []
    const rules = filters.rules?.length ? filters.rules : !filters.storage?.length && current?.rules ? [current.rules] : []
    return { ...filters, storage, rules, q: q.trim() ? [q.trim()] : [] }
  }, [filters, view, q])
  const key = query(active)

  const load = useCallback(async (offset = 0) => {
    setLoading(true)
    try {
      const next = await api<Page>(`/api/app/inventory?${query(active, { sort: sort.key, dir: sort.dir, limit: String(PAGE), offset: String(offset) })}`)
      setPage(p => offset > 0 && p ? { ...next, items: [...p.items, ...next.items] } : next)
      setError('')
    } catch (e) { setError((e as Error).message) } finally { setLoading(false) }
  }, [active, sort])
  const loadFacets = useCallback(() => api<Facets>(`/api/app/inventory/facets?${query(active)}`).then(setFacets).catch(() => setFacets({})), [active])
  useEffect(() => { const t = setTimeout(() => load(0), 200); return () => clearTimeout(t) }, [load])
  useEffect(() => { const t = setTimeout(loadFacets, 250); return () => clearTimeout(t) }, [loadFacets])

  const loadMisplaced = useCallback(() => api<Page>('/api/app/inventory?rules=misplaced&limit=1')
    .then(p => setMisplaced({ lines: p.lines, cards: p.cards })).catch(() => setMisplaced(null)), [])
  useEffect(() => { loadMisplaced() }, [loadMisplaced])
  const refresh = useCallback(() => Promise.all([load(0), loadFacets(), loadMisplaced(), api<Spot[]>('/api/app/storage').then(setSpots)]),
    [load, loadFacets, loadMisplaced])
  async function change(request: Promise<unknown>) {
    try { await request; await refresh() } catch (e) { setError((e as Error).message) }
  }
  const setQuantity = (item: Item, quantity: number) => change(api(`/api/app/inventory/${item.id}`, { method: 'PUT', body: { quantity } }))

  const items = page?.items ?? []
  const current = selection.key === key
  const selected = current ? selection.ids : new Set<string>()
  const allMatching = current && selection.all
  const setSelected = (update: (prev: Set<string>) => Set<string>) =>
    setSelection(s => ({ key, all: false, ids: update(s.key === key ? s.ids : new Set()) }))
  const setAllMatching = (all: boolean) => setSelection(s => ({ key, all, ids: s.key === key ? s.ids : new Set() }))
  const selectedItems = items.filter(i => selected.has(i.id))
  const selectedLines = allMatching ? page?.lines ?? 0 : selected.size
  const selectedCards = allMatching ? page?.cards ?? 0 : selectedItems.reduce((n, i) => n + i.quantity, 0)
  const allVisible = items.length > 0 && items.every(i => selected.has(i.id))

  function toggle(index: number, shift: boolean) {
    const item = items[index]
    if (selection.key !== key) lastClicked.current = null
    setSelected(prev => {
      const next = new Set(prev)
      const on = !prev.has(item.id)
      // Shift-click selects (or clears) the run from the last row clicked.
      const from = shift && lastClicked.current !== null ? Math.min(lastClicked.current, index) : index
      const to = shift && lastClicked.current !== null ? Math.max(lastClicked.current, index) : index
      for (let i = from; i <= to; i++) {
        if (on) next.add(items[i].id)
        else next.delete(items[i].id)
      }
      return next
    })
    lastClicked.current = index
  }
  const toggleAll = () => setSelected(() => allVisible || allMatching ? new Set() : new Set(items.map(i => i.id)))
  const clearSelection = () => setSelection({ key, ids: new Set(), all: false })

  async function bulkMove(target: { locationId: string | null; storageId: string | null; label: string }) {
    const body = allMatching ? { filter: active, ...target } : { ids: [...selected], ...target }
    try {
      const result = await api<{ lines: number; cards: number; skipped: number }>('/api/app/inventory/move', { method: 'POST', body })
      setPicking(false); clearSelection()
      setNotice(`Moved ${result.cards} card${result.cards === 1 ? '' : 's'} (${result.lines} line${result.lines === 1 ? '' : 's'}) to ${target.label}.`
        + (result.skipped ? ` ${result.skipped} synced line${result.skipped === 1 ? '' : 's'} stayed put: CardBox cards wait to be put away at their collection's location.` : ''))
      setError('')
      await refresh()
    } catch (e) { setError((e as Error).message) }
  }

  async function putAway(body: object, done: string) {
    try {
      const r = await api<{ lines: number; cards: number; spots: number; unmatched: number }>('/api/app/inventory/put-away', { method: 'POST', body })
      setNotice(r.cards === 0 && r.unmatched > 0 ? `No storage rule fits ${r.unmatched === 1 ? 'that line' : `those ${r.unmatched} lines`}. Add one under Store › Storage, or use Move to….`
        : `${done.replace('{cards}', `${r.cards} card${r.cards === 1 ? '' : 's'}`).replace('{spots}', `${r.spots} spot${r.spots === 1 ? '' : 's'}`)}`
          + (r.unmatched ? ` ${r.unmatched} line${r.unmatched === 1 ? '' : 's'} had no rule and stayed.` : ''))
      setError(''); clearSelection()
      await refresh()
    } catch (e) { setError((e as Error).message) }
  }
  const putAwaySelected = () => putAway(allMatching ? { filter: active } : { ids: [...selected] }, 'Put {cards} away in {spots}.')

  // Keyboard: / searches, m moves the selection, Escape clears it.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target
      const typing = t instanceof HTMLElement && (t.isContentEditable || ['SELECT', 'TEXTAREA'].includes(t.tagName)
        || (t instanceof HTMLInputElement && !['checkbox', 'radio'].includes(t.type)))
      if (e.key === '/' && !typing) { e.preventDefault(); searchRef.current?.focus() }
      else if (e.key === 'm' && !typing && selectedLines > 0) { e.preventDefault(); setPicking(true) }
      else if (e.key === 'Escape' && !picking && selectedLines > 0) setSelection({ key, ids: new Set(), all: false })
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [selectedLines, picking, key])

  const setFilter = (k: string, values: string[]) => setFilters(f => ({ ...f, [k]: values }))

  function openSaved(v: SavedView) {
    const { q: search, ...rest } = v.filters
    setView('all'); setSavedId(v.id)
    setFilters({ ...rest, location: rest.location ?? filters.location ?? [] })
    setQ(search?.[0] ?? '')
    setSort({ key: v.sort, dir: v.dir })
  }
  async function saveView(e: React.FormEvent) {
    e.preventDefault()
    if (!naming?.trim()) return
    try {
      const next = await api<SavedView[]>('/api/app/inventory/views', { method: 'POST', body: { name: naming.trim(), filters: active, sort: sort.key, dir: sort.dir } })
      setSaved(next); setSavedId(next.find(v => v.name === naming.trim())?.id ?? null); setNaming(null)
      setNotice(`Saved “${naming.trim()}” for everyone on the store.`); setError('')
    } catch (err) { setError((err as Error).message) }
  }
  async function removeView(v: SavedView) {
    if (!confirm(`Remove the saved view “${v.name}” for everyone on the store?`)) return
    try { setSaved(await api<SavedView[]>(`/api/app/inventory/views/${v.id}`, { method: 'DELETE', body: {} })); if (savedId === v.id) setSavedId(null) }
    catch (err) { setError((err as Error).message) }
  }
  const chips = Object.entries(filters).flatMap(([k, values]) => ['location', 'storage', 'rules', 'priceMin', 'priceMax'].includes(k) ? []
    : values.map(v => ({ k, v, label: valueLabel(k, { value: v, label: facets[k]?.find(f => f.value === v)?.label }) })))
  const price = [filters.priceMin?.[0] && `≥ $${filters.priceMin[0]}`, filters.priceMax?.[0] && `≤ $${filters.priceMax[0]}`].filter(Boolean).join(' and ')
  const filtered = chips.length > 0 || !!price || !!filters.storage?.length || !!q.trim()
  const sortBy = (k: string) => setSort(s => ({ key: k, dir: s.key === k && s.dir === 'asc' ? 'desc' : 'asc' }))
  // A filter for a spot only makes sense inside its location.
  const filterLocation = filters.location?.[0] || (open.length === 1 ? open[0].id : '')
  // Cards in each spot itself, and counting the spots inside it, under every filter but where.
  const inSpot = useMemo(() => new Map((facets.storage ?? []).map(f => [f.value, Number(f.cards ?? 0)])), [facets])
  const underSpot = useMemo(() => subtreeCounts(spots, inSpot), [spots, inSpot])
  const grouped = sort.key === 'where'
  const showSpot = (locationId: string, storageId: string) => setFilters(f => ({ ...f, location: several ? [locationId] : f.location ?? [], storage: [storageId] }))
  const spotValues = useMemo(() => [
    { value: 'none', label: 'Not put away', cards: null }, { value: 'any', label: 'Put away anywhere', cards: null },
    ...(filterLocation ? flatTree(spots, filterLocation).map(s => ({ value: s.id, label: placeText(pathOf(spots, s.id)), cards: underSpot.get(s.id) ?? 0 })) : []),
  ], [spots, filterLocation, underSpot])
  function columnFilter(c: (typeof COLUMNS)[number]) {
    if (!c.filter) return null
    if (c.filter === 'price') return <PriceMenu header min={filters.priceMin?.[0] ?? ''} max={filters.priceMax?.[0] ?? ''}
      onChange={(min, max) => setFilters(f => ({ ...f, priceMin: min ? [min] : [], priceMax: max ? [max] : [] }))} />
    if (c.filter === 'storage') return <FacetMenu header single facet={{ key: 'storage', label: 'Where', searchable: true }} values={spotValues}
      picked={filters.storage ?? []} onChange={v => setFilter('storage', v)} />
    const facet = FACETS.find(f => f.key === c.filter)
    return facet ? <FacetMenu header facet={facet} values={facets[facet.key] ?? []} picked={filters[facet.key] ?? []} onChange={v => setFilter(facet.key, v)} /> : null
  }

  return (
    <section className="inventory-page">
      <div className="page-head">
        <h1>Inventory</h1>
        <span className="row-actions">
          <Link to="/app/inventory/counts">Re-inventory</Link>
          <button className={adding ? 'secondary' : ''} onClick={() => setAdding(!adding)}>{adding ? 'Done adding' : 'Add cards'}</button>
        </span>
      </div>
      <ClubCollections owner={owner} locations={locations} spots={spots} onChange={refresh} />
      {adding && <AddCards locations={open} spots={spots} defaultLocation={filterLocation || open[0]?.id || ''} onAdded={refresh} />}

      <div className="inv-views" role="tablist" aria-label="Views">
        {VIEWS.filter(v => !v.rules || view === v.key || (misplaced?.lines ?? 0) > 0).map(v => (
          <button key={v.key} role="tab" aria-selected={!savedId && view === v.key && !filters.storage?.length} className="inv-view"
            onClick={() => {
              setView(v.key); setSavedId(null); setFilters(f => ({ ...f, storage: [], rules: [] }))
              // Put-away stock reads best in shelf order, under a heading for each spot.
              if (v.key === 'any') setSort({ key: 'where', dir: 'asc' })
            }}>
            {v.label}{v.rules && misplaced?.cards ? <span className="count"> {misplaced.cards.toLocaleString()}</span> : null}</button>
        ))}
        {saved.map(v => (
          <span key={v.id} className="inv-saved">
            <button role="tab" aria-selected={savedId === v.id} className="inv-view" title={v.by ? `Saved by ${v.by}` : undefined}
              onClick={() => openSaved(v)}>{v.name}</button>
            {v.canRemove && savedId === v.id && <button className="link icon" aria-label={`Remove saved view ${v.name}`} onClick={() => removeView(v)}>×</button>}
          </span>
        ))}
        {naming === null
          ? <button className="link inv-save" onClick={() => setNaming(saved.find(v => v.id === savedId)?.name ?? '')}
              title="Keep this search, filters and sort as a view everyone on the store can open">{savedId ? 'Update or save as…' : 'Save view…'}</button>
          : <form className="inv-save-form" onSubmit={saveView}>
              <input autoFocus maxLength={60} placeholder="Name, e.g. Red rares to sort" aria-label="View name" value={naming}
                onChange={e => setNaming(e.target.value)} onKeyDown={e => e.key === 'Escape' && setNaming(null)} />
              <button type="submit" className="small" disabled={!naming.trim()}>Save</button>
              <button type="button" className="small ghost" onClick={() => setNaming(null)}>Cancel</button>
            </form>}
      </div>
      <div className="inv-filters">
        <div className="inv-search">
          <SearchIcon />
          <input ref={searchRef} placeholder="Find: name, set, number or card type" aria-label="Find in stock" value={q} onChange={e => setQ(e.target.value)} />
          <span className="kbd" aria-hidden>/</span>
        </div>
        {several && (
          <select aria-label="Location" value={filters.location?.[0] ?? ''} onChange={e => setFilters(f => ({ ...f, location: e.target.value ? [e.target.value] : [], storage: [] }))}>
            <option value="">All locations</option>
            {locations.map(l => <option key={l.id} value={l.id}>{l.name}{l.archived ? ' (closed)' : ''}</option>)}
          </select>
        )}
        <LocationPicker spots={spots} locationId={filterLocation} value={filters.storage?.[0] ?? ''} counts={underSpot}
          onChange={v => setFilter('storage', v ? [v] : [])} />
        <div className="inv-chips">
          {FACETS.map(f => <FacetMenu key={f.key} facet={f} values={facets[f.key] ?? []} picked={filters[f.key] ?? []} onChange={v => setFilter(f.key, v)} />)}
          <PriceMenu min={filters.priceMin?.[0] ?? ''} max={filters.priceMax?.[0] ?? ''}
            onChange={(min, max) => setFilters(f => ({ ...f, priceMin: min ? [min] : [], priceMax: max ? [max] : [] }))} />
        </div>
        {(chips.length > 0 || price) && (
          <div className="inv-active">
            {chips.map(c => (
              <button key={c.k + c.v} className="chip on" onClick={() => setFilter(c.k, (filters[c.k] ?? []).filter(x => x !== c.v))}
                aria-label={`Remove filter ${c.label}`}>{c.label} <span aria-hidden>×</span></button>
            ))}
            {price && <button className="chip on" onClick={() => setFilters(f => ({ ...f, priceMin: [], priceMax: [] }))}>Market {price} <span aria-hidden>×</span></button>}
            <button className="link" onClick={() => setFilters(f => ({ location: f.location ?? [] }))}>Clear filters</button>
          </div>
        )}
      </div>

      {notice && <p className="notice" role="status">{notice} <button className="link" onClick={() => setNotice('')}>Dismiss</button></p>}
      {error && <p className="error" role="alert">{error}</p>}

      {active.rules.length > 0 && page && page.lines > 0 && (
        <div className="panel misplaced">
          <p><strong>{page.cards.toLocaleString()} card{page.cards === 1 ? '' : 's'}</strong> {page.cards === 1 ? 'is' : 'are'} put away somewhere
            your storage rules wouldn't put {page.cards === 1 ? 'it' : 'them'}. Each line shows where its rule sends it.
            Leave any that are there on purpose, or move them all.</p>
          <button className="small" onClick={() => putAway({ filter: active }, 'Moved {cards} to where the rules say, into {spots}.')}>
            Move {page.lines === 1 ? 'it' : `all ${page.lines.toLocaleString()} lines`} where the rules say</button>
        </div>
      )}

      {(filters.storage?.[0] ?? VIEWS.find(v => v.key === view)?.storage) === 'none' && (
        <PutAwayList filter={{ ...active, storage: [] }} version={page} onPutAway={(storageId, label) =>
          putAway({ filter: { ...active, storage: ['none'] }, storageId }, `Filed {cards} into ${label}.`)} />
      )}

      <div className="inv-summary">
        {page ? <span>{page.cards.toLocaleString()} card{page.cards === 1 ? '' : 's'} in {page.lines.toLocaleString()} line{page.lines === 1 ? '' : 's'}
          {Number(page.value) > 0 && <> · {money(page.value)} market</>}</span> : <span className="muted">Loading…</span>}
        <span className="hints"><span><span className="kbd">Shift</span>-click selects a run</span><span><span className="kbd">M</span> moves</span></span>
      </div>

      {selectedLines > 0 && (
        <div className="bulk-bar" role="region" aria-label="Selected cards">
          <strong>{allMatching ? 'All ' : ''}{selectedCards.toLocaleString()} card{selectedCards === 1 ? '' : 's'} in {selectedLines.toLocaleString()} line{selectedLines === 1 ? '' : 's'} selected</strong>
          {!allMatching && allVisible && page && page.lines > selected.size && (
            <button className="link" onClick={() => setAllMatching(true)}>Select all {page.lines.toLocaleString()} matching lines</button>
          )}
          <span className="bulk-actions">
            <button className="small" onClick={() => setPicking(true)}>Move to…</button>
            <button className="small ghost" onClick={putAwaySelected} title="Put each card where your storage rules send it">Put away by rules</button>
            <button className="small ghost" onClick={clearSelection}>Clear</button>
          </span>
        </div>
      )}

      {/* Phones hide the column headers, so selecting every line and sorting live here instead. */}
      {items.length > 0 && (
        <div className="inv-mobile-tools">
          <label className="check-all"><input type="checkbox" checked={allVisible || allMatching} onChange={toggleAll} /> Select all shown</label>
          <select aria-label="Sort by" value={sort.key} onChange={e => setSort(s => ({ key: e.target.value, dir: s.dir }))}>
            {COLUMNS.filter(c => c.sort).map(c => <option key={c.key} value={c.sort}>Sort by {c.key === 'condition' ? 'condition' : c.label.toLowerCase()}</option>)}
          </select>
          <button type="button" className="secondary small" aria-label={sort.dir === 'asc' ? 'Ascending; tap for descending' : 'Descending; tap for ascending'}
            onClick={() => setSort(s => ({ ...s, dir: s.dir === 'asc' ? 'desc' : 'asc' }))}>{sort.dir === 'asc' ? '▲' : '▼'}</button>
        </div>
      )}
      <div className="table-wrap">
        <table className="grid inventory dense">
          <thead><tr>
            <th className="check"><input type="checkbox" aria-label="Select every line shown" checked={allVisible || allMatching} onChange={toggleAll} /></th>
            {COLUMNS.map(c => (
              <th key={c.key} className={c.right ? 'r' : ''} aria-sort={sort.key === c.sort ? (sort.dir === 'asc' ? 'ascending' : 'descending') : undefined}>
                <span className="th-inner">
                  {c.sort ? <button className="sort" title={`Sort by ${c.label.toLowerCase()}`} onClick={() => sortBy(c.sort!)}>{c.label}<span className="arrow" aria-hidden>{sort.key === c.sort ? (sort.dir === 'asc' ? '▲' : '▼') : ''}</span></button> : c.label}
                  {columnFilter(c)}
                </span>
              </th>
            ))}
            <th><span className="sr-only">Actions</span></th>
          </tr></thead>
          <tbody>
            {items.map((item, index) => {
              const before = items[index - 1]
              const heading = grouped && (!before || before.storageId !== item.storageId || before.locationId !== item.locationId)
              return (
                <Fragment key={item.id}>
                  {heading && <LocationHeading item={item} several={several} spots={spots} cards={item.storageId ? inSpot.get(item.storageId) : undefined}
                    picked={filters.storage?.[0]} onPick={id => showSpot(item.locationId, id)} />}
                  <InventoryRow item={item} several={several} locations={open} spots={spots}
                    selected={allMatching || selected.has(item.id)} onSelect={shift => toggle(index, shift)}
                    moving={moving === item.id} onMove={() => setMoving(moving === item.id ? null : item.id)}
                    onQuantity={n => setQuantity(item, n)}
                    onMoved={body => { setMoving(null); change(api(`/api/app/inventory/${item.id}/move`, { method: 'POST', body })) }} />
                </Fragment>
              )
            })}
          </tbody>
        </table>
        {page && items.length === 0 && <p className="empty">{filtered || view !== 'all' ? 'Nothing here matches.' : 'No stock yet. Cards from saved trades and CardBox collections show up here.'}</p>}
        {page?.more && (
          <div className="inv-more">
            <span className="muted">Showing {items.length.toLocaleString()} of {page.lines.toLocaleString()} lines</span>
            <button className="secondary small" disabled={loading} onClick={() => load(items.length)}>{loading ? 'Loading…' : `Show ${Math.min(PAGE, page.lines - items.length)} more`}</button>
          </div>
        )}
      </div>

      {picking && (
        <SpotPicker locations={open} spots={spots} cards={selectedCards} lines={selectedLines} onCancel={() => setPicking(false)} onPick={bulkMove} />
      )}
    </section>
  )
}

/** A heading over each spot's cards when the list is in shelf order; each step of the path narrows the list to it. */
function LocationHeading({ item, several, spots, cards, picked, onPick }: {
  item: Item; several: boolean; spots: Spot[]; cards: number | undefined; picked: string | undefined; onPick: (storageId: string) => void
}) {
  const path = item.storageId ? pathOf(spots, item.storageId) as Spot[] : []
  return (
    <tr className="loc-head">
      <td colSpan={COLUMNS.length + 2}>
        <span className="crumbs">
          {several && <span className="muted">{item.location} › </span>}
          {path.length === 0 ? <span className="unshelved">Not put away</span> : path.map((s, i) => (
            <Fragment key={s.id}>
              {i > 0 && <span className="muted" aria-hidden> › </span>}
              {s.id === picked ? <strong>{s.name}</strong>
                : <button type="button" className="link" title={`Show only what's in ${s.name}`} onClick={() => onPick(s.id)}>{s.name}</button>}
            </Fragment>
          ))}
        </span>
        {cards !== undefined && <span className="muted small"> {cards.toLocaleString()} card{cards === 1 ? '' : 's'}</span>}
      </td>
    </tr>
  )
}

interface PutAwayGroup { storageId: string; location: string; path: PathPart[]; lines: number; cards: number }

/**
 * The cards waiting under the current filters, grouped by the spot the store's rules send them to, in shelf order.
 * Staff carry the pile, file a spot's worth, and press its button.
 */
function PutAwayList({ filter, version, onPutAway }: { filter: Filters; version: unknown; onPutAway: (storageId: string, label: string) => Promise<void> }) {
  const [list, setList] = useState<{ groups: PutAwayGroup[]; unmatched: { lines: number; cards: number } } | null>(null)
  const [open, setOpen] = useState(() => readStored('inventory.putAwayOpen', true))
  const [busy, setBusy] = useState<string | null>(null)
  const params = query(filter)
  useEffect(() => {
    api<typeof list>(`/api/app/inventory/put-away?${params}`).then(setList).catch(() => setList(null))
  }, [params, version])
  useEffect(() => { try { localStorage.setItem('inventory.putAwayOpen', JSON.stringify(open)) } catch { /* private window */ } }, [open])
  if (!list || (list.groups.length === 0 && list.unmatched.cards === 0)) return null
  const several = new Set(list.groups.map(g => g.location)).size > 1
  const total = list.groups.reduce((n, g) => n + g.cards, 0)
  return (
    <div className="panel put-away">
      <div className="panel-head">
        <h2>Put-away list</h2>
        <button className="link" aria-expanded={open} onClick={() => setOpen(!open)}>{open ? 'Hide' : `Show ${list.groups.length} spot${list.groups.length === 1 ? '' : 's'}`}</button>
      </div>
      {list.groups.length === 0
        ? <p className="muted small">None of these cards fit a storage rule yet. Set what goes where under <Link to="/app/store">Store › Storage</Link>.</p>
        : <p className="muted small">Your storage rules have a spot for {total.toLocaleString()} of these cards. File each spot's worth, then mark it done.</p>}
      {open && list.groups.length > 0 && (
        <ul className="put-away-groups">
          {list.groups.map(g => {
            const label = (several ? `${g.location} › ` : '') + placeText(g.path)
            return (
              <li key={g.storageId}>
                <span className="dest">{label}</span>
                <span className="muted num">{g.lines.toLocaleString()} line{g.lines === 1 ? '' : 's'}</span>
                <strong className="num">{g.cards.toLocaleString()} card{g.cards === 1 ? '' : 's'}</strong>
                <button className="small secondary" disabled={busy !== null}
                  onClick={async () => { setBusy(g.storageId); await onPutAway(g.storageId, label); setBusy(null) }}>
                  {busy === g.storageId ? 'Filing…' : 'Done, filed'}</button>
              </li>
            )
          })}
        </ul>
      )}
      {open && list.unmatched.cards > 0 && (
        <p className="muted small">{list.unmatched.cards.toLocaleString()} card{list.unmatched.cards === 1 ? '' : 's'} ({list.unmatched.lines} line{list.unmatched.lines === 1 ? '' : 's'}) fit no rule; move them with Move to….</p>
      )}
    </div>
  )
}

/** Where selected cards go: type part of a spot's path ("sh 2 b 3" finds Shelf 2 › Box 3), arrow to it and press Enter. */
function SpotPicker({ locations, spots, cards, lines, onCancel, onPick }: {
  locations: StoreLocation[]; spots: Spot[]; cards: number; lines: number; onCancel: () => void
  onPick: (target: { locationId: string | null; storageId: string | null; label: string }) => void
}) {
  const [find, setFind] = useState('')
  const [active, setActive] = useState(0)
  const [busy, setBusy] = useState(false)
  const several = locations.length > 1
  const options = useMemo(() => {
    const out: { locationId: string; storageId: string | null; label: string; depth: number; words: string[] }[] = []
    for (const l of locations) {
      out.push({ locationId: l.id, storageId: null, label: several ? `${l.name}: not put away` : 'Not put away', depth: 0, words: ['not', 'put', 'away', ...l.name.toLowerCase().split(/\s+/)] })
      for (const s of flatTree(spots, l.id)) {
        const path = pathOf(spots, s.id)
        const label = several ? `${l.name} › ${placeText(path)}` : placeText(path)
        // Tier labels still find a spot ("sh 2 b 3"), though only the names show.
        const words = `${several ? l.name : ''} ${pathText(path)}`.toLowerCase().split(/[\s›:]+/).filter(Boolean)
        out.push({ locationId: l.id, storageId: s.id, label, depth: s.depth + 1, words })
      }
    }
    return out
  }, [locations, spots, several])
  const terms = find.toLowerCase().split(/\s+/).filter(Boolean)
  // Each typed word starts a word of the path, in order: "sh 2 b 3" is Shelf 2 › Box 3, not Box 2 on shelf 3.
  const shown = options.filter(o => {
    let at = 0
    for (const t of terms) {
      while (at < o.words.length && !o.words[at].startsWith(t)) at++
      if (at++ >= o.words.length) return false
    }
    return true
  })
  const pick = async (o: (typeof options)[number]) => { setBusy(true); await onPick(o); setBusy(false) }

  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) onCancel() }}>
      <div className="modal spot-picker" role="dialog" aria-modal="true" aria-label="Move selected cards">
        <h2>Move {cards.toLocaleString()} card{cards === 1 ? '' : 's'} ({lines.toLocaleString()} line{lines === 1 ? '' : 's'}) to…</h2>
        <input autoFocus placeholder="Type a spot, like “shelf 2 box 3”" aria-label="Find a spot" value={find}
          onChange={e => { setFind(e.target.value); setActive(0) }}
          onKeyDown={e => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setActive(a => Math.min(a + 1, shown.length - 1)) }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(a - 1, 0)) }
            else if (e.key === 'Enter' && shown[active] && !busy) { e.preventDefault(); pick(shown[active]) }
            else if (e.key === 'Escape') onCancel()
          }} />
        <ul className="spot-options" role="listbox">
          {shown.map((o, i) => (
            <li key={o.locationId + (o.storageId ?? '')} role="option" aria-selected={i === active}>
              <button className={i === active ? 'active' : ''} disabled={busy} onMouseEnter={() => setActive(i)} onClick={() => pick(o)}
                style={terms.length ? undefined : { paddingLeft: `${12 + o.depth * 16}px` }}>
                {terms.length ? o.label : o.storageId ? o.label.split(' › ').at(-1) : o.label}
              </button>
            </li>
          ))}
        </ul>
        {shown.length === 0 && <p className="muted">No spot matches. Owners add spots under Store › Storage.</p>}
        <div className="actions"><button className="secondary small" onClick={onCancel}>Cancel</button></div>
      </div>
    </div>
  )
}

/** Scan details are stored as JSON text; a bad one shows as nothing rather than breaking the whole page. */
function scanDetails(details: string | null): string {
  if (!details) return ''
  try {
    const parsed: unknown = JSON.parse(details)
    if (!parsed || typeof parsed !== 'object') return ''
    return Object.entries(parsed as Record<string, unknown>).map(([k, v]) => `${k.replace(/_/g, ' ')}: ${String(v)}`).join(' · ')
  } catch { return '' }
}

/** What the lightbox steps through for a line: each CardBox scan, then the catalog picture of the printing. */
function slidesOf(item: Item): Slide[] {
  const card = `${item.name} · ${item.set.toUpperCase()} #${item.number}`
  const slides: Slide[] = (item.scans ?? []).map((s, i, all) => ({
    image: s.image,
    caption: [card, all.length > 1 ? `scan ${i + 1}` : 'scan', s.quantity > 1 ? `×${s.quantity}` : '', scanDetails(s.details)]
      .filter(Boolean).join(' · '),
  }))
  if (item.image) slides.push({ image: item.image, caption: `${card} · catalog image` })
  return slides
}

/** The line's first scan (or the catalog picture when it has none) in the row; a click opens the lightbox. */
function RowThumb({ item, onOpen }: { item: Item; onOpen: () => void }) {
  const scans = item.scans ?? []
  const first = scans[0]?.image ?? item.image
  if (!first) return <span className="row-thumb noimg" aria-hidden="true" />
  return (
    <button type="button" className={`row-thumb${scans.length ? ' scanned' : ''}`} onClick={onOpen}
            aria-label={`Enlarge ${scans.length ? 'the scans' : 'the picture'} of ${item.name}`}
            title={scans.length > 1 ? `${scans.length} scans. Click to inspect.` : 'Click to inspect'}>
      <img src={first} alt="" loading="lazy" />
      {scans.length > 1 && <span className="row-thumb-count">{scans.length}</span>}
    </button>
  )
}

/**
 * The +/- buttons count up locally and send one total once the clicks stop: saving replaces the line, so each click
 * sending its own count from the row as last loaded used to lose quick clicks.
 */
function QuantityStepper({ item, onQuantity }: { item: Item; onQuantity: (n: number) => Promise<void> }) {
  const [pending, setPending] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  useEffect(() => () => clearTimeout(timer.current), [])
  const shown = pending ?? item.quantity
  async function send(quantity: number) {
    clearTimeout(timer.current)
    if (quantity === item.quantity) { setPending(null); return }
    if (quantity === 0 && !confirm(`Remove ${item.name} (${item.condition}) from inventory?`)) { setPending(null); return }
    setSaving(true)
    try { await onQuantity(quantity) } finally { setSaving(false); setPending(null) }
  }
  function step(by: number) {
    const next = Math.max(0, shown + by)
    setPending(next)
    clearTimeout(timer.current)
    // Going to zero asks straight away; otherwise wait for a pause in the clicking.
    if (next === 0) send(0)
    else timer.current = setTimeout(() => send(next), 500)
  }
  return (
    <span className="stepper" aria-busy={saving || undefined}>
      <button aria-label={`One fewer ${item.name}`} disabled={saving || shown === 0} onClick={() => step(-1)}>−</button>
      <span aria-live="polite">{shown}</span>
      <button aria-label={`One more ${item.name}`} disabled={saving} onClick={() => step(1)}>+</button>
    </span>
  )
}

function InventoryRow({ item, several, locations, spots, selected, onSelect, moving, onMove, onQuantity, onMoved }: {
  item: Item; several: boolean; locations: StoreLocation[]; spots: Spot[]; selected: boolean; onSelect: (shift: boolean) => void
  moving: boolean; onMove: () => void; onQuantity: (n: number) => Promise<void>
  onMoved: (body: { locationId: string; storageId: string | null; quantity: number }) => void
}) {
  const [target, setTarget] = useState({ locationId: item.locationId, storageId: '', quantity: item.quantity })
  const [viewing, setViewing] = useState(false)
  const [why, setWhy] = useState<Why | null>(null)
  const toggleWhy = () => why ? setWhy(null) : api<Why>(`/api/app/inventory/${item.id}/why`).then(setWhy).catch(() => setWhy({ steps: [], destination: [] }))
  const synced = !!item.clubLinkId
  const treatments = (item.treatments ?? '').split(',').filter(Boolean).map(t => TREATMENTS[t] ?? titleCase(t))
  const span = COLUMNS.length + 2
  return (
    <>
      <tr className={selected ? 'selected' : ''}>
        <td className="check"><input type="checkbox" aria-label={`Select ${item.name}`} checked={selected}
          onClick={e => onSelect(e.shiftKey)} onChange={() => { /* the click handler knows about Shift */ }} /></td>
        <td className="card-cell"><div className="card-id">
          <RowThumb item={item} onOpen={() => setViewing(true)} />
          <div><strong>{item.name}</strong>
            {item.typeLine && <div className="muted small">{item.typeLine}</div>}</div>
        </div></td>
        <td className="set-cell" title={item.setName ?? undefined}><span className="set-code">{item.set.toUpperCase()}</span> <span className="muted">#{item.number}</span></td>
        <td className="num c-year">{item.year ?? <span className="muted">—</span>}</td>
        <td className="c-color"><ColorPips colors={item.colors} /></td>
        <td className="c-rarity">{item.rarity ? <span className={`rarity ${item.rarity}`}>{item.rarity}</span> : <span className="muted">—</span>}</td>
        <td className="c-finish"><span style={{ textTransform: 'capitalize' }}>{item.finish}</span>{treatments.length > 0 && <div className="muted small">{treatments.join(', ')}</div>}</td>
        <td className="c-cond">{item.condition}</td>
        <td className="c-where">{several && <div className="muted small">{item.location}</div>}
          {item.path.length ? placeText(item.path) : <span className="unshelved">Not put away</span>}
          {item.destination?.length > 0 && <button type="button" className="headed link" aria-expanded={!!why}
            title="Where your storage rules send it. Click to see why." onClick={toggleWhy}>→ {placeText(item.destination)}</button>}</td>
        <td className="c-collection">{synced ? item.clubCollection : <span className="muted">Store stock</span>}</td>
        <td className="c-batch">{item.batchName ?? <span className="muted">—</span>}</td>
        <td className="r c-market">{money(item.market)}</td>
        <td className="r c-qty">
          {synced ? item.quantity : <QuantityStepper item={item} onQuantity={onQuantity} />}
        </td>
        <td className="r row-links">
          <button className="link" onClick={onMove}>{moving ? 'Cancel' : 'Move'}</button>
        </td>
      </tr>
      {why && (
        <tr className="move-row"><td colSpan={span}><WhyHere why={why} /></td></tr>
      )}
      {viewing && <CardLightbox slides={slidesOf(item)} onClose={() => setViewing(false)} />}
      {moving && (
        <tr className="move-row"><td colSpan={span}>
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
            {synced ? <span className="muted small">Synced cards move as a whole line; CardBox keeps the count.</span> : (
              <label>How many
                <input type="number" min={1} max={item.quantity} value={target.quantity}
                  onChange={e => setTarget({ ...target, quantity: Number(e.target.value) })} />
              </label>
            )}
            <button type="submit" className="small">Move {target.quantity}</button>
          </form>
        </td></tr>
      )}
    </>
  )
}

interface WhyStep { spotId: string; path: PathPart[]; level: number; outcome: 'fits' | 'no' | 'full' | 'through' | 'empty' | 'later'; conditions: Conditions | null }
interface Why { steps: WhyStep[]; destination: PathPart[] }

const OUTCOMES: Record<WhyStep['outcome'], string> = {
  fits: 'takes it', no: "doesn't fit", full: 'fits, but has no room left', through: 'no rule of its own, passes it inside', empty: 'no rule', later: 'not checked: an earlier spot took it',
}

/** How the rules walked a card down the storage tree: each spot they looked at, in order, and what its rule said. */
function WhyHere({ why }: { why: Why }) {
  if (why.steps.length === 0) return <p className="muted small">No storage rules apply to this line.</p>
  return (
    <ol className="plain why-here" aria-label="Why the rules send it here">
      {why.steps.map(step => (
        <li key={step.spotId} className={`why ${step.outcome}`} style={{ paddingLeft: step.level * 18 }}>
          <span className="spot">{step.path[step.path.length - 1] ? `${step.path[step.path.length - 1].label} ${step.path[step.path.length - 1].name}` : ''}</span>
          {step.conditions && <span className="muted"> · holds {ruleText(step.conditions).replace(/^Everything else$/, 'everything else')}</span>}
          <span className="outcome"> → {OUTCOMES[step.outcome]}</span>
        </li>
      ))}
    </ol>
  )
}

/** Finds a card in the catalog and adds copies of it straight to a location or storage spot. */
function AddCards({ locations, spots, defaultLocation, onAdded }: {
  locations: StoreLocation[]; spots: Spot[]; defaultLocation: string; onAdded: () => void
}) {
  const [query, setQuery] = useState('')
  const [game, setGame] = useState<Game>('mtg')
  const [results, setResults] = useState<Card[]>([])
  // The search that came back empty, so the other-games hint answers it and not a query still being typed.
  const [noMatch, setNoMatch] = useState('')
  const [card, setCard] = useState<Card | null>(null)
  const [form, setForm] = useState({ finish: 'normal', condition: 'NM', quantity: 1, locationId: defaultLocation, storageId: '' })
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [active, setActive] = useState(0)
  const [busy, setBusy] = useState(false)
  const searchRef = useRef<HTMLInputElement>(null)
  const qtyRef = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (query.trim().length < 2) return
    const controller = new AbortController()
    const t = setTimeout(() => api<Card[]>(`/api/app/cards?game=${game}&q=${encodeURIComponent(query.trim())}`, { signal: controller.signal })
      .then(r => { setResults(r); setActive(0); setNoMatch(r.length === 0 ? query.trim() : '') }).catch(e => { if (!aborted(e)) setError(e.message) }), 250)
    return () => { clearTimeout(t); controller.abort() }
  }, [query, game])
  const finishes = useMemo(() => card ? FINISHES.filter(f => f.price(card) != null) : [], [card])
  const shown = query.trim().length >= 2 ? results.slice(0, 12) : []

  function pick(c: Card) {
    setCard(c); setResults([]); setQuery('')
    const available = FINISHES.filter(f => f.price(c) != null)
    setForm(f => ({ ...f, finish: available.some(x => x.key === f.finish) ? f.finish : available[0]?.key ?? 'normal' }))
    // Straight to the count: type it and press Enter to add.
    requestAnimationFrame(() => qtyRef.current?.select())
  }
  function onSearchKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive(i => Math.min(shown.length - 1, i + 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(i => Math.max(0, i - 1)) }
    else if (e.key === 'Enter' && shown[active]) { e.preventDefault(); pick(shown[active]) }
    else if (e.key === 'Escape') setQuery('')
  }
  async function add(e: React.FormEvent) {
    e.preventDefault()
    if (!card || busy) return
    setBusy(true)
    try {
      await api('/api/app/inventory', { method: 'POST', body: { cardId: card.id, ...form, storageId: form.storageId || null } })
      setMessage(`Added ${form.quantity} × ${card.name}.`); setError('')
      setCard(null); setForm(f => ({ ...f, quantity: 1 }))
      // Back to the search for the next card; location, spot, finish and condition carry over.
      searchRef.current?.focus()
      onAdded()
    } catch (err) { setError((err as Error).message); setMessage('') }
    finally { setBusy(false) }
  }

  return (
    <div className="panel add-cards">
      <GameSwitch game={game} onChange={g => { setGame(g); setNoMatch(''); searchRef.current?.focus() }} />
      <div className="search">
        <SearchIcon />
        <input ref={searchRef} autoFocus placeholder="Find a card to add" aria-label="Find a card to add" value={query}
          onChange={e => setQuery(e.target.value)} onKeyDown={onSearchKey}
          role="combobox" aria-autocomplete="list" aria-expanded={shown.length > 0} aria-controls="add-matches"
          aria-activedescendant={shown[active] ? `add-match-${active}` : undefined} />
        {shown.length > 0 && <span className="aside hints"><span className="kbd">↑</span><span className="kbd">↓</span> <span className="kbd">Enter</span> picks</span>}
      </div>
      {noMatch && noMatch === query.trim() && <>
        <p className="muted">No cards found.</p>
        <OtherGames query={noMatch} game={game} onPick={g => { setGame(g); setNoMatch(''); searchRef.current?.focus() }} />
      </>}
      {shown.length > 0 && (
        <ul className="pick-list" id="add-matches" role="listbox">
          {shown.map((c, i) => (
            <li key={c.id} id={`add-match-${i}`} role="option" aria-selected={i === active}>
              <button type="button" tabIndex={-1} className={i === active ? 'secondary active' : 'secondary'} onMouseEnter={() => setActive(i)} onClick={() => pick(c)}>
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
          <label>Qty<input ref={qtyRef} type="number" min={1} max={9999} value={form.quantity} onChange={e => setForm({ ...form, quantity: Number(e.target.value) })} /></label>
          {locations.length > 1 && (
            <label>Location<select value={form.locationId} onChange={e => setForm({ ...form, locationId: e.target.value, storageId: '' })}>
              {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}</select></label>
          )}
          <label>Put in<select value={form.storageId} onChange={e => setForm({ ...form, storageId: e.target.value })}>
            <option value="">Not put away</option><SpotOptions spots={spots} locationId={form.locationId} /></select></label>
          <button type="submit" disabled={busy}>{busy ? 'Adding…' : 'Add to inventory'}</button>
        </form>
      )}
      {message && <p className="notice">{message}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  )
}

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, CONDITIONS, FINISHES, money, type Card, type Money, type StoreLocation } from '../api'
import { flatTree, pathOf, pathText, type PathPart, type Spot } from '../storage'
import SearchIcon from '../SearchIcon'
import { FACETS, TREATMENTS, ruleText, titleCase, valueLabel, type Conditions, type Facets } from '../cardDetails'
import { ColorPips, FacetMenu, PriceMenu } from '../cardFilters'
import ClubCollections from './ClubCollections'

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
}
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

const COLUMNS: { key: string; label: string; right?: boolean; sort?: string }[] = [
  { key: 'card', label: 'Card', sort: 'name' }, { key: 'set', label: 'Set', sort: 'set' }, { key: 'year', label: 'Year', sort: 'year' },
  { key: 'color', label: 'Color', sort: 'color' }, { key: 'rarity', label: 'Rarity', sort: 'rarity' },
  { key: 'finish', label: 'Finish', sort: 'finish' }, { key: 'condition', label: 'Cond.', sort: 'condition' },
  { key: 'where', label: 'Where', sort: 'where' }, { key: 'market', label: 'Market', right: true, sort: 'market' },
  { key: 'quantity', label: 'Qty', right: true, sort: 'quantity' },
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
    <option key={s.id} value={s.id}>{'  '.repeat(s.depth)}{s.label} {s.name}</option>)}</>
}


/** Stock on hand: find it by any detail, pick lines (or everything matching), and put them away in bulk. */
export default function Inventory({ locations, registerLocationId, owner }: { locations: StoreLocation[]; registerLocationId: string | null; owner: boolean }) {
  const open = locations.filter(l => !l.archived)
  const several = locations.length > 1
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
  const setQuantity = (item: Item, quantity: number) => {
    if (quantity === 0 && !confirm(`Remove ${item.name} (${item.condition}) from inventory?`)) return
    change(api(`/api/app/inventory/${item.id}`, { method: 'PUT', body: { quantity } }))
  }

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
            onClick={() => { setView(v.key); setSavedId(null); setFilters(f => ({ ...f, storage: [], rules: [] })) }}>
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
        <select aria-label="Where" value={filters.storage?.[0] ?? ''} onChange={e => setFilter('storage', e.target.value ? [e.target.value] : [])}>
          <option value="">Anywhere in this view</option>
          <option value="none">Not put away</option>
          <option value="any">Put away anywhere</option>
          {filterLocation && <SpotOptions spots={spots} locationId={filterLocation} />}
        </select>
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

      <div className="table-wrap">
        <table className="grid inventory dense">
          <thead><tr>
            <th className="check"><input type="checkbox" aria-label="Select every line shown" checked={allVisible || allMatching} onChange={toggleAll} /></th>
            {COLUMNS.map(c => (
              <th key={c.key} className={c.right ? 'r' : ''} aria-sort={sort.key === c.sort ? (sort.dir === 'asc' ? 'ascending' : 'descending') : undefined}>
                {c.sort ? <button className="sort" onClick={() => sortBy(c.sort!)}>{c.label}<span className="arrow" aria-hidden>{sort.key === c.sort ? (sort.dir === 'asc' ? '▲' : '▼') : ''}</span></button> : c.label}
              </th>
            ))}
            <th><span className="sr-only">Actions</span></th>
          </tr></thead>
          <tbody>
            {items.map((item, index) => (
              <InventoryRow key={item.id} item={item} several={several} locations={open} spots={spots}
                selected={allMatching || selected.has(item.id)} onSelect={shift => toggle(index, shift)}
                moving={moving === item.id} onMove={() => setMoving(moving === item.id ? null : item.id)}
                onQuantity={n => setQuantity(item, n)}
                onMoved={body => { setMoving(null); change(api(`/api/app/inventory/${item.id}/move`, { method: 'POST', body })) }} />
            ))}
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
            const label = (several ? `${g.location} › ` : '') + pathText(g.path)
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
        const path = pathText(pathOf(spots, s.id))
        const label = several ? `${l.name} › ${path}` : path
        out.push({ locationId: l.id, storageId: s.id, label, depth: s.depth + 1, words: label.toLowerCase().split(/[\s›:]+/).filter(Boolean) })
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

function InventoryRow({ item, several, locations, spots, selected, onSelect, moving, onMove, onQuantity, onMoved }: {
  item: Item; several: boolean; locations: StoreLocation[]; spots: Spot[]; selected: boolean; onSelect: (shift: boolean) => void
  moving: boolean; onMove: () => void; onQuantity: (n: number) => void
  onMoved: (body: { locationId: string; storageId: string | null; quantity: number }) => void
}) {
  const [target, setTarget] = useState({ locationId: item.locationId, storageId: '', quantity: item.quantity })
  const [scans, setScans] = useState<{ itemId: string; quantity: number; image: string | null; details: string | null }[] | null>(null)
  const toggleScans = () => scans ? setScans(null) : api<typeof scans>(`/api/app/club-links/scans/${item.id}`).then(setScans).catch(() => setScans([]))
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
        <td className="card-cell"><strong>{item.name}</strong>
          <div className="muted small">{[item.typeLine, synced ? `CardBox: ${item.clubCollection}` : null].filter(Boolean).join(' · ')}</div></td>
        <td className="set-cell" title={item.setName ?? undefined}><span className="set-code">{item.set.toUpperCase()}</span> <span className="muted">#{item.number}</span></td>
        <td className="num">{item.year ?? <span className="muted">—</span>}</td>
        <td><ColorPips colors={item.colors} /></td>
        <td>{item.rarity ? <span className={`rarity ${item.rarity}`}>{item.rarity}</span> : <span className="muted">—</span>}</td>
        <td><span style={{ textTransform: 'capitalize' }}>{item.finish}</span>{treatments.length > 0 && <div className="muted small">{treatments.join(', ')}</div>}</td>
        <td>{item.condition}</td>
        <td>{several && <div className="muted small">{item.location}</div>}
          {item.path.length ? pathText(item.path) : <span className="unshelved">Not put away</span>}
          {item.destination?.length > 0 && <button type="button" className="headed link" aria-expanded={!!why}
            title="Where your storage rules send it. Click to see why." onClick={toggleWhy}>→ {pathText(item.destination)}</button>}</td>
        <td className="r">{money(item.market)}</td>
        <td className="r">
          {synced ? item.quantity : (
            <span className="stepper">
              <button aria-label="One fewer" onClick={() => onQuantity(item.quantity - 1)}>−</button>
              <span>{item.quantity}</span>
              <button aria-label="One more" onClick={() => onQuantity(item.quantity + 1)}>+</button>
            </span>
          )}
        </td>
        <td className="r row-links">
          <button className="link" onClick={onMove}>{moving ? 'Cancel' : 'Move'}</button>
          {synced && <button className="link" onClick={toggleScans}>{scans ? 'Hide scans' : 'Scans'}</button>}
        </td>
      </tr>
      {why && (
        <tr className="move-row"><td colSpan={span}><WhyHere why={why} /></td></tr>
      )}
      {scans && (
        <tr className="move-row"><td colSpan={span}>
          <ul className="plain scans">
            {scans.map(s => (
              <li key={s.itemId}>
                {s.image ? <a href={s.image} target="_blank" rel="noreferrer"><img src={s.image} alt={`Scan of ${item.name}`} className="scan-thumb" loading="lazy" /></a>
                  : <span className="muted small">No photo</span>}
                {s.quantity > 1 && <span className="small"> ×{s.quantity}</span>}
                {s.details && <span className="muted small"> · {Object.entries(JSON.parse(s.details) as Record<string, unknown>).map(([k, v]) => `${k.replace(/_/g, ' ')}: ${String(v)}`).join(' · ')}</span>}
              </li>
            ))}
          </ul>
          {scans.length === 0 && <p className="muted small">No scans for this line.</p>}
        </td></tr>
      )}
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

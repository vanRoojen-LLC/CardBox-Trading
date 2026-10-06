import { useEffect, useMemo, useState } from 'react'
import { api, type StoreLocation } from '../api'
import { expandNames, flatTree, pathOf, pathText, type Spot } from '../storage'
import { FACETS, KNOWN, ruleText, type Conditions, type FacetValue, type Facets } from '../cardDetails'
import { FacetMenu } from '../cardFilters'

interface Rule { spotId: string; conditions: Conditions; cards: number; waiting: number }

type Editing = { mode: 'add'; parentId: string | null } | { mode: 'rename'; spot: Spot }

/**
 * The storage layout inside each location, designed by the store: any number of tiers, each with its own label
 * and options (Store room › Shelf › Box › Section, or whatever fits). Owners edit it; everyone can see it.
 */
export default function StorageEditor({ owner, locations }: { owner: boolean; locations: StoreLocation[] }) {
  const open = locations.filter(l => !l.archived)
  const [locationId, setLocationId] = useState(open[0]?.id ?? '')
  const [spots, setSpots] = useState<Spot[]>([])
  const [editing, setEditing] = useState<Editing | null>(null)
  const [label, setLabel] = useState('')
  const [names, setNames] = useState('')
  const [error, setError] = useState('')
  const [rules, setRules] = useState<Rule[]>([])
  const [ruleFor, setRuleFor] = useState<string | null>(null)
  const [facets, setFacets] = useState<Facets>({})
  useEffect(() => { api<Spot[]>('/api/app/storage').then(setSpots).catch(e => setError(e.message)) }, [])
  useEffect(() => { api<Rule[]>('/api/app/storage/rules').then(setRules).catch(() => setRules([])) }, [])
  useEffect(() => {
    if (!locationId) return
    api<Facets>(`/api/app/inventory/facets?location=${locationId}`).then(setFacets).catch(() => setFacets({}))
  }, [locationId])
  const ruleOf = useMemo(() => new Map(rules.map(r => [r.spotId, r])), [rules])
  const tree = useMemo(() => flatTree(spots, locationId), [spots, locationId])
  // Cards in each spot counting everything inside it, which is what a capacity limits.
  const held = useMemo(() => {
    const parent = new Map(spots.map(s => [s.id, s.parentId]))
    const out = new Map<string, number>()
    for (const s of spots) for (let at: string | null | undefined = s.id; at; at = parent.get(at)) out.set(at, (out.get(at) ?? 0) + s.cards)
    return out
  }, [spots])
  const preview = expandNames(names)

  /** Labels to suggest: what siblings already use first, then anything used at the same depth, then everywhere. */
  const suggestions = useMemo(() => {
    if (!editing || editing.mode !== 'add') return []
    const depth = editing.parentId === null ? 0 : (tree.find(s => s.id === editing.parentId)?.depth ?? -1) + 1
    const ordered = [
      ...tree.filter(s => s.parentId === editing.parentId), ...tree.filter(s => s.depth === depth), ...spots]
    return [...new Set(ordered.map(s => s.label))]
  }, [editing, tree, spots])

  function start(next: Editing) {
    setEditing(next); setError(''); setRuleFor(null)
    if (next.mode === 'rename') { setLabel(next.spot.label); setNames(next.spot.name) }
    else {
      const siblings = tree.filter(s => s.parentId === next.parentId)
      setLabel(siblings.at(-1)?.label ?? ''); setNames('')
    }
  }
  async function save(e: React.FormEvent) {
    e.preventDefault()
    if (!editing) return
    try {
      setSpots(editing.mode === 'add'
        ? await api<Spot[]>('/api/app/storage', { method: 'POST', body: { locationId, parentId: editing.parentId, label, names: preview } })
        : await api<Spot[]>(`/api/app/storage/${editing.spot.id}`, { method: 'PUT', body: { label, name: names } }))
      setEditing(null); setError('')
    } catch (err) { setError((err as Error).message) }
  }
  async function remove(s: Spot) {
    const note = s.cards > 0 ? ` Its ${s.cards} card${s.cards === 1 ? '' : 's'} move up a level.` : ''
    if (!confirm(`Remove ${s.label} ${s.name}?${note}`)) return
    try { setSpots(await api<Spot[]>(`/api/app/storage/${s.id}/remove`, { method: 'POST', body: {} })); setError('') }
    catch (err) { setError((err as Error).message) }
  }
  async function move(s: Spot, direction: 'up' | 'down') {
    try {
      setSpots(await api<Spot[]>(`/api/app/storage/${s.id}/reorder`, { method: 'POST', body: { direction } }))
      // Order decides which rule wins, so the counts can change.
      setRules(await api<Rule[]>('/api/app/storage/rules')); setError('')
    } catch (err) { setError((err as Error).message) }
  }

  const form = editing && (
    <form className="spot-form" onSubmit={save}>
      <label>Tier label
        <input required maxLength={40} list="tier-labels" placeholder="e.g. Store room, Shelf, Box, Section" value={label}
          autoFocus onChange={e => setLabel(e.target.value)} />
        <datalist id="tier-labels">{suggestions.map(s => <option key={s} value={s} />)}</datalist>
      </label>
      {editing.mode === 'add' ? (
        <label>Options
          <input required placeholder="e.g. A-F, 1-20, or Front, Back" value={names} onChange={e => setNames(e.target.value)} />
          {preview.length > 0 && <span className="muted small hint">Adds {preview.length}: {preview.slice(0, 12).map(n => `${label || '…'} ${n}`).join(', ')}{preview.length > 12 ? ', …' : ''}</span>}
        </label>
      ) : (
        <label>Name<input required maxLength={60} value={names} onChange={e => setNames(e.target.value)} /></label>
      )}
      <p className="actions">
        <button type="submit" className="small">{editing.mode === 'add' ? 'Add' : 'Save'}</button>
        <button type="button" className="secondary small" onClick={() => setEditing(null)}>Cancel</button>
      </p>
    </form>
  )

  return (
    <div className="panel storage">
      <div className="panel-head">
        <h2>Storage</h2>
        {open.length > 1 && (
          <select aria-label="Location" value={locationId} onChange={e => { setLocationId(e.target.value); setEditing(null) }}>
            {open.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
          </select>
        )}
      </div>
      <p className="muted small">Lay out where cards live, as many tiers deep as you need. Each tier gets your own label and options, so a store room can hold shelves, a shelf boxes, and a box sections.</p>
      <p className="muted small">Say what goes in each spot and Inventory suggests where every new card belongs. A card goes down the tree: at each level the first spot that fits wins, so put narrower spots above an “everything else” spot.</p>
      {error && <p className="error">{error}</p>}
      {rules.length > 0 && <ArrivalSetting owner={owner} />}
      {tree.length === 0 && !editing && <p className="empty">No storage set up here yet. Cards still count toward the location until they're put away.</p>}
      <ul className="spot-tree">
        {tree.map(s => (
          <li key={s.id} style={{ paddingLeft: s.depth * 22 }}>
            <div className="spot-row">
              <span><span className="muted">{s.label}</span> <strong>{s.name}</strong>
                {s.capacity ? <Fill held={held.get(s.id) ?? 0} capacity={s.capacity} />
                  : s.cards > 0 && <span className="muted small"> · {s.cards} card{s.cards === 1 ? '' : 's'}</span>}
                <RuleSummary rule={ruleOf.get(s.id)} facets={facets} /></span>
              {owner && (
                <span className="row-actions">
                  <button className="link" onClick={() => { setEditing(null); setRuleFor(ruleFor === s.id ? null : s.id) }}>What goes here</button>
                  <button className="link icon" aria-label={`Move ${s.label} ${s.name} up`} title="Move up"
                    disabled={tree.find(o => o.parentId === s.parentId)?.id === s.id} onClick={() => move(s, 'up')}>↑</button>
                  <button className="link icon" aria-label={`Move ${s.label} ${s.name} down`} title="Move down"
                    disabled={tree.filter(o => o.parentId === s.parentId).at(-1)?.id === s.id} onClick={() => move(s, 'down')}>↓</button>
                  <button className="link" onClick={() => start({ mode: 'add', parentId: s.id })}>Add inside</button>
                  <button className="link" title={`Change the tier label or name of ${s.label} ${s.name}`} onClick={() => start({ mode: 'rename', spot: s })}>Edit</button>
                  <button className="link" onClick={() => remove(s)}>Remove</button>
                </span>
              )}
            </div>
            {editing && ((editing.mode === 'add' && editing.parentId === s.id) || (editing.mode === 'rename' && editing.spot.id === s.id)) && form}
            {ruleFor === s.id && (
              <RuleEditor spot={s} spots={spots} rule={ruleOf.get(s.id)} ruleOf={ruleOf} facets={facets}
                onSaved={next => { setRules(next); setRuleFor(null) }} onCancel={() => setRuleFor(null)}
                onCapacity={() => api<Spot[]>('/api/app/storage').then(setSpots)} />
            )}
          </li>
        ))}
      </ul>
      {owner && (editing?.mode === 'add' && editing.parentId === null ? form
        : <button className="secondary small" onClick={() => start({ mode: 'add', parentId: null })}>Add top-level storage</button>)}
    </div>
  )
}

/**
 * Whether new cards from trades and CardBox collections are filed by the rules straight away. Off, Inventory suggests
 * a spot and staff confirm from the put-away list.
 */
function ArrivalSetting({ owner }: { owner: boolean }) {
  const [on, setOn] = useState<boolean | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => { api<{ fileOnArrival: boolean }>('/api/app/storage/settings').then(s => setOn(s.fileOnArrival)).catch(() => setOn(null)) }, [])
  async function change(next: boolean) {
    setSaving(true)
    try { setOn((await api<{ fileOnArrival: boolean }>('/api/app/storage/settings', { method: 'PUT', body: { fileOnArrival: next } })).fileOnArrival); setError('') }
    catch (e) { setError((e as Error).message) }
    finally { setSaving(false) }
  }
  if (on === null) return null
  return (
    <div className="arrival-setting">
      <label className="check-row">
        <input type="checkbox" checked={on} disabled={!owner || saving} onChange={e => change(e.target.checked)} />
        <span><strong>File new cards by these rules as they arrive</strong>
          <span className="muted small"> {on
            ? 'Cards from trades and CardBox collections go straight to their spot. Cards no rule fits, and any someone has placed by hand, wait on Inventory.'
            : 'Off: Inventory suggests a spot for each new card, and staff confirm from the put-away list.'}</span></span>
      </label>
      {!owner && <p className="muted small">Only a store owner can change this.</p>}
      {error && <p className="error">{error}</p>}
    </div>
  )
}

/** How full a spot with a limit is; over the limit when every spot its cards fit was already full. */
function Fill({ held, capacity }: { held: number; capacity: number }) {
  const over = held > capacity
  return (
    <span className={over ? 'fill over' : 'fill'} title={over ? 'Over its limit: every spot these cards fit was full' : undefined}>
      {' · '}{held.toLocaleString()} of {capacity.toLocaleString()} cards{over ? `, ${(held - capacity).toLocaleString()} over` : ''}
      <span className="bar" aria-hidden><span style={{ width: `${Math.min(100, Math.round(held / capacity * 100))}%` }} /></span>
    </span>
  )
}

function RuleSummary({ rule, facets }: { rule: Rule | undefined; facets: Facets }) {
  if (!rule) return null
  return (
    <span className="rule-summary">
      <span className="rule-text">Holds {ruleText(rule.conditions, facets).replace(/^Everything else$/, 'everything else')}</span>
      <span className="muted small"> · {rule.cards} card{rule.cards === 1 ? '' : 's'} in stock{rule.waiting > 0 ? `, ${rule.waiting} to put away` : ''}</span>
    </span>
  )
}

/** Picks what goes in one spot, showing live how many cards in stock that would send there. */
function RuleEditor({ spot, spots, rule, ruleOf, facets, onSaved, onCancel, onCapacity }: {
  spot: Spot; spots: Spot[]; rule: Rule | undefined; ruleOf: Map<string, Rule>; facets: Facets
  onSaved: (rules: Rule[]) => void; onCancel: () => void; onCapacity: () => void
}) {
  const [conditions, setConditions] = useState<Conditions>(rule?.conditions ?? {})
  const [capacity, setCapacity] = useState(spot.capacity ? String(spot.capacity) : '')
  const [count, setCount] = useState<{ cards: number; waiting: number } | null>(null)
  const [error, setError] = useState('')
  const path = pathOf(spots, spot.id)
  const ancestors = path.slice(0, -1).filter(p => ruleOf.has((p as Spot).id)) as Spot[]

  useEffect(() => {
    const timer = setTimeout(() => {
      api<{ cards: number; waiting: number }>(`/api/app/storage/${spot.id}/rule/preview`, { method: 'POST', body: { conditions } })
        .then(setCount).catch(() => setCount(null))
    }, 250)
    return () => clearTimeout(timer)
  }, [spot.id, conditions])

  const set = (key: string, values: string[]) => setConditions(c => {
    const next = { ...c }
    if (values.length) next[key] = values
    else delete next[key]
    return next
  })
  const one = (key: string) => conditions[key]?.[0] ?? ''
  /** Known values first, then whatever stock has that the list doesn't. */
  const values = (key: string): FacetValue[] => {
    const stock = facets[key] ?? []
    const known = (KNOWN[key] ?? []).map(v => stock.find(s => s.value === v) ?? { value: v, label: null, cards: null })
    return [...known, ...stock.filter(s => !known.some(k => k.value === s.value))]
  }

  async function save(e: React.FormEvent) {
    e.preventDefault()
    const limit = capacity.trim() === '' ? null : Number(capacity)
    if (limit !== null && (!Number.isInteger(limit) || limit < 1)) { setError('Holds up to must be a whole number of cards, or blank for no limit.'); return }
    try {
      if (limit !== (spot.capacity ?? null)) {
        await api(`/api/app/storage/${spot.id}/capacity`, { method: 'PUT', body: { capacity: limit } })
        onCapacity()
      }
      onSaved(await api<Rule[]>(`/api/app/storage/${spot.id}/rule`, { method: 'PUT', body: { conditions } }))
    } catch (err) { setError((err as Error).message) }
  }
  async function remove() {
    try { onSaved(await api<Rule[]>(`/api/app/storage/${spot.id}/rule`, { method: 'DELETE', body: {} })) }
    catch (err) { setError((err as Error).message) }
  }

  return (
    <form className="rule-form" onSubmit={save}>
      <h3>What goes in {pathText(path)}?</h3>
      {ancestors.length > 0 && <p className="muted small">Only cards that fit {pathText([ancestors.at(-1)!])} come this far.</p>}
      <div className="inv-chips">
        {FACETS.filter(f => f.key !== 'source' && f.key !== 'batch').map(f => (
          <FacetMenu key={f.key} facet={f} values={values(f.key)} picked={conditions[f.key] ?? []} onChange={v => set(f.key, v)}
            custom={f.key === 'set' || f.key === 'year'} always />
        ))}
      </div>
      <div className="rule-ranges">
        <label>Names from<input maxLength={20} placeholder="A" value={one('nameFrom')} onChange={e => set('nameFrom', e.target.value.trim() ? [e.target.value.trim()] : [])} /></label>
        <label>to<input maxLength={20} placeholder="Z" value={one('nameTo')} onChange={e => set('nameTo', e.target.value.trim() ? [e.target.value.trim()] : [])} /></label>
        <label>Price from $<input inputMode="decimal" placeholder="0" value={one('priceMin')} onChange={e => set('priceMin', e.target.value.trim() ? [e.target.value.trim()] : [])} /></label>
        <label>to $<input inputMode="decimal" placeholder="any" value={one('priceMax')} onChange={e => set('priceMax', e.target.value.trim() ? [e.target.value.trim()] : [])} /></label>
        <label title="When it's full, cards go on to the next spot whose rule fits them">Holds up to
          <input inputMode="numeric" placeholder="no limit" value={capacity} onChange={e => setCapacity(e.target.value.replace(/[^\d]/g, ''))} /> cards</label>
      </div>
      <p className="rule-preview">
        <strong>{ruleText(conditions, facets)}</strong>
        {count && <span className="muted"> · {count.cards} card{count.cards === 1 ? '' : 's'} in stock would go here{count.waiting > 0 ? `, ${count.waiting} not put away yet` : ''}</span>}
      </p>
      {Object.keys(conditions).length === 0 && <p className="muted small">With nothing picked this spot takes everything that reaches it and no spot above it took.</p>}
      {error && <p className="error">{error}</p>}
      <p className="actions">
        <button type="submit" className="small">Save rule</button>
        {rule && <button type="button" className="secondary small" onClick={remove}>Remove rule</button>}
        <button type="button" className="secondary small" onClick={onCancel}>Cancel</button>
      </p>
    </form>
  )
}

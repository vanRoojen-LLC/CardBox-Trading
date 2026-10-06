import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react'
import { COLORS, valueLabel, type FacetValue } from './cardDetails'

export function ColorPips({ colors }: { colors: string | null }) {
  if (colors === null) return <span className="muted">—</span>
  if (colors === '') return <span className="pip C" title="Colorless" />
  return <span className="pips" title={[...colors].map(c => COLORS[c]).join(', ')}>{[...colors].map(c => <span key={c} className={`pip ${c}`} />)}</span>
}

/**
 * Where a header filter's menu opens: fixed under its button, kept on screen, because the table scrolls sideways
 * and would clip a menu placed inside it. Scrolling the page closes it rather than leaving it behind.
 */
function useHeaderMenu(header: boolean, open: boolean, ref: React.RefObject<HTMLDivElement | null>, setOpen: (open: boolean) => void): CSSProperties | undefined {
  const [style, setStyle] = useState<CSSProperties>()
  useLayoutEffect(() => {
    if (!header || !open || !ref.current) return
    const r = ref.current.getBoundingClientRect()
    const width = Math.min(320, window.innerWidth - 16)
    setStyle({ position: 'fixed', top: r.bottom + 6, left: Math.max(8, Math.min(r.left, window.innerWidth - width - 8)), width })
    const onScroll = (e: Event) => { if (!(e.target instanceof Node && ref.current?.contains(e.target))) setOpen(false) }
    const onResize = () => setOpen(false)
    window.addEventListener('scroll', onScroll, true); window.addEventListener('resize', onResize)
    return () => { window.removeEventListener('scroll', onScroll, true); window.removeEventListener('resize', onResize) }
  }, [header, open, ref, setOpen])
  return header ? style : undefined
}

/**
 * One filter as a menu of its values; picking several matches any of them. Counts show when the values come from
 * stock. With {@code custom}, a typed value that isn't listed can be added (a set code nothing in stock has yet).
 */
export function FacetMenu({ facet, values, picked, onChange, custom = false, always = false, header = false, single = false }: {
  facet: { key: string; label: string; searchable?: boolean }; values: FacetValue[]; picked: string[]; onChange: (v: string[]) => void
  custom?: boolean; always?: boolean
  /** In a table header: a small funnel button beside the column's name instead of a chip. */
  header?: boolean
  /** One value at a time (a spot): picking another replaces it. */
  single?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [find, setFind] = useState('')
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', close); document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('mousedown', close); document.removeEventListener('keydown', esc) }
  }, [open])
  const menuStyle = useHeaderMenu(header, open, ref, setOpen)
  const shown = values.filter(v => !find || valueLabel(facet.key, v).toLowerCase().includes(find.toLowerCase()))
  // Picked values stay listed even when nothing matches them under the other filters.
  const missing = picked.filter(p => !values.some(v => v.value === p)).map(p => ({ value: p, label: null, cards: null }))
  const typed = find.trim().toUpperCase()
  const canAdd = custom && typed && !values.some(v => v.value.toUpperCase() === typed) && !picked.includes(typed)
  if (!always && values.length === 0 && picked.length === 0) return null
  return (
    <div className={`facet${header ? ' in-header' : ''}`} ref={ref}>
      {header
        ? <FilterButton label={facet.label} on={picked.length > 0} open={open} onClick={() => setOpen(!open)} />
        : <button type="button" className={`chip${picked.length ? ' on' : ''}`} aria-expanded={open} onClick={() => setOpen(!open)}>
            {facet.label}{picked.length ? ` · ${picked.length}` : ''} <span aria-hidden className="caret">▾</span>
          </button>}
      {open && (
        <div className="facet-menu" style={menuStyle} role="dialog" aria-label={`${facet.label} filter`}>
          {(facet.searchable || custom || values.length > 12) && (
            <input autoFocus placeholder={custom ? `Find or type a ${facet.label.toLowerCase()}` : `Find a ${facet.label.toLowerCase()}`}
              aria-label={`Find a ${facet.label.toLowerCase()}`} value={find} onChange={e => setFind(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && canAdd) { e.preventDefault(); onChange([...picked, typed]); setFind('') } }} />
          )}
          <ul>
            {[...missing, ...shown].map(v => (
              <li key={v.value}>
                <label className="facet-option">
                  <input type="checkbox" checked={picked.includes(v.value)}
                    onChange={e => onChange(e.target.checked ? (single ? [v.value] : [...picked, v.value]) : picked.filter(p => p !== v.value))} />
                  {facet.key === 'color' && <ColorPips colors={v.value === 'C' ? '' : v.value} />}
                  <span className="facet-label">{valueLabel(facet.key, v)}</span>
                  {v.cards != null && <span className="facet-count">{Number(v.cards).toLocaleString()}</span>}
                </label>
              </li>
            ))}
          </ul>
          {canAdd && <button type="button" className="link" onClick={() => { onChange([...picked, typed]); setFind('') }}>Add “{typed}”</button>}
          {shown.length === 0 && !canAdd && <p className="muted small">Nothing matches.</p>}
          {picked.length > 0 && <button type="button" className="link" onClick={() => onChange([])}>Clear {facet.label.toLowerCase()}</button>}
        </div>
      )}
    </div>
  )
}

/** The funnel that opens a column's filter; filled in when the column is filtering. */
function FilterButton({ label, on, open, onClick }: { label: string; on: boolean; open: boolean; onClick: () => void }) {
  return (
    <button type="button" className={`col-filter${on ? ' on' : ''}`} aria-expanded={open} onClick={onClick}
      aria-label={`Filter by ${label.toLowerCase()}${on ? ' (filtering)' : ''}`} title={`Filter by ${label.toLowerCase()}`}>
      <svg viewBox="0 0 16 16" width="12" height="12" aria-hidden><path d="M2 3h12l-4.5 5.5V13l-3 1.5V8.5z" /></svg>
    </button>
  )
}

export function PriceMenu({ min, max, onChange, header = false }: { min: string; max: string; onChange: (min: string, max: string) => void; header?: boolean }) {
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState({ min, max })
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', close); document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('mousedown', close); document.removeEventListener('keydown', esc) }
  }, [open])
  const toggle = () => { setForm({ min, max }); setOpen(!open) }
  const menuStyle = useHeaderMenu(header, open, ref, setOpen)
  return (
    <div className={`facet${header ? ' in-header' : ''}`} ref={ref}>
      {header ? <FilterButton label="Market price" on={!!(min || max)} open={open} onClick={toggle} />
        : <button type="button" className={`chip${min || max ? ' on' : ''}`} aria-expanded={open} onClick={toggle}>Market price <span aria-hidden className="caret">▾</span></button>}
      {open && (
        <form className="facet-menu price" style={menuStyle} onSubmit={e => { e.preventDefault(); onChange(form.min.trim(), form.max.trim()); setOpen(false) }}>
          <label>At least $<input inputMode="decimal" autoFocus value={form.min} onChange={e => setForm({ ...form, min: e.target.value })} /></label>
          <label>At most $<input inputMode="decimal" value={form.max} onChange={e => setForm({ ...form, max: e.target.value })} /></label>
          <button type="submit" className="small">Apply</button>
        </form>
      )}
    </div>
  )
}


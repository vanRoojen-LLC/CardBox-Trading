import { useEffect, useRef, useState } from 'react'
import { COLORS, valueLabel, type FacetValue } from './cardDetails'

export function ColorPips({ colors }: { colors: string | null }) {
  if (colors === null) return <span className="muted">—</span>
  if (colors === '') return <span className="pip C" title="Colorless" />
  return <span className="pips" title={[...colors].map(c => COLORS[c]).join(', ')}>{[...colors].map(c => <span key={c} className={`pip ${c}`} />)}</span>
}

/**
 * One filter as a menu of its values; picking several matches any of them. Counts show when the values come from
 * stock. With {@code custom}, a typed value that isn't listed can be added (a set code nothing in stock has yet).
 */
export function FacetMenu({ facet, values, picked, onChange, custom = false, always = false }: {
  facet: { key: string; label: string; searchable?: boolean }; values: FacetValue[]; picked: string[]; onChange: (v: string[]) => void
  custom?: boolean; always?: boolean
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
  const shown = values.filter(v => !find || valueLabel(facet.key, v).toLowerCase().includes(find.toLowerCase()))
  // Picked values stay listed even when nothing matches them under the other filters.
  const missing = picked.filter(p => !values.some(v => v.value === p)).map(p => ({ value: p, label: null, cards: null }))
  const typed = find.trim().toUpperCase()
  const canAdd = custom && typed && !values.some(v => v.value.toUpperCase() === typed) && !picked.includes(typed)
  if (!always && values.length === 0 && picked.length === 0) return null
  return (
    <div className="facet" ref={ref}>
      <button type="button" className={`chip${picked.length ? ' on' : ''}`} aria-expanded={open} onClick={() => setOpen(!open)}>
        {facet.label}{picked.length ? ` · ${picked.length}` : ''} <span aria-hidden className="caret">▾</span>
      </button>
      {open && (
        <div className="facet-menu" role="dialog" aria-label={`${facet.label} filter`}>
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
                    onChange={e => onChange(e.target.checked ? [...picked, v.value] : picked.filter(p => p !== v.value))} />
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

export function PriceMenu({ min, max, onChange }: { min: string; max: string; onChange: (min: string, max: string) => void }) {
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState({ min, max })
  return (
    <div className="facet">
      <button type="button" className={`chip${min || max ? ' on' : ''}`} aria-expanded={open} onClick={() => { setForm({ min, max }); setOpen(!open) }}>Market price <span aria-hidden className="caret">▾</span></button>
      {open && (
        <form className="facet-menu price" onSubmit={e => { e.preventDefault(); onChange(form.min.trim(), form.max.trim()); setOpen(false) }}>
          <label>At least $<input inputMode="decimal" autoFocus value={form.min} onChange={e => setForm({ ...form, min: e.target.value })} /></label>
          <label>At most $<input inputMode="decimal" value={form.max} onChange={e => setForm({ ...form, max: e.target.value })} /></label>
          <button type="submit" className="small">Apply</button>
        </form>
      )}
    </div>
  )
}


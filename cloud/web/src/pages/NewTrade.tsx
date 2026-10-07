import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { aborted, api, CONDITIONS, FINISHES, money, type Card } from '../api'
import CardLightbox from '../CardLightbox'
import SearchIcon from '../SearchIcon'
import OtherGames from '../OtherGames'

interface Line { key: number; card: Card; finish: string; condition: string; quantity: number }
interface PricedLine { valuationUnit: number; creditUnit: number; checkUnit: number; creditRate: number; checkRate: number }
interface Quote {
  lines: PricedLine[]
  /** The line keys this quote priced, in order; the server answers line by line. */
  keys: number[]
  /** The request it answers, so Save waits until the offer on screen matches the trade on screen. */
  asked: string
  marketTotal: number
  creditOffer: number
  checkOffer: number
  settlement: { payment: string; credit: number; check: number }
}
interface Saved { id: string; number: number }
type Payment = 'credit' | 'check' | 'partial'
interface Draft { lines: Line[]; payment: Payment; splitCredit: string; phone: string; customerName: string; checkNumber: string }

const EMPTY: Draft = { lines: [], payment: 'credit', splitCredit: '', phone: '', customerName: '', checkNumber: '' }

/** A trade in progress survives moving to another page or reloading, until it is saved. Only this tab sees it. */
function loadDraft(key: string): Draft {
  try {
    const saved = sessionStorage.getItem(key)
    if (saved) return { ...EMPTY, ...JSON.parse(saved) }
  } catch { /* storage blocked or unreadable: start empty */ }
  return EMPTY
}

let nextKey = 1

const VALUE_HELP = 'The market price after your pricing rules (rarity minimums and the like). Offers are worked out from this.'

/** Quantity you can type into (40 commons is one entry, not 39 presses). Out-of-range or blank input snaps back on blur. */
function QuantityInput({ value, label, onChange }: { value: number; label: string; onChange: (n: number) => void }) {
  const [text, setText] = useState<string | null>(null)
  return (
    <input className="qty" type="text" inputMode="numeric" pattern="[0-9]*" aria-label={label} value={text ?? String(value)}
      onFocus={e => e.target.select()}
      onChange={e => {
        const digits = e.target.value.replace(/\D/g, '').slice(0, 3)
        setText(digits)
        const n = Number(digits)
        if (digits && n >= 1) onChange(Math.min(999, n))
      }}
      onBlur={() => setText(null)}
      onKeyDown={e => { if (e.key === 'Enter' || e.key === 'Escape') e.currentTarget.blur() }} />
  )
}

const finishesOf = (card: Card) => FINISHES.filter(f => f.price(card) != null)
const times = (unit: number | undefined, qty: number) => unit == null ? undefined : Number(unit) * qty
/** Shortcuts act on the page only when the user isn't typing into a field. */
const typing = (target: EventTarget | null) =>
  target instanceof HTMLElement && (target.tagName === 'INPUT' || target.tagName === 'SELECT' || target.tagName === 'TEXTAREA')
/** On a touchscreen focus stays in the search box after an add, so the on-screen keyboard stays up for the next card. */
const touch = () => typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches
const lineId = (key: number) => `trade-line-${key}`

/**
 * `locationId` is this register's location; the trade's cards are tagged to it. `draftKey` names the trade in
 * progress for this person and store, so switching store or signing in as someone else never shows it.
 */
export default function NewTrade({ locationId, draftKey }: { locationId: string | null; draftKey: string }) {
  const [draft] = useState(() => loadDraft(draftKey))
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Card[]>([])
  const [active, setActive] = useState(0)
  const [noMatch, setNoMatch] = useState<string | false>(false)
  const [lines, setLines] = useState<Line[]>(() => {
    nextKey = Math.max(nextKey, ...draft.lines.map(l => l.key + 1))
    return draft.lines
  })
  const [selected, setSelected] = useState<number | null>(null)
  const [payment, setPayment] = useState<Payment>(draft.payment)
  const [splitCredit, setSplitCredit] = useState(draft.splitCredit)
  const [phone, setPhone] = useState(draft.phone)
  const [customerName, setCustomerName] = useState(draft.customerName)
  // The name the phone lookup filled in, so a different phone number can replace it (but never a name staff typed).
  const [lookedUpName, setLookedUpName] = useState<string | null>(null)
  const [known, setKnown] = useState<string | null>(null)
  const [checkNumber, setCheckNumber] = useState(draft.checkNumber)
  const [quote, setQuote] = useState<Quote | null>(null)
  // Separate so a quote that succeeds later never wipes a search or save error.
  const [searchError, setSearchError] = useState('')
  const [quoteError, setQuoteError] = useState('')
  const [saveError, setSaveError] = useState('')
  const [saved, setSaved] = useState<Saved | null>(null)
  const [saving, setSaving] = useState(false)
  // A ref as well as state: a second Ctrl+S can arrive before React re-renders with saving = true.
  const savingRef = useRef(false)
  const [enlarged, setEnlarged] = useState<Card | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const linesRef = useRef<HTMLDivElement>(null)
  // Waits a frame for the line to render, and gives way if staff already started typing the next search.
  const focusLine = useCallback((key: number) => requestAnimationFrame(() => {
    if (document.activeElement === searchRef.current && searchRef.current?.value) return
    linesRef.current?.querySelector<HTMLElement>(`#${lineId(key)}`)?.focus()
  }), [])

  useEffect(() => {
    try {
      if (lines.length === 0 && !phone && !customerName) sessionStorage.removeItem(draftKey)
      else sessionStorage.setItem(draftKey, JSON.stringify({ lines, payment, splitCredit, phone, customerName, checkNumber }))
    } catch { /* storage blocked: the trade lasts until the page is left */ }
  }, [draftKey, lines, payment, splitCredit, phone, customerName, checkNumber])

  useEffect(() => {
    if (query.trim().length < 2) { setResults([]); setNoMatch(false); return }
    // Typing on cancels the last search, so a slow reply for "li" never replaces the results for "lightning".
    const controller = new AbortController()
    const timer = setTimeout(() => {
      api<Card[]>(`/api/app/cards?q=${encodeURIComponent(query.trim())}`, { signal: controller.signal })
        .then(r => { setResults(r); setActive(0); setNoMatch(r.length === 0 ? query.trim() : false); setSearchError('') })
        .catch(e => { if (!aborted(e)) setSearchError(e.message) })
    }, 250)
    return () => { clearTimeout(timer); controller.abort() }
  }, [query])

  // A returning customer's name fills in once their full phone number is typed.
  useEffect(() => {
    setKnown(null)
    if (phone.replace(/\D/g, '').length < 10) return
    const timer = setTimeout(() => {
      api<{ phone: string; name: string }[]>(`/api/app/customers?phone=${encodeURIComponent(phone)}`)
        .then(found => {
          if (found.length === 0) return
          setKnown(found[0].name || 'Returning customer')
          const name = found[0].name
          if (name) {
            // Changing the phone already cleared a looked-up name, so anything still here was typed by staff.
            setCustomerName(current => current || name)
            setLookedUpName(name)
          }
        })
        .catch(() => { /* lookup is a convenience; saving still works without it */ })
    }, 250)
    return () => clearTimeout(timer)
  }, [phone])
  function changePhone(value: string) {
    setPhone(value)
    // A different number is a different customer: drop the name the last lookup filled in.
    if (lookedUpName !== null && customerName === lookedUpName) setCustomerName('')
    setLookedUpName(null)
  }

  const request = useMemo(() => ({
    lines: lines.map(l => ({ cardId: l.card.id, finish: l.finish, condition: l.condition, quantity: l.quantity })),
    payment,
    credit: payment === 'partial' && splitCredit !== '' ? Number(splitCredit) : undefined,
  }), [lines, payment, splitCredit])

  useEffect(() => {
    if (lines.length === 0) { setQuote(null); return }
    // Prices are matched to lines by key, and a reply that arrives after a newer request started is dropped.
    const keys = lines.map(l => l.key)
    let current = true
    const asked = JSON.stringify(request)
    const ask = (body: typeof request) => api<Omit<Quote, 'keys' | 'asked'>>('/api/app/trades/quote', { method: 'POST', body })
      .then(q => { if (current) { setQuote({ ...q, keys, asked }); setQuoteError('') } })
      .catch(e => { if (current) setQuoteError(e.message) })
    const timer = payment === 'partial' && splitCredit === ''
      ? (ask({ ...request, payment: 'credit' }), undefined)
      : setTimeout(() => ask(request), 200)
    return () => { current = false; clearTimeout(timer) }
  }, [request, lines, payment, splitCredit])

  function add(card: Card) {
    const finish = finishesOf(card)[0]?.key ?? 'normal'
    // The same card again (same finish, still NM) is one more copy, not a second line.
    const same = lines.find(l => l.card.id === card.id && l.finish === finish && l.condition === 'NM')
    const key = same?.key ?? nextKey++
    if (same) update(key, { quantity: Math.min(999, same.quantity + 1) })
    else setLines([{ key, card, finish, condition: 'NM', quantity: 1 }, ...lines])
    setSelected(key)
    setQuery('')
    setResults([])
    setNoMatch(false)
    setSaved(null)
    setSaveError('')
    // Focus goes to the new line so 1-5, F, +/- and Delete act on it straight away; typing a letter goes back to search.
    if (touch()) searchRef.current?.focus()
    else focusLine(key)
  }
  const update = useCallback((key: number, change: Partial<Line>) =>
    setLines(current => current.map(l => l.key === key ? { ...l, ...change } : l)), [])
  const remove = useCallback((key: number) => {
    const at = lines.findIndex(l => l.key === key)
    const rest = lines.filter(l => l.key !== key)
    setLines(rest)
    // Selection moves to the next line down, so Delete can clear several in a row.
    if (selected === key) {
      const next = rest[Math.min(at, rest.length - 1)]
      setSelected(next?.key ?? null)
      if (next) focusLine(next.key)
      else searchRef.current?.focus()
    }
  }, [lines, selected, focusLine])

  const current = quote != null && quote.asked === JSON.stringify(request)
  const checkPaid = payment === 'check' || (payment === 'partial' && Number(quote?.settlement.check ?? 0) > 0)
  const needsCheckNumber = checkPaid && checkNumber.trim() === ''
  const canSave = lines.length > 0 && current && !(payment === 'partial' && splitCredit === '') && !needsCheckNumber && !saving
  const save = useCallback(async () => {
    if (!canSave || savingRef.current) return
    savingRef.current = true
    setSaving(true)
    try {
      const result = await api<Saved>('/api/app/trades', {
        method: 'POST',
        body: { ...request, customerPhone: phone || undefined, customerName, checkNumber, locationId: locationId ?? undefined },
      })
      setSaved(result)
      setLines([]); setSelected(null); setPhone(''); setCustomerName(''); setLookedUpName(null); setCheckNumber(''); setSplitCredit(''); setPayment('credit')
      setSaveError('')
      searchRef.current?.focus()
    } catch (e) { setSaveError((e as Error).message) }
    finally { savingRef.current = false; setSaving(false) }
  }, [canSave, request, phone, customerName, checkNumber, locationId])

  // Counter shortcuts: / search, 1-5 condition, F foil, + and - quantity, Delete removes, arrows move, Ctrl/Cmd+S save.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); save(); return }
      if (e.ctrlKey || e.metaKey || e.altKey || typing(e.target) || enlarged) return
      if (e.key === '/') { e.preventDefault(); searchRef.current?.focus(); return }
      const at = lines.findIndex(l => l.key === selected)
      const line = lines[at]
      const digit = Number(e.key)
      const shortcut = line && (/^[fF+=-]$/.test(e.key) || (digit >= 1 && digit <= CONDITIONS.length))
      // Any other letter or number starts the next search, so staff never have to aim for the search box between cards.
      if (!shortcut && e.key.length === 1 && /[\p{L}\p{N}]/u.test(e.key)) {
        e.preventDefault(); setQuery(e.key); searchRef.current?.focus(); return
      }
      if (!line) return
      if (digit >= 1 && digit <= CONDITIONS.length) update(line.key, { condition: CONDITIONS[digit - 1] })
      else if (e.key.toLowerCase() === 'f') {
        const options = finishesOf(line.card).map(f => f.key as string)
        if (options.length > 1) update(line.key, { finish: options[(options.indexOf(line.finish) + 1) % options.length] })
      }
      else if (e.key === '+' || e.key === '=') update(line.key, { quantity: Math.min(999, line.quantity + 1) })
      else if (e.key === '-') update(line.key, { quantity: Math.max(1, line.quantity - 1) })
      else if (e.key === 'Delete' || e.key === 'Backspace') remove(line.key)
      else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        const next = lines[Math.max(0, Math.min(lines.length - 1, at + (e.key === 'ArrowDown' ? 1 : -1)))]
        setSelected(next.key); focusLine(next.key)
      }
      else return
      e.preventDefault()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [lines, selected, update, remove, focusLine, save, enlarged])

  function onSearchKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive(i => Math.min(results.length - 1, i + 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(i => Math.max(0, i - 1)) }
    else if (e.key === 'Enter' && results[active]) { e.preventDefault(); add(results[active]) }
    else if (e.key === 'Escape') {
      setQuery('')
      if (selected !== null && lines.some(l => l.key === selected)) focusLine(selected)
      else if (lines.length) e.currentTarget.blur()
    }
  }

  const cards = lines.reduce((n, l) => n + l.quantity, 0)
  const offer = payment === 'check' ? quote?.checkOffer : quote?.creditOffer

  return (
    <section className="trade">
      <div>
        {saved && (
          <p className="notice" style={{ marginTop: 0 }}>
            Trade #{saved.number} saved. <a href={`/api/app/trades/${saved.id}/pos.csv`}>Download POS CSV</a> ·{' '}
            <Link to={`/app/history/${saved.id}`}>View</Link>
          </p>
        )}
        <div className="picker">
          <div className="search">
            <SearchIcon />
            <input ref={searchRef} autoFocus placeholder="Add a card: name, set or number, e.g. DMU 391" value={query}
                   onChange={e => setQuery(e.target.value)} onKeyDown={onSearchKey} aria-label="Add a card"
                   role="combobox" aria-autocomplete="list" aria-expanded={results.length > 0} aria-controls="matches"
                   aria-activedescendant={results[active] ? `match-${active}` : undefined} />
            {results.length > 0 && <span className="aside hints"><span className="kbd">Enter</span> adds the highlighted card</span>}
          </div>
          {noMatch && query.trim().length >= 2 && <>
            <p className="muted">No cards found.</p>
            {/* Trades take Magic cards; a card from another game links to its price instead. */}
            <OtherGames query={noMatch} game="mtg" link={(g, q) => `/app/price?game=${g}&q=${encodeURIComponent(q)}`} />
          </>}
          {searchError && <p className="error" role="alert">{searchError}</p>}
          {results.length > 0 && (
            <ul className="matches" id="matches" role="listbox">
              {results.map((card, i) => (
                <li key={card.id} id={`match-${i}`} role="option" aria-selected={i === active}>
                  <button type="button" tabIndex={-1} className={i === active ? 'active' : ''} onClick={() => add(card)} onMouseEnter={() => setActive(i)}>
                    {card.image ? <img src={card.image} alt="" loading="lazy" /> : <span className="noimg" style={{ width: 36, height: 50 }} />}
                    <span>
                      <span className="name">{card.name}</span>
                      <span className="meta">{card.set.toUpperCase()} #{card.number} · <span style={{ textTransform: 'capitalize' }}>{card.rarity}</span> · {card.setName}</span>
                    </span>
                    <span className="prices">
                      {finishesOf(card).map(f => <span key={f.key}><small>{f.label}</small>{money(f.price(card))}</span>)}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="lines" ref={linesRef}>
          <div className="lines-head">
            <h2>{lines.length === 0 ? 'This trade' : `This trade · ${lines.length} ${lines.length === 1 ? 'line' : 'lines'}, ${cards} ${cards === 1 ? 'card' : 'cards'}`}</h2>
            <span className="hints" title="After a card is added these act on it. Type a card name, or press /, to search again.">
              <span><span className="kbd">/</span> search</span><span><span className="kbd">1</span>–<span className="kbd">5</span> condition</span>
              <span><span className="kbd">F</span> foil</span><span><span className="kbd">+</span> <span className="kbd">−</span> qty</span>
              <span><span className="kbd">Del</span> remove</span><span><span className="kbd">↑</span> <span className="kbd">↓</span> line</span></span>
          </div>
          {lines.length === 0 ? <p className="empty">Search above to add the customer's cards.</p> : (
            <>
              {lines.map(line => {
                const at = quote?.keys.indexOf(line.key) ?? -1
                const priced = at >= 0 ? quote?.lines[at] : undefined
                const finishes = finishesOf(line.card)
                return (
                  <div key={line.key} id={lineId(line.key)} tabIndex={-1} aria-label={`${line.card.name}, ${line.condition}, quantity ${line.quantity}`}
                       className={line.key === selected ? 'line selected' : 'line'} onPointerDown={() => setSelected(line.key)} onFocus={() => setSelected(line.key)}>
                    {line.card.image
                      ? <button type="button" className="thumb" onClick={() => setEnlarged(line.card)} aria-label={`Enlarge ${line.card.name}`}><img src={line.card.image} alt="" /></button>
                      : <span className="noimg" />}
                    <div className="card">
                      <strong>{line.card.name}</strong>
                      <span className="meta">{line.card.set.toUpperCase()} #{line.card.number} · <span style={{ textTransform: 'capitalize' }}>{line.card.rarity}</span></span>
                    </div>
                    <div className="controls">
                      {finishes.length > 1
                        ? <div className="seg finish" role="group" aria-label="Finish">
                            {finishes.map(f => <button key={f.key} type="button" aria-pressed={line.finish === f.key} onClick={() => update(line.key, { finish: f.key })}>{f.label}</button>)}
                          </div>
                        : <span className="finish muted small">{finishes[0]?.label ?? 'Normal'}</span>}
                      <div className="seg cond" role="group" aria-label="Condition">
                        {CONDITIONS.map(c => <button key={c} type="button" aria-pressed={line.condition === c} onClick={() => update(line.key, { condition: c })}>{c}</button>)}
                      </div>
                      <div className="stepper" role="group" aria-label="Quantity">
                        <button type="button" aria-label={`One fewer ${line.card.name}`} onClick={() => update(line.key, { quantity: Math.max(1, line.quantity - 1) })}>−</button>
                        <QuantityInput value={line.quantity} label={`Quantity of ${line.card.name}`} onChange={quantity => update(line.key, { quantity })} />
                        <button type="button" aria-label={`One more ${line.card.name}`} onClick={() => update(line.key, { quantity: Math.min(999, line.quantity + 1) })}>+</button>
                      </div>
                    </div>
                    {/* Not the market price: the value the store's pricing rules give the card (rarity floors and the like). */}
                    <div className="market" title={VALUE_HELP}>{money(times(priced?.valuationUnit, line.quantity))}
                      <small>{line.quantity > 1 ? `${money(priced?.valuationUnit)} each` : 'our value'}</small></div>
                    <div className="credit">{money(times(priced?.creditUnit, line.quantity))}<small>credit</small></div>
                    <div className="check">{money(times(priced?.checkUnit, line.quantity))}<small>check</small></div>
                    <button type="button" className="remove icon-button" aria-label={`Remove ${line.card.name}`} onClick={() => remove(line.key)}>
                      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                        <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
                      </svg>
                    </button>
                  </div>
                )
              })}
            </>
          )}
        </div>
        {quoteError && <p className="error" role="alert">Couldn’t price the trade: {quoteError}</p>}
      </div>

      <aside className="trade-rail">
        <div className="rail-card">
          <label>Customer phone<input type="tel" inputMode="tel" autoComplete="off" value={phone} onChange={e => changePhone(e.target.value)} placeholder="(555) 010-2030" /></label>
          {known && <div className="match" role="status">Returning customer: <strong>{known}</strong></div>}
          <label>Name<input value={customerName} onChange={e => { setCustomerName(e.target.value); setLookedUpName(null) }} /></label>
        </div>

        <div className="rail-card">
          <div className="sum" title={VALUE_HELP}><span>Our value</span><strong>{money(quote?.marketTotal)}</strong></div>
          <div className="seg wide" role="group" aria-label="Payout">
            {(['credit', 'check', 'partial'] as const).map(p => (
              <button key={p} type="button" aria-pressed={payment === p} onClick={() => setPayment(p)}>
                {p === 'credit' ? 'Credit' : p === 'check' ? 'Check' : 'Split'}
              </button>
            ))}
          </div>
          {!quote ? <p className="muted" style={{ margin: 0 }}>Add cards to see the offer.</p> : payment === 'partial' ? (
            <>
              <label>Store credit amount<input type="number" inputMode="decimal" min={0} step="0.01" value={splitCredit} onChange={e => setSplitCredit(e.target.value)} /></label>
              <div className="offer">
                <div className="label">Pay out</div>
                <div className="amount">{money(quote?.settlement.credit)}</div>
                <p>credit, plus <strong className="num">{money(quote?.settlement.check)}</strong> by check</p>
              </div>
            </>
          ) : (
            <div className="offer">
              <div className="label">{payment === 'check' ? 'Check offer' : 'Store credit offer'}</div>
              <div className="amount">{money(offer)}</div>
              <p>{payment === 'check' ? 'Store credit instead: ' : 'Check instead: '}
                <strong className="num">{money(payment === 'check' ? quote?.creditOffer : quote?.checkOffer)}</strong></p>
            </div>
          )}
          {payment !== 'credit' && (
            <label>Check number{checkPaid && <span className="muted small"> (required)</span>}
              <input value={checkNumber} required={checkPaid} aria-invalid={needsCheckNumber || undefined} onChange={e => setCheckNumber(e.target.value)} /></label>
          )}
          {saveError && <p className="error" role="alert" style={{ margin: 0 }}>{saveError}</p>}
          <button type="button" className="save" onClick={save} disabled={!canSave}>
            {saving ? 'Saving…' : <>Save trade <span className="kbd">Ctrl S</span></>}
          </button>
          {lines.length > 0 && !saving && (() => {
            const why = !current ? 'Updating the offer…' : payment === 'partial' && splitCredit === '' ? 'Enter the store credit amount to save.'
              : needsCheckNumber ? 'Enter the check number to save.' : ''
            return why && <p className="muted small" role="status" style={{ margin: 0, textAlign: 'center' }}>{why}</p>
          })()}
          <p className="muted small" style={{ margin: 0, textAlign: 'center' }}>Saving records the trade and gives you its POS CSV.</p>
        </div>
      </aside>

      {/* Narrow screens stack the rail under the lines; this keeps the offer and Save in reach while scrolling. */}
      {lines.length > 0 && (
        <div className="trade-sticky">
          <span><small>{payment === 'partial' ? 'Pay out' : payment === 'check' ? 'Check offer' : 'Credit offer'}</small>
            <strong className="num">{money(payment === 'partial' ? quote?.settlement.credit : offer)}</strong></span>
          <button type="button" onClick={save} disabled={!canSave}>{saving ? 'Saving…' : 'Save trade'}</button>
        </div>
      )}

      {enlarged?.image && (
        <CardLightbox onClose={() => setEnlarged(null)}
                      slides={[{ image: enlarged.image, caption: `${enlarged.name} · ${enlarged.setName} (${enlarged.set}) #${enlarged.number}` }]} />
      )}
    </section>
  )
}

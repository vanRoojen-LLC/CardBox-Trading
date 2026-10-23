import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { aborted, api, phoneText, type StoreLocation } from '../api'
import { paymentMoney, paymentTotals } from '../nativeMoney'

interface TradeSummary {
  id: string; number: number; created_at: string; payment: string; credit_total: number; check_total: number
  market_total: number; customer_phone: string | null; customer_name: string | null; created_by: string; cards: number
  location_id: string; location: string; actual_payment_currency: string | null; actual_currency_confirmed_at: string | null
}

const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString()
const when = (iso: string) => {
  const d = new Date(iso)
  return sameDay(d, new Date()) ? d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : d.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })
}

export function History({ locations }: { locations: StoreLocation[] }) {
  const [phone, setPhone] = useState('')
  const [location, setLocation] = useState('')
  const several = locations.length > 1
  // null while loading, so "no trades" is only said once the server has answered.
  const [trades, setTrades] = useState<TradeSummary[] | null>(null)
  const [error, setError] = useState('')
  const navigate = useNavigate()
  const digits = phone.replace(/\D/g, '')
  // A partial number would match nearly everyone, so the list waits for 8 digits rather than showing stale results.
  const partial = phone !== '' && digits.length < 8
  useEffect(() => {
    if (partial) return
    const controller = new AbortController()
    const timer = setTimeout(() => {
      setTrades(null)
      const params = new URLSearchParams()
      if (phone) params.set('phone', phone)
      if (location) params.set('location', location)
      // URLSearchParams.size is missing on Safari before 17 (older counter iPads), so test the string instead.
      const search = params.toString()
      api<TradeSummary[]>(`/api/app/trades${search ? `?${search}` : ''}`, { signal: controller.signal })
        .then(t => { setTrades(t); setError('') }).catch(e => { if (!aborted(e)) { setTrades([]); setError(e.message) } })
    }, 300)
    return () => { clearTimeout(timer); controller.abort() }
  }, [phone, location, partial])

  // The list holds the latest 50 trades, so today's totals are exact unless a store does more than 50 in a day.
  const shown = partial ? [] : trades ?? []
  const today = shown.filter(t => sameDay(new Date(t.created_at), new Date()))
  const capped = today.length >= 50
  const sum = (key: 'credit_total' | 'check_total') => paymentTotals(today,key).map(total =>
    <strong className="native-payment-total" key={total}>{total}{capped ? '+' : ''}</strong>)

  return (
    <section>
      <h1>Trade history</h1>
      <div className="toolbar">
        <input type="tel" inputMode="tel" placeholder="Filter by customer phone" aria-label="Filter by customer phone" value={phone} onChange={e => setPhone(e.target.value)} />
        {several && (
          <select aria-label="Filter by location" value={location} onChange={e => setLocation(e.target.value)}>
            <option value="">All locations</option>
            {locations.map(l => <option key={l.id} value={l.id}>{l.name}{l.archived ? ' (closed)' : ''}</option>)}
          </select>
        )}
      </div>
      {!phone && trades && (
        <div className="stats">
          <div className="stat"><span>Trades today{location ? ' here' : ''}</span><strong>{capped ? '50+' : today.length}</strong></div>
          <div className="stat copper"><span>Credit paid out today</span>{sum('credit_total')}</div>
          <div className="stat"><span>Checks written today</span>{sum('check_total')}</div>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      <div className="table-wrap">
        <table className="grid trades">
          <thead><tr><th>#</th><th>When</th><th>Customer</th><th className="r">Cards</th><th className="r">Credit</th><th className="r">Check</th>{several && <th>Location</th>}<th>By</th><th><span className="sr-only">POS export</span></th></tr></thead>
          <tbody>
            {shown.map(t => (
              <tr key={t.id} className="clickable" onClick={() => navigate(`/app/history/${t.id}`)}>
                <td className="t-number"><Link to={`/app/history/${t.id}`} onClick={e => e.stopPropagation()}><strong>{t.number}</strong></Link></td>
                <td className="num t-when">{when(t.created_at)}</td>
                <td className="t-customer">{t.customer_name || (t.customer_phone ? 'Customer' : 'Walk-in')}<div className="muted small num">{phoneText(t.customer_phone)}</div></td>
                <td className="r t-cards">{t.cards}<span className="t-unit"> card{t.cards === 1 ? '' : 's'}</span></td>
                <td className="r credit t-credit">{Number(t.credit_total) > 0 ? <>{t.actual_payment_currency ? paymentMoney(t.credit_total,t.actual_payment_currency) : Number(t.credit_total).toFixed(2)}<span className="t-unit"> credit</span>{!t.actual_payment_currency && <small className="currency-unknown">Currency unrecorded</small>}</> : <span className="t-none">—</span>}</td>
                <td className="r t-check">{Number(t.check_total) > 0 ? <>{t.actual_payment_currency ? paymentMoney(t.check_total,t.actual_payment_currency) : Number(t.check_total).toFixed(2)}<span className="t-unit"> check</span>{!t.actual_payment_currency && <small className="currency-unknown">Currency unrecorded</small>}</> : <span className="t-none">—</span>}</td>
                {several && <td className="t-location">{t.location}</td>}
                <td className="t-by">{t.created_by}</td>
                <td className="t-csv"><a href={`/api/app/trades/${t.id}/pos.csv`} onClick={e => e.stopPropagation()}>CSV</a></td>
              </tr>
            ))}
          </tbody>
        </table>
        {partial ? <p className="empty">Keep typing: enter at least 8 digits of the phone number.</p>
          : trades === null ? <p className="empty">Loading…</p>
          : trades.length === 0 && !error ? <p className="empty">{phone || location ? 'No trades match.' : 'No trades yet.'}</p> : null}
      </div>
    </section>
  )
}

interface TradeDetailData extends TradeSummary {
  check_number: string
  lines: { line_no: number; name: string; set_code: string; collector_number: string; finish: string; condition: string;
    quantity: number; valuation_unit: number; credit_alloc: number; check_alloc: number }[]
}

export function TradeDetail() {
  const { id } = useParams()
  const [trade, setTrade] = useState<TradeDetailData | null>(null)
  const [error, setError] = useState('')
  useEffect(() => { api<TradeDetailData>(`/api/app/trades/${id}`).then(setTrade).catch(e => setError(e.message)) }, [id])
  if (error) return <p className="error">{error}</p>
  if (!trade) return <p className="muted">Loading…</p>
  return (
    <section style={{ maxWidth: 1040, margin: '0 auto' }}>
      <Link to="/app/history" className="back">← History</Link>
      <h1>Trade #{trade.number}</h1>
      <p className="muted" style={{ marginTop: 0 }}>{new Date(trade.created_at).toLocaleString()} · {trade.location} · by {trade.created_by}
        {trade.customer_phone && <> · {trade.customer_name || 'Customer'} <span className="num">{phoneText(trade.customer_phone)}</span></>}
        {trade.check_number && <> · check #{trade.check_number}</>}</p>
      <p className="muted small">{trade.actual_payment_currency
        ? `Actual payment currency ${trade.actual_payment_currency}, confirmed when this trade was saved.`
        : 'Actual payment currency was not recorded.'} Guide currency was not recorded. Amounts are not converted.</p>
      <div className="table-wrap scroll">
        <table className="grid">
          <thead><tr><th>Card</th><th>Finish</th><th>Cond.</th><th className="r">Qty</th><th className="r">Value each</th><th className="r">Credit</th><th className="r">Check</th></tr></thead>
          <tbody>
            {trade.lines.map(l => (
              <tr key={l.line_no}>
                <td><strong>{l.name}</strong><div className="muted small">{l.set_code.toUpperCase()} #{l.collector_number}</div></td>
                <td style={{ textTransform: 'capitalize' }}>{l.finish}</td><td>{l.condition}</td><td className="r">{l.quantity}</td>
                <td className="r">{paymentMoney(l.valuation_unit,null)}</td><td className="r credit">{paymentMoney(l.credit_alloc,trade.actual_payment_currency)}</td><td className="r">{paymentMoney(l.check_alloc,trade.actual_payment_currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="totals">
        <div className="sum" title="The market price after the store's pricing rules, which the offer was worked out from"><span>Our value</span><strong>{paymentMoney(trade.market_total,null)}</strong></div>
        <div className="sum"><span>Store credit paid</span><strong style={{ color: 'var(--copper-text)' }}>{paymentMoney(trade.credit_total,trade.actual_payment_currency)}</strong></div>
        <div className="sum"><span>Check paid</span><strong>{paymentMoney(trade.check_total,trade.actual_payment_currency)}</strong></div>
      </div>
      <div className="actions" style={{ justifyContent: 'flex-end' }}>
        <a className="button" href={`/api/app/trades/${trade.id}/pos.csv`}>Download POS CSV</a>
        <button className="secondary" onClick={() => window.print()}>Print receipt</button>
      </div>
    </section>
  )
}

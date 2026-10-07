import { useEffect, useState } from 'react'
import { api, money } from './api'

export type ConfidenceLevel = 'high' | 'medium' | 'low' | 'none'
export interface Reason { kind: 'good' | 'warn' | 'bad' | 'info'; text: string }
interface Point { source: string; label: string; value: number | null; currency: string; observedAt: string | null; used: boolean }
interface Day { day: string; market: number }
export interface Evidence {
  cardId: string
  game: string
  finish: string
  market: number | null
  url: string | null
  points: Point[]
  history: Day[]
  confidence: ConfidenceLevel
  reasons: Reason[]
  missing: string[]
}

const LABELS: Record<ConfidenceLevel, string> = { high: 'High', medium: 'Medium', low: 'Low', none: 'No price' }
const MARKS: Record<Reason['kind'], string> = { good: '✓', warn: '!', bad: '✕', info: 'i' }

/** "3 hours ago", "2 days ago": how old a datapoint is, the way staff say it. */
export function age(iso: string | null, now = Date.now()): string {
  if (!iso) return 'age unknown'
  const hours = Math.max(0, Math.round((now - Date.parse(iso)) / 3600000))
  if (hours < 1) return 'just now'
  if (hours < 48) return `${hours} ${hours === 1 ? 'hour' : 'hours'} ago`
  return `${Math.round(hours / 24)} days ago`
}

function amount(p: Point) {
  if (p.value == null) return '—'
  return p.currency === 'USD' ? money(p.value) : `€${Number(p.value).toFixed(2)}`
}

/** The confidence label on a line or price. As a button it opens the evidence behind it. */
export function ConfidenceBadge({ level, onClick, open, title }: { level: ConfidenceLevel; onClick?: () => void; open?: boolean; title?: string }) {
  const text = <><span className="dot" aria-hidden="true" />{LABELS[level]}{level !== 'none' && ' confidence'}</>
  return onClick
    ? <button type="button" className={`confidence ${level}`} onClick={onClick} aria-expanded={open} title={title ?? 'Show the price evidence'}>
        {text}<span className="chev" aria-hidden="true">{open ? '▴' : '▾'}</span></button>
    : <span className={`confidence ${level}`} title={title}>{text}</span>
}

export function Reasons({ reasons }: { reasons: Reason[] }) {
  return (
    <ul className="reasons">
      {reasons.map((r, i) => <li key={i} className={r.kind}><span className="mark" aria-label={r.kind}>{MARKS[r.kind]}</span>{r.text}</li>)}
    </ul>
  )
}

/** A small line of the recorded market price, oldest on the left, with the low and high it touched. */
function Trend({ history }: { history: Day[] }) {
  if (history.length < 2) return <p className="muted small">{history.length === 0
    ? 'No price history recorded yet. Trading records each night’s price; trends show after a week.'
    : `Price history started ${new Date(history[0].day).toLocaleDateString()}. Trends show after a week.`}</p>
  const values = history.map(d => Number(d.market))
  const lo = Math.min(...values), hi = Math.max(...values)
  const w = 240, h = 48, span = hi - lo || 1
  const points = values.map((v, i) => `${(i / (values.length - 1)) * w},${h - 4 - ((v - lo) / span) * (h - 8)}`).join(' ')
  const first = values[0], last = values[values.length - 1]
  const change = first ? Math.round(((last - first) / first) * 100) : 0
  return (
    <div className="trend">
      <svg viewBox={`0 0 ${w} ${h}`} width={w} height={h} role="img"
           aria-label={`Market price over ${history.length} days, from ${money(first)} to ${money(last)}`}>
        <polyline points={points} fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
      </svg>
      <span className="small">{history.length} days · {change >= 0 ? '+' : ''}{change}% · low {money(lo)} · high {money(hi)}</span>
    </div>
  )
}

/** Everything behind a card's price: each datapoint with its source and age, the trend, the reasons, and the gaps. */
export function EvidencePanel({ path }: { path: string }) {
  const [evidence, setEvidence] = useState<Evidence | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    let current = true
    setEvidence(null); setError('')
    api<Evidence>(path).then(e => { if (current) setEvidence(e) }).catch(e => { if (current) setError(e.message) })
    return () => { current = false }
  }, [path])
  if (error) return <div className="evidence"><p className="error" role="alert" style={{ margin: 0 }}>{error}</p></div>
  if (!evidence) return <div className="evidence"><p className="muted small" style={{ margin: 0 }}>Loading price evidence…</p></div>
  return (
    <div className="evidence">
      <div className="evidence-head">
        <ConfidenceBadge level={evidence.confidence} />
        {evidence.url && <a href={evidence.url} target="_blank" rel="noreferrer">Open on TCGplayer</a>}
      </div>
      <Reasons reasons={evidence.reasons} />
      <table className="points">
        <thead><tr><th scope="col">Datapoint</th><th scope="col">Value</th><th scope="col">Source</th><th scope="col">Age</th></tr></thead>
        <tbody>
          {evidence.points.map((p, i) => (
            <tr key={i} className={p.used ? 'used' : undefined}>
              <td>{p.label}{p.used && <span className="tag">priced from</span>}</td>
              <td className="num">{amount(p)}</td>
              <td>{p.source}</td>
              <td>{age(p.observedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <Trend history={evidence.history} />
      <details className="missing">
        <summary>Not shown here, and why</summary>
        <ul>{evidence.missing.map((m, i) => <li key={i}>{m}</li>)}</ul>
      </details>
    </div>
  )
}

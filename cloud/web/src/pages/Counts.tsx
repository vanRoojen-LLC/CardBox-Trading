import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, CONDITIONS, FINISHES, money, type Card, type Me, type Money, type StoreLocation } from '../api'
import { flatTree, pathText, type PathPart, type Spot } from '../storage'
import SearchIcon from '../SearchIcon'

interface CountSummary {
  id: string; state: 'open' | 'reconciled' | 'cancelled'; locationId: string; location: string; storageId: string | null
  path: PathPart[]; startedAt: string; startedBy: string; closedAt: string | null; counted?: number
}
interface ReportRow {
  storageId: string | null; path: PathPart[]; cardId: string; name: string; set: string; number: string
  finish: string; condition: string; expected: number; synced: number; counted: number; delta: number
  status: 'ok' | 'missing' | 'extra' | 'moved'; market: Money
}
interface CountLine {
  id: string; path: PathPart[]; name: string; set: string; number: string; finish: string; condition: string
  quantity: number; source: 'typed' | 'club'; image: string | null
}
interface Report extends CountSummary { rows: ReportRow[]; lines: CountLine[]; missing: number; extra: number; valueChange: number }

const where = (c: { location: string; path: PathPart[] }) => c.path.length ? `${c.location} › ${pathText(c.path)}` : c.location
const STATUS: Record<ReportRow['status'], string> = { ok: 'Matches', missing: 'Missing', extra: 'Extra', moved: 'Moved' }

/** Re-inventory: recount a location or a box, see what changed, and accept it. */
export function Counts({ locations }: { locations: StoreLocation[] }) {
  const open = locations.filter(l => !l.archived)
  const navigate = useNavigate()
  const [counts, setCounts] = useState<CountSummary[]>([])
  const [spots, setSpots] = useState<Spot[]>([])
  const [form, setForm] = useState({ locationId: open[0]?.id ?? '', storageId: '' })
  const [error, setError] = useState('')
  useEffect(() => {
    api<CountSummary[]>('/api/app/counts').then(setCounts).catch(e => setError(e.message))
    api<Spot[]>('/api/app/storage').then(setSpots).catch(e => setError(e.message))
  }, [])
  async function start(e: React.FormEvent) {
    e.preventDefault()
    try {
      const count = await api<Report>('/api/app/counts', { method: 'POST', body: { locationId: form.locationId, storageId: form.storageId || null } })
      navigate(`/app/inventory/counts/${count.id}`)
    } catch (err) { setError((err as Error).message) }
  }
  return (
    <section>
      <div className="page-head">
        <h1>Re-inventory</h1>
        <Link to="/app/inventory">Back to inventory</Link>
      </div>
      <p className="lede">Recount a location or a box: scan its cards on CardBox or type them in here, check what's missing or extra, and accept the count to update inventory.</p>
      {error && <p className="error">{error}</p>}
      <form className="panel move-form" onSubmit={start}>
        {open.length > 1 && (
          <label>Location
            <select value={form.locationId} onChange={e => setForm({ locationId: e.target.value, storageId: '' })}>
              {open.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
            </select>
          </label>
        )}
        <label>Count
          <select value={form.storageId} onChange={e => setForm({ ...form, storageId: e.target.value })}>
            <option value="">The whole location</option>
            {flatTree(spots, form.locationId).map(s => <option key={s.id} value={s.id}>{'  '.repeat(s.depth)}{s.label} {s.name}</option>)}
          </select>
        </label>
        <button type="submit">Start count</button>
      </form>
      <div className="table-wrap scroll">
        <table className="grid">
          <thead><tr><th>Counting</th><th>Started</th><th>Status</th><th className="r">Counted</th></tr></thead>
          <tbody>
            {counts.map(c => (
              <tr key={c.id}>
                <td><Link to={`/app/inventory/counts/${c.id}`}>{where(c)}</Link></td>
                <td>{new Date(c.startedAt).toLocaleString()} <span className="muted small">by {c.startedBy}</span></td>
                <td>{c.state === 'open' ? 'In progress' : c.state === 'reconciled' ? 'Accepted' : 'Cancelled'}</td>
                <td className="r">{c.counted}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {counts.length === 0 && <p className="empty">No counts yet.</p>}
      </div>
    </section>
  )
}

export function CountDetail({ me }: { me: Me }) {
  const { id } = useParams()
  const [report, setReport] = useState<Report | null>(null)
  const [spots, setSpots] = useState<Spot[]>([])
  const [onlyChanges, setOnlyChanges] = useState(true)
  const [error, setError] = useState('')
  const load = useCallback(() => api<Report>(`/api/app/counts/${id}`).then(r => { setReport(r); setError('') }).catch(e => setError(e.message)), [id])
  useEffect(() => { load(); api<Spot[]>('/api/app/storage').then(setSpots).catch(() => {}) }, [load])
  // Club scans arrive on their own while a count is open.
  useEffect(() => {
    if (report?.state !== 'open') return
    const t = setInterval(load, 5000)
    return () => clearInterval(t)
  }, [report?.state, load])
  async function send(path: string, body: unknown = {}) {
    try { setReport(await api<Report>(path, { method: 'POST', body })); setError('') } catch (e) { setError((e as Error).message) }
  }
  if (!report) return error ? <p className="error">{error}</p> : <p className="muted">Loading…</p>
  const open = report.state === 'open'
  const rows = onlyChanges ? report.rows.filter(r => r.status !== 'ok') : report.rows
  const inside = flatTree(spots, report.locationId)
  return (
    <section>
      <div className="page-head">
        <h1>Counting {where(report)}</h1>
        <Link to="/app/inventory/counts">All counts</Link>
      </div>
      <p className="lede">
        {open ? 'In progress.' : report.state === 'reconciled' ? 'Accepted: inventory was updated.' : 'Cancelled.'}{' '}
        Started {new Date(report.startedAt).toLocaleString()} by {report.startedBy}.
        {open && ' Scan cards on CardBox in re-inventory mode and pick this count, or type them in below.'}
      </p>
      {error && <p className="error">{error}</p>}
      {open && <AddLine spots={inside} onAdd={body => send(`/api/app/counts/${id}/lines`, body)} />}
      <div className="panel-head">
        <p className="muted">{report.missing} missing · {report.extra} extra · value change {report.valueChange < 0 ? `−${money(-report.valueChange)}` : `+${money(report.valueChange)}`}</p>
        <button className="link" onClick={() => setOnlyChanges(!onlyChanges)}>{onlyChanges ? 'Show every card' : 'Show only differences'}</button>
      </div>
      <div className="table-wrap scroll">
        <table className="grid">
          <thead><tr><th>Card</th><th>Where</th><th>Finish</th><th>Cond.</th><th className="r">Expected</th><th className="r">Counted</th><th className="r">Difference</th><th>Status</th></tr></thead>
          <tbody>
            {rows.map(r => (
              <tr key={`${r.storageId}/${r.cardId}/${r.finish}/${r.condition}`}>
                <td><strong>{r.name}</strong><div className="muted small">{r.set?.toUpperCase()} #{r.number} · {money(r.market)}</div></td>
                <td>{r.path.length ? pathText(r.path) : <span className="muted">Not put away</span>}</td>
                <td style={{ textTransform: 'capitalize' }}>{r.finish}</td>
                <td>{r.condition}</td>
                <td className="r">{r.expected}{r.synced > 0 && <div className="muted small" title="Synced from a CardBox collection; changes to these are made on CardBox">{r.synced} from CardBox</div>}</td>
                <td className="r">{r.counted}</td>
                <td className="r">{r.delta > 0 ? `+${r.delta}` : r.delta}</td>
                <td>{STATUS[r.status]}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <p className="empty">{report.rows.length ? 'Everything counted matches.' : 'Nothing expected or counted yet.'}</p>}
      </div>
      {open && me.role === 'owner' && (
        <p className="actions">
          <button onClick={() => confirm('Update inventory to match this count?') && send(`/api/app/counts/${id}/reconcile`)}>Accept count and update inventory</button>{' '}
          <button className="secondary" onClick={() => confirm('Cancel this count? Inventory stays as it is.') && send(`/api/app/counts/${id}/cancel`)}>Cancel count</button>
        </p>
      )}
      {open && me.role !== 'owner' && <p className="muted small">A store owner accepts the count.</p>}
      <h2>Counted ({report.lines.reduce((n, l) => n + l.quantity, 0)})</h2>
      <ul className="plain count-lines">
        {report.lines.map(l => (
          <li key={l.id}>
            {l.image && <img src={l.image} alt="" className="scan-thumb" loading="lazy" />}
            <span>{l.quantity} × <strong>{l.name}</strong> <span className="muted small">{l.set?.toUpperCase()} #{l.number} · {l.finish} · {l.condition}
              {l.path.length > 0 && <> · {pathText(l.path)}</>} · {l.source === 'club' ? 'scanned on CardBox' : 'typed'}</span></span>
            {open && <button className="link" onClick={() => send(`/api/app/counts/${id}/lines/${l.id}/remove`)}>Remove</button>}
          </li>
        ))}
      </ul>
    </section>
  )
}

/** Types a counted card in: search, pick, set finish, condition, how many and (optionally) the spot inside the count. */
function AddLine({ spots, onAdd }: { spots: (Spot & { depth: number })[]; onAdd: (body: object) => void }) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Card[]>([])
  const [card, setCard] = useState<Card | null>(null)
  const [form, setForm] = useState({ finish: 'normal', condition: 'NM', quantity: 1, storageId: '' })
  useEffect(() => {
    if (query.trim().length < 2) return
    const t = setTimeout(() => api<Card[]>(`/api/app/cards?q=${encodeURIComponent(query.trim())}`).then(setResults).catch(() => {}), 250)
    return () => clearTimeout(t)
  }, [query])
  function add(e: React.FormEvent) {
    e.preventDefault()
    if (!card) return
    onAdd({ cardId: card.id, ...form, storageId: form.storageId || null })
    setCard(null); setForm(f => ({ ...f, quantity: 1 }))
  }
  return (
    <div className="panel add-cards">
      <div className="search">
        <SearchIcon />
        <input placeholder="Type a counted card" aria-label="Type a counted card" value={query} onChange={e => setQuery(e.target.value)} />
      </div>
      {query.trim().length >= 2 && results.length > 0 && (
        <ul className="pick-list">
          {results.slice(0, 12).map(c => (
            <li key={c.id}><button type="button" className="secondary" onClick={() => {
              setCard(c); setResults([]); setQuery('')
              const available = FINISHES.filter(f => f.price(c) != null)
              setForm(f => ({ ...f, finish: available.some(x => x.key === f.finish) ? f.finish : available[0]?.key ?? 'normal' }))
            }}>
              <strong>{c.name}</strong><span className="muted">{c.set.toUpperCase()} #{c.number} · {c.setName}</span>
            </button></li>
          ))}
        </ul>
      )}
      {card && (
        <form className="add-form" onSubmit={add}>
          <div className="picked"><strong>{card.name}</strong> <span className="muted">{card.set.toUpperCase()} #{card.number}</span></div>
          <label>Finish<select value={form.finish} onChange={e => setForm({ ...form, finish: e.target.value })}>
            {FINISHES.filter(f => f.price(card) != null).map(f => <option key={f.key} value={f.key}>{f.label}</option>)}</select></label>
          <label>Condition<select value={form.condition} onChange={e => setForm({ ...form, condition: e.target.value })}>
            {CONDITIONS.map(c => <option key={c}>{c}</option>)}</select></label>
          <label>Qty<input type="number" min={1} max={9999} value={form.quantity} onChange={e => setForm({ ...form, quantity: Number(e.target.value) })} /></label>
          {spots.length > 0 && (
            <label>Found in<select value={form.storageId} onChange={e => setForm({ ...form, storageId: e.target.value })}>
              <option value="">The counted spot</option>
              {spots.map(s => <option key={s.id} value={s.id}>{'  '.repeat(s.depth)}{s.label} {s.name}</option>)}</select></label>
          )}
          <button type="submit">Add to count</button>
        </form>
      )}
    </div>
  )
}

import { useEffect, useState } from 'react'
import { api, type Me } from '../api'

/** `id` only keys the row on screen, so removing one row never shifts another's typing into it. */
interface Rule { id: number; thresholdMin: string; creditRate: string; checkRate: string }
interface ServerRule { thresholdMin: number; creditRate: number; checkRate: number }

const pct = (rate: number) => String(Math.round(rate * 10000) / 100)
let nextId = 1
const values = (rules: Rule[]) => JSON.stringify(rules.map(({ thresholdMin, creditRate, checkRate }) => [thresholdMin, creditRate, checkRate]))

const GAMES = [['', 'Every game'], ['magic-the-gathering', 'Magic'], ['star-wars-unlimited', 'Star Wars: Unlimited']] as const
type Game = typeof GAMES[number][0]
const gameName = (game: Game) => GAMES.find(([key]) => key === game)![1]
const query = (game: Game) => game ? `?game=${game}` : ''

export default function Rates({ me }: { me: Me }) {
  const [game, setGame] = useState<Game>('')
  const [dirtyParts, setDirtyParts] = useState({ rates: false, trust: false })
  const pick = (next: Game) => {
    if (next === game) return
    if ((dirtyParts.rates || dirtyParts.trust) && !window.confirm('Leave without saving your changes?')) return
    setGame(next)
  }
  const owner = me.role === 'owner'
  return (
    <section className="rates">
      <h1>Buy rates</h1>
      <div className="seg wide" role="tablist" aria-label="Rates for">
        {GAMES.map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={game === key} aria-pressed={game === key} onClick={() => pick(key)}>{label}</button>
        ))}
      </div>
      <p className="muted small" style={{ marginTop: 8 }}>{game
        ? `${gameName(game)} cards use these rates and confidence rules. Until you save its own, it uses the ones under Every game.`
        : 'The store’s default rates and confidence rules. A game can have its own; pick it above.'}</p>
      <BuyRates key={`rates${game}`} owner={owner} game={game} onDirty={d => setDirtyParts(p => ({ ...p, rates: d }))} />
      <ConfidenceRules key={`trust${game}`} owner={owner} game={game} onDirty={d => setDirtyParts(p => ({ ...p, trust: d }))} />
    </section>
  )
}

/** One game's buy-rate tiers, or the store's default. */
function BuyRates({ owner, game, onDirty }: { owner: boolean; game: Game; onDirty: (dirty: boolean) => void }) {
  const [rules, setRules] = useState<Rule[] | null>(null)
  const [own, setOwn] = useState(true)
  // What the server has, to tell whether anything on screen is unsaved.
  const [saved, setSaved] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const load = (data: { rules: ServerRule[]; own: boolean }) => {
    const next = data.rules.map(r => ({ id: nextId++, thresholdMin: String(r.thresholdMin), creditRate: pct(r.creditRate), checkRate: pct(r.checkRate) }))
    setRules(next); setSaved(values(next)); setOwn(data.own)
  }
  useEffect(() => { api<{ rules: ServerRule[]; own: boolean }>(`/api/app/rates${query(game)}`).then(load).catch(e => { setRules([]); setError(e.message) }) }, [game])
  const dirty = rules !== null && values(rules) !== saved
  useEffect(() => { onDirty(dirty) }, [dirty])
  const update = (id: number, change: Partial<Rule>) => { setRules(rules!.map(r => r.id === id ? { ...r, ...change } : r)); setMessage('') }

  // Leaving or reloading the page with unsaved rates asks first.
  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  async function save(e: React.FormEvent) {
    e.preventDefault()
    if (!rules || saving) return
    const fail = (text: string) => { setError(text); setMessage('') }
    // Number('') is 0, so a box left empty would quietly become a $0 or 0% tier.
    const blank = rules.findIndex(r => [r.thresholdMin, r.creditRate, r.checkRate].some(v => v.trim() === ''))
    if (blank >= 0) return fail(`Row ${blank + 1} has an empty box. Fill it in or remove the row.`)
    const negative = rules.findIndex(r => Number(r.thresholdMin) < 0)
    if (negative >= 0) return fail(`Row ${negative + 1}: the card value can't be below $0.`)
    const outOfRange = rules.findIndex(r => [r.creditRate, r.checkRate].some(v => Number(v) < 1 || Number(v) > 100))
    if (outOfRange >= 0) return fail(`Row ${outOfRange + 1}: rates must be between 1% and 100%.`)
    const thresholds = rules.map(r => Number(r.thresholdMin))
    const twice = thresholds.findIndex((t, i) => thresholds.indexOf(t) !== i)
    if (twice >= 0) return fail(`Two rows start at $${thresholds[twice].toFixed(2)}. Each tier needs its own card value.`)
    setSaving(true)
    try {
      // Saved lowest tier first, the order the offer reads them in.
      const sorted = [...rules].sort((a, b) => Number(a.thresholdMin) - Number(b.thresholdMin))
      load(await api<{ rules: ServerRule[]; own: boolean }>(`/api/app/rates${query(game)}`, { method: 'PUT', body: { rules: sorted.map(r => ({
        thresholdMin: Number(r.thresholdMin), creditRate: Number(r.creditRate) / 100, checkRate: Number(r.checkRate) / 100 })) } }))
      setMessage('Rates saved.'); setError('')
    } catch (err) { fail((err as Error).message) }
    finally { setSaving(false) }
  }

  async function reset() {
    if (saving) return
    setSaving(true)
    try {
      load(await api<{ rules: ServerRule[]; own: boolean }>(`/api/app/rates${query(game)}`, { method: 'DELETE', body: {} }))
      setMessage(`${gameName(game)} uses the default rates again.`); setError('')
    } catch (err) { setError((err as Error).message); setMessage('') }
    finally { setSaving(false) }
  }

  return (
    <>
      <p className="lede">A card's offer uses the row with the highest threshold below its value. Keep a $0 row for everything else.</p>
      {game && !own && <p className="notice">Showing the default rates. Change them here and save to give {gameName(game)} its own.</p>}
      {rules === null ? <p className="muted">Loading…</p> : (
        <form onSubmit={save}>
          <div className="table-wrap"><table className="grid">
            <thead><tr><th scope="col">Card value over</th><th scope="col">Store credit %</th><th scope="col">Check %</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {rules.map((r, i) => (
                <tr key={r.id}>
                  <td>$<input type="number" min={0} step="0.01" required disabled={!owner} aria-label={`Row ${i + 1}: card value over, in dollars`}
                    value={r.thresholdMin} onChange={e => update(r.id, { thresholdMin: e.target.value })} /></td>
                  <td><input type="number" min={1} max={100} step="any" required disabled={!owner} aria-label={`Row ${i + 1}: store credit percent`}
                    value={r.creditRate} onChange={e => update(r.id, { creditRate: e.target.value })} />%</td>
                  <td><input type="number" min={1} max={100} step="any" required disabled={!owner} aria-label={`Row ${i + 1}: check percent`}
                    value={r.checkRate} onChange={e => update(r.id, { checkRate: e.target.value })} />%</td>
                  <td>{owner && rules.length > 1 && <button type="button" className="link" aria-label={`Remove row ${i + 1}`}
                    onClick={() => { setRules(rules.filter(x => x.id !== r.id)); setMessage('') }}>Remove</button>}</td>
                </tr>
              ))}
            </tbody>
          </table></div>
          {owner ? (
            <p className="actions"><button type="button" className="secondary"
              onClick={() => setRules([...rules, { id: nextId++, thresholdMin: '', creditRate: '50', checkRate: '40' }])}>Add tier</button>{' '}
              <button type="submit" disabled={saving || !dirty}>{saving ? 'Saving…' : 'Save rates'}</button>
              {game && own && <button type="button" className="secondary" disabled={saving} onClick={reset}>Use the default rates</button>}
              {dirty && !saving && <span className="muted small" role="status" style={{ alignSelf: 'center' }}>Unsaved changes</span>}</p>
          ) : <p className="muted">Only the store owner can change rates.</p>}
        </form>
      )}
      {message && <p className="notice">{message}</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </>
  )
}

type Level = 'high' | 'medium' | 'low'
interface TrustRule { level: Level; adjust: string; review: boolean }
const LEVELS: { level: Level; label: string; help: string }[] = [
  { level: 'high', label: 'High', help: 'Fresh, sources agree, listings and recent sales line up.' },
  { level: 'medium', label: 'Medium', help: 'One warning, such as a single source, a fast move or a wide listing spread.' },
  { level: 'low', label: 'Low', help: 'A stale price, sources far apart, or two or more warnings.' },
]

/**
 * What the offer does by how far a card's price can be trusted. Offer % scales the buy-rate offer; "Hold for review"
 * keeps the trade from saving until staff tick that they checked the flagged prices.
 */
function ConfidenceRules({ owner, game, onDirty }: { owner: boolean; game: Game; onDirty: (dirty: boolean) => void }) {
  const [rules, setRules] = useState<TrustRule[] | null>(null)
  const [own, setOwn] = useState(true)
  const [saved, setSaved] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  type Saved = { rules: { level: Level; adjust: number; review: boolean }[]; own: boolean }
  const load = (data: Saved) => {
    const next = data.rules.map(r => ({ level: r.level, adjust: pct(r.adjust), review: r.review }))
    setRules(next); setSaved(JSON.stringify(next)); setOwn(data.own)
  }
  useEffect(() => { api<Saved>(`/api/app/confidence-rules${query(game)}`).then(load).catch(e => setError(e.message)) }, [game])
  const dirty = rules !== null && JSON.stringify(rules) !== saved
  useEffect(() => { onDirty(dirty) }, [dirty])

  async function reset() {
    if (saving) return
    setSaving(true)
    try {
      load(await api<Saved>(`/api/app/confidence-rules${query(game)}`, { method: 'DELETE', body: {} }))
      setMessage(`${gameName(game)} uses the default confidence rules again.`); setError('')
    } catch (err) { setError((err as Error).message); setMessage('') }
    finally { setSaving(false) }
  }
  const update = (level: Level, change: Partial<TrustRule>) => { setRules(rules!.map(r => r.level === level ? { ...r, ...change } : r)); setMessage('') }

  async function save(e: React.FormEvent) {
    e.preventDefault()
    if (!rules || saving) return
    const bad = rules.find(r => r.adjust.trim() === '' || Number(r.adjust) < 1 || Number(r.adjust) > 100)
    if (bad) { setError(`${bad.level[0].toUpperCase() + bad.level.slice(1)}: the offer must be between 1% and 100%.`); setMessage(''); return }
    setSaving(true)
    try {
      load(await api<Saved>(`/api/app/confidence-rules${query(game)}`, { method: 'PUT', body: { rules: rules.map(r => ({ level: r.level, adjust: Number(r.adjust) / 100, review: r.review })) } }))
      setMessage('Confidence rules saved.'); setError('')
    } catch (err) { setError((err as Error).message); setMessage('') }
    finally { setSaving(false) }
  }

  return (
    <>
      <h2 style={{ marginTop: 32 }}>Price confidence</h2>
      <p className="lede">Every trade line gets a confidence label from the price evidence: how fresh it is, whether sources agree, how listings sit against recent sales, and how it has moved.
        Choose what each level does to the offer. 100% and no hold leave offers as the buy rates set them.</p>
      {game && !own && <p className="notice">Showing the default rules. Change them here and save to give {gameName(game)} its own.</p>}
      {rules === null ? (!error && <p className="muted">Loading…</p>) : (
        <form onSubmit={save}>
          <div className="table-wrap"><table className="grid">
            <thead><tr><th scope="col">Confidence</th><th scope="col">Offer</th><th scope="col">Hold for review</th></tr></thead>
            <tbody>
              {LEVELS.map(({ level, label, help }) => {
                const r = rules.find(x => x.level === level)!
                return (
                  <tr key={level}>
                    <td><span className={`confidence ${level}`}><span className="dot" aria-hidden="true" />{label}</span><div className="muted small">{help}</div></td>
                    <td><input type="number" min={1} max={100} step="any" required disabled={!owner} aria-label={`${label} confidence: percent of the normal offer`}
                      value={r.adjust} onChange={e => update(level, { adjust: e.target.value })} />% of the offer</td>
                    <td><label className="inline"><input type="checkbox" disabled={!owner} checked={r.review}
                      onChange={e => update(level, { review: e.target.checked })} /> Staff must check the price</label></td>
                  </tr>
                )
              })}
            </tbody>
          </table></div>
          {owner ? (
            <p className="actions"><button type="submit" disabled={saving || !dirty}>{saving ? 'Saving…' : 'Save confidence rules'}</button>
              {game && own && <button type="button" className="secondary" disabled={saving} onClick={reset}>Use the default rules</button>}
              {dirty && !saving && <span className="muted small" role="status" style={{ alignSelf: 'center' }}>Unsaved changes</span>}</p>
          ) : <p className="muted">Only the store owner can change these.</p>}
        </form>
      )}
      {message && <p className="notice">{message}</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </>
  )
}

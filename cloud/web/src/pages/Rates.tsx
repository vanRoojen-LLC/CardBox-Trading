import { useEffect, useState } from 'react'
import { api, type Me } from '../api'

/** `id` only keys the row on screen, so removing one row never shifts another's typing into it. */
interface Rule { id: number; thresholdMin: string; creditRate: string; checkRate: string }
interface ServerRule { thresholdMin: number; creditRate: number; checkRate: number }

const pct = (rate: number) => String(Math.round(rate * 10000) / 100)
let nextId = 1
const values = (rules: Rule[]) => JSON.stringify(rules.map(({ thresholdMin, creditRate, checkRate }) => [thresholdMin, creditRate, checkRate]))

export default function Rates({ me }: { me: Me }) {
  const [rules, setRules] = useState<Rule[] | null>(null)
  // What the server has, to tell whether anything on screen is unsaved.
  const [saved, setSaved] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const load = (data: { rules: ServerRule[] }) => {
    const next = data.rules.map(r => ({ id: nextId++, thresholdMin: String(r.thresholdMin), creditRate: pct(r.creditRate), checkRate: pct(r.checkRate) }))
    setRules(next); setSaved(values(next))
  }
  useEffect(() => { api<{ rules: ServerRule[] }>('/api/app/rates').then(load).catch(e => { setRules([]); setError(e.message) }) }, [])
  const owner = me.role === 'owner'
  const dirty = rules !== null && values(rules) !== saved
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
      load(await api<{ rules: ServerRule[] }>('/api/app/rates', { method: 'PUT', body: { rules: sorted.map(r => ({
        thresholdMin: Number(r.thresholdMin), creditRate: Number(r.creditRate) / 100, checkRate: Number(r.checkRate) / 100 })) } }))
      setMessage('Rates saved.'); setError('')
    } catch (err) { fail((err as Error).message) }
    finally { setSaving(false) }
  }

  return (
    <section>
      <h1>Buy rates</h1>
      <p className="lede">A card's offer uses the row with the highest threshold below its value. Keep a $0 row for everything else.</p>
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
              {dirty && !saving && <span className="muted small" role="status" style={{ alignSelf: 'center' }}>Unsaved changes</span>}</p>
          ) : <p className="muted">Only the store owner can change rates.</p>}
        </form>
      )}
      {message && <p className="notice">{message}</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </section>
  )
}

import { useEffect, useState } from 'react'
import { api, type Me } from '../api'

interface Rule { thresholdMin: string; creditRate: string; checkRate: string }
interface ServerRule { thresholdMin: number; creditRate: number; checkRate: number }

const pct = (rate: number) => String(Math.round(rate * 10000) / 100)

export default function Rates({ me }: { me: Me }) {
  const [rules, setRules] = useState<Rule[]>([])
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const load = (data: { rules: ServerRule[] }) => setRules(data.rules.map(r => ({
    thresholdMin: String(r.thresholdMin), creditRate: pct(r.creditRate), checkRate: pct(r.checkRate) })))
  useEffect(() => { api<{ rules: ServerRule[] }>('/api/app/rates').then(load).catch(e => setError(e.message)) }, [])
  const owner = me.role === 'owner'
  const update = (i: number, change: Partial<Rule>) => setRules(rules.map((r, j) => j === i ? { ...r, ...change } : r))

  async function save() {
    // Number('') is 0, so a box left empty would quietly become a $0 or 0% tier.
    const blank = rules.findIndex(r => [r.thresholdMin, r.creditRate, r.checkRate].some(v => v.trim() === ''))
    if (blank >= 0) { setError(`Row ${blank + 1} has an empty box. Fill it in or remove the row.`); setMessage(''); return }
    const outOfRange = rules.findIndex(r => [r.creditRate, r.checkRate].some(v => Number(v) < 1 || Number(v) > 100))
    if (outOfRange >= 0) { setError(`Row ${outOfRange + 1}: rates must be between 1% and 100%.`); setMessage(''); return }
    setSaving(true)
    try {
      load(await api<{ rules: ServerRule[] }>('/api/app/rates', { method: 'PUT', body: { rules: rules.map(r => ({
        thresholdMin: Number(r.thresholdMin), creditRate: Number(r.creditRate) / 100, checkRate: Number(r.checkRate) / 100 })) } }))
      setMessage('Rates saved.'); setError('')
    } catch (e) { setError((e as Error).message); setMessage('') }
    finally { setSaving(false) }
  }

  return (
    <section>
      <h1>Buy rates</h1>
      <p className="lede">A card's offer uses the row with the highest threshold below its value. Keep a $0 row for everything else.</p>
      <div className="table-wrap"><table className="grid">
        <thead><tr><th>Card value over</th><th>Store credit %</th><th>Check %</th><th /></tr></thead>
        <tbody>
          {rules.map((r, i) => (
            <tr key={i}>
              <td>$<input type="number" min={0} step="0.01" disabled={!owner} value={r.thresholdMin} onChange={e => update(i, { thresholdMin: e.target.value })} /></td>
              <td><input type="number" min={1} max={100} disabled={!owner} value={r.creditRate} onChange={e => update(i, { creditRate: e.target.value })} />%</td>
              <td><input type="number" min={1} max={100} disabled={!owner} value={r.checkRate} onChange={e => update(i, { checkRate: e.target.value })} />%</td>
              <td>{owner && rules.length > 1 && <button className="link" onClick={() => setRules(rules.filter((_, j) => j !== i))}>Remove</button>}</td>
            </tr>
          ))}
        </tbody>
      </table></div>
      {owner ? (
        <p className="actions"><button className="secondary" onClick={() => setRules([...rules, { thresholdMin: '', creditRate: '50', checkRate: '40' }])}>Add tier</button>{' '}
          <button onClick={save} disabled={saving}>{saving ? 'Saving…' : 'Save rates'}</button></p>
      ) : <p className="muted">Only the store owner can change rates.</p>}
      {message && <p className="notice">{message}</p>}
      {error && <p className="error">{error}</p>}
    </section>
  )
}

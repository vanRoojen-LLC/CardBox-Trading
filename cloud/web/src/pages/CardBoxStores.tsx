import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import DateField from '../DateField'
import { confirmPlan, planLabel, PLANS } from '../plans'

/*
 * Stores live on CardBox (cardbox.club). The Admin screen reads and changes them through /api/cardbox/*, which
 * Trading's server forwards to CardBox as the signed-in person; CardBox's error messages are shown as they are.
 * Each store's team is managed on the Team page (TeamAdmin.tsx).
 */

interface CardBoxStore { id: string; name: string; slug?: string }

interface TradingStore { id: string; name: string; planStatus: string; trialEndsAt: string; entitled: boolean; cardboxStoreId: string | null
  people: number; trades: number; cards: number }

/**
 * The platform owner's store list with the link on: CardBox's stores (created and renamed there), each with the
 * Trading plan and trial that go with it, and any Trading store not yet tied to a CardBox store.
 */
export function CardBoxStores() {
  const [stores, setStores] = useState<CardBoxStore[] | null>(null)
  const [trading, setTrading] = useState<TradingStore[]>([])
  const [busy, setBusy] = useState(false)
  const [newName, setNewName] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const load = useCallback(() => Promise.all([
    api<CardBoxStore[]>('/api/cardbox/stores').then(setStores),
    api<TradingStore[]>('/api/admin/stores').then(setTrading),
  ]), [])
  useEffect(() => { load().catch(e => setError(e.message)) }, [load])

  /** Runs one change at a time (controls are disabled meanwhile) and says whether it worked. */
  async function run(request: () => Promise<unknown>, done: string): Promise<boolean> {
    setBusy(true)
    try { await request(); await load(); setMessage(done); setError(''); return true }
    catch (e) { setError((e as Error).message); setMessage(''); return false }
    finally { setBusy(false) }
  }
  if (stores === null) return error ? <p className="error">{error}</p> : <p className="muted">Loading stores…</p>
  const byCardBox = new Map(trading.filter(t => t.cardboxStoreId).map(t => [t.cardboxStoreId as string, t]))
  const unlinked = trading.filter(t => !t.cardboxStoreId)
  // An unlinked store sharing its name with a CardBox store that already has a Trading store is usually a leftover
  // from before stores moved to CardBox. Saying so stops it reading as a second copy of the real store.
  const linkedNames = new Set(stores.filter(s => byCardBox.has(s.id)).map(s => s.name.trim().toLowerCase()))
  const leftover = (t: TradingStore) => linkedNames.has(t.name.trim().toLowerCase())
  const linkable = stores.filter(s => !byCardBox.has(s.id) || (byCardBox.get(s.id)!.trades === 0 && byCardBox.get(s.id)!.cards === 0))

  return (
    <>
      {message && <p className="notice">{message}</p>}
      {error && <p className="error">{error}</p>}
      <div className="table-wrap"><table className="grid">
        <thead><tr><th>Store</th><th>Plan</th><th>Trial ends</th><th className="r">Trades</th><th className="r">Cards</th></tr></thead>
        <tbody>{stores.map(s => <CardBoxStoreRow key={`${s.id}:${s.name}`} store={s} trading={byCardBox.get(s.id)} run={run} busy={busy} />)}</tbody>
      </table></div>
      <form className="inline-form" onSubmit={async e => {
        e.preventDefault()
        // Cleared only once CardBox has the store, so a failed create keeps the name to retry.
        if (await run(() => api('/api/cardbox/stores', { method: 'POST', body: { name: newName.trim() } }), `${newName.trim()} created.`)) setNewName('')
      }}>
        <label>New store<input required maxLength={120} value={newName} onChange={e => setNewName(e.target.value)} /></label>
        <button type="submit" className="small" disabled={busy}>Create on CardBox</button>
      </form>
      {unlinked.length > 0 && (
        <>
          <h2>Trading stores not yet tied to CardBox</h2>
          <p className="muted">Most link themselves by name the first time their manager signs in. Tie the rest here so their trades and inventory carry over.</p>
          <table className="grid"><tbody>{unlinked.map(t => (
            <tr key={t.id}><td>{t.name}
                {leftover(t) && <div className="muted small">{t.trades === 0 && t.cards === 0 ? 'An empty leftover. ' : ''}The {t.name} above
                  is the one in use, tied to a different Trading store. Nobody can switch to this one.</div>}</td>
              <td className="muted small">{t.people} {t.people === 1 ? 'person' : 'people'} · {t.trades} trades · {t.cards} cards</td>
              <td><select aria-label={`CardBox store for ${t.name}`} value="" disabled={busy} onChange={e => e.target.value &&
                run(() => api(`/api/admin/stores/${t.id}/cardbox`, { method: 'PUT', body: { cardboxStoreId: e.target.value } }), `${t.name} now belongs to ${stores.find(s => s.id === e.target.value)?.name}.`)}>
                <option value="">Tie to a CardBox store…</option>
                {linkable.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select></td></tr>
          ))}</tbody></table>
        </>
      )}
    </>
  )
}

function CardBoxStoreRow({ store: s, trading: t, run, busy }: {
  store: CardBoxStore; trading?: TradingStore; run: (r: () => Promise<unknown>, done: string) => Promise<boolean>; busy: boolean
}) {
  const [name, setName] = useState(s.name)
  return (
    <tr>
      <td>
        <form className="inline-form" onSubmit={e => { e.preventDefault(); run(() => api(`/api/cardbox/stores/${encodeURIComponent(s.id)}`, { method: 'PATCH', body: { name: name.trim() } }), `Renamed to ${name.trim()}.`) }}>
          <input aria-label={`Name of ${s.name}`} required maxLength={120} value={name} onChange={e => setName(e.target.value)} />
          {name.trim() !== s.name && <button type="submit" className="small secondary" disabled={busy}>Rename</button>}
        </form>
        <Link className="small" to={`/app/staff?store=${encodeURIComponent(s.id)}`}>Team</Link>
        {!t && <div className="muted small">No one has used it on Trading yet</div>}
        {t && !t.entitled && <div className="error small">Locked</div>}
      </td>
      <td>{t && <select aria-label={`Plan for ${s.name}`} value={t.planStatus} disabled={busy}
        onChange={e => confirmPlan(s.name, e.target.value) && run(() => api(`/api/admin/stores/${t.id}`, { method: 'PUT', body: { planStatus: e.target.value } }), `${s.name} is now ${planLabel(e.target.value)}.`)}>
        {PLANS.map(p => <option key={p} value={p}>{planLabel(p)}</option>)}
      </select>}</td>
      <td>{t && <DateField label={`Trial end for ${s.name}`} value={t.trialEndsAt.slice(0, 10)} disabled={busy}
        onSave={day => run(() => api(`/api/admin/stores/${t.id}`, { method: 'PUT', body: { trialEndsAt: day } }), `${s.name}'s trial now ends ${day}.`)} />}</td>
      <td className="r">{t?.trades ?? '—'}</td><td className="r">{t?.cards ?? '—'}</td>
    </tr>
  )
}

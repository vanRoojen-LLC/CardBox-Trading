import { useCallback, useEffect, useState } from 'react'
import { api } from './api'

interface GameCoverage {
  printings: number; linked: number; unlinked: number
  byScryfall: number; bySwuDb: number; byTcgplayer: number; receivedAt: string | null
  tradingCards: number; tradingCardsWithoutPrinting: number
  lastPush: { startedAt: string; finishedAt: string | null } | null
}
type Coverage = Record<string, GameCoverage>

const GAMES: Record<string, string> = { 'magic-the-gathering': 'Magic: The Gathering', 'star-wars-unlimited': 'Star Wars: Unlimited' }
const n = (v: number) => v.toLocaleString()
const pct = (part: number, whole: number) => whole ? `${Math.round((100 * part) / whole)}%` : '—'
const when = (iso: string | null | undefined) => iso ? new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '—'

/**
 * How much of Trading's own catalog the CardBox catalog service already covers, per game. Club relays the catalog's
 * printings twice a day; once nearly every Trading card has one, Trading can read cards from the catalog instead.
 */
export default function CatalogCoverage() {
  const [data, setData] = useState<Coverage | null>(null)
  const [error, setError] = useState('')
  const load = useCallback(() => api<Coverage>('/api/admin/catalog-links')
    .then(d => { setData(d); setError('') }).catch(e => setError((e as Error).message)), [])
  useEffect(() => { load() }, [load])

  return (
    <section id="catalog-coverage" style={{ marginTop: 28 }}>
      <div className="page-head">
        <h2>Catalog coverage</h2>
        <button className="small secondary" onClick={load}>Refresh</button>
      </div>
      <p className="muted small">CardBox's catalog printings, relayed by Club twice a day, matched to Trading's own cards.</p>
      {error && <p className="error">{error}</p>}
      {!data && !error && <p className="muted">Loading…</p>}
      {data && (
        <div className="table-wrap">
          <table className="grid">
            <thead><tr><th>Game</th><th>Trading cards covered</th><th>Not covered yet</th><th>Catalog printings</th>
              <th>Matched by</th><th>Last push</th></tr></thead>
            <tbody>
              {Object.entries(data).map(([game, g]) => {
                const covered = g.tradingCards - g.tradingCardsWithoutPrinting
                return (
                  <tr key={game}>
                    <td>{GAMES[game] ?? game}</td>
                    <td className="num">{n(covered)} of {n(g.tradingCards)} <span className="muted">({pct(covered, g.tradingCards)})</span></td>
                    <td className="num">{n(g.tradingCardsWithoutPrinting)}</td>
                    <td className="num">{n(g.printings)}<div className="muted small">{n(g.unlinked)} with no Trading card</div></td>
                    <td className="small">
                      {game === 'magic-the-gathering' ? `Scryfall ${n(g.byScryfall)}`
                        : `swu-db ${n(g.bySwuDb)} · TCGplayer ${n(g.byTcgplayer)}`}
                    </td>
                    <td className="small">{g.lastPush
                      ? g.lastPush.finishedAt ? when(g.lastPush.finishedAt) : `Running since ${when(g.lastPush.startedAt)}`
                      : 'None yet'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

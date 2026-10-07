import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from './api'
import { gameName, useGames, type Game, type GameScope } from './games'

export type { Game }
const label = (game: Game) => gameName(game) ?? game

/** Another game that has matches for a search that found nothing in this one. */
interface Elsewhere { game: Game; count: number; more: boolean; names: string[] }

/**
 * "Did you mean to search Star Wars: Unlimited?" under a card search that found nothing. Render it only once the
 * search in its own game has come back empty: it asks the other games as its own request, so the search itself never
 * waits on it. Either switches the game in place (onPick) or links to the price check for that game (link).
 */
export default function OtherGames({ query, game, onPick, link }: {
  query: string; game: Game; onPick?: (game: Game) => void; link?: (game: Game, query: string) => string
}) {
  const [found, setFound] = useState<Elsewhere[]>([])
  useEffect(() => {
    setFound([])
    const q = query.trim()
    if (q.length < 2) return
    const controller = new AbortController()
    api<{ games: Elsewhere[] }>(`/api/public/cards/elsewhere?game=${game}&q=${encodeURIComponent(q)}&v=${__BUILD_ID__}`, { signal: controller.signal })
      .then(r => setFound(r.games))
      .catch(() => { /* only a suggestion: say nothing rather than an error */ })
    return () => controller.abort()
  }, [query, game])

  return <>{found.map(e => {
    const matches = `${e.count}${e.more ? '+' : ''} ${e.count === 1 && !e.more ? 'match' : 'matches'} in ${label(e.game)}`
    return (
      <p key={e.game} className="elsewhere">
        Did you mean to search {label(e.game)}?{' '}
        {onPick ? <button type="button" className="link" onClick={() => onPick(e.game)}>{matches}</button>
          : <Link to={link ? link(e.game, query.trim()) : `/?game=${e.game}&q=${encodeURIComponent(query.trim())}`}>{matches}</Link>}
        <span className="muted small"> {e.names.join(' · ')}</span>
      </p>
    )
  })}</>
}

/**
 * The game switch above a card search: Magic and Star Wars: Unlimited as buttons, and every other game the viewer may
 * search in a compact "More games" select, preview games marked (only a platform owner sees those). `scope` says which
 * list: the free price check's ('public') or a signed-in store's ('app').
 */
export function GameSwitch({ game, onChange, scope = 'app' }: { game: Game; onChange: (game: Game) => void; scope?: GameScope }) {
  const games = useGames(scope)
  const native = games.filter(g => g.key === 'mtg' || g.key === 'swu')
  const more = games.filter(g => g.key !== 'mtg' && g.key !== 'swu')
  return (
    <div className="seg game-switch" role="group" aria-label="Game">
      {native.map(g => (
        <button key={g.key} type="button" aria-pressed={g.key === game} onClick={() => onChange(g.key)}>{g.name}</button>
      ))}
      {more.length > 0 && (
        <select aria-label="More games" value={more.some(g => g.key === game) ? game : ''}
                className={more.some(g => g.key === game) ? 'chosen' : undefined}
                onChange={e => { if (e.target.value) onChange(e.target.value) }}>
          <option value="">More games…</option>
          {more.map(g => <option key={g.key} value={g.key}>{g.preview ? `${g.name} (Preview)` : g.name}</option>)}
        </select>
      )}
    </div>
  )
}

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from './api'

export type Game = 'mtg' | 'swu'
export const GAME_LABELS: Record<Game, string> = { mtg: 'Magic', swu: 'Star Wars: Unlimited' }

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
    const label = `${e.count}${e.more ? '+' : ''} ${e.count === 1 && !e.more ? 'match' : 'matches'} in ${GAME_LABELS[e.game]}`
    return (
      <p key={e.game} className="elsewhere">
        Did you mean to search {GAME_LABELS[e.game]}?{' '}
        {onPick ? <button type="button" className="link" onClick={() => onPick(e.game)}>{label}</button>
          : <Link to={link ? link(e.game, query.trim()) : `/?game=${e.game}&q=${encodeURIComponent(query.trim())}`}>{label}</Link>}
        <span className="muted small"> {e.names.join(' · ')}</span>
      </p>
    )
  })}</>
}

/** The Magic / Star Wars: Unlimited switch above a card search. */
export function GameSwitch({ game, onChange }: { game: Game; onChange: (game: Game) => void }) {
  return (
    <div className="seg game-switch" role="group" aria-label="Game">
      {(Object.keys(GAME_LABELS) as Game[]).map(g => (
        <button key={g} type="button" aria-pressed={g === game} onClick={() => onChange(g)}>{GAME_LABELS[g]}</button>
      ))}
    </div>
  )
}

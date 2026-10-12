import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from './api'
import { gameName, useGames, type Game, type GameInfo, type GameScope } from './games'

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
 * search in a "More games" picker you can type into, with preview games under their own heading (only people on the
 * Preview list see those). `scope` says which list: the free price check's ('public') or a signed-in store's ('app').
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
      {more.length > 0 && <GamePicker games={more} game={game} onChange={onChange} />}
    </div>
  )
}

/** "More games": a button that opens a searchable list, released games first, then Preview games under their heading. */
function GamePicker({ games, game, onChange }: { games: GameInfo[]; game: Game; onChange: (game: Game) => void }) {
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState('')
  const [active, setActive] = useState(0)
  const box = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const chosen = games.find(g => g.key === game)
  const term = filter.trim().toLowerCase()
  const shown = games.filter(g => !term || g.name.toLowerCase().includes(term) || g.key.toLowerCase().includes(term))
  // Released games first, then Preview: the order the list is drawn in, so arrow keys follow what is on screen.
  const ordered = [...shown.filter(g => !g.preview), ...shown.filter(g => g.preview)]
  useEffect(() => {
    if (!open) return
    const away = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', away)
    return () => document.removeEventListener('mousedown', away)
  }, [open])
  const close = () => { setOpen(false); setFilter(''); button.current?.focus() }
  const pick = (g: GameInfo) => { onChange(g.key); setOpen(false); setFilter('') }
  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close() }
    else if (e.key === 'ArrowDown') { e.preventDefault(); setActive(i => Math.min(ordered.length - 1, i + 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(i => Math.max(0, i - 1)) }
    else if (e.key === 'Enter') { e.preventDefault(); if (ordered[active]) pick(ordered[active]) }
  }
  const option = (g: GameInfo) => {
    const i = ordered.indexOf(g)
    return (
      <li key={g.key} id={`game-option-${g.key}`} role="option" aria-selected={g.key === game}
          className={i === active ? 'active' : undefined} onMouseEnter={() => setActive(i)}
          onMouseDown={e => e.preventDefault()} onClick={() => pick(g)}>{g.name}</li>
    )
  }
  const released = ordered.filter(g => !g.preview), preview = ordered.filter(g => g.preview)
  return (
    <div className="game-picker" ref={box}>
      <button ref={button} type="button" aria-haspopup="listbox" aria-expanded={open} aria-pressed={!!chosen}
              onClick={() => { setOpen(!open); setActive(0) }}>
        {chosen ? chosen.name : 'More games'}{chosen?.preview && <span className="preview-tag">Preview</span>}
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
      </button>
      {open && (
        <div className="game-picker-panel">
          <input autoFocus placeholder={`Find a game (${games.length})`} aria-label="Find a game" role="combobox" aria-expanded="true"
                 aria-controls="game-options" aria-activedescendant={ordered[active] ? `game-option-${ordered[active].key}` : undefined}
                 value={filter} onChange={e => { setFilter(e.target.value); setActive(0) }} onKeyDown={onKey} />
          <ul id="game-options" role="listbox" aria-label="Games">
            {released.length > 0 && preview.length > 0 && <li role="presentation" className="group">Games</li>}
            {released.map(option)}
            {preview.length > 0 && <li role="presentation" className="group">Preview</li>}
            {preview.map(option)}
            {ordered.length === 0 && <li role="presentation" className="none">No game matches.</li>}
          </ul>
        </div>
      )}
    </div>
  )
}

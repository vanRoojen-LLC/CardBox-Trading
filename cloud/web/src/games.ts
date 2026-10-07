import { useEffect, useState } from 'react'
import { api } from './api'

/** A game a card search can look in: `key` is what ?game= takes, `segment` the game stock is filed under. */
export interface GameInfo { key: string; segment: string; name: string; preview: boolean }
export type Game = string

/** Trading's own catalogs, always offered, and shown before the list arrives. */
export const NATIVE_GAMES: GameInfo[] = [
  { key: 'mtg', segment: 'magic-the-gathering', name: 'Magic', preview: false },
  { key: 'swu', segment: 'star-wars-unlimited', name: 'Star Wars: Unlimited', preview: false },
]

/** Names by key and by segment, filled in as lists arrive, so labels anywhere can read them. */
const names: Record<string, string> = {}
for (const g of NATIVE_GAMES) { names[g.key] = g.name; names[g.segment] = g.name }
const known = new Set(NATIVE_GAMES.map(g => g.segment))

/**
 * 'public' is the free price check's list (games out of preview); 'app' is a signed-in store's, which for a platform
 * owner also has the preview games. Each is asked once per page load.
 */
export type GameScope = 'public' | 'app'
const loads: Partial<Record<GameScope, Promise<GameInfo[]>>> = {}

export function loadGames(scope: GameScope): Promise<GameInfo[]> {
  loads[scope] ??= api<{ games: GameInfo[] }>(`/api/${scope}/games`)
    .then(r => {
      for (const g of r.games) { names[g.key] = g.name; names[g.segment] = g.name; known.add(g.segment) }
      return r.games
    })
    .catch(() => { delete loads[scope]; return NATIVE_GAMES })
  return loads[scope]!
}

/** The games for this scope: Magic and SWU at once, the rest when the list arrives. */
export function useGames(scope: GameScope): GameInfo[] {
  const [games, setGames] = useState<GameInfo[]>(NATIVE_GAMES)
  useEffect(() => {
    let current = true
    loadGames(scope).then(g => { if (current) setGames(g) })
    return () => { current = false }
  }, [scope])
  return games
}

/** A game's name from its key ("swu") or segment ("star-wars-unlimited"); undefined if no list named it. */
export function gameName(keyOrSegment: string): string | undefined {
  return names[keyOrSegment]
}

/** Every segment a list so far has named, for rules that may name a game nothing in stock has yet. */
export function knownSegments(): string[] {
  return [...known]
}

import { useEffect, useState } from 'react'
import { api, money, type Card } from '../api'
import SearchIcon from '../SearchIcon'
import CardLightbox from '../CardLightbox'
import OtherGames, { GameSwitch, type Game } from '../OtherGames'
import { loadGames, useGames, type GameInfo } from '../games'

/** A public result row; Star Wars: Unlimited rows also name their variant and link to TCGplayer. */
type PriceCard = Card & { variant?: string | null; url?: string | null }
interface SearchResult { cards: PriceCard[]; pricesUpdatedAt: string | null }

/** Magic and SWU have their own wording; every other game reads "<name> price check". */
const COPY: Record<string, { title: string; example: string }> = {
  mtg: { title: 'Magic card price check', example: 'e.g. Lightning Bolt or DMU 391' },
  swu: { title: 'Star Wars: Unlimited price check', example: 'e.g. Darth Vader or SOR 010' },
}
const copy = (game: Game, games: GameInfo[]) => COPY[game]
  ?? { title: `${games.find(g => g.key === game)?.name ?? 'Card'} price check`, example: 'Card name, or set code and number' }

/** Prices refresh nightly; two days without a good refresh is said on the page rather than shown as current. */
const STALE_MS = 48 * 3600 * 1000

/** The game in the address (?game=swu), so a store can link straight to it. Magic otherwise. */
function gameFromUrl(): Game {
  return new URLSearchParams(window.location.search).get('game') || 'mtg'
}

/** The free price check: search a card, see its market price. Nothing else, by design. */
export default function PriceCheck() {
  const [game, setGame] = useState<Game>(gameFromUrl)
  const games = useGames('public')
  const [query, setQuery] = useState(() => new URLSearchParams(window.location.search).get('q') ?? '')
  const [result, setResult] = useState<SearchResult | null>(null)
  const [error, setError] = useState('')
  const [enlarged, setEnlarged] = useState<PriceCard | null>(null)
  // The search the shown result answers, so the other-games hint never runs for a query still being typed.
  const [searched, setSearched] = useState('')

  // A link to a game that is not (or no longer) public falls back to Magic once the list says so.
  useEffect(() => {
    const linked = gameFromUrl()
    let current = true
    loadGames('public').then(list => { if (current && !list.some(g => g.key === linked)) choose('mtg') })
    return () => { current = false }
  }, [])

  useEffect(() => {
    if (query.trim().length < 2) { setResult(null); setError(''); return }
    let current = true
    const q = query.trim()
    const timer = setTimeout(() => {
      api<SearchResult>(`/api/public/cards?game=${game}&q=${encodeURIComponent(q)}&v=${__BUILD_ID__}`)
        .then(r => { if (current) { setResult(r); setSearched(q); setError('') } })
        .catch(e => { if (current) setError(e.message) })
    }, 300)
    return () => { current = false; clearTimeout(timer) }
  }, [query, game])

  function choose(next: Game) {
    setGame(next)
    setResult(null)
    const url = new URL(window.location.href)
    if (next === 'mtg') url.searchParams.delete('game')
    else url.searchParams.set('game', next)
    window.history.replaceState(null, '', url)
  }

  return (
    <section>
      <h1>{copy(game, games).title}</h1>
      <p className="lede">Free, no account. Search by name, set code or collector number.</p>
      <GameSwitch game={game} onChange={choose} scope="public" />
      <div className="search">
        <SearchIcon />
        <input autoFocus placeholder={copy(game, games).example} value={query}
               onChange={e => setQuery(e.target.value)} aria-label="Card name, set or number" />
        {result && <span className="aside">{result.cards.length} {result.cards.length === 1 ? 'match' : 'matches'}</span>}
      </div>
      {error && <p className="error">{error}</p>}
      {result && (
        <>
          {result.cards.length === 0 ? <>
            <p className="muted">No cards found.</p>
            <OtherGames query={searched} game={game} onPick={choose} />
          </> : (
            <div className="rows">
              <div className="rows-head"><span style={{ flex: 1 }}>Card</span><span className="price-col">Normal</span><span className="price-col">Foil</span></div>
              {result.cards.map(card => (
                <article key={card.id} className="result">
                  {card.image
                    ? <button type="button" className="thumb" onClick={() => setEnlarged(card)} aria-label={`Enlarge ${card.name}`}>
                        <img src={card.image} alt="" loading="lazy" />
                      </button>
                    : <div className="noimg" />}
                  <div className="info">
                    <h3>{card.name}</h3>
                    <div className="meta"><span>{card.setName}</span><b>{card.set.toUpperCase()} #{card.number}</b><span className={`rarity ${card.rarity}`}>{card.rarity}</span>
                      {card.variant && <span className="variant">{card.variant}</span>}
                      {card.url && <a href={card.url} target="_blank" rel="noreferrer">TCGplayer</a>}</div>
                  </div>
                  <div className="price-col"><small>Normal</small>{money(card.usd)}</div>
                  <div className="price-col foil"><small>Foil</small>{money(card.usdFoil)}
                    {card.usdEtched != null && <span className="etched">Etched {money(card.usdEtched)}</span>}</div>
                </article>
              ))}
            </div>
          )}
          {result.pricesUpdatedAt && <p className="muted small">Prices updated {new Date(result.pricesUpdatedAt).toLocaleString()}.
            {Date.now() - Date.parse(result.pricesUpdatedAt) > STALE_MS && <> <b>Prices may be out of date.</b></>}</p>}
        </>
      )}
      {enlarged?.image && (
        <CardLightbox onClose={() => setEnlarged(null)}
                      slides={[{ image: enlarged.image, caption: `${enlarged.name} · ${enlarged.setName} (${enlarged.set}) #${enlarged.number}` }]} />
      )}
    </section>
  )
}

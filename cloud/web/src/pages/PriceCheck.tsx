import { useEffect, useState } from 'react'
import { api, money, type Card } from '../api'
import SearchIcon from '../SearchIcon'
import CardLightbox from '../CardLightbox'

/** A public result row; Star Wars: Unlimited rows also name their variant and link to TCGplayer. */
type PriceCard = Card & { variant?: string | null; url?: string | null }
interface SearchResult { cards: PriceCard[]; pricesUpdatedAt: string | null }
type Game = 'mtg' | 'swu'
/** Another game that has matches for a search that found nothing in this one. */
interface Elsewhere { game: Game; count: number; more: boolean; names: string[] }

const GAMES: Record<Game, { label: string; title: string; example: string }> = {
  mtg: { label: 'Magic', title: 'Magic card price check', example: 'e.g. Lightning Bolt or DMU 391' },
  swu: { label: 'Star Wars: Unlimited', title: 'Star Wars: Unlimited price check', example: 'e.g. Darth Vader or SOR 010' },
}

/** Prices refresh nightly; two days without a good refresh is said on the page rather than shown as current. */
const STALE_MS = 48 * 3600 * 1000

/** The game in the address (?game=swu), so a store can link straight to it. Magic otherwise. */
function gameFromUrl(): Game {
  return new URLSearchParams(window.location.search).get('game') === 'swu' ? 'swu' : 'mtg'
}

/** The free price check: search a card, see its market price. Nothing else, by design. */
export default function PriceCheck() {
  const [game, setGame] = useState<Game>(gameFromUrl)
  const [query, setQuery] = useState('')
  const [result, setResult] = useState<SearchResult | null>(null)
  const [error, setError] = useState('')
  const [enlarged, setEnlarged] = useState<PriceCard | null>(null)
  const [elsewhere, setElsewhere] = useState<Elsewhere[]>([])

  useEffect(() => {
    setElsewhere([])
    if (query.trim().length < 2) { setResult(null); setError(''); return }
    let current = true
    const q = encodeURIComponent(query.trim())
    const timer = setTimeout(() => {
      api<SearchResult>(`/api/public/cards?game=${game}&q=${q}&v=${__BUILD_ID__}`)
        .then(r => {
          if (!current) return
          setResult(r); setError('')
          // Only after this game found nothing: ask, as a separate request, whether another game has the card.
          if (r.cards.length === 0) {
            api<{ games: Elsewhere[] }>(`/api/public/cards/elsewhere?game=${game}&q=${q}&v=${__BUILD_ID__}`)
              .then(e => { if (current) setElsewhere(e.games) })
              .catch(() => {})
          }
        })
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
      <h1>{GAMES[game].title}</h1>
      <p className="lede">Free, no account. Search by name, set code or collector number.</p>
      <div className="seg game-switch" role="group" aria-label="Game">
        {(Object.keys(GAMES) as Game[]).map(g => (
          <button key={g} type="button" aria-pressed={g === game} onClick={() => choose(g)}>{GAMES[g].label}</button>
        ))}
      </div>
      <div className="search">
        <SearchIcon />
        <input autoFocus placeholder={GAMES[game].example} value={query}
               onChange={e => setQuery(e.target.value)} aria-label="Card name, set or number" />
        {result && <span className="aside">{result.cards.length} {result.cards.length === 1 ? 'match' : 'matches'}</span>}
      </div>
      {error && <p className="error">{error}</p>}
      {result && (
        <>
          {result.cards.length === 0 ? <>
            <p className="muted">No cards found.</p>
            {elsewhere.map(e => (
              <p key={e.game} className="elsewhere">
                Did you mean to search {GAMES[e.game].label}?{' '}
                <button type="button" className="link" onClick={() => choose(e.game)}>
                  {e.count}{e.more ? '+' : ''} {e.count === 1 && !e.more ? 'match' : 'matches'} in {GAMES[e.game].label}
                </button>
                <span className="muted small"> {e.names.join(' · ')}</span>
              </p>
            ))}
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

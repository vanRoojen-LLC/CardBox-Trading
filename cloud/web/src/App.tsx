import { useCallback, useEffect, useState } from 'react'
import { Link, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { api, ApiError, SIGNED_OUT, type Me } from './api'
import PriceCheck from './pages/PriceCheck'
import { Login, Signup } from './pages/Auth'
import { safeReturnTo } from './returnTo'
import StoreApp from './pages/StoreApp'
import { Mark, Wordmark } from './Brand'
import AppSwitcher from './AppSwitcher'

export default function App() {
  const [me, setMe] = useState<Me | null | undefined>(undefined)
  const refresh = useCallback(
    () => api<Me>('/api/auth/me').then(setMe).catch(() => setMe(null)),
    [],
  )
  useEffect(() => { refresh() }, [refresh])
  // A request that finds the session gone sends the person to sign in again; a trade in progress is kept as a draft.
  const [expired, setExpired] = useState(false)
  useEffect(() => {
    const onSignedOut = () => { setExpired(true); setMe(null) }
    window.addEventListener(SIGNED_OUT, onSignedOut)
    return () => window.removeEventListener(SIGNED_OUT, onSignedOut)
  }, [])

  return (
    <Routes>
      <Route path="/" element={<Public me={me}><PriceCheck /></Public>} />
      <Route path="/login" element={me ? <Navigate to={safeReturnTo(new URLSearchParams(window.location.search).get('returnTo')) ?? '/app'} replace /> : <Public me={me}><Login /></Public>} />
      <Route path="/signup" element={me ? <Navigate to="/app" replace /> : <Public me={me}><Signup onDone={refresh} /></Public>} />
      <Route path="/app/*" element={
        me === undefined ? <p className="page muted">Loading…</p>
          : me === null ? <SignIn expired={expired} />
          : <StoreApp me={me} onSignOut={refresh} />
      } />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

function Public({ me, children }: { me: Me | null | undefined; children: React.ReactNode }) {
  return (
    <>
      <header className="topbar">
        <div className="topbar-left"><AppSwitcher /><Link to="/" className="brand"><Mark /><Wordmark /></Link></div>
        <nav className="who">
          {me
            ? <Link to="/app" className="button small open-store" title={`Open ${me.store}`}>Open store<small>{me.store}</small></Link>
            : <><a href="/api/auth/login" className="button small ghost">Store sign in</a><Link to="/signup" className="button small">Start free trial</Link></>}
        </nav>
      </header>
      <main className="page">{children}</main>
      <footer className="footer">
        Magic prices from <a href="https://scryfall.com" target="_blank" rel="noreferrer">Scryfall</a>. Star Wars: Unlimited prices
        from <a href="https://www.tcgplayer.com" target="_blank" rel="noreferrer">TCGplayer</a>, via <a href="https://tcgcsv.com" target="_blank" rel="noreferrer">TCGCSV</a> and <a href="https://www.swu-db.com" target="_blank" rel="noreferrer">swu-db</a>.
        Price lookup is free, with no account needed. Magic card names and data are property of Wizards of the Coast.
        Star Wars: Unlimited is a trademark of Lucasfilm and Fantasy Flight Games.
      </footer>
    </>
  )
}

/** Clears our session, then ends the Auth0 session too so the next sign-in asks again. */
export function useSignOut(onSignOut: () => Promise<void>) {
  const navigate = useNavigate()
  return async () => {
    try {
      const { logoutUrl } = await api<{ logoutUrl: string }>('/api/auth/logout', { method: 'POST', body: {} })
      window.location.assign(logoutUrl)
      return
    } catch (e) { if (!(e instanceof ApiError)) throw e }
    await onSignOut()
    navigate('/')
  }
}

/**
 * Someone arriving at a store page signed out (often from cardbox.club's Trading link) goes straight through CardBox
 * sign-in: the shared Auth0 session answers without asking, so they land on the page signed in. A session that ended
 * mid-work, or a second try within a minute (the first came back without a session), shows the sign-in page instead,
 * so a sign-in that cannot stick never loops.
 */
const SILENT_TRY = 'cardbox.silentSignIn'
function SignIn({ expired }: { expired: boolean }) {
  const here = window.location.pathname + window.location.search
  let recent = false
  try { recent = Date.now() - Number(sessionStorage.getItem(SILENT_TRY) ?? 0) < 60_000 } catch { recent = true }
  const silent = !expired && !recent
  useEffect(() => {
    if (!silent) return
    try { sessionStorage.setItem(SILENT_TRY, String(Date.now())) } catch { /* guarded above */ }
    window.location.replace(`/api/auth/login?returnTo=${encodeURIComponent(here)}`)
  }, [silent, here])
  return silent ? <p className="page muted">Signing you in…</p> : <Navigate to={signInUrl(expired)} replace />
}

/** The sign-in page, coming back to the page the person asked for once they are in. */
function signInUrl(expired: boolean) {
  const params = new URLSearchParams()
  if (expired) params.set('error', 'Your session ended. Sign in again to carry on.')
  const here = window.location.pathname + window.location.search
  if (here !== '/app' && here !== '/app/') params.set('returnTo', here)
  const query = params.toString()
  return query ? `/login?${query}` : '/login'
}

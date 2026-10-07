import { useCallback, useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { api, registerLocation, setRegisterLocation, type Me, type StoreInfo } from '../api'
import { useSignOut } from '../App'
import { Mark, Wordmark } from '../Brand'
import NewTrade from './NewTrade'
import { History, TradeDetail } from './History'
import Inventory from './Inventory'
import { CountDetail, Counts } from './Counts'
import PriceCheck from './PriceCheck'
import Rates from './Rates'
import Staff from './Staff'
import Store from './Store'
import Admin from './Admin'
import HelpFeedback from '../HelpFeedback'

export default function StoreApp({ me, onSignOut }: { me: Me; onSignOut: () => Promise<void> }) {
  const signOut = useSignOut(onSignOut)
  const { pathname } = useLocation()
  const [store, setStore] = useState<StoreInfo | null>(null)
  const [locationId, setLocationId] = useState<string | null>(null)
  const loadStore = useCallback((info: StoreInfo) => {
    setStore(info)
    setLocationId(current => info.locations.some(l => l.id === current && !l.archived) ? current : registerLocation(info)?.id ?? null)
  }, [])
  // Inventory, counts and Store need the store's locations; if they can't load, say so and offer a retry.
  const [storeError, setStoreError] = useState('')
  const fetchStore = useCallback(() => {
    return api<StoreInfo>('/api/app/store').then(loadStore).catch(e => setStoreError((e as Error).message))
  }, [loadStore])
  useEffect(() => { if (me.entitled) fetchStore() }, [me.entitled, fetchStore])
  const needsStore = (page: React.ReactNode) => store ? page : storeError
    ? <div className="error" role="alert"><p>Couldn’t load your store’s locations: {storeError}</p>
        <button className="small secondary" onClick={() => { setStoreError(''); fetchStore() }}>Try again</button></div>
    : <p className="muted">Loading…</p>
  const open = store?.locations.filter(l => !l.archived) ?? []
  const pickLocation = (id: string) => { setRegisterLocation(id); setLocationId(id) }
  const [switching, setSwitching] = useState(false)
  const [switchError, setSwitchError] = useState('')
  async function switchStore(tenantId: string) {
    setSwitching(true); setSwitchError('')
    try {
      await api('/api/auth/switch', { method: 'POST', body: { tenantId } })
      // Everything on screen belongs to the old store, so start the new one fresh.
      window.location.assign('/app/trade')
    } catch (e) {
      // The picker is tied to the store signed in now, so it springs back on its own.
      setSwitchError(`Couldn’t switch store: ${(e as Error).message}`)
      setSwitching(false)
    }
  }
  // The phone menu holds the less-used pages and the account controls; it closes whenever the page changes.
  const [menuAt, setMenuAt] = useState<string | null>(null)
  const menuOpen = menuAt === pathname
  const setMenuOpen = (on: boolean) => setMenuAt(on ? pathname : null)
  useEffect(() => {
    if (!menuOpen) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenuAt(null) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [menuOpen])
  const secondary = ['/app/rates', '/app/staff', '/app/store', '/app/admin'].some(p => pathname.startsWith(p))
  const account = (
    <>
      {me.stores.length > 1 ? (
        <select className="register" aria-label="Switch store" disabled={switching} value={me.stores.find(s => s.current)?.tenantId}
          onChange={e => switchStore(e.target.value)}>
          {me.stores.map(s => <option key={s.tenantId} value={s.tenantId}>{s.name}</option>)}
        </select>
      ) : <strong>{me.store}</strong>}
      {open.length > 1 && locationId && (
        <select className="register" aria-label="This register's location" title="Trades taken on this device go to this location"
          value={locationId} onChange={e => pickLocation(e.target.value)}>
          {open.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
        </select>
      )}
      <span className="who-name">{me.name}</span>
      <HelpFeedback />
      <button className="small ghost" onClick={signOut}>Sign out</button>
    </>
  )
  const trialDays = Math.max(0, Math.ceil((new Date(me.trialEndsAt).getTime() - Date.now()) / 86_400_000))
  return (
    <>
      <header className="topbar app">
        <NavLink to="/app/trade" className="brand"><Mark /><Wordmark /></NavLink>
        <span className="topbar-store">{me.stores.find(s => s.current)?.name ?? me.store}
          {open.length > 1 && locationId && <span className="muted"> · {open.find(l => l.id === locationId)?.name}</span>}</span>
        {/* On phones the four counter pages stay as tabs and everything else lives under More. */}
        <nav className="tabs" aria-label="Main">
          <NavLink to="/app/trade"><span className="long">New trade</span><span className="short">Trade</span></NavLink>
          <NavLink to="/app/price"><span className="long">Price check</span><span className="short">Prices</span></NavLink>
          <NavLink to="/app/history">History</NavLink>
          <NavLink to="/app/inventory">Inventory</NavLink>
          <NavLink to="/app/rates" className="wide-only">Buy rates</NavLink>
          <NavLink to="/app/staff" className="wide-only">Team</NavLink>
          <NavLink to="/app/store" className="wide-only">Store</NavLink>
          {me.admin && <NavLink to="/app/admin" className="wide-only">Admin</NavLink>}
          <button type="button" className={`more-tab${secondary || menuOpen ? ' active' : ''}`} aria-expanded={menuOpen} aria-controls="app-menu"
            onClick={() => setMenuOpen(!menuOpen)}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
              {menuOpen ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
            </svg>More</button>
        </nav>
        <div className="who">{account}</div>
      </header>
      {menuOpen && (
        <>
          <div className="menu-scrim" onClick={() => setMenuOpen(false)} />
          <div className="app-menu" id="app-menu" role="dialog" aria-label="More">
            <nav className="app-menu-links" aria-label="More pages">
              <NavLink to="/app/rates">Buy rates</NavLink>
              <NavLink to="/app/staff">Team</NavLink>
              <NavLink to="/app/store">Store</NavLink>
              {me.admin && <NavLink to="/app/admin">Admin</NavLink>}
            </nav>
            <div className="app-menu-account">
              <span className="muted small">Signed in as {me.name}</span>
              {account}
            </div>
          </div>
        </>
      )}
      {switchError && <div className="banner error" role="alert">{switchError}</div>}
      {me.planStatus === 'trial' && me.entitled && <div className="banner">Free trial: {trialDays} days left.</div>}
      <main className={pathname.startsWith('/app/trade') || pathname.startsWith('/app/history') || pathname.startsWith('/app/inventory') || pathname.startsWith('/app/admin') ? 'page wide' : 'page'}>
        {pathname.startsWith('/app/admin') && me.admin ? <Admin me={me} onChange={onSignOut} />
        : !me.entitled ? (
          <div className="panel"><h1>Your trial has ended</h1><p>Contact us to keep using trade-ins, history and exports. The free price check still works.</p></div>
        ) : (
          <Routes>
            <Route path="trade" element={<NewTrade locationId={locationId} draftKey={`cardbox.tradeDraft:${me.email}:${me.stores.find(s => s.current)?.tenantId ?? me.store}`} />} />
            <Route path="price" element={<PriceCheck />} />
            <Route path="history" element={<History locations={store?.locations ?? []} />} />
            <Route path="history/:id" element={<TradeDetail />} />
            <Route path="inventory" element={needsStore(store && <Inventory locations={store.locations} registerLocationId={locationId} owner={me.role === 'owner'} />)} />
            <Route path="inventory/counts" element={needsStore(store && <Counts locations={store.locations} />)} />
            <Route path="inventory/counts/:id" element={<CountDetail me={me} />} />
            <Route path="rates" element={<Rates me={me} />} />
            <Route path="staff" element={<Staff me={me} onChange={onSignOut} />} />
            <Route path="store" element={needsStore(store && <Store me={me} store={store} onSaved={info => { loadStore(info); onSignOut() }} />)} />
            {/* Absolute: a relative target inside this splat route resolves against the unknown path and loops. */}
            <Route path="*" element={<Navigate to="/app/trade" replace />} />
          </Routes>
        )}
      </main>
    </>
  )
}

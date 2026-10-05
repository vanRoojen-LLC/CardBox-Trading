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
  useEffect(() => {
    if (me.entitled) api<StoreInfo>('/api/app/store').then(loadStore).catch(() => setStore(null))
  }, [me.entitled, loadStore])
  const open = store?.locations.filter(l => !l.archived) ?? []
  const pickLocation = (id: string) => { setRegisterLocation(id); setLocationId(id) }
  async function switchStore(tenantId: string) {
    await api('/api/auth/switch', { method: 'POST', body: { tenantId } })
    // Everything on screen belongs to the old store, so start the new one fresh.
    window.location.assign('/app/trade')
  }
  const trialDays = Math.max(0, Math.ceil((new Date(me.trialEndsAt).getTime() - Date.now()) / 86_400_000))
  return (
    <>
      <header className="topbar">
        <NavLink to="/app/trade" className="brand"><Mark /><Wordmark /></NavLink>
        <nav className="tabs" aria-label="Main">
          <NavLink to="/app/trade">New trade</NavLink>
          <NavLink to="/app/price">Price check</NavLink>
          <NavLink to="/app/history">History</NavLink>
          <NavLink to="/app/inventory">Inventory</NavLink>
          <NavLink to="/app/rates">Buy rates</NavLink>
          <NavLink to="/app/staff">Team</NavLink>
          <NavLink to="/app/store">Store</NavLink>
          {me.admin && <NavLink to="/app/admin">Admin</NavLink>}
        </nav>
        <div className="who">
          {me.stores.length > 1 ? (
            <select className="register" aria-label="Store" value={me.stores.find(s => s.current)?.tenantId}
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
          <span>{me.name}</span>
          <HelpFeedback />
          <button className="small ghost" onClick={signOut}>Sign out</button>
        </div>
      </header>
      {me.planStatus === 'trial' && me.entitled && <div className="banner">Free trial: {trialDays} days left.</div>}
      <main className={pathname.startsWith('/app/trade') || pathname.startsWith('/app/history') || pathname.startsWith('/app/inventory') || pathname.startsWith('/app/admin') ? 'page wide' : 'page'}>
        {pathname.startsWith('/app/admin') && me.admin ? <Admin me={me} onChange={onSignOut} />
        : !me.entitled ? (
          <div className="panel"><h1>Your trial has ended</h1><p>Contact us to keep using trade-ins, history and exports. The free price check still works.</p></div>
        ) : (
          <Routes>
            <Route path="trade" element={<NewTrade locationId={locationId} />} />
            <Route path="price" element={<PriceCheck />} />
            <Route path="history" element={<History locations={store?.locations ?? []} />} />
            <Route path="history/:id" element={<TradeDetail />} />
            <Route path="inventory" element={store ? <Inventory locations={store.locations} registerLocationId={locationId} owner={me.role === 'owner'} /> : <p className="muted">Loading…</p>} />
            <Route path="inventory/counts" element={store ? <Counts locations={store.locations} /> : <p className="muted">Loading…</p>} />
            <Route path="inventory/counts/:id" element={<CountDetail me={me} />} />
            <Route path="rates" element={<Rates me={me} />} />
            <Route path="staff" element={<Staff me={me} onChange={onSignOut} />} />
            <Route path="store" element={<Store me={me} store={store} onSaved={info => { loadStore(info); onSignOut() }} />} />
            <Route path="*" element={<Navigate to="trade" replace />} />
          </Routes>
        )}
      </main>
    </>
  )
}

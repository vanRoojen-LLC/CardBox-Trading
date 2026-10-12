import { useEffect, useRef, useState } from 'react'

/**
 * The switch between the two CardBox sites, top left on both: cardbox.club (collections and scanning) and
 * cardbox.trading (this site). Both sign in with the same CardBox login, so moving across needs no second sign-in.
 */
const APPS = [
  { name: 'CardBox Club', about: 'Your collection, scanning and imports', href: 'https://cardbox.club/', current: false },
  { name: 'CardBox Trading', about: 'Store trades, inventory and buy rates', href: '/app', current: true },
]

export default function AppSwitcher() {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const away = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', away)
    window.addEventListener('keydown', esc)
    return () => { document.removeEventListener('mousedown', away); window.removeEventListener('keydown', esc) }
  }, [open])
  return (
    <div className="app-switcher" ref={box}>
      <button type="button" className="app-switcher-button" aria-label="Switch CardBox app" title="Switch CardBox app"
              aria-haspopup="true" aria-expanded={open} onClick={() => setOpen(!open)}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          {[4, 12, 20].flatMap(y => [4, 12, 20].map(x => <circle key={`${x}${y}`} cx={x} cy={y} r="2.2" />))}
        </svg>
      </button>
      {open && (
        <div className="app-switcher-menu" role="menu">
          {APPS.map(a => (
            <a key={a.name} role="menuitem" href={a.href} aria-current={a.current ? 'page' : undefined} onClick={() => setOpen(false)}>
              <strong>{a.name}</strong><span>{a.about}</span>
            </a>
          ))}
        </div>
      )}
    </div>
  )
}

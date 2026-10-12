/**
 * Light, dark, or follow the device (UX audit, 2026-10-11). Kept per device rather than per person: a counter
 * iPad is shared by the whole shift, and the right theme depends on the room's light, not on who signed in.
 * The inline script in index.html applies the saved choice before the first paint.
 */
export type ThemeChoice = 'system' | 'light' | 'dark'
export const THEME_KEY = 'cardbox.theme'

export function readTheme(): ThemeChoice {
  try {
    const v = window.localStorage.getItem(THEME_KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch { return 'system' }
}

export function saveTheme(choice: ThemeChoice): void {
  try { window.localStorage.setItem(THEME_KEY, choice) } catch { /* not remembered; the page still switches */ }
  if (choice === 'system') document.documentElement.removeAttribute('data-theme')
  else document.documentElement.setAttribute('data-theme', choice)
}

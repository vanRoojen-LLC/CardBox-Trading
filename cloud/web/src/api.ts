export type Money = number | string | null

export interface Card {
  id: string
  name: string
  set: string
  setName: string
  number: string
  rarity: string
  image: string | null
  usd: Money
  usdFoil: Money
  usdEtched: Money
}

export interface Me {
  name: string
  email: string
  role: 'owner' | 'staff'
  /** Platform owner (verified OWNER_EMAIL), separate from owning a store. */
  admin: boolean
  store: string
  planStatus: string
  trialEndsAt: string
  entitled: boolean
  /** Every store this login is on; `current` marks the one signed in now. */
  stores: { tenantId: string; name: string; role: 'owner' | 'staff'; current: boolean }[]
  /** People, stores and roles come from CardBox (cardbox.club) and are changed there, through /api/cardbox. */
  cardbox: boolean
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/** Fired when a signed-in request comes back 401 (the session ended); App sends the person to sign in again. */
export const SIGNED_OUT = 'cardbox:signed-out'

export async function api<T>(path: string, options: { method?: string; body?: unknown; signal?: AbortSignal } = {}): Promise<T> {
  const response = await fetch(path, {
    method: options.method ?? 'GET',
    credentials: 'same-origin',
    headers: options.body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  })
  const text = await response.text()
  // A proxy or gateway error page is HTML, not JSON: report the status instead of a raw SyntaxError.
  let data: unknown = null
  try { data = text ? JSON.parse(text) : null } catch {
    if (response.ok) throw new ApiError(response.status, 'The server sent a reply we couldn’t read. Try again.')
  }
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/api/auth/')) window.dispatchEvent(new Event(SIGNED_OUT))
    const message = (data as { error?: unknown } | null)?.error
    throw new ApiError(response.status, typeof message === 'string' && message ? message
      : response.status >= 500 ? `The server had a problem (${response.status}). Try again in a moment.` : `Request failed (${response.status})`)
  }
  return data as T
}

/** True for the error a cancelled request rejects with, which callers ignore. */
export const aborted = (e: unknown) => e instanceof DOMException && e.name === 'AbortError'

export function money(value: Money | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const n = Number(value)
  if (!Number.isFinite(n)) return '—'
  // Rounded to cents first, so a tiny negative never shows as "-$0.00".
  const cents = Math.round(n * 100)
  return `${cents < 0 ? '-' : ''}$${(Math.abs(cents) / 100).toFixed(2)}`
}

/** US numbers are stored as bare digits; show them the way staff read them aloud. */
export function phoneText(value: string | null | undefined): string {
  const digits = (value ?? '').replace(/\D/g, '')
  const local = digits.length === 11 && digits.startsWith('1') ? digits.slice(1) : digits
  return local.length === 10 ? `(${local.slice(0, 3)}) ${local.slice(3, 6)}-${local.slice(6)}` : value ?? ''
}

export const FINISHES = [
  { key: 'normal', label: 'Normal', price: (c: Card) => c.usd },
  { key: 'foil', label: 'Foil', price: (c: Card) => c.usdFoil },
  { key: 'etched', label: 'Etched', price: (c: Card) => c.usdEtched },
] as const

export const CONDITIONS = ['NM', 'LP', 'MP', 'HP', 'DMG'] as const

export interface StoreLocation { id: string; name: string; address: string; phone: string; archived: boolean }

export interface StoreInfo {
  name: string
  website: string
  phone: string
  contactEmail: string
  /** Tied to a CardBox store, whose name is CardBox's: renaming here renames it there too. */
  onCardBox: boolean
  /** Whether this person may change the name: anyone owning a store not on CardBox, or a platform owner. */
  canRename: boolean
  /** Open locations first, in the order they were added. */
  locations: StoreLocation[]
}

const REGISTER_LOCATION = 'cardbox.registerLocation'

/** The location this device takes trades at: the one picked here if it is still open, else the store's first open one. */
export function registerLocation(store: StoreInfo | null): StoreLocation | null {
  const open = store?.locations.filter(l => !l.archived) ?? []
  let saved: string | null = null
  try { saved = localStorage.getItem(REGISTER_LOCATION) } catch { /* private mode: fall back to the first location */ }
  return open.find(l => l.id === saved) ?? open[0] ?? null
}

export function setRegisterLocation(id: string) {
  try { localStorage.setItem(REGISTER_LOCATION, id) } catch { /* the choice lasts until reload */ }
}

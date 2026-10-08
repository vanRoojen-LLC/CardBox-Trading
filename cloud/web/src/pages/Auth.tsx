import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import { safeReturnTo } from '../returnTo'

// Sign-in is a full-page redirect to Auth0 Universal Login, so these are plain links, not fetches.
const SIGN_IN = '/api/auth/login'
const SIGN_UP = '/api/auth/login?signup=true'
const OTHER_ACCOUNT = '/api/auth/login?chooseAccount=true'

const withReturn = (href: string, returnTo: string | null) =>
  returnTo ? `${href}${href.includes('?') ? '&' : '?'}returnTo=${encodeURIComponent(returnTo)}` : href

export function Login() {
  const [params] = useSearchParams()
  const error = params.get('error')
  const returnTo = safeReturnTo(params.get('returnTo'))
  return (
    <div className="panel narrow">
      <h1>Store sign in</h1>
      <p className="muted">You sign in with your CardBox login, the same one you use on cardbox.club.</p>
      {error && <p className="error">{error}</p>}
      <a className="button" href={withReturn(SIGN_IN, returnTo)}>Sign in</a>
      <p className="muted"><a href={withReturn(OTHER_ACCOUNT, returnTo)}>Use a different account</a></p>
      <p className="muted">New store? <a href={SIGN_UP}>Start a free trial</a></p>
    </div>
  )
}

/** After Auth0 sign-up (or a first sign-in with no store yet): name the store to start its trial. */
export function Signup({ onDone }: { onDone: () => Promise<void> }) {
  const [pending, setPending] = useState<{ email: string } | null | undefined>(undefined)
  const [form, setForm] = useState({ storeName: '', name: '' })
  const [error, setError] = useState('')
  const navigate = useNavigate()
  useEffect(() => {
    api<{ email: string; name: string }>('/api/auth/pending')
      .then(p => { setPending(p); setForm(f => ({ ...f, name: p.name })) })
      .catch(e => { if (e instanceof ApiError && e.status === 404) setPending(null); else setError(e.message) })
  }, [])
  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: e.target.value })
  async function submit(e: React.FormEvent) {
    e.preventDefault()
    try {
      await api('/api/auth/signup', { method: 'POST', body: form })
      await onDone()
      navigate('/app')
    } catch (err) { setError((err as Error).message) }
  }
  if (pending === undefined) return <p>{error || 'Loading…'}</p>
  if (pending === null) return (
    <div className="panel narrow">
      <h1>Start your store's free trial</h1>
      <p className="muted">30 days free. Trade-ins, payouts, history and POS exports for your whole staff.</p>
      <a className="button" href={SIGN_UP}>Create your login</a>
      <p className="muted">Already have a CardBox login? <a href={SIGN_IN}>Sign in</a></p>
    </div>
  )
  return (
    <form className="panel narrow" onSubmit={submit}>
      <h1>Name your store</h1>
      <p className="muted">Signed in as {pending.email}. Your 30-day free trial starts now.</p>
      <label>Store name<input required value={form.storeName} onChange={set('storeName')} /></label>
      <label>Your name<input required autoComplete="name" value={form.name} onChange={set('name')} /></label>
      {error && <p className="error">{error}</p>}
      <button type="submit">Create store</button>
    </form>
  )
}

import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useLocation } from 'react-router-dom'
import { api } from './api'

/** The last few uncaught errors in this tab, sent with a report so a bug comes with what the browser saw. */
const recentErrors: string[] = []
function remember(message: string) {
  const line = message.replace(/\s+/g, ' ').trim().slice(0, 300)
  if (!line) return
  recentErrors.push(line)
  if (recentErrors.length > 5) recentErrors.shift()
}
if (typeof window !== 'undefined') {
  window.addEventListener('error', e => {
    const where = e.filename ? ` (${e.filename.split('/').pop()?.split('?')[0]}:${e.lineno})` : ''
    remember(`${e.message}${where}`)
  })
  window.addEventListener('unhandledrejection', e => {
    const reason = e.reason instanceof Error ? `${e.reason.name}: ${e.reason.message}` : String(e.reason)
    remember(`Unhandled rejection: ${reason}`)
  })
}

const KINDS = [
  { key: 'bug', label: 'Bug', prompt: 'What went wrong? What did you expect to happen?' },
  { key: 'question', label: 'Question', prompt: 'What would you like to know?' },
  { key: 'idea', label: 'Idea', prompt: 'What would make CardBox Trading better for your store?' },
] as const
type Kind = typeof KINDS[number]['key']

function environment() {
  const language = navigator.language
  return {
    userAgent: navigator.userAgent.slice(0, 400),
    viewport: `${Math.round(window.innerWidth)}x${Math.round(window.innerHeight)}`,
    build: __BUILD_ID__,
    language: /^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}$/.test(language) ? language : undefined,
    recentErrors: recentErrors.length ? [...recentErrors] : undefined,
  }
}

/** "Help & feedback" in the store app's top bar: a bug, question or idea, sent privately to the CardBox team. */
export default function HelpFeedback() {
  const [open, setOpen] = useState(false)
  const close = useCallback(() => setOpen(false), [])
  return (
    <>
      <button className="small ghost" onClick={() => setOpen(true)}>Help &amp; feedback</button>
      {/* Rendered on the body, so it doesn't pick up the top bar's colors and sizes. */}
      {open && createPortal(<FeedbackDialog onClose={close} />, document.body)}
    </>
  )
}

function FeedbackDialog({ onClose }: { onClose: () => void }) {
  const { pathname } = useLocation()
  const [kind, setKind] = useState<Kind>('bug')
  const [description, setDescription] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [sent, setSent] = useState(false)
  const text = useRef<HTMLTextAreaElement>(null)
  useEffect(() => {
    text.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!description.trim()) return
    setSending(true)
    try {
      await api('/api/support/reports', { method: 'POST', body: { kind, description: description.trim(), page: pathname, environment: environment() } })
      setSent(true); setError('')
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="modal-backdrop" onClick={e => { if (e.target === e.currentTarget) onClose() }}>
      <div className="modal panel" role="dialog" aria-modal="true" aria-labelledby="feedback-title">
        <h2 id="feedback-title">Help &amp; feedback</h2>
        {sent ? (
          <>
            <p className="notice">Thanks, we've got it. The CardBox team will take a look.</p>
            <div className="actions"><button onClick={onClose}>Done</button></div>
          </>
        ) : (
          <form onSubmit={submit}>
            <div className="seg wide" role="group" aria-label="Kind of report">
              {KINDS.map(k => (
                <button key={k.key} type="button" aria-pressed={kind === k.key} onClick={() => setKind(k.key)}>{k.label}</button>
              ))}
            </div>
            <label>{KINDS.find(k => k.key === kind)!.prompt}
              <textarea ref={text} required maxLength={5000} rows={6} value={description} onChange={e => setDescription(e.target.value)} />
            </label>
            <p className="muted small">
              Reports go privately to the CardBox team, with this page's address, your browser and screen size, the app
              version and any recent error messages, so we can reproduce problems. Please don't include customer details.
            </p>
            {error && <p className="error">{error}</p>}
            <div className="actions">
              <button type="submit" disabled={sending || !description.trim()}>{sending ? 'Sending…' : 'Send'}</button>
              <button type="button" className="secondary" onClick={onClose}>Cancel</button>
            </div>
          </form>
        )}
      </div>
    </div>
  )
}

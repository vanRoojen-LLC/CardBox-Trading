import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from './api'

interface SupportReport {
  id: string; kind: 'bug' | 'question' | 'idea'; description: string; page: string
  environment: { userAgent?: string; viewport?: string; build?: string; language?: string; recentErrors?: string[] }
  status: 'saved' | 'filed' | 'failed'; githubIssueNumber: number | null; githubIssueUrl: string | null
  githubState: string | null; attemptCount: number; lastError: string | null; createdAt: string
  storeId: string; store: string; reporterName: string; reporterEmail: string
}
interface Page { reports: SupportReport[]; total: number; page: number; size: number }

const SIZE = 25
const KIND: Record<SupportReport['kind'], string> = { bug: 'Bug', question: 'Question', idea: 'Idea' }
const STATUS: Record<SupportReport['status'], string> = { saved: 'Saved', filed: 'Filed', failed: 'Not filed' }
const when = (iso: string) => new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })

/**
 * Help & feedback reports from every store, newest first. GitHub links appear once a report is filed; a GitHub
 * issue links back here with ?report=<id>#support-reports, which highlights that report.
 */
export default function SupportReports() {
  const [params] = useSearchParams()
  const focus = params.get('report')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page | null>(null)
  const [error, setError] = useState('')
  const load = useCallback((p: number) => api<Page>(`/api/admin/support-reports?page=${p}&size=${SIZE}`)
    .then(d => { setData(d); setError('') }).catch(e => setError((e as Error).message)), [])
  useEffect(() => { load(page) }, [load, page])
  useEffect(() => {
    if (focus && data) document.getElementById('support-reports')?.scrollIntoView({ block: 'start' })
  }, [focus, data])
  const pages = data ? Math.max(1, Math.ceil(data.total / SIZE)) : 1

  return (
    <section id="support-reports" className="support-reports" style={{ marginTop: 28 }}>
      <div className="page-head">
        <h2>Support reports{data ? ` (${data.total})` : ''}</h2>
        <button className="small secondary" onClick={() => load(page)}>Refresh</button>
      </div>
      <p className="muted small">Sent from Help &amp; feedback in the store app. Filed on GitHub without names, emails or store names.</p>
      {error && <p className="error">{error}</p>}
      <div className="table-wrap">
        <table className="grid">
          <thead><tr><th>Sent</th><th>From</th><th>Kind</th><th>Report</th><th>Status</th></tr></thead>
          <tbody>
            {data?.reports.map(r => (
              <tr key={r.id} className={r.id === focus ? 'highlight' : undefined}>
                <td className="num">{when(r.createdAt)}</td>
                <td>{r.reporterName}<div className="muted small">{r.reporterEmail}</div><div className="muted small">{r.store}</div></td>
                <td>{KIND[r.kind]}</td>
                <td>
                  <div className="description">{r.description}</div>
                  <details>
                    <summary className="muted small">{r.page || 'No page'} · {r.environment.viewport ?? '—'} · build {r.environment.build ?? '—'}</summary>
                    <pre>{[`Report ${r.id}`, `Store ${r.storeId}`, r.environment.userAgent, r.environment.language,
                      ...(r.environment.recentErrors ?? []).map(e => `Error: ${e}`)].filter(Boolean).join('\n')}</pre>
                  </details>
                </td>
                <td>
                  <span className={`status ${r.status}`}>{STATUS[r.status]}</span>
                  {r.githubIssueUrl && <div><a href={r.githubIssueUrl} target="_blank" rel="noreferrer">#{r.githubIssueNumber}</a></div>}
                  {r.status !== 'filed' && r.lastError && <div className="muted small">{r.lastError}</div>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {data && data.reports.length === 0 && <p className="empty">No reports yet.</p>}
      </div>
      {pages > 1 && (
        <div className="actions">
          <button className="small secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</button>
          <span className="muted small" style={{ alignSelf: 'center' }}>Page {page + 1} of {pages}</span>
          <button className="small secondary" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Older</button>
        </div>
      )}
    </section>
  )
}

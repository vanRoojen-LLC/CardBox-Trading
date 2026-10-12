/**
 * The CardBox mark, the same four-card bloom cardbox.club shows (CardBox `frontend/public/cardbox-mark.svg`).
 * One logo for both sites (Toby, 2026-10-12): Trading is told apart by the word beside it, not by a second mark.
 */
export function Mark({ size = 29 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="107 107 810 810" aria-hidden="true">
      <rect x="347" y="191" width="330" height="226" rx="64" fill="#4F86FF" transform="rotate(45 512 304)" />
      <rect x="555" y="399" width="330" height="226" rx="64" fill="#F5A640" transform="rotate(135 720 512)" />
      <rect x="347" y="607" width="330" height="226" rx="64" fill="#2F65DF" transform="rotate(225 512 720)" />
      <rect x="139" y="399" width="330" height="226" rx="64" fill="#82A7FF" transform="rotate(315 304 512)" />
    </svg>
  )
}

export function Wordmark() {
  return <span className="wordmark">CardBox <span>Trading</span></span>
}

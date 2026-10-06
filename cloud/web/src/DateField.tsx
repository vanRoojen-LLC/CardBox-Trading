import { useState } from 'react'

/**
 * A date that saves when you leave the field or press Enter, never per keystroke: typing a year into a date input
 * passes through dates like 0002-03-04 on the way. Escape puts the saved date back.
 */
export default function DateField({ value, label, disabled, onSave }: {
  value: string; label: string; disabled?: boolean; onSave: (day: string) => void
}) {
  const [draft, setDraft] = useState<string | null>(null)
  const shown = draft ?? value
  function commit() {
    setDraft(null)
    // Only a whole, plausible date that differs from the saved one is sent.
    if (draft && draft !== value && /^\d{4}-\d{2}-\d{2}$/.test(draft) && Number(draft.slice(0, 4)) >= 2000) onSave(draft)
  }
  return (
    <input type="date" aria-label={label} disabled={disabled} value={shown} min="2000-01-01"
      onChange={e => setDraft(e.target.value)} onBlur={commit}
      onKeyDown={e => {
        if (e.key === 'Enter') { e.preventDefault(); commit() }
        else if (e.key === 'Escape') setDraft(null)
      }} />
  )
}

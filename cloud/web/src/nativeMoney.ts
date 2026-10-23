/** Currency is a recorded fact. Unknown amounts never acquire a currency from the formatter. */
export function paymentMoney(value: number | string | null | undefined, currency?: string | null): string {
  if (value == null || value === '' || !Number.isFinite(Number(value))) return '—'
  if (!currency) return `${Number(value).toFixed(2)} (currency unrecorded)`
  try { return new Intl.NumberFormat(undefined, { style: 'currency', currency, currencyDisplay: 'code' }).format(Number(value)) }
  catch { return `${Number(value).toFixed(2)} (currency unrecorded)` }
}

/** Today's figures stay separate for each recorded currency, including the unknown group. */
export function paymentTotals(rows: { actual_payment_currency?: string | null; credit_total: number; check_total: number }[],
  key: 'credit_total' | 'check_total'): string[] {
  const totals = new Map<string, number>()
  for (const row of rows) {
    const currency = row.actual_payment_currency || ''
    totals.set(currency, (totals.get(currency) ?? 0) + Number(row[key] ?? 0))
  }
  return [...totals.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([currency, amount]) => paymentMoney(amount, currency))
}

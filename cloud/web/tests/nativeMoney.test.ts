import test from 'node:test'
import assert from 'node:assert/strict'
import { paymentMoney, paymentTotals, unrecordedMoneyNumber } from '../src/nativeMoney.ts'

test('unknown currency is explicit and never defaults to a dollar symbol', () => {
  assert.equal(paymentMoney('12.50',null),'12.50 (currency unrecorded)')
  assert.equal(paymentMoney(null,'EUR'),'—')
  assert.equal(unrecordedMoneyNumber('12.50'),'12.50')
  assert.match(paymentMoney('12.50','EUR'),/EUR/)
  assert.match(paymentMoney('12.50','EUR'),/12[.,]50/)
})
test('history totals never sum different currencies or unknown legacy amounts together', () => {
  const rows=[{actual_payment_currency:'EUR',credit_total:12.5,check_total:1},
    {actual_payment_currency:'USD',credit_total:20,check_total:2},
    {actual_payment_currency:'EUR',credit_total:1,check_total:3},
    {actual_payment_currency:null,credit_total:4,check_total:5}]
  const groups=paymentTotals(rows,'credit_total')
  assert.equal(groups.length,3)
  const text=groups.join(' · ')
  assert.match(text,/4.00 \(currency unrecorded\)/)
  assert.match(text,/EUR\s+13[.,]50/)
  assert.match(text,/USD\s+20[.,]00/)
  assert.doesNotMatch(text,/37[.,]50/)
})

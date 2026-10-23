# Prospective native-currency transaction facts

Issue CardBox3042 / REQ459. Independent Trading master base d10e97c; V35 is owned by PR81.
This stream claims V36 before writing any DDL. It preserves legacy null currency and immutable
source events. No current or historical buy-in is relabelled a retail sale; no FX is introduced.

The bounded contract captures currency only from an explicit store or transaction confirmation,
freezes it with the actual check/store-credit consideration, and keeps guide currency separately
attributable or unknown. There is no defaultUSD, country/symbol inference, historical rewrite or
retroactive assignment from today's store settings. Existing prices, allocations and quantity
semantics remain unchanged.

The producer's v1 unknown-currency payload remains readable and byte-identical. A compatible v2
contract will retain actual consideration and guide facts as separate monetary domains. Deployment
must first establish compatible receiver retention, then enable a producer revision after readback;
existing pending/receipt/ACK transport remains off until its reviewed activation gate. Work must
refresh from actual master after PR81 lands before touching its producer integration; never stack.

This branch implements prospective capture and a standalone monetary-domain contract against current
master. Its additive DDL follows the claimed number and reviewed contract. No paid calls, new credentials, live enabling,
production writes, retail checkout/refund implementation or arbitrary provider assumptions are in scope.

## Implemented capture and draft contract

`TradeController.SaveRequest` accepts optional `actualPaymentCurrency` and
`actualCurrencyConfirmed`. Missing code stays unknown; a supplied code needs an explicit true
confirmation and a recognized uppercase ISO monetary code. The authenticated user and server time
are frozen in the same `TradeService.save` transaction as the original totals, lines and stock.
Currency precision is checked without rounding or FX; the existing numeric(12,2) allocation/quote
engine is unchanged. V36 adds only nullable transaction metadata and an all-null-or-confirmed tuple
constraint; no migration sets a currency on any old row. API history/detail expose the frozen fields.

Checkout defaults blank and binds confirmation to the exact quote request and code. Any card,
quantity, payment/split or currency change requires a fresh confirmation. Native payment amounts and
unknown legacy amounts display separately; today's history totals group by currency (including the
unknown group) instead of combining different currencies. Guide currency remains unrecorded and is
never inferred from a payment confirmation or a dollar symbol. POS CSV columns/calculations remain
unchanged.

`NativeTransactionMoney.version2Domains` is a standalone draft, not a live emitter or an upgrader of
stored v1 events. It carries explicit actual consideration currency, state and confirmation separately
from guide currency/state. Amounts remain unsigned plain decimal strings at existing numeric(12,2)
precision; guide amounts may be null. Nested/numeric surrogate amounts are refused. Buy-in admission
remains held. The eventual producer must emit v2 only for new transactions, retain stable v1 IDs/hash/
payload bytes, and never create another event for a trade line that already has a v1 event. Receiver
and delivery coordinator must both accept the reviewed v2 envelope before producer emission is wired.

## Release and rollback boundaries

**Do not merge V36 before PR81/V35 actually lands master and this branch is normally refreshed.**
Applying a higher Flyway version first can cause the lower migration to be ignored. This independent
branch deliberately does not copy V35 or merge an unmerged producer branch. The present fixture can
validate V34→V36 in isolation, but release inventory must include V35 once it is applied. Producer v2
wiring and immutable-event compatibility proof follow actual master integration, not this draft.

A compatible forward rollback retains every then-applied Flyway migration and all confirmation/event/
receipt rows. It disables only new checkout currency capture, keeps native/unknown history formatting
and currency-separated totals, and allows the legacy save shape to insert null confirmation metadata.
Do not restore an arbitrary old whole UI that labels confirmed non-USD facts as dollars. No columns,
events or history are deleted and no recorded confirmation is recomputed from current settings.
No rollback image, transport activation or authenticated production acceptance is claimed here.

## Focused evidence

Eight Java21/Testcontainers PostgreSQL17 tests pass in the existing offline dsh dev runtime: explicit
EUR capture with unchanged allocations/authenticated confirmer, absent/invalid confirmation, tuple
constraint, failed-inventory transaction rollback, unchanged old quote/history/POS behavior, V34→V36
prior-row/old-insert compatibility, precision and standalone v2 domain rules. Web production build and
two native-format/grouped-total tests pass; the tests join the existing web CI job. Lint succeeds with
existing repository warnings. Rendered actual NewTrade/History checks used a synthetic fetch-only fixture outside the repository:
blank default, checked EUR confirmation, quantity-change invalidation, exact save payload, native and
unknown grouped totals, and desktop/mobile390/320 bounds passed. The first mobile pass exposed a
crowded legacy unknown label; it was fixed with a separate small label and compact per-currency totals,
then rechecked visually. Console had no errors/warnings. Evidence:
`/private/tmp/native-currency-history-mobile-fixed.png` and the local fixture screenshots. Browser plugin
unavailable; installed Playwright CLI used. Initial implementation Verify40037181112 and
Cloud40037181647 were green; the mobile correction requires renewed latest-head CI. No paid provider, new credential, production write or live enablement occurred.

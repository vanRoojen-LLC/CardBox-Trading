# Prospective transaction copy facts producer

Draft preparation for CardBox issue3042 / REQ459; Trading Flyway **V37** is claimed before DDL.
Baseline is actual master `d10e97c88319348b19834ec60c2fb20bd2c45ef2`. Master currently ends at V34;
open PR81 owns V35 and PR82 owns V36. Neither branch is a source dependency for this preparation.

New typed helpers validate prospective save-confirmed copy facts and construct the immutable
`completed_buyin_copy_facts` v1 companion settled with the catalog076 receiver. They reuse actual
`TradeService.PricedLine` and `CurrentUser` semantics. Unknowns stay unknown automatically for each
valid saved line; saved NM/normal defaults never establish physical condition or raw form. No money,
guide currency, model interpretation, retail sale or Club physical-copy/account mapping is invented.

The actual save/controller/schema/queue hooks remain open and held until PR81/82 are actually on
master. No V35/V36 code is copied and no DDL, transport activation or new credential is included here.
Pure-helper readiness does not complete the producer: actual-save atomicity, parent snapshot/hash,
server-owned actor/time/context, default-on capture, one-queue pending/ACK compatibility and full
receiver receipt validation must be integrated and tested after those dependencies land.

Catalog075 has since reached actual CardBox main; receiver076 has a normal refresh in CI. All histories and
every then-applied migration must be retained by a compatible forward rollback artifact; no history
deletion, down migration or arbitrary old-image rollback is claimed.

## Implemented preparation boundary

`TransactionCopyFacts` accepts optional typed input for an actual `TradeService.PricedLine` and guarded
`CurrentUser`. Missing/null input produces explicit unknowns without confirmation. Provided input has
exactly `context_sha256`, boolean `confirmed:true` and the complete typed `facts` tuple. Booleans,
numbers, prose and unknown fields are never coerced into grading, signature or condition facts.

The version-one context digest covers `context_version:1`, store tenant, actor user, line number,
Trading card UUID, quantity, saved condition, finish and language. A future quote adapter should expose
the server's digest; save must recheck it against the actual server-priced line and authenticated
actor/store. A selection cannot be transferred to another actor, store, card, quantity or line. Its
constructor is private, so the event builder only accepts selections produced by the validating
parser. This confirmation records transaction-source context, not a universal manual approval gate
for appraisals. Automatic unknown receipts require no additional click.

The typed tuple matches catalog076: nullable confirmed condition; form unknown/raw/graded; optional
grading company/label/certificate; signature unknown/signed/unsigned; serial state unknown/numbered/
unnumbered and optional number; known-value-variant unknown/present/absent. Known condition must equal
the actual saved condition. Grouped lines retain wholly unknown facts because one grade/serial cannot
be assigned to every physical unit. Unicode text limits count code points, matching the Python receiver.

`TransactionCopyFactEvents` checks the original v1 event envelope's exact fields, canonical SHA-256,
512KB bound, source, trade/line/card/store/quantity/condition/finish/language, original money domain and
complete exact/candidate/unresolved identity. It copies the original identity without re-resolving it.
The new companion has the settled deterministic source event ID and a 576KB bound; no monetary fields
are duplicated or reinterpreted. Check/credit and guides remain exclusively in the original v1 event,
including its legacy unknown currency. Native-currency v2 emission is still a separate held integration.

Capture and confirmation time use the original persisted database completion timestamp. This keeps
replay stable and avoids application-versus-database clock skew. The actor UUID comes from the guarded
save context. The helper cannot prove a historical actor or parent persistence from a supplied DTO:
the eventual save adapter must load the actual stored parent on its own transaction connection and
verify `trades.created_by` against that actor. There is no caller-supplied origin/actor/time/mapping
authority field and no Club physical-copy/account binding inferred from these store transactions.

The event retains immutable canonical JSON bytes and hash; returned JSON objects are detached copies.
Serialization recursively sorts object keys, retains array order, native Unicode and decimal strings,
and rejects floating-point JSON, NUL and invalid surrogate text. Exact golden payload/parent/context
fixtures match the Python contract, including Unicode, decimal strings, nulls and reordered keys.
The companion remains held as a buy-in; it cannot claim retail-sale admission, refund or correction.

## Validation and remaining integration

The bounded offline Maven/Java21 run passed **11 tests, zero failures/errors/skips**. It compiled the
actual current-master API/domain source plus only these new helpers/tests. The established dsh
development Docker host supplied its cached official Maven image and existing dependencies mounted
read-only; networking was disabled, memory/CPU bounded, and source/build output lived only in temporary
container memory. No runtime/provider credential, host installation or production call was involved.

Tests cover exact settled receiver payload/hash; automatic unknown NM/normal and grouped-line facts;
explicit raw and graded tuples; actor/store/line context isolation; strict boolean/number/unknown-field
rejection; quantity bounds; original monetary-domain and identity fences; more than500KB of complete
candidate identity; immutable detached output; UTF-8/code-point limits; and Python/Java context-recipe
drift control. This is genuine compiled helper/fixture proof, not actual save/transport acceptance.

After PR81/82 are actually merged, normal-refresh this branch from actual master and implement:

1. Strict optional save input and server quote-context output, reusing existing authentication and
   price-review behavior; no guide/price recalculation policy changes.
2. V37 only after that integration: immutable companion/receipt storage, default-on recording in the
   same actual save transaction as original event/header/lines/grouped stock, with rollback proof.
   No retrospective backfill or invented facts for old transactions.
3. Extend the existing parent-ID queue with original/facts pending flags and per-kind order indexes,
   rather than adding a competing queue/poller. Each validated durable ACK clears only its flag and
   deletes the queue row only when both are clear; legacy rows and original v1 clients keep working.
4. Extend existing protected pending/ACK routes and default-off coordinator with kind-specific bounds
   and exact event/hash/receipt-set validation. Original receiver receipts must commit before companion
   delivery. Prove failed ACK/batch rollback, retry after receiver commit and full receipt readback.
5. Preserve every then-applied migration and history in a compatible forward rollback artifact. Deploy
   and verify through the release manager before any separately reviewed transport activation.

No schema, current save/controller or queue file is changed by this preparation. No actual transaction
capture, physical-copy appraisal, retail/refund workflow or live delivery completion is claimed.
Import accuracy/throughput effects are unmeasured; this adds no model calls or pricing-policy change.

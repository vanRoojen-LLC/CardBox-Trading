# Completed buy-in evidence producer

Draft implementation for CardBox issue #3042 / REQ459. Source audit: Trading master
`d10e97c88319348b19834ec60c2fb20bd2c45ef2`. No production activation or catalog receiver is included.

## What exists

`TradeService.save` (`cloud/api/src/main/java/com/cardpricer/cloud/trade/TradeService.java:163`)
atomically writes the saved header, line allocations, and inventory receipts. It is a store buying
cards, paying by check, store credit or a split. `market_unit` and `valuation_unit` are calculated
prices; `credit_alloc` and `check_alloc` are actual per-line consideration. They must stay distinct.
The V1 trades/lines schema stores no currency, retail sale status, refund or reversal. Current store
configuration has no currency. New events therefore carry null currency and `unrecorded` currency
state; they cannot enter a USD market blend. No retail sale or refund is fabricated.

Club's inventory sync is current state, not transaction evidence (`cloud/CLUB_SYNC.md:13`). Its
versioned items can be replaced and inventory rebuilt. Catalog values and market summaries also flow
Club -> Trading, not back. `catalog_printing_links` allows multiple printings for one Trading card and
finish (V31:18). `club_cards.club_printing_id` is unique (V11:7), but preferred catalog matches can bypass
that stand-in (`ClubSync.java:477`). A transaction freezes exact/candidate/unresolved identity at save;
later catalog updates do not retroactively change it. Only a single compatible printing is exact.

## Producer and delivery boundary

Additive Flyway V35 is reserved by this PR before DDL. An immutable event is inserted for every saved
line inside the existing save transaction, by default. A stable server-derived event key identifies
origin + trade UUID + line number + schema version. Source identity is server-owned, never supplied by
a caller. The frozen body includes quantity, condition, finish, original Trading card ID, exact or
candidate Club IDs, separate check and credit totals, and separate guide/valuation amounts. It omits
customer, user, phone, check number and free text. A SHA256 covers the exact stored canonical body;
replaying an event key with different facts is an explicit conflict. No retrospective event backfill.

Outbound transport is off until the receiver is ready; recording remains on to avoid losing history.
`GET /api/partner/club-sync/transaction-events?limit=200` returns at most 200 pending events and their
payload digests, with no high-watermark cursor that could skip an uncommitted lower sequence. Repeated
reads return the same facts until ACK. `POST /transaction-events/ack` accepts at most 200 receipts:
`{event_id,payload_sha256,receiver_receipt_id}`. Every key/hash must match; any missing or conflicting
receipt rolls back the whole ACK batch (409). An exact replay is idempotent. ACK appends a delivery
receipt; it never mutates the event. The receiver must commit durable evidence before ACK. A lost ACK
only repeats delivery and is deduplicated by the receiver's stable source-event key.

Both routes reuse the existing `ClubSyncAuth` trusted M2M issuer/audience/signature/expiry/allowed-client
and `inventory:sync` checks. This reuses the scope only for this already trusted integration and grants
no user access. No new tenant resource, scope, provider, secret or credential is created. Transport has
its own default-off configuration; existing inventory/canonical catalog operations continue normally.

## Remaining integration and acceptance

The catalog receiver needs a distinct immutable typed first-party event receipt, keyed by source event,
with conflicting replay held. Store credit and cash consideration are not silently combined; a buy-in
is not labeled a retail realized sale and calculated guide values cannot feed back as actual sales.
No catalog migration number is reserved here. Refund/reversal events require an authoritative workflow
and explicit links to original events, and are not invented by this producer. Production activation,
first real event readback, and any valuation admission policy remain separate gates.

Tests cover transaction rollback, same/conflicting event replay, exact/candidate/unresolved identity,
precision and quantity, separate consideration dimensions, null currency, pending paging, all-or-nothing
ACK, repeat ACK, trusted M2M protection and disabled transport. No paid/provider calls are required.
Import accuracy/throughput effects are unmeasured; this slice adds no LLM calls.

Prior art: [Spring transaction boundaries](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
keep the event and existing save under one transaction. [Auth0 access-token validation](https://auth0.com/docs/secure/tokens/access-tokens/validate-access-tokens)
supports reuse of the current audience, scope and standard claim checks; existing allow-listed client
validation remains mandatory.

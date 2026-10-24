# Prospective transaction copy facts producer

Draft preparation for CardBox issue3042 / REQ459; Trading Flyway **V37** is claimed before DDL.
Baseline is actual master `d10e97c88319348b19834ec60c2fb20bd2c45ef2`. Master currently ends at V34;
open PR81 owns V35 and PR82 owns V36. Neither branch is a source dependency for this preparation.

New typed helpers will validate prospective save-confirmed copy facts and construct the immutable
`completed_buyin_copy_facts` v1 companion settled with the catalog076 receiver. They reuse actual
`TradeService.PricedLine` and `CurrentUser` semantics. Unknowns stay unknown automatically for each
valid saved line; saved NM/normal defaults never establish physical condition or raw form. No money,
guide currency, model interpretation, retail sale or Club physical-copy/account mapping is invented.

The actual save/controller/schema/queue hooks remain open and held until PR81/82 are actually on
master. No V35/V36 code is copied and no DDL, transport activation or new credential is included here.
Pure-helper readiness does not complete the producer: actual-save atomicity, parent snapshot/hash,
server-owned actor/time/context, default-on capture, one-queue pending/ACK compatibility and full
receiver receipt validation must be integrated and tested after those dependencies land.

Receiver076 remains a green frozen draft pending actual catalog075 integration. All histories and
every then-applied migration must be retained by a compatible forward rollback artifact; no history
deletion, down migration or arbitrary old-image rollback is claimed.

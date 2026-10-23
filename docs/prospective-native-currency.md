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

Initial work is pure contract/design/tests and prospective capture against current master. Migration
DDL follows the claimed number and reviewed contract. No paid calls, new credentials, live enabling,
production writes, retail checkout/refund implementation or arbitrary provider assumptions are in scope.

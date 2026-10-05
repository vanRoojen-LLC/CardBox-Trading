# Provisional receiving CSV contract

The audit proved a header/row mismatch. This implementation fixes that internal contract; no POS vendor/version or accepted external fixture was supplied. Confirm all modes and mappings with the actual POS before rollout.

UTF-8, RFC 4180 escaping, dot-decimal money, CRLF row endings. Every receiving row has 19 fields:

The receiving POS requires commas in card names to be replaced with the literal character `ɕ` (U+0255, decimal entity `&#597;`). A name containing commas alone is therefore exported without quotation marks. This is an export-only substitution; the saved trade and receipt retain the original name. Other characters requiring CSV escaping, such as literal quotes or newlines, still use the CSV encoder.

| Position | Field | Value |
|---|---|---|
| 1 | LINE NO | Sequential exported row number |
| 2 | DEPARTMENT | Existing value `5` |
| 3 | CATEGORY | Existing value `5.2` |
| 4 | TYPE | Empty |
| 5 | CODE | POS set alias, collector identifier and finish suffix, with set promos mapped to base foils |
| 6 | ITEM TYPE | Empty |
| 7 | ORDER NO | Empty |
| 8 | DESCRIPTION | Full name and finish |
| 9 | UOM | Empty |
| 10 | QTY ON ORD | Acquired quantity |
| 11 | RESTOCK LEVEL | Empty |
| 12 | REORDER POINT | Empty |
| 13 | QTY ON HAND | Empty |
| 14 | COST | Approved unit acquisition cost |
| 15 | DISCOUNT | Empty |
| 16 | BID | Empty |
| 17 | EXTENDED COST | Quantity times approved unit cost |
| 18 | TAX CODE | Existing value `TAX` |
| 19 | PRICE | Condition-adjusted valuation |

The sum of extended costs equals the settlement allocation for included stock lines. MISC remains in the settlement and receipt but has no stock row. When a line's exact acquisition cost is not divisible into identical cent-denominated unit costs, it produces two quantity rows whose combined cost and quantity reconcile exactly.

Provider identity stays unchanged internally. Existing COK/XED aliases map only at export. Trade exports map P-prefixed promos of recognized base sets to base-set foil stock: `PWAR 220s` → `WAR 220F`, `PEOE 210p` → `EOE 210F`. Numeric promo collector numbers may include `p`, `s`, or star markers, which are removed for this mapping. Promo and base-foil lines may share a code when their card names and languages match; unrelated identity collisions still fail. Original promo valuations and acquisition costs are preserved. Other finishes remain distinct (`F`, `E`, `S`), and ordinary sets such as PIP/PCY and PLST composite identifiers retain their existing mapping.

PLST composite identifiers omit the PLST prefix and replace hyphens with spaces: `PLST C17-149` → `C17 149`. A List reprint and its original printing may share that code when their card names, languages and finishes match. This exception does not allow two distinct provider identities within PLST or within the original set to share a code. Saved identities, valuations and acquisition costs remain separate.

Other supported schemas remain Import Utility (8 columns), Item Wizard (9 columns), and Item Wizard Change Qty (5 columns). The zero-quantity mode is the same for combined and individual exports. Inventory Change Qty is a snapshot, with blank old quantity and explicit new quantity; zero can reset stock.

Trade-to-inventory Change Qty output combines quantities for lines sharing an inventory code, including promo/base and List/original aliases, so repeated rows cannot overwrite part of the count. Receiving exports keep separate rows to preserve approved costs.

Unresolved external acceptance: actual tax/category meanings, condition representation, per-unit versus extended acquisition cost semantics, duplicate SKU rows for cent allocation, quantity update semantics and all finish/legacy aliases.

# OCC Card Pricer

Java 25 / Swing desktop card pricing, receiving, inventory and trade history.

The multi-store web version runs at **https://cardbox.trading**; see [cloud/README.md](cloud/README.md).

This repository lives at **https://github.com/vanRoojen-LLC/OCC_PRICER** (moved from `tvanroo/OCC_PRICER`; GitHub redirects the old URL). It is a fork of [NinjaPanda351/OCC_PRICER](https://github.com/NinjaPanda351/OCC_PRICER), which still publishes the desktop releases the update check reads. Point an existing clone at the new home with `git remote set-url origin https://github.com/vanRoojen-LLC/OCC_PRICER.git`.

## Appearance

The refreshed interface uses **OCC Midnight**: charcoal surfaces, subtle blue accents, a persistent sidebar, and clearer trade totals. The overview opens the existing trade, set pricing, inventory and history workflows.

The workspace adapts to window size. Navigation becomes a compact icon strip on narrower windows; the menu button expands it. Tables gain space as the window grows and scroll horizontally when their columns need more room. In short trade windows, totals become compact and **Customer details** opens the customer fields. Settings pages and toolbars scroll or wrap to keep actions reachable. Window size, position and maximized state are remembered and fitted to connected monitors.

Choose **Preferences → Appearance → OCC Midnight → Apply theme** to enable it. New installations use it by default; the previous default dark theme migrates once. Other saved theme choices remain available, and the status-bar sun/moon button switches between light and Midnight.

See the [overview preview](docs/ui/overview.png), [laptop trade layout](docs/ui/trade-laptop.png), [wide trade layout](docs/ui/trade-wide.png), and [UI notes](docs/UI_REFRESH.md). Previews render the actual Swing components with isolated sample data.

## Build and verify

Install JDK 25 and set `JAVA_HOME`. No IDE or vendored JAR setup is required.

```powershell
.\mvnw.cmd clean verify
.\build.ps1
```

On macOS use `bash ./mvnw clean verify` and `bash ./build.sh`. Maven Wrapper downloads the pinned Maven distribution and verifies its checksum. `pom.xml` is the version source; a release tag must match it. Suffix versions sort as prereleases, before the corresponding unsuffixed version.

Tests use in-memory preferences, temporary files and synthetic provider data. They do not contact Scryfall, a mail account, the workstation registry or a POS. Windows and macOS CI run verification and packaging before a release draft can be created. Actual macOS execution remains a release acceptance check until CI runs.

Packages stage only the shaded application JAR and third-party notices. Each packaging run has separate input/output directories under `target`; final archives go to `dist`. Tests inspect the shaded JAR and build scripts inspect the application payload. See [dependency notices](THIRD_PARTY_NOTICES.md).

## Trade behavior

The **Overview** homepage includes a standalone **Price check** area. Enter a set/collector code (including finish suffixes such as `TDM 3f` or `PLST ARB 1e`), or press **F2 / Ctrl+F** to use the same name-search dialog as trade entry. Entering a name and pressing **Enter** also opens name search. Selecting a card shows its NM market price, condition-adjusted store price, store-credit offer and check offer. Finish and condition selectors use the same pricing and buy-rate rules as Trades; unavailable finish prices display **N/A**. Price checking does not create or modify a trade.

- Each newly loaded homepage price-check card starts at NM. The previous card's condition and prices remain visible until the next result loads.
- In Trades, `misc` and `MTG misc` open the same manual name/price entry dialog. Vintage cards show art on hover; entering their codes no longer opens a persistent floating image. The high-value confirmation focuses **Add to Trade**, so Enter confirms it.
- The table owns typed rows with stable line IDs. Quantities, condition and prices feed the same quote and settlement paths. Sorting changes presentation only.
- Provider set/collector IDs, language, finishes and original names survive lookup and recovery. POS aliases are applied at the output boundary.
- Default offers remain 50% credit / 40% check. Configured tiers and bounties override these defaults.
- Prices use the existing rarity floor and rounding policy. NM preserves the adjusted base. Worn conditions round after multiplying the base, stay at or below that base, and preserve at least the smaller of the base and $0.10. The existing $9.50-to-$10 rounding exception remains.
- A manual price override belongs to the condition where it was entered. Changing condition uses the original base; returning to the overridden condition restores the override. Duplication preserves both.
- Split tender uses one proportion across the entire quote, including MISC. The check amount must equal the cent-rounded complement of the credit amount. Negative amounts, excess precision, overpayment and a zero/zero split for a payable trade are rejected.
- Each tender allocates residual cents by largest remainder, with row order as the tie-breaker. MISC acquisition costs stay with MISC. A stock quantity can produce two CSV rows when two different unit costs are necessary to preserve its exact extended cost.

## Storage and recovery

In **Files & history → History**, select a trade to read its receipt, check **Inventoried into POS**, or choose **Edit trade**. Use the POS status filter to find trades still needing inventory. The checkbox syncs across computers running this version and configured with the same **Preferences → Network → Shared Trades Folder**. It tracks completion; it does not send anything to the POS.

**Local Files** and **Shared Files** list CSV imports only. Receipts remain available through **History**, where **Copy file path** copies the CSV for the selected trade revision. If that CSV is unavailable, the action reports it without changing the clipboard.

Use **Delete trade** in History or **Delete** in either Files tab to remove a trade and all its revisions, CSVs and receipts across workstations. The app records the deletion before removing files, cancels queued exports, and retries offline deletions when the shared folder reconnects. Other computers remove their copies on sync. Keep the shared `.trade-deletions` folder: it prevents older copies and retry queues from restoring deleted trades. Deleting files directly in Windows does not record this intent, so a retry can recreate them. Update every workstation before using shared deletion.

POS checkbox changes save locally immediately and publish to the shared folder in the background. Computers check for shared changes every 15 seconds, and an open History screen refreshes automatically while keeping the selected trade and filters. **Refresh** or **Retry exports** requests a sync immediately. The label below the checkbox shows whether status is synced, local only, or awaiting network access. Offline changes survive restart and retry automatically when the share returns. Previously saved local checkmarks migrate into the shared history on first sync.

Status changes are stored as separate immutable JSON documents under `pos-inventory-status/` in the shared trades folder. A trade ID and revision link the checkbox to its exact saved trade, independent of the receipt filename. For old text-only receipts, the filename is the shared identity. A later change that has seen earlier changes supersedes them; equal-sequence conflicting changes resolve to unchecked so the trade can be reviewed. This ordering does not depend on synchronized computer clocks. Corrections to a newer trade revision always require a new POS review. Keep the status documents with the shared trade files when backing up or moving the share.

**Edit trade** opens a saved trade from any workstation configured with the same **Shared Trades Folder**, including customer information, cards, condition, quantities, prices and payment. Existing lines retain their saved buy rates; newly added lines use current rates. **Save changes** updates the same history entry, retains the original snapshot and files, and creates revised receipt/JSON/CSV files. Edited trades need POS review again, so their inventory checkbox clears. If the original CSV was already imported, reconcile the correction in the POS before importing revised quantities. **Cancel edit** leaves the saved trade unchanged, and interrupted edits can be recovered at startup.

All workstations must use this updated version and a shared network folder that supports file locks and atomic file replacement. Shared corrections require a connection to that folder. Multiple people may open a trade, but each save checks its original revision under a per-trade lock. If someone saved first, the later save is rejected and its draft stays open; refresh History and reopen the latest revision before applying the correction. A busy-save message indicates another workstation is currently saving. Disabling or changing the configured folder cannot bypass these checks for a trade already opened through shared editing. Separate cloud-synced copies of a folder are not a substitute for a shared filesystem.

**Revision history** shows the changes to customer/payment fields and card lines, with the saving workstation, operating-system account and time. Existing revisions have unknown authors; the account is descriptive metadata, not an authenticated staff identity. Shared revision documents retain every snapshot and the exact export contents in `.trade-revisions`. If a save is interrupted after the shared revision commits, History can display it immediately and **Retry exports** regenerates its missing shared files. Opening it also rebuilds its local ledger copy. Include this subfolder in shared-folder backups.

Receipts use labeled sections, numbered card details, per-card payouts and a separate totals section. Older text-only receipts open a text editor with an automatic backup of the original; those edits do not recalculate a POS export. Full editing on another workstation requires the structured trade JSON or a shared revision document.

Trade receiving CSVs replace commas in card names with `ɕ` for POS compatibility, so those names do not need comma-related quotation marks. Saved trades and receipts retain the original names.

Trade receiving and quantity exports map set promos to the base-set foil inventory code: for example, `PWAR 220s` becomes `WAR 220F`, and `PEOE 210p` becomes `EOE 210F`. This applies to P-prefixed versions of recognized base sets with numeric collector numbers and optional `p`, `s`, or star promo markers. Saved trades and receipts retain the original promo printing and approved valuation. Quantity exports combine promo/base aliases into one total per inventory code; receiving rows retain their individual quantities and acquisition costs.

Windows stores data under `%APPDATA%\OCC_Trade_Pricer`; other systems use `~/.occ_trade_pricer`. For isolated verification, `-Dcardpricer.dataDir=/temporary/path` overrides this location.

| Location | Purpose |
|---|---|
| `session/draft-v1.json` | Atomic recoverable draft with identity, base, overrides, customer fields and payment selection |
| `ledger/trades.sqlite` | Local approved snapshots, previous revisions, POS completion status, exact settlements, output jobs, legacy receipt attachments and sync jobs |
| `trades/YYYY-MM-DD_HH-mm-ss - Customer - Employee - <short ID>.*` | Matching receipt, structured JSON and stock CSV, named with the local date/time and saved names |
| `trades/YYYY-MM-DD_HH-mm-ss - Customer - Employee - <short ID>_rev<N>.*` | Revised outputs; History shows the current revision once |
| `config/buy_rates_local.json` | Revisioned local rates, with a previous-version backup |
| `cache/catalog.ndjson.gz` | Last usable catalog snapshot |
| `bulk-runs/<UUID>/` | One bulk generation, its files and manifest |

Edits debounce draft saves for two seconds; orderly exit flushes them. Approval commits the snapshot, exact allocation and output jobs together. A failed file write reports **saved, export pending**. Use **Retry exports** to retry saved jobs without another payment. A crash after approval cannot turn the committed draft into another payment request.

The SQLite ledger must stay on the workstation. The configured shared folder receives documents, never the live database. Copy jobs persist across restart, compare SHA-256 hashes and retain conflicts. Fix a conflicting destination and use **Retry exports** to retry it. Sync state appears above the trade action buttons. Local pricing continues when the share is unavailable.

In Preferences, **Review shared rates** compares saved local/shared rules and bounties, including unversioned legacy files. Choose which complete version to apply. Both originals are backed up, and any intervening edit requires another review. **Reload saved rates** refreshes a stale editor; neither action includes unsaved table edits.

If bounty import reports that the shared file has no revision or needs migration, finish **Save Bounties** on the computer with the desired list, then select **Review shared rates → Use this computer's version**. This upgrades the shared file and retains the previous local and shared files as `.previous` backups. On any other computer still showing a conflict, review both lists and choose **Use shared version** to accept the published list. The choice includes both rules and bounties; save or export any edits you want to keep before replacing them. Computers without pending edits catch up automatically, even after missing several shared updates. **Shared rates** shows current sync status; **Sync now** retries immediately, and **Reload saved rates** loads downloaded rates into the tables.

Entered identification fields remain in local drafts, approved snapshots and structured JSON. A configured shared folder also receives that JSON. The new printed receipt omits the identification field. An owner-approved retention/redaction policy is still required before rollout.

Legacy preference rules and bounties migrate before defaults are applied. Legacy data directories copy recursively with verified hashes and a resumable manifest; originals remain intact. Old TXT receipts are preserved as legacy attachments. Old autosaves did not retain enough information to reconstruct original printing/base values: recovered legacy lines are marked unverified and must be re-entered before approval.

Legacy catalogs sometimes collapsed different printings into the same code. The loader retains unambiguous entries and leaves conflicting codes for online lookup. Preferences shows the count; a successful catalog refresh replaces the legacy snapshot with exact provider identities. Both JSON arrays and JSONL are supported during bulk refresh.

Back up the application data folder with the application closed before upgrading. Restore a compatible backup when rolling back; an older binary must not write a newer ledger schema.

## Exports and operator checks

The receiving CSV uses the provisional 19-column header documented in [POS schema](docs/POS_SCHEMA.md). Inventory export is explicitly a **snapshot**: untouched priced variants emit zero quantities. Normal, foil and etched inventory quantities have separate columns. Bulk exports include priced variants only. Provider finish availability is retained even when its price is missing; such a variant needs a price before export.

PDF output preserves Unicode through glyph outlines and `ActualText`. It requires installed fonts covering the receipt characters and reports missing glyphs instead of replacing names. Saving and opening a PDF report separate outcomes.

Bug reports use a user-reviewed issue submission; no shared SMTP password is included. Previously distributed credentials still require provider-side revocation.

## Release status

This implementation has local deterministic verification and Windows package validation. It is **not yet cleared for production release**. See [implementation status and remaining acceptance work](audit/IMPLEMENTATION_STATUS.md), the original [repair plan](audit/REPAIR_PLAN.md), and [baseline architecture audit](audit/ARCHITECTURE_AUDIT.md).

## License

Copyright © 2026 vanRoojen LLC and the original OCC Card Pricer authors. This is not open-source software. The CardBox Trading web service in `cloud/` is source-available under the [PolyForm Shield License 1.0.0](cloud/LICENSE.md), which forbids using it to build a competing product; everything else is all rights reserved. See [LICENSE.md](LICENSE.md) for which terms cover which files.

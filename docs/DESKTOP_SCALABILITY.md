# Desktop performance and recovery

Implemented October 8, 2026 for the large-trade performance and recovery item in the [commercial product plan](COMMERCIAL_PRODUCT_PLAN.md).

## Behavior

- Quantity, condition and manual-price edits update cached row contributions. A buy-rate change invalidates all contributions; approved lines keep their saved rates and provider snapshots.
- The Swing thread captures detached row values and customer/payment fields. The save worker builds and encodes the immutable draft. Unchanged captures reuse their encoded lines.
- One save runs at a time, with one pending capture per draft and a maximum of 32 pending draft IDs. Repeated edits replace a pending capture. Save failures remain visible and block orderly exit until persistence succeeds.
- **Save for later** waits for durable saving, then starts a fresh trade. **Drafts** lists recoverable work. Opening another saved trade parks the current draft; reopening it resumes its retained changes. Each edited draft retains its original parent revision, so competing changes still fail the existing revision check.
- Approval removes only its matching recovery snapshot. Different local edits survive a competing/imported revision. Canceling or clearing the current draft does not delete parked drafts.
- History applies full-text, payment and inventory filters in SQL, selects the latest applicable revision, computes exact cent totals separately, and pages by date and record key. Metadata and previews for the rest of history are not loaded into the UI.

The ledger migrates from schema 5 to schema 6 transactionally. It adds draft edit-parent/save-time fields and an indexed history listing projection. Old `session/draft-v1.json` files move into the ledger after a successful save, with a retained `session/migrated-draft-<id>-<revision>.json` backup. Pre-typed legacy autosaves still require card verification before approval.

## Measurements

Synthetic benchmark on Windows 11, Java 25.0.1, with `-Xmx512m`. The trade contains **10,000 distinct lines**, not merely 10,000 copies of one card. History contains **100,000 receipt summaries**. Figures are elapsed wall time; p95 uses 50 edit/capture samples, 10 save/commit samples and 20 page/search samples. The first commit is listed separately.

| Operation | Result |
|---|---:|
| Quantity edit and Swing summary update, p95 | 0.30 ms |
| Capture detached editor state, p95 | 0.94 ms |
| First capture of all rows on the EDT | 7.64 ms |
| First encode and durable draft save | 343 ms |
| Subsequent durable draft save, p95 | 81 ms |
| First local approval/settlement/output-job commit | 575 ms |
| Local approval/settlement/output-job commit, p95 | 467 ms |
| First 100 history results, including aggregate query, p95 | 142 ms |
| Indexed search for one older receipt, p95 | 3.06 ms |

The normal two-second debounce precedes a background save. These results support the proposed desktop latency targets on this machine; they do not establish a supported maximum or measure slow/network storage. This run excludes the full card catalog, real receipt bodies, directory scanning, UI painting, external export delivery and POS acceptance. The shared-folder scan still enumerates files in the background; hosted incremental synchronization remains a separate phase. Draft opening/approval preparation can still require full line materialization. Full-catalog memory coexistence and Windows/macOS rendered UI testing remain release checks.

## Reproduce

Build with `mvnw.cmd -B verify`, then run the optional probe with Java 25 and a fresh isolated data directory. It uses synthetic data and is excluded from the packaged application:

```powershell
& 'C:\Program Files\Java\jdk-25\bin\java.exe' -Xmx512m --enable-native-access=ALL-UNNAMED `
  '-Djava.awt.headless=true' `
  '-Djava.util.prefs.PreferencesFactory=com.cardpricer.testsupport.MemoryPreferencesFactory' `
  '-Dcardpricer.dataDir=target/scalability-probe-data-fresh' `
  -cp 'target/test-classes;target/classes;target/OCC_Trade_Pricer.jar' `
  com.cardpricer.service.ScalabilityProbe target/scalability-probe.json
```

Regression checks cover incremental arithmetic and detached captures, bounded/coalesced writes and failures, multiple drafts, edited-trade switching, competing revisions, schema and single-file migrations, SQL paging/filtering, exact aggregates, lazy metadata/preview loading, shared history, and existing crash recovery.

## Card search measurements

The follow-up search work adds an immutable name index sharing provider records with the exact-code catalog. Compact two/three-character postings narrow candidates before matching and ranking; the code and name indexes publish together after a successful load or refresh. This uses the existing cached records without adding a second serialized card database.

F2 uses local results first. Plain names, optional set filters and exact collector codes resolve locally; missing matches, advanced Scryfall syntax and **Search online** use the provider. The dialog returns at most 50 printings and reports the matching count. Narrow the name or set to find additional printings. Surge and promo identifiers remain distinct, and explicit code finishes carry through selection.

Local name search and Trades code previews debounce for 125 ms; Enter bypasses the timer. Advanced online expressions retain a 400 ms typing debounce. Repeated requests share pending work; bounded recent-result caches expire after five minutes and include the catalog generation. Errors are not cached. Canceling or replacing a view prevents stale delivery without canceling a shared request belonging to another subscriber. Separate bounded local and online pools keep network waits out of local search workers. Scryfall request limits and backoff remain in force.

Measured October 8, 2026 against an isolated copy of the workstation's **106,830-printing downloaded catalog**, with Java 25.0.1 and `-Xmx512m`. The 9,400,009-byte cache had SHA-256 `cc22a542d5aee2e2f751061e7fdc1576e27c2a6695ff2393125013a0061dcd17`. This is the existing catalog snapshot, not a claim of complete current Scryfall coverage.

| Operation | Result |
|---|---:|
| Initial catalog load and both indexes | 1,161 ms |
| Reload with the old catalog still available | 976 ms |
| Indexed `Sol Ring` search, p95 | 0.073 ms |
| Indexed `ring` search, 1,835 matches, p95 | 0.410 ms |
| Indexed `an` search, 20,233 matches, p95 | 2.818 ms |
| Typing debounce, query and Swing list-model delivery, p95 | 128 ms |
| Loaded heap after GC | 125 MiB |
| Sampled peak heap across load/reload/search | 384 MiB |

Each indexed query has 100 samples; debounce/delivery has 40. Other tested queries were `lightning bolt`, `island`, `urzas`, `teferi`, `counterspell` and `black lotus`. Indexed-query measurements bypass the recent-result cache. Delivery includes the actual configured delay, asynchronous service/presenter callback and detached card list-model population. It does not measure window painting, live API latency or catalog/large-trade coexistence.

Run `CardSearchProbe` with a source catalog path and report path, using the same Java/headless flags and classpath as the earlier probe, plus a fresh isolated `cardpricer.dataDir`. It copies the source catalog into that directory and rejects network calls. The full verification after these changes passed **239 unit/workflow tests plus one packaging integration test**. Search regression cases include accents, ranking, limits, aliases, promo/surge identity, refresh invalidation, bounded caches, shared requests, blocked-network isolation, stale-view rejection, preview/Enter reuse, and correct set filtering after fuzzy fallback.

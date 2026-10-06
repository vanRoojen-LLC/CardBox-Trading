# Club collections in store inventory: handoff for cardbox.club

cardbox.trading is ready to receive a cardbox.club collection into a store's inventory. This is what Club builds so a
person with a store role can mark one of their collections "Sync to store", and every card in it shows up in
that store's Trading inventory and stays in step, with the spot it was scanned into, a photo and any extra detail.
It also covers re-inventory: scanning a box on Club to recount it on Trading (section 5b).

Trading's side is built and switched off (`CLUB_SYNC_ENABLED=false`) until Club is ready. Nothing below changes how
Club treats people, stores or roles: those stay Club's, as agreed for the shared roles work.

## The model in one paragraph

Club owns what is in a collection; Trading owns the store's storage spots, and a scan can say which spot a card went into. Club pushes the current state of
each card (not events) with a version number, from an outbox written in the same transaction as the card change.
Trading keeps the newest version of every card and rebuilds that collection's inventory lines after each delivery,
so repeats, retries and out-of-order deliveries are harmless. A full snapshot on first link and once a night removes
anything that drifted. Sync runs server to server with an Auth0 machine token, so nobody needs to be signed in.

## 1. Auth (done)

Toby set this up on 2026-10-05: the "CardBox Trading" API with `inventory:sync` exists, and Club's application
`WB4mbh9ky62gjPZHFOHBQCXhYytIie7K` is granted it and is on Trading's allow list. For the record, the setup was:

1. In the shared Auth0 tenant (`dev-tnnibhkgdbepzjy1`), create an API named "CardBox Trading", identifier
   `https://cardbox.trading/api`, signing RS256, with one permission: `inventory:sync`.
2. Authorize CardBox's existing machine-to-machine application (the one Club already uses for client credentials,
   `account_roles.py` around line 652) for that API with `inventory:sync`.
3. Send Trading that application's **client id** (not the secret). Trading only accepts tokens whose `azp` is on its
   allow list (`CLUB_SYNC_CLIENT_IDS`).

Club then gets a token with:

```
POST https://<auth0 domain>/oauth/token
{"grant_type": "client_credentials", "client_id": "...", "client_secret": "...", "audience": "https://cardbox.trading/api"}
```

Cache it until shortly before `expires_in` (24 hours by default): the free Auth0 plan limits machine tokens per month.
On a 401 from Trading, fetch a new token once and retry; a second 401 is a configuration problem, so stop and alert.

Every call below sends `Authorization: Bearer <token>` and `Content-Type: application/json`.

## 2. Who may link

- Only a signed-in Club account that holds `store_manager` or `store_employee` on a store (in `account_roles`) can
  turn sync on, and only to that store. Check this on Club when the toggle is used.
- Roles live on the parent account; any of its collections (the parent itself or a child collection user) can be
  linked. One collection syncs to one store at a time. Several collections can sync to the same store.
- If the person holds roles at several stores, they pick the store.

## 3. Club data changes

Add a migration module (next free version, 133 or later), appended to `Database._migration_plan()`:

```sql
CREATE SEQUENCE IF NOT EXISTS store_sync_version_seq;
ALTER TABLE cards ADD COLUMN IF NOT EXISTS sync_version BIGINT;

-- One row per collection synced to a store.
CREATE TABLE IF NOT EXISTS store_sync_links (
    collection_user_id TEXT PRIMARY KEY REFERENCES users(id),
    store_id           TEXT NOT NULL REFERENCES stores(id),
    linked_by_user_id  TEXT NOT NULL REFERENCES users(id),     -- the parent account that turned it on
    state              TEXT NOT NULL DEFAULT 'active',         -- active | paused | error
    paused_reason      TEXT,                                   -- role_revoked | collection_deleted
    last_error         TEXT,                                   -- shown on the collection screen
    needs_snapshot     BOOLEAN NOT NULL DEFAULT TRUE,
    last_snapshot_at   TEXT,
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL
);

-- Cards to send. Rows are hints: the worker always sends the card's state at send time.
CREATE TABLE IF NOT EXISTS store_sync_outbox (
    id                 BIGSERIAL PRIMARY KEY,
    collection_user_id TEXT NOT NULL,
    card_id            TEXT NOT NULL,
    version            BIGINT NOT NULL,                        -- nextval at the change; used for removals
    created_at         TEXT NOT NULL,
    attempts           INTEGER NOT NULL DEFAULT 0,
    next_attempt_at    TEXT
);
CREATE INDEX IF NOT EXISTS store_sync_outbox_due ON store_sync_outbox (collection_user_id, id);
```

Stamping versions and filling the outbox has to catch **every** write path (scan pairing in `service.py` around
11651, importer refinement around 211 and 735, edits, `batch_transfer.move_batch`, and the hard deletes around 1267,
13246 and 13260). The most reliable way is Postgres triggers, so no path can be missed:

- `BEFORE INSERT OR UPDATE ON cards`: `NEW.sync_version := nextval('store_sync_version_seq')`.
- `AFTER INSERT OR UPDATE ON cards`: if `NEW.owner_user_id` has an active link, insert an outbox row
  (`NEW.owner_user_id`, `NEW.id`, `NEW.sync_version`). If `OLD.owner_user_id` differs (moved between collections)
  and the old collection has an active link, insert a row for the old collection too, with a fresh `nextval`.
- `AFTER DELETE ON cards`: if `OLD.owner_user_id` has an active link, insert a row with a fresh `nextval`.
- Club's `inventory_items` (extra copies of a card) change that card's quantity, so an insert or delete there
  should touch the parent card (`UPDATE cards SET updated_at = ... WHERE id = ...`), which fires the above.

If triggers don't fit Club's conventions, put the same logic in one helper and call it from every path above, with a
test that fails when a new write path skips it.

## 4. What Club sends for a card

The worker reads the card as it is now, by `(collection_user_id, card_id)`:

- Card gone, moved out of this collection, or not settled yet (a job in flight on its pair, or
  `publication_status = 'withheld_review'`): send a **removal** with the outbox row's `version`. When it settles,
  its update stamps a newer version and it goes as an upsert.
- Otherwise send an **upsert** with the card's current `sync_version`:

| Field | From |
| --- | --- |
| `item_id` | `cards.id` |
| `version` | `cards.sync_version` |
| `game` | the card's segment, e.g. `magic-the-gathering` or `star-wars-unlimited`. Send every game |
| `club_printing_id` | Every game other than Magic: Club's printing id. Trading keeps its own card for each printing, named from `name`, `set_code` and `collector_number` as last sent. Without it the card is not matched. Magic still goes by `scryfall_id` so it prices from Trading's catalog |
| `treatment` | Every game other than Magic: the printing's treatment (`normal`, `foil`, `hyperspace`, `showcase`...), shown as the finish. Plain if left out |
| `scryfall_id` | Magic: `scryfall:id` in the printing's `external_ids_json` (`catalog_printing_id` → `catalog_card_printings`) |
| `finish` | Magic: `scryfall:finish` from the same JSON: `nonfoil`, `foil` or `etched` |
| `quantity` | 1 plus the card's extra copies in Club's `inventory_items` |
| `condition` | Leave out. Club has none; Trading uses the store's default for the collection. If Club adds one later: `NM`, `LP`, `MP`, `HP` or `DMG` |
| `name`, `set_code`, `collector_number` | from the printing and definition. For Magic, only for the store's "not matched" list; for other games, the card Trading shows |
| `storage_id` | Optional. The Trading storage spot the card was scanned into (an `id` from `GET /stores/{store_id}/storage`, section 5a). Leave out to use the spot the store picked for the whole collection. A spot that no longer exists falls back to that too. Once store staff put a card away on Trading, it stays in their spot through later deliveries until Club sends a different `storage_id` for it |
| `image_url` | Optional. An `https://` link (2000 characters at most) to Club's photo of this exact card. Store staff see it on the Inventory page. Anything else is ignored |
| `details` | Optional. A JSON object of 4000 characters at most with whatever Club knows beyond the catalog, e.g. `{"grade": "PSA 9", "serial": "12/250", "notes": "..."}`. Shown as label: value pairs. Anything else is ignored |
| `batch_id`, `batch_name` | Optional. The import batch the card came in with (`pairs.batch_id` and that batch's `name`): an upload or scan session. Store staff see, sort and filter inventory by it, and cards from different batches get their own lines. A resend at the same `version` updates only these two when they differ, so the nightly snapshot backfills cards sent before Club named batches and carries batch renames. Leave out for none; a resend without them keeps what Trading has |

Sealed product (`collection_sealed_items`) is out of scope.

## 5. Trading's API

Base: `https://cardbox.trading/api/partner/club-sync` (paths below are under it). JSON in snake_case. Errors are
`{"detail": "..."}`, like Club's own.

**Link (create, refresh, or resume a paused link)**

```
PUT /links/{collection_user_id}
{"store_id": "<stores.id>", "collection_name": "Box 12",
 "linked_by": {"account_id": "<users.id>", "auth0_sub": "<users.auth0_sub>", "email": "lee@example.com", "name": "Lee"}}

200 {"collection_id": "...", "collection_name": "Box 12", "store_id": "...", "state": "active", "paused_reason": null,
     "location": "Main", "storage_path": [{"label": "Box", "name": "12"}], "default_condition": "NM",
     "items": 0, "matched": 0, "not_matched": 0, "cards": 0, "last_synced_at": null}
404  Trading has no store for that CardBox store yet (a store manager must sign in to cardbox.trading once)
409  this collection already syncs to another store
```

Call it again whenever the collection is renamed. After a link or relink, send a snapshot.

**Get the link** (for the collection screen): `GET /links/{id}`, same body as above. 404 if not linked.

**Send changes** (at most 500 upserts plus removals per call)

```
POST /links/{id}/items
{"upserts": [{"item_id": "c1", "version": 1042, "game": "magic-the-gathering",
              "scryfall_id": "1e8d8b5c-...", "finish": "foil", "quantity": 1,
              "name": "Lightning Bolt", "set_code": "2x2", "collector_number": "117"}],
 "removals": [{"item_id": "c9", "version": 1043}]}

200 {"applied": 2, "skipped": 0, "not_matched": [{"item_id": "c7", "reason": "No Club printing id"}]}
```

`skipped` counts items Trading already had at that version or newer. That is normal after a retry.
One exception: an item Trading couldn't place before (it was in `not_matched`) is applied again at the same
version, so the next snapshot puts it in once Trading can (for example after a new game is supported).

**Snapshot** (on first link, on relink, nightly, and whenever `needs_snapshot` is set)

1. Read `as_of` with `SELECT last_value FROM store_sync_version_seq` **before** reading the cards.
2. `POST /links/{id}/snapshots {"as_of_version": as_of}` → `{"snapshot_id": "..."}`.
3. Send every current settled card in pages of up to 500:
   `POST /links/{id}/items {"snapshot_id": "...", "upserts": [...]}`.
4. `POST /links/{id}/snapshots/{snapshot_id}/complete {"item_count": <number of upserts sent>}`.
   Trading removes cards it has that the snapshot didn't include, but only those last written before the snapshot
   started and at or below `as_of`, so anything delivered while it ran (even a late retry) stays. If the count
   doesn't match what arrived, or the snapshot id is unknown or already completed, it answers 409 and removes
   nothing; start a new snapshot.

Normal outbox deliveries can keep flowing while a snapshot runs.

**Unlink** (the person turned sync off on Club)

```
POST /links/{id}/unlink {"cards": "keep"}   // or "remove"
200 {"cards": 37}
```

Ask the person first: "Leave these 37 cards in <store>'s inventory?" Keep makes them ordinary store stock on Trading;
remove takes them out. Then delete the Club link row and its outbox rows.

**Pause** (Club stopped it for a reason the person didn't choose)

```
POST /links/{id}/pause {"reason": "role_revoked"}   // or "collection_deleted"
```

The cards stay in the store's inventory and a store owner decides on Trading whether to keep or remove them.

## 5a. Store storage spots

Stores keep cards in a tree of spots per location (a case, a box, a row in the box). Club needs it to let the
person say where a scan is going.

```
GET /stores/{store_id}/storage
200 {"locations": [{"id": "...", "name": "Main",
                    "spots": [{"id": "s1", "parent_id": null, "label": "Case", "name": "A"},
                              {"id": "s2", "parent_id": "s1", "label": "Box", "name": "12"}]}]}
```

On the scan screen, when the collection syncs to a store, offer "Put into" with this tree (remember the last pick per
collection) and send the pick as `storage_id` on each card. Moving a card later on Club sends the new spot as an
ordinary upsert.

## 5b. Re-inventory (counting a box on Club)

A store recounts a location, or one spot and everything under it, on a schedule. Someone starts a count on Trading
(Inventory › Re-inventory); Trading copies what it expects there. People then type cards in on Trading or scan them
on Club, Trading shows the difference (missing, extra, moved, value change), and an owner accepts it, which updates
the store's stock.

Club's part is a scanning mode, separate from collections. Counted cards do not go into any collection.

```
GET /stores/{store_id}/counts
200 [{"count_id": "...", "location": "Main", "storage_path": [{"label": "Box", "name": "12"}],
      "started_at": "...", "started_by": "Lee"}]

POST /counts/{count_id}/items
{"store_id": "<stores.id>",
 "upserts": [{"item_id": "scan-881", "game": "magic-the-gathering", "scryfall_id": "...", "finish": "nonfoil",
              "quantity": 1, "storage_id": "s2", "image_url": "https://...", "details": {"grade": "PSA 9"}}]}
200 {"applied": 1, "not_matched": [{"item_id": "scan-882", "reason": "..."}]}
409 the count is closed (accepted or cancelled on Trading)
```

- Show "Count for <store>" only to people with `store_manager` or `store_employee` at that store, and check that
  role before every call; `store_id` must be the store you checked. Trading trusts Club on this.
- The person picks an open count, then scans. Send each scan with a stable `item_id` (one per physical scan).
  Sending the same `item_id` again replaces that line, so a correction or retry is safe. `version` is not used here.
- `storage_id` should be a spot inside the count's area; anything else counts at the area's top spot.
- `condition` is optional (`NM` if left out). Up to 500 items per call.
- On 409 tell the person the count was closed on Trading and return to the picker.

## 5c. Store renames

Trading ties its store to Club's by `stores.id` (`tenants.cardbox_store_id`) everywhere: links, items, storage,
counts and sign-in. The name is only shown. When a store is renamed on Club, tell Trading so its screens show the new
name straight away:

```
PUT /stores/{store_id}
{"name": "Gamers Guild"}
200 {"store_id": "...", "name": "Gamers Guild"}
404 no Trading store is tied to that CardBox store yet (it takes Club's name when it is)
```

Best effort: if it fails, Trading still picks the name up at the next sign-in of anyone at the store.

## 6. The delivery worker

- Per active link, take the oldest pending outbox rows (up to 500, coalesced to the newest row per card), send
  them as one `items` call, and delete the rows on 200. Links are independent; one stuck link must not block others.
- On a timeout, connection error, 429 or 5xx: keep the rows, back off 1 minute doubling to 1 hour. After 24 hours of
  failures, set `needs_snapshot` so the link heals once Trading answers again.
- On 401: new token, retry once.
- On 503: sync is switched off on Trading. Treat it like any 5xx (keep the rows, back off).
- On 404 from a link call with `detail` exactly "This collection is not linked to a store on cardbox.trading": the
  store ended the link on Trading. Any other 404 is not that. Set the Club link to `state = 'error'` with
  "The store stopped syncing this collection", turn the toggle off, and delete its outbox rows.
- On 409 "paused": stop sending for that link until it is linked again.
- On 400: a bug in the payload. Stop that link, store `detail` in `last_error`, alert.
- Nightly, run a snapshot for every active link.

## 7. Pausing when access ends

- When a `store_manager` or `store_employee` role is removed (the role-grant delete path in `account_roles.py`),
  pause every active link that person made to that store: Club link `state = 'paused'`, then
  `POST /pause {"reason": "role_revoked"}`. Trading also pauses such links the next time that person signs in to
  cardbox.trading, as a backstop.
- When a collection is deleted (`family.py`), pause its link with `collection_deleted` before the user row goes.

## 8. Collection screen (web and iOS)

- A "Sync to store" switch on each collection, shown only to someone with a store role. With roles at several
  stores, pick the store when switching on.
- While on, show what `GET /links/{id}` returns: "37 cards in Main › Box 12 at <store>, last updated <time>",
  plus "<n> cards couldn't go into the store's inventory" when `not_matched` is above zero (Trading's Inventory page
  lists which and why).
- Paused or error: show the reason and a way to link again (which resumes it and sends a snapshot).
- Switching off asks the keep-or-remove question above.

## 9. Tests worth having on Club

- Every card write path (scan, import, edit, move between collections, delete, extra copies) produces an outbox row
  for a linked collection and none for an unlinked one.
- Scans carry `storage_id`, `image_url` and `details` when the person picked a spot or Club has a photo.
- Re-inventory: only people with a store role at that store see the mode; resending an `item_id` replaces it.
- A worker run against a stand-in Trading: batching, coalescing, backoff, 401 refresh, 404 turning the link off.
- Snapshot: `as_of` read before the cards; a card changed during the snapshot is not removed.
- Removing a store role pauses that person's links to that store only.

## 10. Rollout

1. Trading deploys this with sync off (no visible change).
2. Auth0 setup in section 1 (done).
3. Club deploys its side with the worker off.
4. Turn both on for one test store, link a small collection, scan, edit and delete a card, unlink. Then everyone.

## What to report back to Trading

- Anything in this contract Club can't do as written (for example, if a card can belong to more than one collection,
  or if Club wants Trading to push anything back).
- When Club's side is deployed, so Trading turns `CLUB_SYNC_ENABLED` on.

package com.cardpricer.cloud.clubsync;

import com.cardpricer.cloud.inventory.InventoryRepository;
import com.cardpricer.cloud.web.ApiException;
import com.cardpricer.util.CardConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Club collections synced into store inventory. Club sends each card's latest state with its version; this keeps
 * the newest per card in club_link_items and, after every change, rebuilds the link's inventory lines from them,
 * so what the store sees always matches what Club last sent.
 */
@Service
public class ClubSync {
    /** One Club card as Club sees it now. {@code quantity} counts Club's extra copies of it too. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Upsert(String itemId, Long version, String game, String scryfallId, String finish, Integer quantity,
                         String condition, String name, String setCode, String collectorNumber,
                         String storageId, String imageUrl, JsonNode details, String clubPrintingId, String treatment) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Removal(String itemId, Long version) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LinkedBy(String accountId, String auth0Sub, String email, String name) {}

    /** What a scanned card turns into for Trading: a known card and finish, or the reason it can't be used. */
    public record Match(UUID cardId, String finish, String reason) {}

    public static final int MAX_BATCH = 500;
    private static final String MAGIC = "magic-the-gathering";
    private static final String NOT_IN_LIST = "Not in Trading's card list yet";

    private final JdbcTemplate jdbc;
    private final InventoryRepository inventory;

    public ClubSync(JdbcTemplate jdbc, InventoryRepository inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    // ---- Called by Club ----

    /** Creates the link, or refreshes its name and who linked it. Linking a paused collection again resumes it. */
    @Transactional
    public Map<String, Object> link(String collectionId, String storeId, String collectionName, LinkedBy by) {
        UUID tenant = tenantFor(storeId);
        String name = collectionName == null || collectionName.isBlank() ? "Collection" : collectionName.trim();
        var existing = find(collectionId);
        if (existing != null) {
            if (!tenant.equals(existing.get("tenant_id")))
                throw new ApiException(HttpStatus.CONFLICT, "This collection already syncs to another store. Unlink it there first.");
            jdbc.update("""
                    UPDATE club_links SET collection_name = ?, linked_by_sub = ?, linked_by_account = ?, linked_by_name = ?,
                        linked_by_email = ?, state = 'active', paused_reason = NULL, updated_at = now() WHERE id = ?""",
                    name, by.auth0Sub(), text(by.accountId()), text(by.name()), text(by.email()), existing.get("id"));
            return view(collectionId);
        }
        var location = jdbc.queryForList("""
                SELECT id FROM locations WHERE tenant_id = ? AND archived_at IS NULL ORDER BY created_at LIMIT 1""", UUID.class, tenant);
        if (location.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "This store has no open location to put cards in");
        jdbc.update("""
                INSERT INTO club_links (id, tenant_id, collection_id, collection_name, linked_by_sub, linked_by_account,
                                        linked_by_name, linked_by_email, location_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), tenant, collectionId, name, by.auth0Sub(), text(by.accountId()), text(by.name()),
                text(by.email()), location.getFirst());
        return view(collectionId);
    }

    public Map<String, Object> view(String collectionId) {
        var link = find(collectionId);
        if (link == null) throw notLinked();
        return view(link, true);
    }

    /** Applies upserts and removals, each only if newer than what is here, then rebuilds the link's lines. */
    @Transactional
    public Map<String, Object> apply(String collectionId, UUID snapshotId, List<Upsert> upserts, List<Removal> removals) {
        var link = active(collectionId);
        UUID linkId = (UUID) link.get("id");
        if (upserts.size() + removals.size() > MAX_BATCH) throw ApiException.badRequest("Send at most " + MAX_BATCH + " items per call");
        if (snapshotId != null) openSnapshot(linkId, snapshotId);
        Set<UUID> known = knownCards(upserts);
        int applied = 0, skipped = 0;
        List<Map<String, String>> notMatched = new ArrayList<>();
        for (Upsert u : upserts) {
            if (u.itemId() == null || u.itemId().isBlank() || u.version() == null)
                throw ApiException.badRequest("Every item needs item_id and version");
            if (u.quantity() != null && (u.quantity() < 1 || u.quantity() > 9999))
                throw ApiException.badRequest("quantity must be between 1 and 9999 (send a removal for none)");
            // A card Trading's list doesn't have yet keeps its id and goes in once the nightly import adds it.
            Match match = match(u);
            if (match.reason() != null || !known.contains(match.cardId()))
                notMatched.add(Map.of("item_id", u.itemId(), "reason", match.reason() != null ? match.reason() : NOT_IN_LIST));
            int changed = jdbc.update("""
                    INSERT INTO club_link_items (link_id, item_id, version, removed, card_id, finish, condition, quantity, game,
                                                 name, set_code, collector_number, unmatched_reason, storage_id, image_url, details)
                    VALUES (?, ?, ?, false, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (link_id, item_id) DO UPDATE SET version = EXCLUDED.version, removed = false,
                        card_id = EXCLUDED.card_id, finish = EXCLUDED.finish, condition = EXCLUDED.condition,
                        quantity = EXCLUDED.quantity, game = EXCLUDED.game, name = EXCLUDED.name, set_code = EXCLUDED.set_code,
                        collector_number = EXCLUDED.collector_number, unmatched_reason = EXCLUDED.unmatched_reason,
                        storage_id = EXCLUDED.storage_id, image_url = EXCLUDED.image_url, details = EXCLUDED.details, updated_at = now()
                    WHERE club_link_items.version < EXCLUDED.version""",
                    linkId, u.itemId(), u.version(), match.reason() == null ? match.cardId() : null,
                    match.finish() == null ? "normal" : match.finish(), condition(u.condition()),
                    u.quantity() == null ? 1 : u.quantity(), text(u.game()), text(u.name()), text(u.setCode()),
                    text(u.collectorNumber()), match.reason(), spotIn((UUID) link.get("tenant_id"), u.storageId()),
                    imageUrl(u.imageUrl()), details(u.details()));
            if (changed > 0) applied++; else skipped++;
            if (snapshotId != null)
                jdbc.update("UPDATE club_link_items SET snapshot_id = ? WHERE link_id = ? AND item_id = ?", snapshotId, linkId, u.itemId());
        }
        for (Removal r : removals) {
            if (r.itemId() == null || r.itemId().isBlank() || r.version() == null)
                throw ApiException.badRequest("Every removal needs item_id and version");
            int changed = jdbc.update("""
                    INSERT INTO club_link_items (link_id, item_id, version, removed) VALUES (?, ?, ?, true)
                    ON CONFLICT (link_id, item_id) DO UPDATE SET version = EXCLUDED.version, removed = true, updated_at = now()
                    WHERE club_link_items.version < EXCLUDED.version""", linkId, r.itemId(), r.version());
            if (changed > 0) applied++; else skipped++;
        }
        rebuild(linkId);
        return Map.of("applied", applied, "skipped", skipped, "not_matched", notMatched);
    }

    @Transactional
    public Map<String, Object> startSnapshot(String collectionId, long asOfVersion) {
        UUID linkId = (UUID) active(collectionId).get("id");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO club_link_snapshots (id, link_id, as_of_version) VALUES (?, ?, ?)", id, linkId, asOfVersion);
        return Map.of("snapshot_id", id);
    }

    /**
     * Ends a snapshot. If every item arrived, items it didn't include are removed if they were last written before it
     * started and at or below its version (a late delivery of an older version still counts as written during it); if the count is off, nothing is removed and Club sends a new snapshot.
     */
    @Transactional
    public Map<String, Object> completeSnapshot(String collectionId, UUID snapshotId, int itemCount) {
        UUID linkId = (UUID) active(collectionId).get("id");
        long asOf = openSnapshot(linkId, snapshotId);
        Integer received = jdbc.queryForObject("SELECT count(*) FROM club_link_items WHERE link_id = ? AND snapshot_id = ?",
                Integer.class, linkId, snapshotId);
        if (received == null || received != itemCount)
            throw new ApiException(HttpStatus.CONFLICT, "This snapshot received " + received + " of " + itemCount
                    + " items. Nothing was removed; send a new snapshot.");
        int removed = jdbc.update("""
                UPDATE club_link_items SET removed = true, updated_at = now()
                WHERE link_id = ? AND NOT removed AND snapshot_id IS DISTINCT FROM ? AND version <= ?
                  AND updated_at < (SELECT started_at FROM club_link_snapshots WHERE id = ?)""", linkId, snapshotId, asOf, snapshotId);
        jdbc.update("UPDATE club_link_snapshots SET completed_at = now() WHERE id = ?", snapshotId);
        rebuild(linkId);
        Map<String, Object> result = new LinkedHashMap<>(view(collectionId));
        result.put("removed", removed);
        return result;
    }

    /** The person unlinked the collection on Club: its cards stay as the store's own stock, or leave inventory. */
    @Transactional
    public Map<String, Object> unlink(String collectionId, boolean keep) {
        var link = find(collectionId);
        if (link == null) throw notLinked();
        return Map.of("cards", end((UUID) link.get("id"), keep));
    }

    /** Club paused the link (role ended, collection deleted). Its cards stay put until an owner decides. */
    @Transactional
    public Map<String, Object> pause(String collectionId, String reason) {
        var link = find(collectionId);
        if (link == null) throw notLinked();
        jdbc.update("UPDATE club_links SET state = 'paused', paused_reason = ?, updated_at = now() WHERE id = ?",
                reason, link.get("id"));
        return view(collectionId);
    }

    // ---- Called from the store app ----

    public List<Map<String, Object>> links(UUID tenant) {
        return jdbc.queryForList("SELECT * FROM club_links WHERE tenant_id = ? ORDER BY lower(collection_name), created_at", tenant)
                .stream().map(l -> view(l, false)).toList();
    }

    /** Moves a link's cards to another location or spot, and sets the condition used when Club sends none. */
    @Transactional
    public void retarget(UUID tenant, UUID linkId, UUID location, UUID storage, String defaultCondition) {
        owned(tenant, linkId);
        Integer open = jdbc.queryForObject("SELECT count(*) FROM locations WHERE id = ? AND tenant_id = ? AND archived_at IS NULL",
                Integer.class, location, tenant);
        if (open == null || open == 0) throw ApiException.badRequest("That location is closed or not part of this store");
        if (storage != null && !inventory.spot(tenant, storage).locationId().equals(location))
            throw ApiException.badRequest("That spot is in a different location");
        String condition = condition(defaultCondition);
        if (condition == null) throw ApiException.badRequest("Condition must be one of " + String.join(", ", CardConstants.CONDITIONS));
        jdbc.update("UPDATE club_links SET location_id = ?, storage_id = ?, default_condition = ?, updated_at = now() WHERE id = ?",
                location, storage, condition, linkId);
        rebuild(linkId);
    }

    /** A store owner ends a link. Club learns at its next delivery (404) and turns the collection's sync off. */
    @Transactional
    public int endByStore(UUID tenant, UUID linkId, boolean keep) {
        owned(tenant, linkId);
        return end(linkId, keep);
    }

    public List<Map<String, Object>> notMatched(UUID tenant, UUID linkId) {
        owned(tenant, linkId);
        return jdbc.queryForList("""
                SELECT i.item_id AS "itemId", i.name, i.set_code AS "set", i.collector_number AS number, i.game, i.quantity,
                       coalesce(i.unmatched_reason, ?) AS reason
                FROM club_link_items i LEFT JOIN inventory_cards c ON c.id = i.card_id
                WHERE i.link_id = ? AND NOT i.removed AND c.id IS NULL
                ORDER BY lower(i.name), i.set_code, i.collector_number LIMIT 200""", NOT_IN_LIST, linkId);
    }

    /** The Club scans behind one synced inventory line: each card's photo and detail. */
    public List<Map<String, Object>> scans(UUID tenant, UUID lineId) {
        var lines = jdbc.queryForList("SELECT * FROM inventory_items WHERE id = ? AND tenant_id = ?", lineId, tenant);
        if (lines.isEmpty()) throw ApiException.notFound("That card is no longer in inventory");
        var line = lines.getFirst();
        if (line.get("club_link_id") == null) return List.of();
        return jdbc.queryForList("""
                SELECT i.item_id AS "itemId", i.quantity, i.image_url AS image, i.details::text AS details
                FROM club_link_items i JOIN club_links l ON l.id = i.link_id
                LEFT JOIN storage_spots s ON s.id = i.storage_id AND s.tenant_id = l.tenant_id
                WHERE i.link_id = ? AND NOT i.removed AND i.card_id = ? AND i.finish = ?
                  AND coalesce(i.condition, l.default_condition) = ?
                  AND coalesce(s.location_id, l.location_id) = ? AND coalesce(s.id, l.storage_id) IS NOT DISTINCT FROM ?
                ORDER BY i.item_id LIMIT 200""",
                line.get("club_link_id"), line.get("card_id"), line.get("finish"), line.get("condition"),
                line.get("location_id"), line.get("storage_id"));
    }

    /** A CardBox store's open locations and storage spots, for Club to tag scans with. */
    public Map<String, Object> storage(String storeId) {
        UUID tenant = tenantFor(storeId);
        var spots = inventory.spots(tenant);
        List<Map<String, Object>> locations = new ArrayList<>();
        for (var l : jdbc.queryForList("SELECT id, name FROM locations WHERE tenant_id = ? AND archived_at IS NULL ORDER BY created_at", tenant)) {
            List<Map<String, Object>> tree = new ArrayList<>();
            for (var s : spots) {
                if (!s.locationId().equals(l.get("id"))) continue;
                Map<String, Object> spot = new LinkedHashMap<>();
                spot.put("id", s.id());
                spot.put("parent_id", s.parentId());
                spot.put("label", s.label());
                spot.put("name", s.name());
                tree.add(spot);
            }
            locations.add(Map.of("id", l.get("id"), "name", l.get("name"), "spots", tree));
        }
        return Map.of("locations", locations);
    }

    public UUID tenantFor(String storeId) {
        var tenants = jdbc.queryForList("SELECT id FROM tenants WHERE cardbox_store_id = ?", UUID.class, storeId);
        if (tenants.isEmpty())
            throw ApiException.notFound("cardbox.trading has no store for this CardBox store yet. A store manager signs in to cardbox.trading once to set it up.");
        return tenants.getFirst();
    }

    /** The name Trading shows for the store tied to a CardBox store. */
    public String storeName(String storeId) {
        return jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantFor(storeId));
    }

        /** At sign-in: links made by this person to stores where CardBox no longer gives them a role are paused. */
    public void pauseWithoutRole(String sub, Collection<UUID> storesWithRole) {
        jdbc.update("""
                UPDATE club_links SET state = 'paused', paused_reason = 'role_revoked', updated_at = now()
                WHERE linked_by_sub = ? AND state = 'active' AND NOT (tenant_id = ANY (?))""",
                sub, storesWithRole.toArray(new UUID[0]));
    }

    // ---- Inside ----

    /** Replaces the link's inventory lines with the sum of its matched, present items. */
    private void rebuild(UUID linkId) {
        jdbc.update("DELETE FROM inventory_items WHERE club_link_id = ?", linkId);
        jdbc.update("""
                INSERT INTO inventory_items (id, tenant_id, location_id, storage_id, card_id, name, set_code, collector_number,
                                             rarity, lang, finish, condition, quantity, club_link_id)
                SELECT gen_random_uuid(), l.tenant_id, coalesce(s.location_id, l.location_id), coalesce(s.id, l.storage_id),
                       c.id, c.name, c.set_code, c.collector_number,
                       c.rarity, c.lang, i.finish, coalesce(i.condition, l.default_condition), sum(i.quantity), l.id
                FROM club_link_items i JOIN club_links l ON l.id = i.link_id JOIN inventory_cards c ON c.id = i.card_id
                -- A card scanned into a spot sits there; otherwise at the collection's target.
                LEFT JOIN storage_spots s ON s.id = i.storage_id AND s.tenant_id = l.tenant_id
                WHERE i.link_id = ? AND NOT i.removed
                GROUP BY l.tenant_id, coalesce(s.location_id, l.location_id), coalesce(s.id, l.storage_id), l.id, c.id, c.name, c.set_code,
                         c.collector_number, c.rarity, c.lang, i.finish, coalesce(i.condition, l.default_condition)""",
                linkId);
        jdbc.update("UPDATE club_links SET last_synced_at = now() WHERE id = ?", linkId);
    }

    /** Ends a link: its lines become the store's own stock (merged with any matching line) or leave inventory. */
    private int end(UUID linkId, boolean keep) {
        Integer cards = jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM inventory_items WHERE club_link_id = ?",
                Integer.class, linkId);
        if (keep) {
            jdbc.update("""
                    INSERT INTO inventory_items (id, tenant_id, location_id, storage_id, card_id, name, set_code, collector_number,
                                                 rarity, lang, finish, condition, quantity)
                    SELECT gen_random_uuid(), tenant_id, location_id, storage_id, card_id, name, set_code, collector_number,
                           rarity, lang, finish, condition, quantity FROM inventory_items WHERE club_link_id = ?
                    ON CONFLICT (location_id, storage_id, card_id, finish, condition, club_link_id)
                    DO UPDATE SET quantity = inventory_items.quantity + EXCLUDED.quantity, updated_at = now()""", linkId);
        }
        jdbc.update("DELETE FROM inventory_items WHERE club_link_id = ?", linkId);
        jdbc.update("DELETE FROM club_links WHERE id = ?", linkId);
        return cards == null ? 0 : cards;
    }

    private Map<String, Object> view(Map<String, Object> link, boolean forClub) {
        UUID id = (UUID) link.get("id");
        var counts = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE NOT i.removed) AS items,
                       count(*) FILTER (WHERE NOT i.removed AND c.id IS NOT NULL) AS matched,
                       count(*) FILTER (WHERE NOT i.removed AND c.id IS NULL) AS not_matched,
                       coalesce(sum(i.quantity) FILTER (WHERE NOT i.removed AND c.id IS NOT NULL), 0) AS cards
                FROM club_link_items i LEFT JOIN inventory_cards c ON c.id = i.card_id WHERE i.link_id = ?""", id);
        String location = jdbc.queryForObject("SELECT name FROM locations WHERE id = ?", String.class, link.get("location_id"));
        UUID storage = (UUID) link.get("storage_id");
        var path = storage == null ? List.of()
                : InventoryRepository.paths(inventory.spots((UUID) link.get("tenant_id"))).getOrDefault(storage, List.of());
        Map<String, Object> view = new LinkedHashMap<>();
        if (forClub) {
            view.put("collection_id", link.get("collection_id"));
            view.put("collection_name", link.get("collection_name"));
            view.put("store_id", jdbc.queryForObject("SELECT cardbox_store_id FROM tenants WHERE id = ?", String.class, link.get("tenant_id")));
            view.put("state", link.get("state"));
            view.put("paused_reason", link.get("paused_reason"));
            view.put("location", location);
            view.put("storage_path", path);
            view.put("default_condition", link.get("default_condition"));
            view.put("items", counts.get("items"));
            view.put("matched", counts.get("matched"));
            view.put("not_matched", counts.get("not_matched"));
            view.put("cards", counts.get("cards"));
            view.put("last_synced_at", link.get("last_synced_at"));
        } else {
            view.put("id", id);
            view.put("collectionName", link.get("collection_name"));
            view.put("linkedBy", link.get("linked_by_name"));
            view.put("linkedByEmail", link.get("linked_by_email"));
            view.put("state", link.get("state"));
            view.put("pausedReason", link.get("paused_reason"));
            view.put("locationId", link.get("location_id"));
            view.put("location", location);
            view.put("storageId", storage);
            view.put("path", path);
            view.put("defaultCondition", link.get("default_condition"));
            view.put("items", counts.get("items"));
            view.put("notMatched", counts.get("not_matched"));
            view.put("cards", counts.get("cards"));
            view.put("lastSyncedAt", link.get("last_synced_at"));
        }
        return view;
    }

    private Map<String, Object> find(String collectionId) {
        var rows = jdbc.queryForList("SELECT * FROM club_links WHERE collection_id = ? FOR UPDATE", collectionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The link, locked so one link's deliveries apply one at a time; refused while paused. */
    private Map<String, Object> active(String collectionId) {
        var link = find(collectionId);
        if (link == null) throw notLinked();
        if (!"active".equals(link.get("state")))
            throw new ApiException(HttpStatus.CONFLICT, "Sync is paused for this collection (" + link.get("paused_reason")
                    + "). Link it again to resume.");
        return link;
    }

    private void owned(UUID tenant, UUID linkId) {
        var rows = jdbc.queryForList("SELECT id FROM club_links WHERE id = ? AND tenant_id = ? FOR UPDATE", UUID.class, linkId, tenant);
        if (rows.isEmpty()) throw ApiException.notFound("That collection is no longer linked to this store");
    }

    /** The snapshot's starting version, if it is this link's and still open. */
    private long openSnapshot(UUID linkId, UUID snapshotId) {
        var rows = jdbc.queryForList("SELECT as_of_version FROM club_link_snapshots WHERE id = ? AND link_id = ? AND completed_at IS NULL",
                Long.class, snapshotId, linkId);
        // Not 404: to Club that means the link itself is gone.
        if (rows.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "No open snapshot with that id for this collection. Start a new snapshot.");
        return rows.getFirst();
    }

    /**
     * Magic goes in by its Scryfall id, so it prices from Trading's catalog. Any other game goes in by Club's printing
     * id, as Club describes it (see {@link #knownCards}). Whether Trading has a Magic card yet is checked apart.
     */
    public static Match match(Upsert u) {
        if (!MAGIC.equals(u.game())) {
            String printing = text(u.clubPrintingId()).trim();
            String reason = printing.isEmpty() ? "No Club printing id"
                    : printing.length() > 200 ? "Club printing id too long"
                    : text(u.name()).isBlank() ? "No card name"
                    : null;
            return new Match(reason == null ? clubCardId(printing) : null, treatment(u.treatment()), reason);
        }
        String finish = finish(u.finish());
        UUID card = parse(u.scryfallId());
        String reason = card == null ? "No Scryfall id"
                : finish == null ? "Unknown finish " + u.finish()
                : null;
        return new Match(card, finish, reason);
    }

    /** The same Club printing is always the same card here. */
    static UUID clubCardId(String clubPrintingId) {
        return UUID.nameUUIDFromBytes(("cardbox.club printing " + clubPrintingId).getBytes(StandardCharsets.UTF_8));
    }

    /** Club's treatment (normal, foil, hyperspace, showcase...) as a finish; plain if none. */
    static String treatment(String treatment) {
        if (treatment == null || treatment.isBlank()) return "normal";
        String t = treatment.trim().toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
        if (t.isEmpty()) return "normal";
        return t.equals("nonfoil") || t.equals("standard") ? "normal" : t.substring(0, Math.min(40, t.length()));
    }

    /** The spot, if it is one of this store's; anything else counts as no spot. */
    public UUID spotIn(UUID tenant, String id) {
        UUID spot = parse(id);
        if (spot == null) return null;
        Integer found = jdbc.queryForObject("SELECT count(*) FROM storage_spots WHERE id = ? AND tenant_id = ?", Integer.class, spot, tenant);
        return found != null && found > 0 ? spot : null;
    }

    /** Only https links are shown as images. */
    public static String imageUrl(String url) {
        return url != null && url.startsWith("https://") && url.length() <= 2000 ? url : null;
    }

    /** Free-form detail (grading, serial number, notes) as Club sent it, if it is a JSON object of modest size. */
    public static String details(JsonNode details) {
        return details != null && details.isObject() && details.toString().length() <= 4000 ? details.toString() : null;
    }

    /**
     * The cards among these that inventory can hold. Cards from other games are added to Club's catalog here first
     * (name, set and number as Club last sent them); Magic cards must already be in Trading's.
     */
    public Set<UUID> knownCards(List<Upsert> upserts) {
        Set<UUID> ids = new HashSet<>();
        for (Upsert u : upserts) {
            Match m = match(u);
            if (m.reason() != null) continue;
            ids.add(m.cardId());
            if (!MAGIC.equals(u.game()))
                jdbc.update("""
                        INSERT INTO club_cards (id, club_printing_id, game, name, set_code, collector_number) VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET game = EXCLUDED.game, name = EXCLUDED.name, set_code = EXCLUDED.set_code,
                            collector_number = EXCLUDED.collector_number, updated_at = now()
                        WHERE (club_cards.game, club_cards.name, club_cards.set_code, club_cards.collector_number)
                            IS DISTINCT FROM (EXCLUDED.game, EXCLUDED.name, EXCLUDED.set_code, EXCLUDED.collector_number)""",
                        m.cardId(), u.clubPrintingId().trim(), text(u.game()), clip(u.name()), clip(u.setCode()), clip(u.collectorNumber()));
        }
        if (ids.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.queryForList("SELECT id FROM inventory_cards WHERE id = ANY (?)", UUID.class,
                (Object) ids.toArray(UUID[]::new)));
    }

    private static String clip(String value) {
        String v = text(value).trim();
        return v.substring(0, Math.min(200, v.length()));
    }

    private static ApiException notLinked() {
        return ApiException.notFound("This collection is not linked to a store on cardbox.trading");
    }

    /** Scryfall's finish names (nonfoil, foil, etched) or Trading's; null if neither. */
    static String finish(String finish) {
        if (finish == null || finish.isBlank()) return "normal";
        return switch (finish.trim().toLowerCase()) {
            case "nonfoil", "normal" -> "normal";
            case "foil" -> "foil";
            case "etched" -> "etched";
            default -> null;
        };
    }

    /** A condition Trading knows, or null (the link's default applies). */
    public static String condition(String condition) {
        if (condition == null) return null;
        String c = condition.trim().toUpperCase();
        return Arrays.asList(CardConstants.CONDITIONS).contains(c) ? c : null;
    }

    static UUID parse(String id) {
        if (id == null) return null;
        try {
            return UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(String s) { return s == null ? "" : s.trim(); }
}

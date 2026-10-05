package com.cardpricer.cloud.inventory;

import com.cardpricer.cloud.clubsync.ClubSync;
import com.cardpricer.cloud.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Re-inventory: recount a location, or a spot and everything under it, compare with what the store expected there,
 * and apply the difference. Counted cards come from typing on Trading or scanning on cardbox.club.
 */
@Service
public class InventoryCounts {
    private static final UUID NO_SPOT = new UUID(0, 0);

    private final JdbcTemplate jdbc;
    private final InventoryRepository inventory;
    private final ClubSync clubSync;

    public InventoryCounts(JdbcTemplate jdbc, InventoryRepository inventory, ClubSync clubSync) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.clubSync = clubSync;
    }

    /** Starts a count and copies what the store has there now as the expected stock. */
    @Transactional
    public UUID start(UUID tenant, UUID user, UUID location, UUID storage) {
        Integer open = jdbc.queryForObject("SELECT count(*) FROM locations WHERE id = ? AND tenant_id = ? AND archived_at IS NULL",
                Integer.class, location, tenant);
        if (open == null || open == 0) throw ApiException.badRequest("That location is closed or not part of this store");
        if (storage != null && !inventory.spot(tenant, storage).locationId().equals(location))
            throw ApiException.badRequest("That spot is in a different location");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_counts (id, tenant_id, location_id, storage_id, started_by) VALUES (?, ?, ?, ?, ?)",
                id, tenant, location, storage, user);
        jdbc.update("""
                INSERT INTO inventory_count_expected (count_id, storage_id, card_id, finish, condition, quantity, club_link_id)
                SELECT ?, storage_id, card_id, finish, condition, quantity, club_link_id FROM inventory_items
                WHERE tenant_id = ? AND location_id = ? AND\s""" + scope(storage), args(id, tenant, location, storage));
        return id;
    }

    public List<Map<String, Object>> list(UUID tenant) {
        var paths = InventoryRepository.paths(inventory.spots(tenant));
        return jdbc.queryForList("""
                SELECT c.id, c.state, c.location_id AS "locationId", l.name AS location, c.storage_id AS "storageId",
                       c.started_at AS "startedAt", u.name AS "startedBy", c.closed_at AS "closedAt",
                       (SELECT coalesce(sum(quantity), 0) FROM inventory_count_lines WHERE count_id = c.id) AS counted
                FROM inventory_counts c JOIN locations l ON l.id = c.location_id JOIN users u ON u.id = c.started_by
                WHERE c.tenant_id = ? ORDER BY c.state = 'open' DESC, c.started_at DESC LIMIT 50""", tenant)
                .stream().map(row -> withPath(row, paths)).toList();
    }

    /**
     * The count with its delta report: one row per spot, card, finish and condition with what was expected, what
     * was counted and the difference. Cards synced from a Club collection are counted but never changed here.
     */
    public Map<String, Object> report(UUID tenant, UUID id) {
        var count = count(tenant, id, false);
        var paths = InventoryRepository.paths(inventory.spots(tenant));
        var rows = jdbc.queryForList("""
                WITH e AS (SELECT coalesce(storage_id, ?) AS spot, card_id, finish, condition,
                                  coalesce(sum(quantity) FILTER (WHERE club_link_id IS NULL), 0) AS store,
                                  coalesce(sum(quantity) FILTER (WHERE club_link_id IS NOT NULL), 0) AS synced
                           FROM inventory_count_expected WHERE count_id = ? GROUP BY 1, 2, 3, 4),
                     n AS (SELECT coalesce(storage_id, ?) AS spot, card_id, finish, condition, sum(quantity) AS counted
                           FROM inventory_count_lines WHERE count_id = ? GROUP BY 1, 2, 3, 4)
                SELECT nullif(coalesce(e.spot, n.spot), ?) AS "storageId", coalesce(e.card_id, n.card_id) AS "cardId",
                       coalesce(e.finish, n.finish) AS finish, coalesce(e.condition, n.condition) AS condition,
                       coalesce(e.store, 0) + coalesce(e.synced, 0) AS expected, coalesce(e.synced, 0) AS synced,
                       coalesce(n.counted, 0) AS counted, c.name, c.set_code AS "set", c.collector_number AS number,
                       CASE coalesce(e.finish, n.finish) WHEN 'foil' THEN c.usd_foil WHEN 'etched' THEN c.usd_etched ELSE c.usd END AS market
                FROM e FULL JOIN n ON e.spot = n.spot AND e.card_id = n.card_id AND e.finish = n.finish AND e.condition = n.condition
                LEFT JOIN inventory_cards c ON c.id = coalesce(e.card_id, n.card_id)
                ORDER BY lower(c.name), c.set_code, c.collector_number""", NO_SPOT, id, NO_SPOT, id, NO_SPOT);
        // A card short in one spot and over in another, with the same total, was moved rather than lost.
        Map<String, Integer> net = new HashMap<>();
        for (var r : rows) net.merge(cardKey(r), num(r.get("counted")) - num(r.get("expected")), Integer::sum);
        List<Map<String, Object>> report = new ArrayList<>();
        int missing = 0, extra = 0;
        double valueChange = 0;
        for (var r : rows) {
            Map<String, Object> row = withPath(r, paths);
            int delta = num(r.get("counted")) - num(r.get("expected"));
            String status = delta == 0 ? "ok" : net.get(cardKey(r)) == 0 ? "moved" : delta < 0 ? "missing" : "extra";
            row.put("delta", delta);
            row.put("status", status);
            report.add(row);
            if (delta < 0 && !"moved".equals(status)) missing -= delta;
            if (delta > 0 && !"moved".equals(status)) extra += delta;
            if (r.get("market") instanceof Number market) valueChange += delta * market.doubleValue();
        }
        var lines = jdbc.queryForList("""
                SELECT l.id, l.storage_id AS "storageId", l.card_id AS "cardId", c.name, c.set_code AS "set",
                       c.collector_number AS number, l.finish, l.condition, l.quantity, l.source, l.image_url AS image, l.added_at AS "addedAt"
                FROM inventory_count_lines l LEFT JOIN inventory_cards c ON c.id = l.card_id
                WHERE l.count_id = ? ORDER BY l.added_at DESC LIMIT 500""", id).stream().map(r -> withPath(r, paths)).toList();
        Map<String, Object> result = new LinkedHashMap<>(withPath(count, paths));
        result.put("rows", report);
        result.put("lines", lines);
        result.put("missing", missing);
        result.put("extra", extra);
        result.put("valueChange", Math.round(valueChange * 100) / 100.0);
        return result;
    }

    /** Typed on Trading: some copies of a card, counted in a spot inside the count (default: the counted spot). */
    @Transactional
    public void addTyped(UUID tenant, UUID user, UUID id, UUID card, String finish, String condition, int quantity, UUID storage) {
        var count = count(tenant, id, true);
        if (quantity < 1 || quantity > 9999) throw ApiException.badRequest("Count between 1 and 9999");
        String f = finish == null ? "normal" : finish;
        if (!List.of("normal", "foil", "etched").contains(f)) throw ApiException.badRequest("Finish must be normal, foil or etched");
        String c = condition == null ? "NM" : ClubSync.condition(condition);
        if (c == null) throw ApiException.badRequest("Unknown condition");
        Integer known = jdbc.queryForObject("SELECT count(*) FROM inventory_cards WHERE id = ?", Integer.class, card);
        if (known == null || known == 0) throw ApiException.badRequest("Unknown card");
        jdbc.update("""
                INSERT INTO inventory_count_lines (id, count_id, storage_id, card_id, finish, condition, quantity, source, added_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'typed', ?)""",
                UUID.randomUUID(), id, inScope(tenant, count, storage), card, f, c, quantity, user);
    }

    @Transactional
    public void removeLine(UUID tenant, UUID id, UUID line) {
        count(tenant, id, true);
        jdbc.update("DELETE FROM inventory_count_lines WHERE id = ? AND count_id = ?", line, id);
    }

    /**
     * Scanned on cardbox.club into an open count of this CardBox store. A card sent again replaces its earlier line,
     * so Club can retry freely. Returns what was applied and what couldn't be used.
     */
    @Transactional
    public Map<String, Object> addScanned(String storeId, UUID id, List<ClubSync.Upsert> upserts) {
        UUID tenant = clubSync.tenantFor(storeId);
        var count = count(tenant, id, true);
        if (upserts.size() > ClubSync.MAX_BATCH) throw ApiException.badRequest("Send at most " + ClubSync.MAX_BATCH + " items per call");
        Set<UUID> known = clubSync.knownCards(upserts);
        int applied = 0;
        List<Map<String, String>> notMatched = new ArrayList<>();
        for (var u : upserts) {
            if (u.itemId() == null || u.itemId().isBlank()) throw ApiException.badRequest("Every item needs item_id");
            var match = ClubSync.match(u);
            if (match.reason() != null || !known.contains(match.cardId())) {
                notMatched.add(Map.of("item_id", u.itemId(), "reason", match.reason() != null ? match.reason() : "Not in Trading's card list yet"));
                continue;
            }
            int quantity = u.quantity() == null ? 1 : Math.max(1, Math.min(9999, u.quantity()));
            String condition = u.condition() == null ? null : ClubSync.condition(u.condition());
            UUID spot = inScope(tenant, count, clubSync.spotIn(tenant, u.storageId()));
            jdbc.update("""
                    INSERT INTO inventory_count_lines (id, count_id, storage_id, card_id, finish, condition, quantity, source,
                                                       club_item_id, image_url, details)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'club', ?, ?, ?::jsonb)
                    ON CONFLICT (count_id, club_item_id) DO UPDATE SET storage_id = EXCLUDED.storage_id, card_id = EXCLUDED.card_id,
                        finish = EXCLUDED.finish, condition = EXCLUDED.condition, quantity = EXCLUDED.quantity,
                        image_url = EXCLUDED.image_url, details = EXCLUDED.details, added_at = now()""",
                    UUID.randomUUID(), id, spot, match.cardId(), match.finish(), condition == null ? "NM" : condition, quantity,
                    u.itemId(), ClubSync.imageUrl(u.imageUrl()), ClubSync.details(u.details()));
            applied++;
        }
        return Map.of("applied", applied, "not_matched", notMatched);
    }

    /** Open counts of a CardBox store, for Club's re-inventory picker. */
    public List<Map<String, Object>> openForClub(String storeId) {
        UUID tenant = clubSync.tenantFor(storeId);
        var paths = InventoryRepository.paths(inventory.spots(tenant));
        return jdbc.queryForList("""
                SELECT c.id, l.name AS location, c.storage_id, c.started_at, u.name AS started_by
                FROM inventory_counts c JOIN locations l ON l.id = c.location_id JOIN users u ON u.id = c.started_by
                WHERE c.tenant_id = ? AND c.state = 'open' ORDER BY c.started_at DESC""", tenant).stream().map(row -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("count_id", row.get("id"));
            view.put("location", row.get("location"));
            UUID storage = (UUID) row.get("storage_id");
            view.put("storage_path", storage == null ? List.of() : paths.getOrDefault(storage, List.of()));
            view.put("started_at", row.get("started_at"));
            view.put("started_by", row.get("started_by"));
            return view;
        }).toList();
    }

    /**
     * Accepts the count: each spot, card, finish and condition's store-owned stock changes by counted minus expected,
     * leaving out what syncs from Club. Applying the difference (not the counted number) keeps stock that came or
     * went while the count ran. Every change is logged.
     */
    @Transactional
    public void reconcile(UUID tenant, UUID user, UUID id) {
        var count = count(tenant, id, true);
        UUID location = (UUID) count.get("locationId");
        for (var r : (List<Map<String, Object>>) report(tenant, id).get("rows")) {
            int synced = num(r.get("synced"));
            int storeExpected = num(r.get("expected")) - synced;
            // Club owns its synced cards; what was counted beyond them is the store's own.
            int change = Math.max(0, num(r.get("counted")) - synced) - storeExpected;
            if (change == 0) continue;
            UUID storage = (UUID) r.get("storageId");
            UUID card = (UUID) r.get("cardId");
            String finish = (String) r.get("finish"), condition = (String) r.get("condition");
            if (storage != null) {
                Integer exists = jdbc.queryForObject("SELECT count(*) FROM storage_spots WHERE id = ? AND tenant_id = ?", Integer.class, storage, tenant);
                if (exists == null || exists == 0)
                    throw new ApiException(HttpStatus.CONFLICT, "A spot in this count was removed. Cancel it and start a new count.");
            }
            if (change > 0) {
                var c = jdbc.queryForMap("SELECT name, set_code, collector_number, rarity, lang FROM inventory_cards WHERE id = ?", card);
                inventory.add(tenant, location, storage, new InventoryRepository.Stock(card, (String) c.get("name"),
                        (String) c.get("set_code"), (String) c.get("collector_number"), (String) c.get("rarity"),
                        (String) c.get("lang"), finish, condition, change));
            } else {
                jdbc.update("""
                        UPDATE inventory_items SET quantity = quantity + ?, updated_at = now()
                        WHERE location_id = ? AND storage_id IS NOT DISTINCT FROM ? AND card_id = ? AND finish = ?
                          AND condition = ? AND club_link_id IS NULL AND quantity + ? > 0""",
                        change, location, storage, card, finish, condition, change);
                jdbc.update("""
                        DELETE FROM inventory_items WHERE location_id = ? AND storage_id IS NOT DISTINCT FROM ? AND card_id = ?
                          AND finish = ? AND condition = ? AND club_link_id IS NULL AND quantity + ? <= 0""",
                        location, storage, card, finish, condition, change);
            }
            jdbc.update("""
                    INSERT INTO inventory_adjustments (id, tenant_id, location_id, storage_id, card_id, finish, condition, change,
                                                       source, count_id, user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'count', ?, ?)""",
                    UUID.randomUUID(), tenant, location, storage, card, finish, condition, change, id, user);
        }
        jdbc.update("UPDATE inventory_counts SET state = 'reconciled', closed_by = ?, closed_at = now() WHERE id = ?", user, id);
    }

    @Transactional
    public void cancel(UUID tenant, UUID user, UUID id) {
        count(tenant, id, true);
        jdbc.update("UPDATE inventory_counts SET state = 'cancelled', closed_by = ?, closed_at = now() WHERE id = ?", user, id);
    }

    // ---- Inside ----

    private Map<String, Object> count(UUID tenant, UUID id, boolean mustBeOpen) {
        var rows = jdbc.queryForList("""
                SELECT c.id, c.state, c.location_id AS "locationId", l.name AS location, c.storage_id AS "storageId",
                       c.started_at AS "startedAt", u.name AS "startedBy", c.closed_at AS "closedAt"
                FROM inventory_counts c JOIN locations l ON l.id = c.location_id JOIN users u ON u.id = c.started_by
                WHERE c.id = ? AND c.tenant_id = ?""" + (mustBeOpen ? " FOR UPDATE OF c" : ""), id, tenant);
        if (rows.isEmpty()) throw ApiException.notFound("Count not found");
        if (mustBeOpen && !"open".equals(rows.getFirst().get("state")))
            throw new ApiException(HttpStatus.CONFLICT, "This count is already " + rows.getFirst().get("state"));
        return rows.getFirst();
    }

    /** A spot inside the count's area, or the counted spot itself when none (or one outside it) is given. */
    private UUID inScope(UUID tenant, Map<String, Object> count, UUID storage) {
        UUID root = (UUID) count.get("storageId");
        if (storage == null) return root;
        Integer inside = jdbc.queryForObject("SELECT count(*) FROM storage_spots s WHERE s.id = ? AND s.tenant_id = ? AND s.location_id = ? AND "
                + (root == null ? "true" : "s.id IN (" + TREE + ")"), Integer.class,
                root == null ? new Object[]{storage, tenant, count.get("locationId")} : new Object[]{storage, tenant, count.get("locationId"), root});
        return inside != null && inside > 0 ? storage : root;
    }

    private static final String TREE = "WITH RECURSIVE tree AS (SELECT id FROM storage_spots WHERE id = ?"
            + " UNION ALL SELECT c.id FROM storage_spots c JOIN tree t ON c.parent_id = t.id) SELECT id FROM tree";

    /** The whole location, or the spot and everything under it. */
    private static String scope(UUID storage) {
        return storage == null ? "true" : "storage_id IN (" + TREE + ")";
    }

    private static Object[] args(UUID id, UUID tenant, UUID location, UUID storage) {
        return storage == null ? new Object[]{id, tenant, location} : new Object[]{id, tenant, location, storage};
    }

    private static Map<String, Object> withPath(Map<String, Object> row, Map<UUID, List<Map<String, String>>> paths) {
        Map<String, Object> view = new LinkedHashMap<>(row);
        UUID storage = (UUID) row.get("storageId");
        view.put("path", storage == null ? List.of() : paths.getOrDefault(storage, List.of()));
        return view;
    }

    private static String cardKey(Map<String, Object> r) {
        return r.get("cardId") + "/" + r.get("finish") + "/" + r.get("condition");
    }

    private static int num(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
}

package com.cardpricer.cloud.inventory;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.catalog.CardRow;
import com.cardpricer.cloud.catalog.CatalogRepository;
import com.cardpricer.cloud.clubsync.ClubSync;
import com.cardpricer.cloud.web.ApiException;
import com.cardpricer.util.CardConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Inventory: what the store has on hand at each location, and the storage tree it is put away in.
 * Anyone on the store can count, add and move stock; only owners change how storage is laid out.
 */
@RestController
@RequestMapping("/api/app")
public class InventoryController {
    /** Adds one spot per name under {@code parentId} (or at the top of the location), all with the same tier label. */
    public record SpotsBody(@NotNull UUID locationId, UUID parentId, @NotBlank @Size(max = 40) String label,
                            @NotNull @Size(min = 1, max = 200) List<@NotBlank @Size(max = 60) String> names) {}
    public record SpotBody(@NotBlank @Size(max = 40) String label, @NotBlank @Size(max = 60) String name) {}
    public record AddBody(@NotNull UUID cardId, String finish, String condition, @Min(1) @Max(9999) int quantity,
                          @NotNull UUID locationId, UUID storageId) {}
    public record UpdateBody(@Min(0) @Max(9999) int quantity, String condition) {}
    /** Moves {@code quantity} (default: all) to a spot, or to a location's "not put away" pile when storageId is null. */
    public record MoveBody(UUID locationId, UUID storageId, Integer quantity) {}

    private static final List<String> FINISHES = List.of("normal", "foil", "etched");
    /** Lines one bulk action may touch. */
    static final int MAX_BULK = 20000;

    private final InventoryRepository inventory;
    private final CatalogRepository catalog;
    private final JdbcTemplate jdbc;
    private final ClubSync clubSync;
    private final StorageRules rules;

    public InventoryController(InventoryRepository inventory, CatalogRepository catalog, JdbcTemplate jdbc, ClubSync clubSync,
                               StorageRules rules) {
        this.rules = rules;
        this.inventory = inventory;
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.clubSync = clubSync;
    }

    // ---- Storage layout ----

    /** Every spot in the store with the stock directly in it; the client builds the tree from parentId. */
    @GetMapping("/storage")
    public List<Map<String, Object>> storage(HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.query("SELECT storage_id, sum(quantity) FROM inventory_items WHERE tenant_id = ? AND storage_id IS NOT NULL GROUP BY storage_id",
                rs -> { counts.put(rs.getObject(1, UUID.class), rs.getLong(2)); }, tenant);
        return inventory.spots(tenant).stream().map(s -> {
            Map<String, Object> view = new HashMap<>();
            view.put("id", s.id());
            view.put("locationId", s.locationId());
            view.put("parentId", s.parentId());
            view.put("label", s.label());
            view.put("name", s.name());
            view.put("cards", counts.getOrDefault(s.id(), 0L));
            return view;
        }).toList();
    }

    @PostMapping("/storage")
    @Transactional
    public List<Map<String, Object>> addSpots(@Valid @RequestBody SpotsBody body, HttpServletRequest request) {
        UUID tenant = requireOwner(request).tenantId();
        requireLocation(tenant, body.locationId());
        if (body.parentId() != null && !inventory.spot(tenant, body.parentId()).locationId().equals(body.locationId()))
            throw ApiException.badRequest("That spot is in a different location");
        Integer next = jdbc.queryForObject("""
                SELECT coalesce(max(position), -1) + 1 FROM storage_spots
                WHERE location_id = ? AND parent_id IS NOT DISTINCT FROM ?""", Integer.class, body.locationId(), body.parentId());
        int position = next == null ? 0 : next;
        try {
            for (String name : new LinkedHashSet<>(body.names().stream().map(String::trim).toList())) {
                jdbc.update("INSERT INTO storage_spots (id, tenant_id, location_id, parent_id, label, name, position) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), tenant, body.locationId(), body.parentId(), body.label().trim(), name, position++);
            }
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "There is already a " + body.label().trim() + " with one of those names here");
        }
        return storage(request);
    }

    @PutMapping("/storage/{id}")
    public List<Map<String, Object>> renameSpot(@PathVariable UUID id, @Valid @RequestBody SpotBody body, HttpServletRequest request) {
        UUID tenant = requireOwner(request).tenantId();
        inventory.spot(tenant, id);
        try {
            jdbc.update("UPDATE storage_spots SET label = ?, name = ? WHERE id = ? AND tenant_id = ?",
                    body.label().trim(), body.name().trim(), id, tenant);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "There is already a " + body.label().trim() + " " + body.name().trim() + " here");
        }
        return storage(request);
    }

    /** Removes an empty-of-children spot. Any cards in it move up to the spot that held it. */
    @PostMapping("/storage/{id}/remove")
    @Transactional
    public List<Map<String, Object>> removeSpot(@PathVariable UUID id, HttpServletRequest request) {
        UUID tenant = requireOwner(request).tenantId();
        var spot = inventory.spot(tenant, id);
        Integer children = jdbc.queryForObject("SELECT count(*) FROM storage_spots WHERE parent_id = ?", Integer.class, id);
        if (children != null && children > 0)
            throw ApiException.badRequest("Remove what's inside " + spot.label() + " " + spot.name() + " first");
        jdbc.update("""
                INSERT INTO inventory_items (id, tenant_id, location_id, storage_id, card_id, name, set_code, collector_number,
                                             rarity, lang, finish, condition, quantity, club_link_id)
                SELECT gen_random_uuid(), tenant_id, location_id, ?, card_id, name, set_code, collector_number, rarity, lang,
                       finish, condition, quantity, club_link_id FROM inventory_items WHERE storage_id = ?
                ON CONFLICT (location_id, storage_id, card_id, finish, condition, club_link_id)
                DO UPDATE SET quantity = inventory_items.quantity + EXCLUDED.quantity, updated_at = now()""", spot.parentId(), id);
        // Club collections synced into this spot land in the spot that held it from now on.
        jdbc.update("UPDATE club_links SET storage_id = ?, updated_at = now() WHERE storage_id = ?", spot.parentId(), id);
        jdbc.update("UPDATE club_link_items SET storage_id = ? WHERE storage_id = ?", spot.parentId(), id);
        jdbc.update("UPDATE club_link_items SET placed_storage_id = ? WHERE placed_storage_id = ?", spot.parentId(), id);
        jdbc.update("DELETE FROM inventory_items WHERE storage_id = ?", id);
        jdbc.update("DELETE FROM storage_spots WHERE id = ?", id);
        return storage(request);
    }

    // ---- Stock ----

    /**
     * Stock filtered by any card detail, where it is and where it came from (see {@link InventoryQuery}), sorted by
     * any column, a page at a time. {@code storage} is a spot (and everything under it), {@code none} for not put
     * away yet, or {@code any} for put away.
     */
    @GetMapping("/inventory")
    public Map<String, Object> list(@RequestParam MultiValueMap<String, String> params, HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        var query = new InventoryQuery(tenant, params);
        var where = query.where();
        int limit = Math.clamp(intParam(params, "limit", 100), 1, 500);
        int offset = Math.max(0, intParam(params, "offset", 0));
        List<Object> args = new ArrayList<>(List.of(tenant));
        args.addAll(where.args());
        args.add(limit + 1);
        args.add(offset);
        var rows = jdbc.queryForList(InventoryQuery.TREE + """
                SELECT i.id, i.location_id AS "locationId", loc.name AS location, i.storage_id AS "storageId", i.card_id AS "cardId",
                       i.name, i.set_code AS "set", i.collector_number AS number, i.rarity, i.finish, i.condition, i.quantity,
                       c.image_small AS image, i.club_link_id AS "clubLinkId", cl.collection_name AS "clubCollection",
                       c.game, c.set_name AS "setName", extract(year FROM c.released_at)::int AS year, c.type_line AS "typeLine",
                       array_to_string(c.colors, '') AS colors, array_to_string(c.treatments, ',') AS treatments,
                       c.mana_value AS "manaValue", """ + InventoryQuery.MARKET + " AS market "
                        + InventoryQuery.FROM + " LEFT JOIN tree w ON w.id = i.storage_id WHERE " + where.sql()
                        + " ORDER BY " + InventoryQuery.orderBy(params.getFirst("sort"), params.getFirst("dir")) + " LIMIT ? OFFSET ?",
                args.toArray());
        var paths = InventoryRepository.paths(inventory.spots(tenant));
        var shown = rows.subList(0, Math.min(limit, rows.size()));
        // Cards waiting to be put away show where the store's rules would send them.
        var waiting = shown.stream().filter(r -> r.get("storageId") == null).map(r -> r.get("id").toString()).toArray(String[]::new);
        Map<UUID, UUID> destinations = waiting.length == 0 ? Map.of()
                : rules.destinations(tenant, new InventoryQuery.Where("i.id = ANY (?::uuid[])", List.of((Object) waiting)), null, null);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : shown) {
            Map<String, Object> item = new HashMap<>(row);
            item.put("path", row.get("storageId") == null ? List.of() : paths.getOrDefault((UUID) row.get("storageId"), List.of()));
            UUID to = destinations.get((UUID) row.get("id"));
            item.put("destinationId", to);
            item.put("destination", to == null ? List.of() : paths.getOrDefault(to, List.of()));
            items.add(item);
        }
        var totals = jdbc.queryForMap("SELECT coalesce(sum(i.quantity), 0) AS cards, count(*) AS lines, coalesce(sum(i.quantity * "
                + InventoryQuery.MARKET + "), 0) AS value " + InventoryQuery.FROM + " WHERE " + where.sql(), where.args().toArray());
        return Map.of("items", items, "more", rows.size() > limit, "offset", offset, "cards", totals.get("cards"),
                "lines", totals.get("lines"), "value", totals.get("value"));
    }

    /** For each filter, its values with how many cards each would show, given the other filters picked. */
    @GetMapping("/inventory/facets")
    public Map<String, Object> facets(@RequestParam MultiValueMap<String, String> params, HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        var query = new InventoryQuery(tenant, params);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String facet : InventoryQuery.FACETS) {
            var where = query.where(facet);
            List<Object> args = new ArrayList<>();
            if (facet.equals("type")) args.add(InventoryQuery.TYPES.toArray(String[]::new));
            args.addAll(where.args());
            out.put(facet, jdbc.queryForList(query.facetSql(facet).formatted(where.sql()), args.toArray()));
        }
        return out;
    }

    /**
     * Moves whole lines, picked by id or by a filter ("everything matching"), into a spot, or to "not put away"
     * (at {@code locationId}, else where each line is). Synced lines keep syncing from CardBox in their new spot.
     */
    public record BulkMoveBody(List<UUID> ids, Map<String, List<String>> filter, UUID locationId, UUID storageId) {}

    @PostMapping("/inventory/move")
    @Transactional
    public Map<String, Object> bulkMove(@RequestBody BulkMoveBody body, HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        UUID target = body.storageId() != null ? inventory.spot(tenant, body.storageId()).locationId() : body.locationId();
        if (target != null) requireLocation(tenant, target);
        return moveLines(tenant, lines(tenant, body.ids(), body.filter()), target, body.storageId());
    }

    /** Lines picked by id or by filter, locked for a change. */
    List<Map<String, Object>> lines(UUID tenant, List<UUID> ids, Map<String, List<String>> filter) {
        if ((ids == null) == (filter == null)) throw ApiException.badRequest("Send either ids or filter");
        if (ids != null) {
            if (ids.size() > MAX_BULK) throw ApiException.badRequest("Pick at most " + MAX_BULK + " lines at a time");
            return jdbc.queryForList("SELECT * FROM inventory_items WHERE tenant_id = ? AND id = ANY (?::uuid[]) FOR UPDATE",
                    tenant, ids.stream().map(UUID::toString).toArray(String[]::new));
        }
        var where = new InventoryQuery(tenant, filter).where();
        var lines = jdbc.queryForList("SELECT i.* " + InventoryQuery.FROM + " WHERE " + where.sql() + " FOR UPDATE OF i", where.args().toArray());
        if (lines.size() > MAX_BULK) throw ApiException.badRequest("That is " + lines.size() + " lines; narrow it to " + MAX_BULK + " or fewer");
        return lines;
    }

    /**
     * Moves whole lines into a spot, or to "not put away" at {@code location} (null: where each line is). Synced lines
     * keep syncing from CardBox in their new spot.
     */
    Map<String, Object> moveLines(UUID tenant, List<Map<String, Object>> lines, UUID target, UUID storage) {
        int moved = 0, cards = 0, skipped = 0;
        Set<UUID> links = new HashSet<>();
        for (var line : lines) {
            UUID location = target != null ? target : (UUID) line.get("location_id");
            if (location.equals(line.get("location_id")) && Objects.equals(storage, line.get("storage_id"))) continue;
            UUID link = (UUID) line.get("club_link_id");
            if (link != null) {
                // A synced card that isn't in a spot sits at its collection's location.
                UUID home = jdbc.queryForObject("SELECT location_id FROM club_links WHERE id = ?", UUID.class, link);
                if (storage == null && !location.equals(home)) { skipped++; continue; }
                clubSync.place(line, storage);
                links.add(link);
            } else {
                jdbc.update("DELETE FROM inventory_items WHERE id = ?", line.get("id"));
                inventory.add(tenant, location, storage, stock(line, (String) line.get("condition"), (Integer) line.get("quantity")));
            }
            moved++;
            cards += (Integer) line.get("quantity");
        }
        links.forEach(clubSync::rebuild);
        return Map.of("lines", moved, "cards", cards, "skipped", skipped);
    }

    // ---- Put away by the store's rules ----

    /**
     * Where the store's rules would put the cards not put away yet (under the given filters), grouped by spot in
     * shelf order: the put-away list. Cards no rule fits are counted separately.
     */
    @GetMapping("/inventory/put-away")
    public Map<String, Object> putAwayList(@RequestParam MultiValueMap<String, String> params, HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        Map<String, List<String>> filter = new LinkedHashMap<>(params);
        filter.put("storage", List.of("none"));
        var where = new InventoryQuery(tenant, filter).where();
        var lines = jdbc.queryForList("SELECT i.id, i.quantity " + InventoryQuery.FROM + " WHERE " + where.sql(), where.args().toArray());
        var destinations = rules.destinations(tenant, where, null, null);
        var spots = inventory.spots(tenant);
        var paths = InventoryRepository.paths(spots);
        Map<UUID, long[]> by = new LinkedHashMap<>();
        long[] none = new long[2];
        for (var line : lines) {
            UUID to = destinations.get((UUID) line.get("id"));
            long[] sum = to == null ? none : by.computeIfAbsent(to, k -> new long[2]);
            sum[0]++;
            sum[1] += (Integer) line.get("quantity");
        }
        Map<UUID, String> locationNames = new HashMap<>();
        jdbc.query("SELECT id, name FROM locations WHERE tenant_id = ?", rs -> { locationNames.put(rs.getObject(1, UUID.class), rs.getString(2)); }, tenant);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (var spot : StorageRules.treeOrder(spots)) {
            long[] sum = by.get(spot.id());
            if (sum == null) continue;
            groups.add(Map.of("storageId", spot.id(), "locationId", spot.locationId(), "location", locationNames.get(spot.locationId()),
                    "path", paths.get(spot.id()), "lines", sum[0], "cards", sum[1]));
        }
        return Map.of("groups", groups, "unmatched", Map.of("lines", none[0], "cards", none[1]));
    }

    /** Puts lines (by id or filter) where the rules say, or only those headed for {@code storageId} when given. */
    public record PutAwayBody(List<UUID> ids, Map<String, List<String>> filter, UUID storageId) {}

    @PostMapping("/inventory/put-away")
    @Transactional
    public Map<String, Object> putAway(@RequestBody PutAwayBody body, HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        var lines = lines(tenant, body.ids(), body.filter());
        var ids = lines.stream().map(l -> ((UUID) l.get("id")).toString()).toArray(String[]::new);
        var destinations = rules.destinations(tenant, new InventoryQuery.Where("i.id = ANY (?::uuid[])", List.of((Object) ids)), null, null);
        Map<UUID, List<Map<String, Object>>> by = new LinkedHashMap<>();
        int unmatched = 0;
        for (var line : lines) {
            UUID to = destinations.get((UUID) line.get("id"));
            if (to == null) { unmatched++; continue; }
            if (body.storageId() != null && !body.storageId().equals(to)) continue;
            by.computeIfAbsent(to, k -> new ArrayList<>()).add(line);
        }
        int moved = 0, cards = 0;
        for (var entry : by.entrySet()) {
            var result = moveLines(tenant, entry.getValue(), inventory.spot(tenant, entry.getKey()).locationId(), entry.getKey());
            moved += (Integer) result.get("lines");
            cards += (Integer) result.get("cards");
        }
        return Map.of("lines", moved, "cards", cards, "spots", by.size(), "unmatched", unmatched);
    }

    @PostMapping("/inventory")
    public Map<String, Object> add(@Valid @RequestBody AddBody body, HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        requireLocation(tenant, body.locationId());
        requireSpotAt(tenant, body.storageId(), body.locationId());
        CardRow card = catalog.find(body.cardId()).orElseThrow(() -> ApiException.badRequest("Unknown card"));
        inventory.add(tenant, body.locationId(), body.storageId(), new InventoryRepository.Stock(card.id(), card.name(), card.setCode(),
                card.collectorNumber(), card.rarity(), card.lang(), finish(body.finish()), condition(body.condition()), body.quantity()));
        return Map.of("ok", true);
    }

    /** Sets the count (0 removes the line) and, optionally, the condition. A changed condition merges with a matching line. */
    @PutMapping("/inventory/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable UUID id, @Valid @RequestBody UpdateBody body, HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        var item = item(tenant, id);
        String condition = body.condition() == null ? (String) item.get("condition") : condition(body.condition());
        jdbc.update("DELETE FROM inventory_items WHERE id = ?", id);
        if (body.quantity() > 0)
            inventory.add(tenant, (UUID) item.get("location_id"), (UUID) item.get("storage_id"), stock(item, condition, body.quantity()));
        return Map.of("ok", true);
    }

    @PostMapping("/inventory/{id}/move")
    @Transactional
    public Map<String, Object> move(@PathVariable UUID id, @RequestBody MoveBody body, HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        var line = line(tenant, id);
        int have = (Integer) line.get("quantity");
        int moving = body.quantity() == null ? have : body.quantity();
        if (line.get("club_link_id") != null) {
            // Club owns how many there are, so a synced line moves whole; it keeps syncing in its new spot.
            if (moving != have) throw ApiException.badRequest("Cards synced from CardBox move a whole line at a time");
            var result = bulkMove(new BulkMoveBody(List.of(id), null, body.locationId(), body.storageId()), request);
            if ((Integer) result.get("skipped") > 0)
                throw ApiException.badRequest("Cards synced from CardBox can only wait to be put away at their collection's location");
            return Map.of("ok", true);
        }
        if (moving < 1 || moving > have) throw ApiException.badRequest("Move between 1 and " + have);
        // A spot decides its own location; without a spot, the cards go to a location's "not put away" pile.
        UUID location = body.storageId() != null ? inventory.spot(tenant, body.storageId()).locationId()
                : body.locationId() != null ? body.locationId() : (UUID) line.get("location_id");
        requireLocation(tenant, location);
        if (moving == have) jdbc.update("DELETE FROM inventory_items WHERE id = ?", id);
        else jdbc.update("UPDATE inventory_items SET quantity = quantity - ?, updated_at = now() WHERE id = ?", moving, id);
        inventory.add(tenant, location, body.storageId(), stock(line, (String) line.get("condition"), moving));
        return Map.of("ok", true);
    }

    private Map<String, Object> line(UUID tenant, UUID id) {
        var rows = jdbc.queryForList("SELECT * FROM inventory_items WHERE id = ? AND tenant_id = ? FOR UPDATE", id, tenant);
        if (rows.isEmpty()) throw ApiException.notFound("That card is no longer in inventory");
        return rows.getFirst();
    }

    private static int intParam(MultiValueMap<String, String> params, String name, int fallback) {
        try {
            String v = params.getFirst(name);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " must be a number");
        }
    }

    /** A line of the store's own stock, locked for a change. Synced lines are counted and conditioned on CardBox. */
    private Map<String, Object> item(UUID tenant, UUID id) {
        var rows = List.of(line(tenant, id));
        if (rows.getFirst().get("club_link_id") != null) {
            // Club owns synced lines: one change here would be undone by the next delivery.
            String collection = jdbc.queryForObject("SELECT collection_name FROM club_links WHERE id = ?", String.class,
                    rows.getFirst().get("club_link_id"));
            throw new ApiException(HttpStatus.CONFLICT, "This card syncs from the CardBox collection " + collection
                    + ". Change how many there are on CardBox; you can still move it here.");
        }
        return rows.getFirst();
    }

    private static InventoryRepository.Stock stock(Map<String, Object> row, String condition, int quantity) {
        return new InventoryRepository.Stock((UUID) row.get("card_id"), (String) row.get("name"), (String) row.get("set_code"),
                (String) row.get("collector_number"), (String) row.get("rarity"), (String) row.get("lang"),
                (String) row.get("finish"), condition, quantity);
    }

    private void requireLocation(UUID tenant, UUID location) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM locations WHERE id = ? AND tenant_id = ? AND archived_at IS NULL",
                Integer.class, location, tenant);
        if (found == null || found == 0) throw ApiException.badRequest("That location is closed or not part of this store");
    }

    private void requireSpotAt(UUID tenant, UUID spot, UUID location) {
        if (spot != null && !inventory.spot(tenant, spot).locationId().equals(location))
            throw ApiException.badRequest("That spot is in a different location");
    }

    private static String finish(String finish) {
        String f = finish == null ? "normal" : finish;
        if (!FINISHES.contains(f)) throw ApiException.badRequest("Finish must be normal, foil or etched");
        return f;
    }

    private static String condition(String condition) {
        String c = condition == null ? "NM" : condition;
        if (!Arrays.asList(CardConstants.CONDITIONS).contains(c))
            throw ApiException.badRequest("Condition must be one of " + String.join(", ", CardConstants.CONDITIONS));
        return c;
    }

    private static CurrentUser requireOwner(HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        if (!user.owner()) throw ApiException.forbidden("Only a store owner can change how storage is laid out");
        return user;
    }
}

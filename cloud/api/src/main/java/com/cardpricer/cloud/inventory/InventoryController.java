package com.cardpricer.cloud.inventory;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.catalog.CardRow;
import com.cardpricer.cloud.catalog.CatalogRepository;
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
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    private final InventoryRepository inventory;
    private final CatalogRepository catalog;
    private final JdbcTemplate jdbc;

    public InventoryController(InventoryRepository inventory, CatalogRepository catalog, JdbcTemplate jdbc) {
        this.inventory = inventory;
        this.catalog = catalog;
        this.jdbc = jdbc;
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
        jdbc.update("DELETE FROM inventory_items WHERE storage_id = ?", id);
        jdbc.update("DELETE FROM storage_spots WHERE id = ?", id);
        return storage(request);
    }

    // ---- Stock ----

    /**
     * Stock at a location (or every location), optionally inside one spot and everything under it, or only what
     * hasn't been put away ({@code storage=none}), filtered by card name, set or number.
     */
    @GetMapping("/inventory")
    public Map<String, Object> list(@RequestParam(value = "location", required = false) UUID location,
                                    @RequestParam(value = "storage", defaultValue = "") String storage,
                                    @RequestParam(value = "q", defaultValue = "") String q,
                                    HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        List<Object> args = new ArrayList<>(List.of(tenant));
        StringBuilder where = new StringBuilder("i.tenant_id = ?");
        if (location != null) { where.append(" AND i.location_id = ?"); args.add(location); }
        if (storage.equals("none")) where.append(" AND i.storage_id IS NULL");
        else if (!storage.isBlank()) {
            UUID root;
            try {
                root = UUID.fromString(storage);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Unknown storage spot");
            }
            inventory.spot(tenant, root);
            where.append(" AND i.storage_id IN (WITH RECURSIVE tree AS (SELECT id FROM storage_spots WHERE id = ?"
                    + " UNION ALL SELECT c.id FROM storage_spots c JOIN tree t ON c.parent_id = t.id) SELECT id FROM tree)");
            args.add(root);
        }
        String term = q.trim().toLowerCase();
        if (!term.isEmpty()) {
            where.append(" AND (lower(i.name) LIKE ? OR lower(i.set_code) = ? OR ltrim(i.collector_number, '0') = ltrim(?, '0'))");
            args.add("%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            args.add(term);
            args.add(term);
        }
        var rows = jdbc.queryForList("""
                SELECT i.id, i.location_id AS "locationId", loc.name AS location, i.storage_id AS "storageId", i.card_id AS "cardId",
                       i.name, i.set_code AS "set", i.collector_number AS number, i.rarity, i.finish, i.condition, i.quantity,
                       c.image_small AS image, i.club_link_id AS "clubLinkId", cl.collection_name AS "clubCollection",
                       CASE i.finish WHEN 'foil' THEN c.usd_foil WHEN 'etched' THEN c.usd_etched ELSE c.usd END AS market
                FROM inventory_items i JOIN locations loc ON loc.id = i.location_id LEFT JOIN inventory_cards c ON c.id = i.card_id
                LEFT JOIN club_links cl ON cl.id = i.club_link_id
                WHERE\s""" + where + " ORDER BY lower(i.name), i.set_code, i.collector_number, i.finish, i.condition LIMIT 501",
                args.toArray());
        var paths = InventoryRepository.paths(inventory.spots(tenant));
        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : rows.subList(0, Math.min(500, rows.size()))) {
            Map<String, Object> item = new HashMap<>(row);
            item.put("path", row.get("storageId") == null ? List.of() : paths.getOrDefault((UUID) row.get("storageId"), List.of()));
            items.add(item);
        }
        var totals = jdbc.queryForMap("SELECT coalesce(sum(i.quantity), 0) AS cards, count(*) AS lines FROM inventory_items i WHERE "
                + where, args.toArray());
        return Map.of("items", items, "more", rows.size() > 500, "cards", totals.get("cards"), "lines", totals.get("lines"));
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
        var item = item(tenant, id);
        int have = (Integer) item.get("quantity");
        int moving = body.quantity() == null ? have : body.quantity();
        if (moving < 1 || moving > have) throw ApiException.badRequest("Move between 1 and " + have);
        // A spot decides its own location; without a spot, the cards go to a location's "not put away" pile.
        UUID location = body.storageId() != null ? inventory.spot(tenant, body.storageId()).locationId()
                : body.locationId() != null ? body.locationId() : (UUID) item.get("location_id");
        requireLocation(tenant, location);
        if (moving == have) jdbc.update("DELETE FROM inventory_items WHERE id = ?", id);
        else jdbc.update("UPDATE inventory_items SET quantity = quantity - ?, updated_at = now() WHERE id = ?", moving, id);
        inventory.add(tenant, location, body.storageId(), stock(item, (String) item.get("condition"), moving));
        return Map.of("ok", true);
    }

    private Map<String, Object> item(UUID tenant, UUID id) {
        var rows = jdbc.queryForList("SELECT * FROM inventory_items WHERE id = ? AND tenant_id = ? FOR UPDATE", id, tenant);
        if (rows.isEmpty()) throw ApiException.notFound("That card is no longer in inventory");
        if (rows.getFirst().get("club_link_id") != null) {
            // Club owns synced lines: one change here would be undone by the next delivery.
            String collection = jdbc.queryForObject("SELECT collection_name FROM club_links WHERE id = ?", String.class,
                    rows.getFirst().get("club_link_id"));
            throw new ApiException(HttpStatus.CONFLICT, "This card syncs from the CardBox collection " + collection
                    + ". Change it there, or move the whole collection from Club collections.");
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

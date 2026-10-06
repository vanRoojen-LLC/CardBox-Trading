package com.cardpricer.cloud.inventory;

import com.cardpricer.cloud.clubsync.ClubSync;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Moving stock between spots: by hand, by the store's storage rules, and, for stores that turn it on, as cards arrive
 * from trades and CardBox collections.
 */
@Service
public class PutAway {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PutAway.class);
    private final JdbcTemplate jdbc;
    private final InventoryRepository inventory;
    private final ClubSync clubSync;
    private final StorageRules rules;

    public PutAway(JdbcTemplate jdbc, InventoryRepository inventory, ClubSync clubSync, StorageRules rules) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.clubSync = clubSync;
        this.rules = rules;
    }

    /**
     * Moves whole lines into a spot, or to "not put away" at {@code location} (null: where each line is). Synced lines
     * keep syncing from CardBox in their new spot.
     */
    public Map<String, Object> moveLines(UUID tenant, List<Map<String, Object>> lines, UUID target, UUID storage) {
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
                inventory.add(tenant, location, storage, InventoryController.stock(line, (String) line.get("condition"), (Integer) line.get("quantity")));
            }
            moved++;
            cards += (Integer) line.get("quantity");
        }
        links.forEach(clubSync::rebuild);
        return Map.of("lines", moved, "cards", cards, "skipped", skipped);
    }

    /**
     * Puts lines where the rules say, or only those headed for {@code onlyTo} when given. Lines already in their spot,
     * or somewhere inside it, stay where staff filed them.
     */
    public Map<String, Object> byRules(UUID tenant, List<Map<String, Object>> lines, UUID onlyTo) {
        if (lines.isEmpty()) return Map.of("lines", 0, "cards", 0, "spots", 0, "unmatched", 0);
        var ids = lines.stream().map(l -> ((UUID) l.get("id")).toString()).toArray(String[]::new);
        var destinations = rules.destinations(tenant, new InventoryQuery.Where("i.id = ANY (?::uuid[])", List.of((Object) ids)), null, null);
        Map<UUID, UUID> parents = new HashMap<>();
        for (var spot : inventory.spots(tenant)) parents.put(spot.id(), spot.parentId());
        Map<UUID, List<Map<String, Object>>> by = new LinkedHashMap<>();
        int unmatched = 0;
        lines:
        for (var line : lines) {
            UUID to = destinations.get((UUID) line.get("id"));
            if (to == null) { unmatched++; continue; }
            for (UUID at = (UUID) line.get("storage_id"); at != null; at = parents.get(at)) if (at.equals(to)) continue lines;
            if (onlyTo != null && !onlyTo.equals(to)) continue;
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

    /** Whether the store files arriving cards by its rules itself, instead of waiting for staff to confirm. */
    public boolean filesOnArrival(UUID tenant) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT file_on_arrival FROM tenants WHERE id = ?", Boolean.class, tenant));
    }

    public void setFilesOnArrival(UUID tenant, boolean on) {
        jdbc.update("UPDATE tenants SET file_on_arrival = ? WHERE id = ?", on, tenant);
    }

    /** Files arrivals without failing what brought them: the trade or delivery is already saved, and staff can file by hand. */
    public void arrived(Runnable filing) {
        try {
            filing.run();
        } catch (RuntimeException e) {
            log.warn("Filing arriving cards by storage rules failed; they wait to be put away", e);
        }
    }

    /** Cards a trade just bought in, at its location, filed by the rules when the store asks for that. */
    @Transactional
    public void tradeArrived(UUID tenant, UUID location, List<UUID> cards) {
        if (cards.isEmpty() || !filesOnArrival(tenant)) return;
        file(tenant, "i.location_id = ? AND i.club_link_id IS NULL AND i.card_id = ANY (?::uuid[])",
                location, cards.stream().map(UUID::toString).toArray(String[]::new));
    }

    /**
     * Cards a CardBox collection just delivered, filed by the rules when the store asks for that. Cards someone has
     * placed already (including back to "not put away" on purpose), and cards scanned into a spot on Club, stay put.
     */
    @Transactional
    public void collectionArrived(String collectionId) {
        var links = jdbc.queryForList("SELECT id, tenant_id FROM club_links WHERE collection_id = ? AND state = 'active'", collectionId);
        if (links.isEmpty()) return;
        UUID tenant = (UUID) links.getFirst().get("tenant_id");
        if (!filesOnArrival(tenant)) return;
        file(tenant, """
                i.club_link_id = ? AND NOT EXISTS (SELECT 1 FROM club_link_items u WHERE u.link_id = i.club_link_id
                    AND NOT u.removed AND u.card_id = i.card_id AND u.finish = i.finish AND u.placed)""",
                links.getFirst().get("id"));
    }

    private void file(UUID tenant, String scope, Object... args) {
        List<Object> all = new ArrayList<>(List.of(tenant));
        all.addAll(List.of(args));
        var lines = jdbc.queryForList("SELECT i.* " + InventoryQuery.FROM + " WHERE i.tenant_id = ? AND i.storage_id IS NULL AND "
                + scope + " FOR UPDATE OF i", all.toArray());
        byRules(tenant, lines, null);
    }
}

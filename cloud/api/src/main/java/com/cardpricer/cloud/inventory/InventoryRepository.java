package com.cardpricer.cloud.inventory;

import com.cardpricer.cloud.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stock on hand and the storage spots it sits in. Every query is scoped to one store. */
@Repository
public class InventoryRepository {
    /** One card, finish and condition to put into stock. */
    public record Stock(UUID cardId, String name, String setCode, String collectorNumber, String rarity, String lang,
                        String finish, String condition, int quantity) {}

    public record Spot(UUID id, UUID locationId, UUID parentId, String label, String name, int position) {}

    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds stock to a spot (null: not put away yet), merging with the same card, finish and condition already there. */
    public void add(UUID tenant, UUID location, UUID storage, Stock s) {
        jdbc.update("""
                INSERT INTO inventory_items (id, tenant_id, location_id, storage_id, card_id, name, set_code, collector_number,
                                             rarity, lang, finish, condition, quantity)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (location_id, storage_id, card_id, finish, condition, club_link_id)
                DO UPDATE SET quantity = inventory_items.quantity + EXCLUDED.quantity, updated_at = now()""",
                UUID.randomUUID(), tenant, location, storage, s.cardId(), s.name(), s.setCode(), s.collectorNumber(),
                s.rarity(), s.lang(), s.finish(), s.condition(), s.quantity());
    }

    /** Every storage spot in the store, in the order the store arranged them within each parent. */
    public List<Spot> spots(UUID tenant) {
        return jdbc.query("""
                SELECT id, location_id, parent_id, label, name, position FROM storage_spots
                WHERE tenant_id = ? ORDER BY position, created_at""",
                (rs, i) -> new Spot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), rs.getInt(6)), tenant);
    }

    /** The spot, checked to belong to this store. */
    public Spot spot(UUID tenant, UUID id) {
        return jdbc.query("SELECT id, location_id, parent_id, label, name, position FROM storage_spots WHERE id = ? AND tenant_id = ?",
                (rs, i) -> new Spot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), rs.getInt(6)), id, tenant)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Storage spot not found"));
    }

    /** "Store room Back · Shelf A · Box 3" style path for every spot, keyed by id. */
    public static Map<UUID, List<Map<String, String>>> paths(List<Spot> spots) {
        Map<UUID, Spot> byId = new HashMap<>();
        for (Spot s : spots) byId.put(s.id(), s);
        Map<UUID, List<Map<String, String>>> paths = new HashMap<>();
        for (Spot s : spots) {
            List<Map<String, String>> path = new ArrayList<>();
            for (Spot at = s; at != null; at = at.parentId() == null ? null : byId.get(at.parentId()))
                path.addFirst(Map.of("label", at.label(), "name", at.name()));
            paths.put(s.id(), path);
        }
        return paths;
    }
}

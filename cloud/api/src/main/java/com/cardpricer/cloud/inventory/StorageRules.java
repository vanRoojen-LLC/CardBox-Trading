package com.cardpricer.cloud.inventory;

import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "What goes here" rules on storage spots, and where they send each card. A rule is a saved inventory filter (see
 * {@link InventoryQuery}). A card walks down its location's storage tree: at each level the first spot, in the store's
 * order, whose rule fits it wins, and its children are tried next. The card's destination is the deepest spot reached;
 * none when nothing at the top fits. A rule on a box only ever sees cards that fit the shelf it sits on. A spot without
 * a rule is never a destination: cards pass through it to spots inside it that have one, and otherwise skip it.
 */
@Service
public class StorageRules {
    /** The card details a rule may test; the same names inventory filters use. */
    public static final List<String> FIELDS = List.of("game", "set", "year", "rarity", "color", "type", "finish", "treatment",
            "condition", "source", "priceMin", "priceMax", "nameFrom", "nameTo");
    private static final TypeReference<Map<String, List<String>>> CONDITIONS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final InventoryRepository inventory;
    private final ObjectMapper json;

    public StorageRules(JdbcTemplate jdbc, InventoryRepository inventory, ObjectMapper json) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.json = json;
    }

    /** Every rule in the store, by spot. */
    public Map<UUID, Map<String, List<String>>> rules(UUID tenant) {
        Map<UUID, Map<String, List<String>>> out = new LinkedHashMap<>();
        jdbc.query("SELECT spot_id, conditions::text FROM storage_rules WHERE tenant_id = ?",
                rs -> { out.put(rs.getObject(1, UUID.class), parse(rs.getString(2))); }, tenant);
        return out;
    }

    public void save(UUID tenant, UUID spot, UUID user, Map<String, List<String>> conditions) {
        inventory.spot(tenant, spot);
        String text;
        try {
            text = json.writeValueAsString(clean(conditions));
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("That rule can't be saved");
        }
        jdbc.update("""
                INSERT INTO storage_rules (spot_id, tenant_id, conditions, updated_by) VALUES (?, ?, ?::jsonb, ?)
                ON CONFLICT (spot_id) DO UPDATE SET conditions = EXCLUDED.conditions, updated_by = EXCLUDED.updated_by, updated_at = now()""",
                spot, tenant, text, user);
    }

    public void remove(UUID tenant, UUID spot) {
        jdbc.update("DELETE FROM storage_rules WHERE spot_id = ? AND tenant_id = ?", spot, tenant);
    }

    /**
     * Where the rules send each line matching {@code restrict}; lines no rule fits are left out. {@code trySpot} and
     * {@code tryConditions} stand in a rule for one spot (null conditions: no rule) to preview an edit before saving.
     */
    public Map<UUID, UUID> destinations(UUID tenant, InventoryQuery.Where restrict, UUID trySpot, Map<String, List<String>> tryConditions) {
        var rules = rules(tenant);
        if (trySpot != null) {
            if (tryConditions == null) rules.remove(trySpot);
            else rules.put(trySpot, clean(tryConditions));
        }
        if (rules.isEmpty()) return Map.of();
        var spots = inventory.spots(tenant);
        // Which lines each rule fits, within its own location.
        Map<UUID, Set<UUID>> fits = new HashMap<>();
        Map<UUID, InventoryRepository.Spot> byId = new HashMap<>();
        for (var spot : spots) byId.put(spot.id(), spot);
        for (var rule : rules.entrySet()) {
            var spot = byId.get(rule.getKey());
            if (spot == null) continue;
            var where = new InventoryQuery(tenant, rule.getValue()).where();
            List<Object> args = new ArrayList<>(where.args());
            args.add(spot.locationId());
            args.addAll(restrict.args());
            fits.put(spot.id(), new HashSet<>(jdbc.queryForList("SELECT i.id " + InventoryQuery.FROM + " WHERE " + where.sql()
                    + " AND i.location_id = ? AND (" + restrict.sql() + ")", UUID.class, args.toArray())));
        }
        Map<UUID, List<InventoryRepository.Spot>> children = new HashMap<>();
        for (var spot : spots) children.computeIfAbsent(spot.parentId(), k -> new ArrayList<>()).add(spot);
        Set<UUID> lines = new HashSet<>();
        fits.values().forEach(lines::addAll);
        Map<UUID, UUID> out = new HashMap<>();
        for (UUID line : lines) {
            UUID at = null;
            for (List<InventoryRepository.Spot> level = children.getOrDefault(null, List.of()); ; ) {
                UUID next = null;
                for (var spot : level) {
                    if (takes(spot.id(), line, fits, children)) { next = spot.id(); break; }
                }
                if (next == null) break;
                at = next;
                level = children.getOrDefault(next, List.of());
            }
            if (at != null) out.put(line, at);
        }
        return out;
    }

    /**
     * Whether a spot takes the line: its rule fits, or, without a rule of its own, something inside it does. So a
     * shelf with no rule passes cards through to the boxes on it that have one, and is never a destination itself.
     */
    private static boolean takes(UUID spot, UUID line, Map<UUID, Set<UUID>> fits, Map<UUID, List<InventoryRepository.Spot>> children) {
        if (fits.containsKey(spot)) return fits.get(spot).contains(line);
        for (var child : children.getOrDefault(spot, List.of())) if (takes(child.id(), line, fits, children)) return true;
        return false;
    }

    /** Spots in tree order: each spot, then everything inside it, siblings in the store's order. */
    public static List<InventoryRepository.Spot> treeOrder(List<InventoryRepository.Spot> spots) {
        Map<UUID, List<InventoryRepository.Spot>> children = new HashMap<>();
        for (var spot : spots) children.computeIfAbsent(spot.parentId(), k -> new ArrayList<>()).add(spot);
        List<InventoryRepository.Spot> out = new ArrayList<>();
        walk(children, null, out);
        return out;
    }

    private static void walk(Map<UUID, List<InventoryRepository.Spot>> children, UUID parent, List<InventoryRepository.Spot> out) {
        for (var spot : children.getOrDefault(parent, List.of())) {
            out.add(spot);
            walk(children, spot.id(), out);
        }
    }

    /** Only known fields with values; checks they make a valid filter. */
    static Map<String, List<String>> clean(Map<String, List<String>> conditions) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (conditions != null) conditions.forEach((field, values) -> {
            if (!FIELDS.contains(field)) throw ApiException.badRequest("A rule can't test " + field);
            if (values == null) return;
            List<String> kept = values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
            if (kept.size() > 200) throw ApiException.badRequest("Too many values for " + field);
            if (!kept.isEmpty()) out.put(field, kept);
        });
        new InventoryQuery(UUID.randomUUID(), out).where();
        return out;
    }

    private Map<String, List<String>> parse(String text) {
        try {
            return json.readValue(text, CONDITIONS);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}

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
        var fits = fits(tenant, rules, spots, restrict);
        var children = children(spots);
        Set<UUID> lines = new HashSet<>();
        fits.values().forEach(lines::addAll);
        var room = new Room(tenant, spots, lines);
        Map<UUID, UUID> out = new HashMap<>();
        for (UUID line : room.order(lines)) {
            UUID at = null;
            for (List<InventoryRepository.Spot> level = children.getOrDefault(null, List.of()); ; ) {
                UUID next = room.pick(level.stream().filter(s -> takes(s.id(), line, fits, children)).toList(), line);
                if (next == null) break;
                at = next;
                level = children.getOrDefault(next, List.of());
            }
            if (at != null) { out.put(line, at); room.add(at, line); }
        }
        return out;
    }

    /**
     * Spot capacities while routing cards: a spot that fits but has no room for a whole line passes it to the next
     * sibling that fits, so boxes fill in order. When every fitting sibling is full, the card goes to the last one
     * and Storage shows it over capacity, rather than the card having nowhere to go. Stock already put away counts,
     * except the lines being routed, which count where they are sent.
     */
    private final class Room {
        final Map<UUID, Integer> capacity = new HashMap<>();
        final Map<UUID, Long> used = new HashMap<>();
        final Map<UUID, Integer> quantity = new HashMap<>();
        final Map<UUID, String> name = new HashMap<>();
        final Map<UUID, UUID> parents = new HashMap<>();

        Room(UUID tenant, List<InventoryRepository.Spot> spots, Set<UUID> lines) {
            jdbc.query("SELECT id, capacity FROM storage_spots WHERE tenant_id = ? AND capacity IS NOT NULL",
                    rs -> { capacity.put(rs.getObject(1, UUID.class), rs.getInt(2)); }, tenant);
            if (capacity.isEmpty() || lines.isEmpty()) return;
            for (var spot : spots) parents.put(spot.id(), spot.parentId());
            String[] ids = lines.stream().map(UUID::toString).toArray(String[]::new);
            jdbc.query("SELECT id, quantity, name FROM inventory_items WHERE id = ANY (?::uuid[])", rs -> {
                quantity.put(rs.getObject(1, UUID.class), rs.getInt(2));
                name.put(rs.getObject(1, UUID.class), rs.getString(3));
            }, (Object) ids);
            jdbc.query("SELECT storage_id, sum(quantity) FROM inventory_items WHERE tenant_id = ? AND storage_id IS NOT NULL"
                    + " AND NOT (id = ANY (?::uuid[])) GROUP BY storage_id", rs -> {
                for (UUID at = rs.getObject(1, UUID.class); at != null; at = parents.get(at)) used.merge(at, rs.getLong(2), Long::sum);
            }, tenant, ids);
        }

        /** Alphabetical, so a run of boxes fills A to Z; without capacities the order doesn't matter. */
        List<UUID> order(Set<UUID> lines) {
            if (capacity.isEmpty()) return new ArrayList<>(lines);
            return lines.stream().sorted(java.util.Comparator.comparing((UUID l) -> name.getOrDefault(l, "").toLowerCase())
                    .thenComparing(UUID::toString)).toList();
        }

        boolean fits(UUID spot, UUID line) {
            Integer cap = capacity.get(spot);
            return cap == null || used.getOrDefault(spot, 0L) + quantity.getOrDefault(line, 0) <= cap;
        }

        /** The first candidate with room, else the last one; null when there are none. */
        UUID pick(List<InventoryRepository.Spot> candidates, UUID line) {
            if (candidates.isEmpty()) return null;
            for (var spot : candidates) if (fits(spot.id(), line)) return spot.id();
            return candidates.getLast().id();
        }

        void add(UUID spot, UUID line) {
            if (capacity.isEmpty()) return;
            for (UUID at = spot; at != null; at = parents.get(at)) used.merge(at, (long) quantity.getOrDefault(line, 0), Long::sum);
        }
    }

    /** Which lines each rule fits, within its own location. */
    private Map<UUID, Set<UUID>> fits(UUID tenant, Map<UUID, Map<String, List<String>>> rules, List<InventoryRepository.Spot> spots,
                                      InventoryQuery.Where restrict) {
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
        return fits;
    }

    private static Map<UUID, List<InventoryRepository.Spot>> children(List<InventoryRepository.Spot> spots) {
        Map<UUID, List<InventoryRepository.Spot>> children = new HashMap<>();
        for (var spot : spots) children.computeIfAbsent(spot.parentId(), k -> new ArrayList<>()).add(spot);
        return children;
    }

    /**
     * One spot the rules looked at for a line. {@code outcome} is {@code fits} (its rule takes the card), {@code no}
     * (its rule doesn't), {@code full} (it fits but has no room left), {@code through} (no rule of its own, but a spot inside it takes the card), {@code empty} (no
     * rule, and nothing inside takes it) or {@code later} (not tried: an earlier spot at this level already won).
     */
    public record Step(UUID spotId, int level, String outcome, Map<String, List<String>> conditions) {}

    /** Why the rules send a line where they do: every spot looked at, level by level down the tree, in order. */
    public List<Step> explain(UUID tenant, UUID line, UUID locationId) {
        var rules = rules(tenant);
        var spots = inventory.spots(tenant).stream().filter(s -> s.locationId().equals(locationId)).toList();
        var fits = fits(tenant, rules, spots, new InventoryQuery.Where("i.id = ?", List.of(line)));
        var children = children(spots);
        var room = new Room(tenant, spots, Set.of(line));
        List<Step> out = new ArrayList<>();
        int depth = 0;
        for (List<InventoryRepository.Spot> level = children.getOrDefault(null, List.of()); !level.isEmpty(); depth++) {
            UUID next = room.pick(level.stream().filter(s -> takes(s.id(), line, fits, children)).toList(), line);
            boolean passed = false;
            for (var spot : level) {
                String outcome = passed ? "later"
                        : spot.id().equals(next) ? (fits.containsKey(spot.id()) ? "fits" : "through")
                        : takes(spot.id(), line, fits, children) ? "full"
                        : fits.containsKey(spot.id()) ? "no" : "empty";
                if (spot.id().equals(next)) passed = true;
                out.add(new Step(spot.id(), depth, outcome, rules.get(spot.id())));
            }
            if (next == null) break;
            level = children.getOrDefault(next, List.of());
        }
        return out;
    }

    /**
     * Put-away lines that sit outside the spot the rules would send them to (and everything inside it). Lines no rule
     * fits are left out: the rules have no opinion on them.
     */
    public List<UUID> misplaced(UUID tenant) {
        var destinations = destinations(tenant, new InventoryQuery.Where("i.storage_id IS NOT NULL", List.of()), null, null);
        if (destinations.isEmpty()) return List.of();
        Map<UUID, UUID> parents = new HashMap<>();
        for (var spot : inventory.spots(tenant)) parents.put(spot.id(), spot.parentId());
        List<UUID> out = new ArrayList<>();
        jdbc.query("SELECT id, storage_id FROM inventory_items WHERE tenant_id = ? AND storage_id IS NOT NULL", rs -> {
            UUID line = rs.getObject(1, UUID.class);
            UUID to = destinations.get(line);
            if (to == null) return;
            for (UUID at = rs.getObject(2, UUID.class); at != null; at = parents.get(at)) if (at.equals(to)) return;
            out.add(line);
        }, tenant);
        return out;
    }

    /**
     * Turns the {@code rules=misplaced} filter, which SQL alone can't answer, into the lines it picks, so lists,
     * filter counts and bulk actions all see the same lines.
     */
    public Map<String, List<String>> resolve(UUID tenant, Map<String, List<String>> filters) {
        if (filters == null || !List.of("misplaced").equals(filters.get("rules"))) return filters;
        Map<String, List<String>> out = new LinkedHashMap<>(filters);
        out.remove("rules");
        var ids = misplaced(tenant).stream().map(UUID::toString).toList();
        out.put("ids", ids.isEmpty() ? List.of(new UUID(0, 0).toString()) : ids);
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

    /** How many cards a spot holds, counting everything inside it; null for no limit. */
    public void setCapacity(UUID tenant, UUID spot, Integer capacity) {
        inventory.spot(tenant, spot);
        if (capacity != null && (capacity < 1 || capacity > 1_000_000)) throw ApiException.badRequest("A spot holds 1 to 1,000,000 cards");
        jdbc.update("UPDATE storage_spots SET capacity = ? WHERE id = ? AND tenant_id = ?", capacity, spot, tenant);
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

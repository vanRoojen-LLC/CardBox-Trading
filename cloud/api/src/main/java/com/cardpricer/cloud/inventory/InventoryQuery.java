package com.cardpricer.cloud.inventory;

import com.cardpricer.cloud.web.ApiException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the inventory filters people pick (by any card detail, where it is, where it came from) into SQL over
 * {@link #FROM}, so the list, its filter counts and bulk actions all select exactly the same lines.
 * Every value is a bind parameter; field and sort names come from fixed lists.
 */
public final class InventoryQuery {
    /** {@code i} is the line, {@code c} its card, {@code loc} its location, {@code cl} the CardBox collection it syncs from. */
    public static final String FROM = """
            FROM inventory_items i JOIN locations loc ON loc.id = i.location_id
            LEFT JOIN inventory_cards c ON c.id = i.card_id
            LEFT JOIN club_links cl ON cl.id = i.club_link_id""";
    public static final String MARKET = "CASE i.finish WHEN 'foil' THEN c.usd_foil WHEN 'etched' THEN c.usd_etched ELSE c.usd END";
    static final String YEAR = "extract(year FROM c.released_at)::int";
    static final String RARITY_RANK = "array_position(ARRAY['common','uncommon','rare','mythic','special','bonus'], i.rarity)";
    /** Main card types, in the order stores usually think of them. */
    static final List<String> TYPES = List.of("Creature", "Instant", "Sorcery", "Enchantment", "Artifact", "Planeswalker",
            "Land", "Battle", "Legendary", "Token");

    /** Every filter, in the order the page shows them. Multi-valued ones match any of their values. */
    public static final List<String> FACETS = List.of("game", "set", "year", "rarity", "color", "type", "finish", "treatment",
            "condition", "source");

    /** Sort keys; "; " separates the expressions of one key. */
    private static final Map<String, String> SORTS = Map.ofEntries(
            Map.entry("name", "lower(i.name)"),
            Map.entry("set", "lower(i.set_code)"),
            Map.entry("number", "nullif(regexp_replace(i.collector_number, '\\D', '', 'g'), '')::bigint"),
            Map.entry("year", YEAR),
            Map.entry("rarity", RARITY_RANK),
            Map.entry("color", "coalesce(array_to_string(c.colors, ''), '~')"),
            Map.entry("type", "lower(c.type_line)"),
            Map.entry("finish", "i.finish"),
            Map.entry("condition", "array_position(ARRAY['NM','LP','MP','HP','DMG'], i.condition)"),
            Map.entry("where", "loc.name; w.k"),
            Map.entry("market", MARKET),
            Map.entry("quantity", "i.quantity"),
            Map.entry("updated", "i.updated_at"));

    /** The spots of one store, each with a key that sorts them in tree order. Join as {@code w} on the line's spot. */
    public static final String TREE = """
            WITH RECURSIVE tree AS (
                SELECT id, lpad(position::text, 6, '0') || lower(label || ' ' || name) AS k FROM storage_spots
                WHERE tenant_id = ? AND parent_id IS NULL
                UNION ALL
                SELECT s.id, t.k || '/' || lpad(s.position::text, 6, '0') || lower(s.label || ' ' || s.name)
                FROM storage_spots s JOIN tree t ON s.parent_id = t.id)
            """;

    private final UUID tenant;
    private final Map<String, List<String>> filters;

    public InventoryQuery(UUID tenant, Map<String, List<String>> filters) {
        this.tenant = tenant;
        this.filters = new LinkedHashMap<>();
        if (filters != null) filters.forEach((k, v) -> {
            if (v == null) return;
            List<String> values = v.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
            if (!values.isEmpty()) this.filters.put(k, values);
        });
    }

    /** SQL condition and its arguments. */
    public record Where(String sql, List<Object> args) {}

    public Where where() {
        return where(null);
    }

    /** Every filter except {@code skip}: a filter's own counts show what picking another value would add. */
    public Where where(String skip) {
        List<Object> args = new ArrayList<>(List.of(tenant));
        StringBuilder sql = new StringBuilder("i.tenant_id = ?");
        for (var entry : filters.entrySet()) {
            String key = entry.getKey();
            if (key.equals(skip)) continue;
            List<String> v = entry.getValue();
            switch (key) {
                case "q" -> {
                    String term = v.getFirst().toLowerCase();
                    String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                    sql.append(" AND (lower(i.name) LIKE ? OR lower(i.set_code) = ? OR ltrim(i.collector_number, '0') = ltrim(?, '0')"
                            + " OR lower(c.set_name) LIKE ? OR lower(c.type_line) LIKE ?)");
                    args.addAll(List.of(like, term, term, like, like));
                }
                case "location" -> { sql.append(" AND i.location_id = ?"); args.add(uuid(v.getFirst(), "location")); }
                case "storage" -> {
                    if (v.getFirst().equals("none")) sql.append(" AND i.storage_id IS NULL");
                    else if (v.getFirst().equals("any")) sql.append(" AND i.storage_id IS NOT NULL");
                    else {
                        sql.append(" AND i.storage_id IN (WITH RECURSIVE sub AS (SELECT id FROM storage_spots WHERE id = ? AND tenant_id = ?"
                                + " UNION ALL SELECT s.id FROM storage_spots s JOIN sub ON s.parent_id = sub.id) SELECT id FROM sub)");
                        args.add(uuid(v.getFirst(), "storage spot"));
                        args.add(tenant);
                    }
                }
                case "game" -> in(sql, args, "c.game", v);
                case "set" -> in(sql, args, "upper(i.set_code)", v.stream().map(String::toUpperCase).toList());
                case "year" -> {
                    sql.append(" AND ").append(YEAR).append(" = ANY (?)");
                    args.add(v.stream().map(y -> integer(y, "year")).toArray(Integer[]::new));
                }
                case "rarity" -> in(sql, args, "i.rarity", v);
                case "finish" -> in(sql, args, "i.finish", v);
                case "condition" -> in(sql, args, "i.condition", v);
                case "color" -> {
                    // Any of the picked colors; C is colorless and M multicolored.
                    List<String> parts = new ArrayList<>();
                    List<String> colors = v.stream().filter(x -> "WUBRG".contains(x) && x.length() == 1).toList();
                    if (!colors.isEmpty()) { parts.add("c.colors && ?::text[]"); args.add(colors.toArray(String[]::new)); }
                    if (v.contains("C")) parts.add("cardinality(c.colors) = 0");
                    if (v.contains("M")) parts.add("cardinality(c.colors) > 1");
                    sql.append(parts.isEmpty() ? " AND false" : " AND (" + String.join(" OR ", parts) + ")");
                }
                case "type" -> {
                    List<String> parts = new ArrayList<>();
                    for (String t : v) {
                        if (!TYPES.contains(t)) throw ApiException.badRequest("Unknown card type " + t);
                        parts.add("c.type_line ~ ?");
                        args.add("\\m" + t + "\\M");
                    }
                    sql.append(" AND (").append(String.join(" OR ", parts)).append(")");
                }
                case "treatment" -> {
                    // "none" is a plain printing.
                    List<String> named = v.stream().filter(t -> !t.equals("none")).toList();
                    List<String> parts = new ArrayList<>();
                    if (!named.isEmpty()) { parts.add("c.treatments && ?::text[]"); args.add(named.toArray(String[]::new)); }
                    if (v.contains("none")) parts.add("coalesce(cardinality(c.treatments), 0) = 0");
                    sql.append(" AND (").append(String.join(" OR ", parts)).append(")");
                }
                case "source" -> {
                    // "store" is the store's own stock; anything else is a CardBox collection link id.
                    List<String> parts = new ArrayList<>();
                    List<String> links = v.stream().filter(s -> !s.equals("store")).map(s -> uuid(s, "collection").toString()).toList();
                    if (v.contains("store")) parts.add("i.club_link_id IS NULL");
                    if (!links.isEmpty()) { parts.add("i.club_link_id = ANY (?::uuid[])"); args.add(links.toArray(String[]::new)); }
                    sql.append(" AND (").append(String.join(" OR ", parts)).append(")");
                }
                case "priceMin" -> { sql.append(" AND ").append(MARKET).append(" >= ?"); args.add(price(v.getFirst())); }
                case "priceMax" -> { sql.append(" AND ").append(MARKET).append(" <= ?"); args.add(price(v.getFirst())); }
                // Names from one letter (or prefix) to another, both ends included: A to L takes "Lightning Bolt".
                case "nameFrom" -> { sql.append(" AND upper(i.name) >= upper(?)"); args.add(v.getFirst()); }
                case "nameTo" -> {
                    sql.append(" AND upper(left(i.name, length(?))) <= upper(?)");
                    args.add(v.getFirst());
                    args.add(v.getFirst());
                }
                default -> { } // sort, paging and anything unknown are not filters
            }
        }
        return new Where(sql.toString(), args);
    }

    /** ORDER BY for a sort key and direction; ties fall back to name, printing, finish and condition. */
    public static String orderBy(String sort, String dir) {
        String expr = SORTS.getOrDefault(sort == null ? "name" : sort, SORTS.get("name"));
        String direction = "desc".equalsIgnoreCase(dir) ? " DESC NULLS LAST" : " ASC NULLS FIRST";
        StringBuilder out = new StringBuilder();
        for (String part : expr.split("; ")) out.append(out.isEmpty() ? "" : ", ").append(part).append(direction);
        return out + ", lower(i.name), i.set_code, i.collector_number, i.finish, i.condition, i.id";
    }

    /** Values of one filter with their card counts, under every other filter. */
    public String facetSql(String facet) {
        return switch (facet) {
            case "game" -> "SELECT c.game AS value, NULL::text AS label, sum(i.quantity) AS cards " + FROM + " WHERE %s GROUP BY 1 ORDER BY 3 DESC";
            case "set" -> "SELECT upper(i.set_code) AS value, max(c.set_name) AS label, sum(i.quantity) AS cards " + FROM
                    + " WHERE %s GROUP BY 1 ORDER BY max(c.released_at) DESC NULLS LAST, 1";
            case "year" -> "SELECT " + YEAR + "::text AS value, NULL::text AS label, sum(i.quantity) AS cards " + FROM
                    + " WHERE %s AND c.released_at IS NOT NULL GROUP BY 1 ORDER BY 1 DESC";
            case "rarity" -> "SELECT i.rarity AS value, NULL::text AS label, sum(i.quantity) AS cards " + FROM
                    + " WHERE %s AND i.rarity <> '' GROUP BY 1 ORDER BY min(" + RARITY_RANK + ")";
            case "color" -> "SELECT v.value, NULL::text AS label, sum(v.quantity) AS cards FROM (SELECT unnest(CASE WHEN cardinality(c.colors) = 0"
                    + " THEN ARRAY['C'] WHEN cardinality(c.colors) > 1 THEN c.colors || 'M'::text ELSE c.colors END) AS value, i.quantity "
                    + FROM + " WHERE %s) v GROUP BY 1 ORDER BY array_position(ARRAY['W','U','B','R','G','M','C'], v.value)";
            case "type" -> "SELECT t.value, NULL::text AS label, sum(i.quantity) AS cards " + FROM
                    + " JOIN unnest(?::text[]) WITH ORDINALITY t(value, n) ON c.type_line ~ ('\\m' || t.value || '\\M')"
                    + " WHERE %s GROUP BY t.value, t.n ORDER BY t.n";
            case "finish" -> "SELECT i.finish AS value, NULL::text AS label, sum(i.quantity) AS cards " + FROM + " WHERE %s GROUP BY 1 ORDER BY 3 DESC";
            case "treatment" -> "SELECT v.value, NULL::text AS label, sum(v.quantity) AS cards FROM (SELECT unnest(CASE WHEN"
                    + " coalesce(cardinality(c.treatments), 0) = 0 THEN ARRAY['none'] ELSE c.treatments END) AS value, i.quantity "
                    + FROM + " WHERE %s) v GROUP BY 1 ORDER BY 3 DESC";
            case "condition" -> "SELECT i.condition AS value, NULL::text AS label, sum(i.quantity) AS cards " + FROM
                    + " WHERE %s GROUP BY 1 ORDER BY min(array_position(ARRAY['NM','LP','MP','HP','DMG'], i.condition))";
            case "source" -> "SELECT coalesce(i.club_link_id::text, 'store') AS value, max(cl.collection_name) AS label, sum(i.quantity) AS cards "
                    + FROM + " WHERE %s GROUP BY 1 ORDER BY (coalesce(i.club_link_id::text, 'store') <> 'store'), 2";
            default -> throw new IllegalArgumentException(facet);
        };
    }

    private static void in(StringBuilder sql, List<Object> args, String column, List<String> values) {
        sql.append(" AND ").append(column).append(" = ANY (?)");
        args.add(values.toArray(String[]::new));
    }

    private static UUID uuid(String value, String what) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown " + what);
        }
    }

    private static Integer integer(String value, String what) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Unknown " + what + " " + value);
        }
    }

    private static BigDecimal price(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Price must be a number");
        }
    }
}

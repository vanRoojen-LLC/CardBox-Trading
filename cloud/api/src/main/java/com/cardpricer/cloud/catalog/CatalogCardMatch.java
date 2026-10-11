package com.cardpricer.cloud.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Finds Trading's own card for a catalog printing: by Scryfall id for Magic, by swu-db card id for Star Wars:
 * Unlimited with the TCGplayer product as the fallback, matched on the finish. When the catalog holds neither id for an
 * SWU printing (its starwarsunlimited.com id is not swu-db's), the printed set, number and treatment find it, if exactly
 * one of Trading's cards has them. Club's market evidence, the catalog printing links and collection sync all match
 * this way, so a printing always lands on the same card.
 */
@Component
public class CatalogCardMatch {
    public static final String MAGIC = "magic-the-gathering";
    public static final String SWU = "star-wars-unlimited";

    /** The card and the external id that found it. */
    public record Match(UUID cardId, String matchedBy) {}

    private final JdbcTemplate jdbc;

    public CatalogCardMatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String finish(String treatment, boolean foil) {
        String t = treatment == null ? "" : treatment.toLowerCase();
        if (t.contains("etched")) return "etched";
        return foil ? "foil" : "normal";
    }

    /** Trading's card for this printing, or null when Trading doesn't carry it. */
    public Match find(String game, Map<String, Object> externalIds, boolean foil) {
        return find(game, externalIds, foil, null, null, null);
    }

    /** As {@link #find(String, Map, boolean)}, with the printed set, number and treatment as the last SWU fallback. */
    public Match find(String game, Map<String, Object> externalIds, boolean foil, String setCode, String collectorNumber,
                      String treatment) {
        if (MAGIC.equals(game)) return magic(externalIds);
        Match byId = swu(externalIds, foil);
        if (byId != null) return byId;
        UUID byNumber = swuByNumber(jdbc, setCode, collectorNumber, treatment);
        return byNumber == null ? null : new Match(byNumber, "number");
    }

    /**
     * The one SWU card with this set, printed number and treatment, or null when none or several have them. The
     * catalog prints the number as "001/267"; Trading keeps "001". Treatments compare as slugs ("Hyperspace Foil" is
     * hyperspace_foil), so an absent treatment matches nothing.
     */
    public static UUID swuByNumber(JdbcTemplate jdbc, String setCode, String collectorNumber, String treatment) {
        String set = setCode == null ? "" : setCode.trim().toUpperCase(Locale.ROOT);
        String number = printedNumber(collectorNumber), slug = slug(treatment);
        if (set.isEmpty() || number.isEmpty() || slug.isEmpty()) return null;
        List<UUID> found = jdbc.query("""
                SELECT id FROM swu_cards WHERE upper(set_code) = ? AND upper(collector_number) = ? AND treatment = ?
                LIMIT 2""", (rs, i) -> rs.getObject(1, UUID.class), set, number, slug);
        return found.size() == 1 ? found.getFirst() : null;
    }

    static String printedNumber(String collectorNumber) {
        String n = collectorNumber == null ? "" : collectorNumber.trim().toUpperCase(Locale.ROOT);
        int slash = n.indexOf('/');
        if (slash >= 0) n = n.substring(0, slash).trim();
        return n.matches("\\d{1,2}") ? "0".repeat(3 - n.length()) + n : n;
    }

    static String slug(String treatment) {
        return treatment == null ? ""
                : treatment.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private Match magic(Map<String, Object> ids) {
        String scryfall = id(ids, "scryfall:id");
        if (scryfall == null) scryfall = id(ids, "scryfall");
        UUID id;
        try {
            id = scryfall == null ? null : UUID.fromString(scryfall);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (id == null) return null;
        return jdbc.query("SELECT id FROM cards WHERE id = ?", (rs, i) -> new Match(rs.getObject(1, UUID.class), "scryfall"), id)
                .stream().findFirst().orElse(null);
    }

    private Match swu(Map<String, Object> ids, boolean foil) {
        String cid = id(ids, "swu-db:cid"), tcgplayer = id(ids, "tcgplayer");
        if (cid == null && tcgplayer == null) return null;
        // The swu-db card id names one variant; the TCGplayer product is the fallback, matched on the finish.
        return jdbc.query("""
                SELECT id, swu_cid FROM swu_cards
                WHERE (swu_cid = ? OR tcgplayer_id = ?) AND (treatment LIKE '%foil%') = ?
                ORDER BY (swu_cid IS NOT DISTINCT FROM ?) DESC LIMIT 1""",
                (rs, i) -> new Match(rs.getObject(1, UUID.class),
                        cid != null && cid.equals(rs.getString(2)) ? "swu-db" : "tcgplayer"),
                cid, tcgplayer, foil, cid).stream().findFirst().orElse(null);
    }

    private static String id(Map<String, Object> ids, String key) {
        Object v = ids == null ? null : ids.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() || s.length() > 100 ? null : s;
    }
}

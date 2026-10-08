package com.cardpricer.cloud.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Finds Trading's own card for a catalog printing: by Scryfall id for Magic, by swu-db card id for Star Wars:
 * Unlimited with the TCGplayer product as the fallback, matched on the finish. Club's market evidence and the
 * catalog printing links both match this way, so a printing always lands on the same card.
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
        return MAGIC.equals(game) ? magic(externalIds) : swu(externalIds, foil);
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

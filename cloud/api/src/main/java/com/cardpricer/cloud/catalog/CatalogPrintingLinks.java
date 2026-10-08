package com.cardpricer.cloud.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which catalog printing each of Trading's cards is. Club relays every printing of the games Trading sells from its
 * synced copy of CardBox's catalog, twice a day and in pages; each is matched to Trading's card the same way market
 * evidence is (CatalogCardMatch) and kept even when unmatched, so the gap between the two catalogs can be counted
 * before Trading stops importing its own. The last page of a push drops printings that push no longer carries.
 */
@Repository
public class CatalogPrintingLinks {
    public static final Set<String> GAMES = Set.of(CatalogCardMatch.MAGIC, CatalogCardMatch.SWU);

    /** What Club sends for one printing. */
    public record Printing(String printingId, String setCode, String collectorNumber, String treatment, String language,
                           boolean foil, Map<String, Object> externalIds) {}

    public record Result(int printings, int linked, int removed) {}

    private final JdbcTemplate jdbc;
    private final CatalogCardMatch match;
    private final ObjectMapper json = new ObjectMapper();

    public CatalogPrintingLinks(JdbcTemplate jdbc, CatalogCardMatch match) {
        this.jdbc = jdbc;
        this.match = match;
    }

    @Transactional
    public Result ingest(String game, boolean firstPage, boolean lastPage, List<Printing> printings) {
        if (firstPage) jdbc.update("""
                INSERT INTO catalog_printing_pushes (game, started_at) VALUES (?, now())
                ON CONFLICT (game) DO UPDATE SET started_at = now(), finished_at = NULL""", game);
        int linked = 0;
        for (Printing p : printings) {
            CatalogCardMatch.Match found = match.find(game, p.externalIds(), p.foil());
            if (found != null) linked++;
            jdbc.update("""
                    INSERT INTO catalog_printing_links (printing_id, game, card_id, finish, matched_by, set_code,
                                                        collector_number, treatment, language, external_ids, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
                    ON CONFLICT (printing_id) DO UPDATE SET game = EXCLUDED.game, card_id = EXCLUDED.card_id,
                        finish = EXCLUDED.finish, matched_by = EXCLUDED.matched_by, set_code = EXCLUDED.set_code,
                        collector_number = EXCLUDED.collector_number, treatment = EXCLUDED.treatment,
                        language = EXCLUDED.language, external_ids = EXCLUDED.external_ids,
                        received_at = EXCLUDED.received_at""",
                    p.printingId(), game, found == null ? null : found.cardId(),
                    CatalogCardMatch.finish(p.treatment(), p.foil()), found == null ? null : found.matchedBy(),
                    nz(p.setCode()), nz(p.collectorNumber()), nz(p.treatment()),
                    p.language() == null || p.language().isBlank() ? "en" : p.language(), ids(p.externalIds()));
        }
        int removed = 0;
        if (lastPage) {
            removed = jdbc.update("""
                    DELETE FROM catalog_printing_links l USING catalog_printing_pushes p
                    WHERE p.game = ? AND l.game = p.game AND l.received_at < p.started_at""", game);
            jdbc.update("UPDATE catalog_printing_pushes SET finished_at = now() WHERE game = ?", game);
        }
        return new Result(printings.size(), linked, removed);
    }

    /**
     * Per game: catalog printings received, how many found a Trading card and by which id, and how many of Trading's
     * own cards (with their finishes counted once) no catalog printing points at yet.
     */
    public Map<String, Object> coverage() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String game : List.of(CatalogCardMatch.MAGIC, CatalogCardMatch.SWU)) {
            Map<String, Object> g = new LinkedHashMap<>(jdbc.queryForMap("""
                    SELECT count(*) AS printings, count(card_id) AS linked, count(*) - count(card_id) AS unlinked,
                           count(*) FILTER (WHERE matched_by = 'scryfall') AS "byScryfall",
                           count(*) FILTER (WHERE matched_by = 'swu-db') AS "bySwuDb",
                           count(*) FILTER (WHERE matched_by = 'tcgplayer') AS "byTcgplayer",
                           max(received_at) AS "receivedAt"
                    FROM catalog_printing_links WHERE game = ?""", game));
            String table = CatalogCardMatch.MAGIC.equals(game) ? "cards" : "swu_cards";
            g.put("tradingCards", jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
            g.put("tradingCardsWithoutPrinting", jdbc.queryForObject("SELECT count(*) FROM " + table
                    + " c WHERE NOT EXISTS (SELECT 1 FROM catalog_printing_links l WHERE l.card_id = c.id)", Long.class));
            g.put("lastPush", jdbc.queryForList("SELECT started_at AS \"startedAt\", finished_at AS \"finishedAt\" "
                    + "FROM catalog_printing_pushes WHERE game = ?", game).stream().findFirst().orElse(null));
            out.put(game, g);
        }
        return out;
    }

    private String ids(Map<String, Object> ids) {
        try {
            return json.writeValueAsString(ids == null ? Map.of() : ids);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

package com.cardpricer.cloud.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CardBox Club's market evidence for Trading's cards: eBay sold and asking prices, research sales (PriceCharting) and
 * other independent sources, as per-source aggregates. Club reads them from its catalog and pushes them twice a day,
 * one game at a time and in pages; the last page of a push drops the rows that push no longer carries.
 * <p>
 * Club's printings are matched to Trading's cards by their Scryfall id (Magic) or swu-db card id, falling back to the
 * TCGplayer product (Star Wars: Unlimited). TCGplayer's own price, and copies of it, are left out: Trading already
 * holds it, and counting it twice would make one source look like two.
 */
@Repository
public class ClubMarketSummaries {
    public static final Set<String> GAMES = Set.of("magic-the-gathering", "star-wars-unlimited");
    static final Set<String> KINDS = Set.of("asking", "realized", "estimate");

    /** One source's aggregate for a card and finish. */
    public record Summary(String source, String valueKind, int observations, BigDecimal median, BigDecimal low,
                          BigDecimal high, Instant newest) {}

    /** What Club sends for one printing. */
    public record Printing(String printingId, Map<String, Object> externalIds, String treatment, boolean foil,
                           List<Summary> summaries) {}

    public record Result(int printings, int matched, int stored, int removed) {}

    private final JdbcTemplate jdbc;

    public ClubMarketSummaries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Result ingest(String game, boolean firstPage, boolean lastPage, List<Printing> printings) {
        if (firstPage) jdbc.update("""
                INSERT INTO club_market_pushes (game, started_at) VALUES (?, now())
                ON CONFLICT (game) DO UPDATE SET started_at = now(), finished_at = NULL""", game);
        int matched = 0, stored = 0;
        for (Printing p : printings) {
            String finish = finish(p);
            List<Summary> kept = p.summaries().stream().filter(s -> !"tcgplayer".equals(s.source())).toList();
            UUID card = game.equals("magic-the-gathering") ? magic(p) : swu(p);
            if (card == null) continue;
            matched++;
            for (Summary s : kept) {
                stored += jdbc.update("""
                        INSERT INTO club_market_summaries (card_id, finish, game, source, value_kind, observations, median,
                                                           low, high, newest, received_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                        ON CONFLICT (card_id, finish, source, value_kind) DO UPDATE SET observations = EXCLUDED.observations,
                            median = EXCLUDED.median, low = EXCLUDED.low, high = EXCLUDED.high, newest = EXCLUDED.newest,
                            game = EXCLUDED.game, received_at = EXCLUDED.received_at""",
                        card, finish, game, s.source(), s.valueKind(), s.observations(), s.median(), s.low(), s.high(),
                        Timestamp.from(s.newest()));
            }
        }
        int removed = 0;
        if (lastPage) {
            removed = jdbc.update("""
                    DELETE FROM club_market_summaries m USING club_market_pushes p
                    WHERE p.game = ? AND m.game = p.game AND m.received_at < p.started_at""", game);
            jdbc.update("UPDATE club_market_pushes SET finished_at = now() WHERE game = ?", game);
        }
        return new Result(printings.size(), matched, stored, removed);
    }

    /** Every stored summary for these cards, by card and finish. */
    public Map<PriceEvidence.Key, List<Summary>> forCards(List<UUID> ids) {
        Map<PriceEvidence.Key, List<Summary>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        UUID[] array = ids.toArray(UUID[]::new);
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT card_id, finish, source, value_kind, observations, median, low, high, newest
                    FROM club_market_summaries WHERE card_id = ANY(?) ORDER BY source, value_kind""");
            Array a = con.createArrayOf("uuid", array);
            ps.setArray(1, a);
            return ps;
        }, rs -> {
            out.computeIfAbsent(new PriceEvidence.Key(rs.getObject(1, UUID.class), rs.getString(2)), k -> new ArrayList<>())
                    .add(new Summary(rs.getString(3), rs.getString(4), rs.getInt(5), rs.getBigDecimal(6),
                            rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getTimestamp(9).toInstant()));
        });
        return out;
    }

    static String finish(Printing p) {
        String treatment = p.treatment() == null ? "" : p.treatment().toLowerCase();
        if (treatment.contains("etched")) return "etched";
        return p.foil() ? "foil" : "normal";
    }

    private UUID magic(Printing p) {
        String scryfall = id(p, "scryfall:id");
        if (scryfall == null) scryfall = id(p, "scryfall");
        UUID id;
        try {
            id = scryfall == null ? null : UUID.fromString(scryfall);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (id == null) return null;
        return jdbc.query("SELECT id FROM cards WHERE id = ?", (rs, i) -> rs.getObject(1, UUID.class), id)
                .stream().findFirst().orElse(null);
    }

    private UUID swu(Printing p) {
        String cid = id(p, "swu-db:cid"), tcgplayer = id(p, "tcgplayer");
        if (cid == null && tcgplayer == null) return null;
        // The swu-db card id names one variant; the TCGplayer product is the fallback, matched on the finish.
        return jdbc.query("""
                SELECT id FROM swu_cards
                WHERE (swu_cid = ? OR tcgplayer_id = ?) AND (treatment LIKE '%foil%') = ?
                ORDER BY (swu_cid IS NOT DISTINCT FROM ?) DESC LIMIT 1""",
                (rs, i) -> rs.getObject(1, UUID.class), cid, tcgplayer, p.foil(), cid).stream().findFirst().orElse(null);
    }

    private static String id(Printing p, String key) {
        Object v = p.externalIds() == null ? null : p.externalIds().get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() || s.length() > 100 ? null : s;
    }

}

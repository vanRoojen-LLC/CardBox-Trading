package com.cardpricer.cloud.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every price datapoint Trading holds for a card and finish, with its source and age, the recorded history, and the
 * {@link Confidence} they add up to. The market price it reports is the one quotes use, so the panel and the offer
 * never disagree.
 */
@Service
public class PriceEvidence {
    /** One datapoint. {@code used} marks the price offers are worked out from. */
    public record Point(String source, String label, BigDecimal value, String currency, Instant observedAt, boolean used) {}

    public record Day(LocalDate day, BigDecimal market) {}

    public record Evidence(UUID cardId, String game, String finish, BigDecimal market, String url, List<Point> points,
                           List<Day> history, Confidence.Level confidence, List<Confidence.Reason> reasons,
                           List<String> missing) {}

    public record Key(UUID cardId, String finish) {}

    static final String SINCE = "7 October 2026";
    private static final List<String> MISSING = List.of(
            "Listing count, sellers and the listings themselves (price, shipping, seller rating): only the official "
                    + "TCGplayer API, which needs a partner key, or crawling TCGplayer's site gives these.",
            "TCGplayer's sales history and its 3-month low, high and number sold: the TCGplayer API only. Trading "
                    + "records its own nightly price from " + SINCE + ".",
            "eBay and other sold prices: CardBox Club collects these for cards in Club collections; Trading doesn't "
                    + "read them yet.");
    private static final String MAGIC_LISTINGS = "Lowest, median and highest TCGplayer listing: Scryfall doesn't "
            + "publish them for Magic. TCGCSV does, and is the next source to add.";

    private sealed interface Raw permits Magic, Swu {}

    private record Magic(BigDecimal usd, BigDecimal usdFoil, BigDecimal usdEtched, BigDecimal eur, BigDecimal eurFoil,
                         String tcgplayerId, Instant updatedAt) implements Raw {
        BigDecimal market(String finish) {
            return switch (finish) {
                case "normal" -> usd;
                case "foil" -> usdFoil;
                case "etched" -> usdEtched;
                default -> null;
            };
        }

        BigDecimal eur(String finish) {
            return switch (finish) {
                case "normal" -> eur;
                case "foil" -> eurFoil;
                default -> null;
            };
        }
    }

    private record Swu(boolean foil, BigDecimal tcgMarket, BigDecimal tcgLow, BigDecimal tcgMid, BigDecimal tcgHigh,
                       BigDecimal tcgDirectLow, Instant tcgObservedAt, BigDecimal swuMarket, BigDecimal swuLow,
                       Instant swuObservedAt, String tcgplayerId) implements Raw {
        /** The same choice as inventory_cards (V23): TCGCSV unless it is missing or three days behind swu-db. */
        boolean tcgCurrent() {
            return tcgMarket != null && (swuObservedAt == null || tcgObservedAt == null
                    || tcgObservedAt.isAfter(swuObservedAt.minus(Duration.ofDays(3))));
        }
    }

    private final JdbcTemplate jdbc;

    public PriceEvidence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The evidence for one card and finish, or null for a card Trading has no prices for (a Club-only card). */
    public Evidence evidence(UUID cardId, String finish, Instant now) {
        Raw raw = load(List.of(cardId)).get(cardId);
        if (raw == null) return null;
        List<Day> history = history(List.of(cardId), 90).getOrDefault(new Key(cardId, finish), List.of());
        return build(cardId, finish, raw, history, now);
    }

    /** Confidence for many lines at once: three queries, however many lines. Unknown cards are left out. */
    public Map<Key, Evidence> evidence(Collection<Key> keys, Instant now) {
        List<UUID> ids = keys.stream().map(Key::cardId).distinct().toList();
        Map<UUID, Raw> raw = load(ids);
        Map<Key, List<Day>> history = history(ids, 30);
        Map<Key, Evidence> out = new HashMap<>();
        for (Key key : keys) {
            Raw r = raw.get(key.cardId());
            if (r != null) out.put(key, build(key.cardId(), key.finish(), r, history.getOrDefault(key, List.of()), now));
        }
        return out;
    }

    private Evidence build(UUID id, String finish, Raw raw, List<Day> history, Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(30));
        List<BigDecimal> recent = history.stream()
                .filter(d -> !d.day().atStartOfDay().toInstant(java.time.ZoneOffset.UTC).isBefore(cutoff))
                .map(Day::market).toList();
        List<Point> points = new ArrayList<>();
        List<String> missing = new ArrayList<>(MISSING);
        Confidence.Inputs inputs;
        String game, url;
        BigDecimal market;
        if (raw instanceof Magic m) {
            game = "magic-the-gathering";
            market = m.market(finish);
            points.add(new Point("Scryfall", "TCGplayer market", market, "USD", m.updatedAt(), market != null));
            BigDecimal eur = m.eur(finish);
            if (eur != null) points.add(new Point("Scryfall", "Cardmarket trend", eur, "EUR", m.updatedAt(), false));
            missing.addFirst(MAGIC_LISTINGS);
            inputs = new Confidence.Inputs(market, m.updatedAt(), null, null, null, null, recent);
            url = m.tcgplayerId();
        } else {
            Swu s = (Swu) raw;
            game = "star-wars-unlimited";
            boolean matches = s.foil() == finish.equals("foil");
            boolean tcg = s.tcgCurrent();
            market = !matches ? null : tcg ? s.tcgMarket() : s.swuMarket();
            if (matches) {
                points.add(new Point("TCGCSV", "TCGplayer market", s.tcgMarket(), "USD", s.tcgObservedAt(), tcg));
                points.add(new Point("TCGCSV", "Lowest listing", s.tcgLow(), "USD", s.tcgObservedAt(), false));
                points.add(new Point("TCGCSV", "Median listing", s.tcgMid(), "USD", s.tcgObservedAt(), false));
                points.add(new Point("TCGCSV", "Highest listing", s.tcgHigh(), "USD", s.tcgObservedAt(), false));
                points.add(new Point("TCGCSV", "Lowest TCGplayer Direct", s.tcgDirectLow(), "USD", s.tcgObservedAt(), false));
                points.add(new Point("swu-db", "TCGplayer market, swu-db's copy", s.swuMarket(), "USD", s.swuObservedAt(), !tcg));
                points.add(new Point("swu-db", "Lowest listing, swu-db's copy", s.swuLow(), "USD", s.swuObservedAt(), false));
                points.removeIf(p -> p.value() == null && !p.used());
            }
            inputs = tcg
                    ? new Confidence.Inputs(market, s.tcgObservedAt(), "swu-db's copy", s.swuMarket(), s.tcgLow(), s.tcgMid(), recent)
                    : new Confidence.Inputs(market, s.swuObservedAt(), "TCGCSV", s.tcgMarket(), s.swuLow(), null, recent);
            url = s.tcgplayerId();
        }
        Confidence.Result result = Confidence.assess(inputs, now);
        return new Evidence(id, game, finish, market, url == null ? null : "https://www.tcgplayer.com/product/" + url,
                points, history, result.level(), result.reasons(), missing);
    }

    private Map<UUID, Raw> load(List<UUID> ids) {
        Map<UUID, Raw> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        UUID[] array = ids.toArray(UUID[]::new);
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id, usd, usd_foil, usd_etched, eur, eur_foil, tcgplayer_id, updated_at"
                    + " FROM cards WHERE id = ANY(?)");
            ps.setArray(1, con.createArrayOf("uuid", array));
            return ps;
        }, rs -> {
            out.put(rs.getObject(1, UUID.class), new Magic(rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                    rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7), instant(rs.getTimestamp(8))));
        });
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT id, treatment LIKE '%foil%', tcgplayer_market, tcgplayer_low, tcgplayer_mid, tcgplayer_high,
                           tcgplayer_direct_low, tcgplayer_observed_at, market, low, price_observed_at, tcgplayer_id
                    FROM swu_cards WHERE id = ANY(?)""");
            ps.setArray(1, con.createArrayOf("uuid", array));
            return ps;
        }, rs -> {
            out.put(rs.getObject(1, UUID.class), new Swu(rs.getBoolean(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                    rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), instant(rs.getTimestamp(8)),
                    rs.getBigDecimal(9), rs.getBigDecimal(10), instant(rs.getTimestamp(11)), rs.getString(12)));
        });
        return out;
    }

    /** TCGplayer's recorded market price per card and finish over the last {@code days}, oldest first. */
    private Map<Key, List<Day>> history(List<UUID> ids, int days) {
        Map<Key, List<Day>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        UUID[] array = ids.toArray(UUID[]::new);
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT card_id, finish, day, market FROM price_history
                    WHERE card_id = ANY(?) AND source = 'tcgplayer' AND day > current_date - ? ORDER BY day""");
            Array a = con.createArrayOf("uuid", array);
            ps.setArray(1, a);
            ps.setInt(2, days);
            return ps;
        }, rs -> {
            out.computeIfAbsent(new Key(rs.getObject(1, UUID.class), rs.getString(2)), k -> new ArrayList<>())
                    .add(new Day(rs.getDate(3).toLocalDate(), rs.getBigDecimal(4)));
        });
        return out;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

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
 * {@link Confidence} they add up to. TCGplayer's market price is the anchor; CardBox Club's eBay sold prices and other
 * independent origins join it in a {@link Blend}, and that blended value is the one quotes use, so the panel and the
 * offer never disagree.
 */
@Service
public class PriceEvidence {
    /**
     * One datapoint. {@code used} marks the prices the trade value is blended from, and {@code weight} is each one's
     * share of it; {@code observations} is how many sales or listings an aggregate rests on.
     */
    public record Point(String source, String label, BigDecimal value, String currency, Instant observedAt, boolean used,
                        Double weight, Integer observations) {
        Point(String source, String label, BigDecimal value, String currency, Instant observedAt, boolean used) {
            this(source, label, value, currency, observedAt, used, null, null);
        }

        Point weighted(Double share) {
            return new Point(source, label, value, currency, observedAt, share != null, share, observations);
        }
    }

    public record Day(LocalDate day, BigDecimal market) {}

    /** {@code market} is the blended trade value; {@code tcgplayer} is TCGplayer's market price alone. */
    public record Evidence(UUID cardId, String game, String finish, BigDecimal market, BigDecimal tcgplayer, String url,
                           List<Point> points, List<Day> history, Confidence.Level confidence,
                           List<Confidence.Reason> reasons, List<String> missing, int sources) {}

    public record Key(UUID cardId, String finish) {}

    static final String SINCE = "7 October 2026";
    private static final List<String> MISSING = List.of(
            "Listing count, sellers and the listings themselves (price, shipping, seller rating): only the official "
                    + "TCGplayer API, which needs a partner key, or crawling TCGplayer's site gives these.",
            "TCGplayer's sales history and its 3-month low, high and number sold: the TCGplayer API only. Trading "
                    + "records its own nightly price from " + SINCE + ".",
            "Live eBay listings and individual sales: CardBox Club sends Trading only their counts and medians.");
    private static final String NO_CLUB = "eBay sold prices: CardBox Club has none for this card yet. It collects "
            + "sales for cards in Club collections, and eBay asking prices for releases its collectors own.";

    /** How much one origin's price counts in the blend, against TCGplayer's 1. */
    static final Map<String, Double> REALIZED_WEIGHT = Map.of("ebay", 0.9, "pricecharting", 0.6);
    static final double OTHER_REALIZED_WEIGHT = 0.6, ESTIMATE_WEIGHT = 0.4, HISTORY_WEIGHT = 0.8;
    /** An aggregate of this many sales counts in full; fewer count in proportion. */
    static final int FULL_WEIGHT_SALES = 5;
    private static final Map<String, String> NAMES = Map.of("ebay", "eBay", "pricecharting", "PriceCharting",
            "cardmarket", "Cardmarket", "manapool", "Manapool", "cardtrader", "CardTrader", "other", "Other shops");
    private static final String MAGIC_LISTINGS = "Lowest, median and highest TCGplayer listing: Scryfall doesn't "
            + "publish them for Magic. TCGCSV does, and is the next source to add.";

    private sealed interface Raw permits Magic, Swu, Tcg {}

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
                       Instant swuObservedAt, String tcgplayerId, String tcgSource) implements Raw {
        /** The mirror that served TCGplayer's price: TCGTracking, or TCGCSV as the fallback. */
        String tcgLabel() {
            return SwuTcgplayerPrices.TCGTRACKING.equals(tcgSource) ? "TCGTracking" : "TCGCSV";
        }

        /** The same choice as inventory_cards (V23): TCGCSV unless it is missing or three days behind swu-db. */
        boolean tcgCurrent() {
            return tcgMarket != null && (swuObservedAt == null || tcgObservedAt == null
                    || tcgObservedAt.isAfter(swuObservedAt.minus(Duration.ofDays(3))));
        }
    }

    /** A TCGTracking product in one subtype: TCGplayer's market and lowest listing, and nothing to check them against. */
    private record Tcg(String game, boolean foil, BigDecimal market, BigDecimal low, Instant observedAt, int productId)
            implements Raw {}

    /** TCGplayer's price for one condition in English, with its active listing count, from TCGTracking's SKU files. */
    record Sku(String condition, BigDecimal market, BigDecimal low, Integer listings, Instant observedAt) {}

    /** Conditions in the order the panel lists them. */
    static final List<String> CONDITIONS = List.of("NM", "LP", "MP", "HP", "DMG");
    private static final Map<String, String> CONDITION_NAMES = Map.of("NM", "Near Mint", "LP", "Lightly Played",
            "MP", "Moderately Played", "HP", "Heavily Played", "DMG", "Damaged");
    /** Fewer near-mint listings than this is a thin market: the price can move on one sale. */
    static final int THIN_LISTINGS = 3;
    private static final String LISTINGS_KNOWN = "Sellers and the listings themselves (price, shipping, seller rating): "
            + "only the official TCGplayer API, which needs a partner key, or crawling TCGplayer's site gives these.";

    /** Another origin's latest price from price_history, such as Manapool's through TCG Tracking. */
    private record Recorded(String source, BigDecimal market, LocalDate day) {}

    private record Extra(List<ClubMarketSummaries.Summary> club, List<Recorded> recorded, List<Sku> skus) {
        static final Extra NONE = new Extra(List.of(), List.of(), List.of());

        Extra(List<ClubMarketSummaries.Summary> club, List<Recorded> recorded) {
            this(club, recorded, new ArrayList<>());
        }
    }

    private final JdbcTemplate jdbc;
    private final ClubMarketSummaries club;

    public PriceEvidence(JdbcTemplate jdbc, ClubMarketSummaries club) {
        this.jdbc = jdbc;
        this.club = club;
    }

    /** The evidence for one card and finish, or null for a card Trading has no prices for (a Club-only card). */
    public Evidence evidence(UUID cardId, String finish, Instant now) {
        Raw raw = load(List.of(cardId)).get(cardId);
        if (raw == null) return null;
        Key key = new Key(cardId, finish);
        List<Day> history = history(List.of(cardId), 90).getOrDefault(key, List.of());
        return build(cardId, finish, raw, history, extras(List.of(cardId)).getOrDefault(key, Extra.NONE), now);
    }

    /** Confidence for many lines at once: five queries, however many lines. Unknown cards are left out. */
    public Map<Key, Evidence> evidence(Collection<Key> keys, Instant now) {
        List<UUID> ids = keys.stream().map(Key::cardId).distinct().toList();
        Map<UUID, Raw> raw = load(ids);
        Map<Key, List<Day>> history = history(ids, 30);
        Map<Key, Extra> extras = extras(ids);
        Map<Key, Evidence> out = new HashMap<>();
        for (Key key : keys) {
            Raw r = raw.get(key.cardId());
            if (r != null) out.put(key, build(key.cardId(), key.finish(), r, history.getOrDefault(key, List.of()),
                    extras.getOrDefault(key, Extra.NONE), now));
        }
        return out;
    }

    private Evidence build(UUID id, String finish, Raw raw, List<Day> history, Extra extra, Instant now) {
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
        } else if (raw instanceof Tcg t) {
            game = t.game();
            market = t.foil() == finish.equals("foil") ? t.market() : null;
            if (market != null) {
                points.add(new Point("TCGTracking", "TCGplayer market", market, "USD", t.observedAt(), true));
                if (t.low() != null) points.add(new Point("TCGTracking", "Lowest listing", t.low(), "USD", t.observedAt(), false));
            }
            inputs = new Confidence.Inputs(market, t.observedAt(), null, null, market == null ? null : t.low(), null, recent);
            url = String.valueOf(t.productId());
        } else {
            Swu s = (Swu) raw;
            game = "star-wars-unlimited";
            boolean matches = s.foil() == finish.equals("foil");
            boolean tcg = s.tcgCurrent();
            market = !matches ? null : tcg ? s.tcgMarket() : s.swuMarket();
            if (matches) {
                String mirror = s.tcgLabel();
                points.add(new Point(mirror, "TCGplayer market", s.tcgMarket(), "USD", s.tcgObservedAt(), tcg));
                points.add(new Point(mirror, "Lowest listing", s.tcgLow(), "USD", s.tcgObservedAt(), false));
                points.add(new Point(mirror, "Median listing", s.tcgMid(), "USD", s.tcgObservedAt(), false));
                points.add(new Point(mirror, "Highest listing", s.tcgHigh(), "USD", s.tcgObservedAt(), false));
                points.add(new Point(mirror, "Lowest TCGplayer Direct", s.tcgDirectLow(), "USD", s.tcgObservedAt(), false));
                points.add(new Point("swu-db", "TCGplayer market, swu-db's copy", s.swuMarket(), "USD", s.swuObservedAt(), !tcg));
                points.add(new Point("swu-db", "Lowest listing, swu-db's copy", s.swuLow(), "USD", s.swuObservedAt(), false));
                points.removeIf(p -> p.value() == null && !p.used());
            }
            inputs = tcg
                    ? new Confidence.Inputs(market, s.tcgObservedAt(), "swu-db's copy", s.swuMarket(), s.tcgLow(), s.tcgMid(), recent)
                    : new Confidence.Inputs(market, s.swuObservedAt(), s.tcgLabel(), s.tcgMarket(), s.swuLow(), null, recent);
            url = s.tcgplayerId();
        }
        // The blend: TCGplayer's price, then every independent origin Trading holds for this card and finish.
        List<Blend.Input> blendInputs = new ArrayList<>();
        List<Point> extraPoints = new ArrayList<>();
        List<Confidence.Check> checks = new ArrayList<>();
        if (market != null) {
            blendInputs.add(new Blend.Input("tcgplayer", market, 1.0));
            for (Recorded r : extra.recorded()) {
                String origin = Blend.origin(r.source());
                if (origin.equals("tcgplayer") || origin.equals("cardmarket")) continue;
                Instant seen = r.day().atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
                String via = r.source().contains(" via ") ? r.source().substring(r.source().indexOf(" via ") + 5) : origin;
                extraPoints.add(new Point(name(via), name(origin) + " market", r.market(), "USD", seen, false));
                blendInputs.add(new Blend.Input(origin + "#" + (extraPoints.size() - 1), r.market(), HISTORY_WEIGHT));
                checks.add(new Confidence.Check(name(origin), r.market(), null));
            }
            for (ClubMarketSummaries.Summary s : extra.club()) {
                double weight = weight(s, now);
                extraPoints.add(new Point("CardBox Club", name(s.source()) + " " + kind(s.valueKind()) + ", median of "
                        + s.observations(), s.median(), "USD", s.newest(), false, null, s.observations()));
                blendInputs.add(new Blend.Input(s.source() + "#" + (extraPoints.size() - 1), s.median(), weight));
                if (s.valueKind().equals("realized"))
                    checks.add(new Confidence.Check(name(s.source()) + " sold", s.median(), s.observations()));
            }
        }
        List<Point> skuPoints = new ArrayList<>();
        Integer nearMintListings = null;
        for (Sku k : extra.skus().stream().sorted(java.util.Comparator.comparingInt(k -> CONDITIONS.indexOf(k.condition())))
                .toList()) {
            if (k.market() != null) skuPoints.add(new Point("TCGTracking", "TCGplayer market, "
                    + CONDITION_NAMES.getOrDefault(k.condition(), k.condition()) + (k.listings() == null ? ""
                    : " (" + k.listings() + " listing" + (k.listings() == 1 ? "" : "s") + ")"), k.market(), "USD",
                    k.observedAt(), false, null, k.listings()));
            if (k.condition().equals("NM")) nearMintListings = k.listings();
        }
        if (!skuPoints.isEmpty()) missing.replaceAll(m -> m.equals(MISSING.getFirst()) ? LISTINGS_KNOWN : m);
        Blend.Result blend = collapse(blendInputs);
        List<Point> weighted = new ArrayList<>();
        for (Point p : points) weighted.add(p.used() ? p.weighted(blend.weights().getOrDefault("tcgplayer", 1.0)) : p);
        for (int i = 0; i < extraPoints.size(); i++) weighted.add(extraPoints.get(i).weighted(blend.weights().get("#" + i)));
        weighted.addAll(skuPoints);
        if (extra.club().stream().noneMatch(s -> s.valueKind().equals("realized"))) missing.add(NO_CLUB);
        inputs = new Confidence.Inputs(inputs.market(), inputs.observedAt(), inputs.otherSource(), inputs.other(),
                inputs.low(), inputs.mid(), inputs.history(), checks);
        Confidence.Result result = Confidence.assess(inputs, now);
        List<Confidence.Reason> reasons = new ArrayList<>(result.reasons());
        BigDecimal value = blend.value() == null ? market : blend.value();
        if (market != null && nearMintListings != null && nearMintListings < THIN_LISTINGS)
            reasons.add(new Confidence.Reason(Confidence.Kind.warn, nearMintListings == 0
                    ? "No near-mint listings on TCGplayer: the price rests on past sales only."
                    : "Thin market: " + nearMintListings + " near-mint listing" + (nearMintListings == 1 ? "" : "s")
                    + " on TCGplayer, so one sale can move the price."));
        int sources = blend.weights().size();
        if (market != null && value.compareTo(market) != 0)
            reasons.add(new Confidence.Reason(Confidence.Kind.info, "Trade value " + money(value) + " blends " + sources
                    + " sources; TCGplayer alone says " + money(market) + "."));
        return new Evidence(id, game, finish, value, market, url == null ? null : "https://www.tcgplayer.com/product/" + url,
                weighted, history, result.level(), reasons, missing, Math.max(sources, market == null ? 0 : 1));
    }

    /**
     * Blends with each extra datapoint under its own key, so the panel can show each one's share, while copies of one
     * origin still collapse: the heaviest copy keeps the origin's place and the rest get no share.
     */
    private static Blend.Result collapse(List<Blend.Input> inputs) {
        Map<String, Blend.Input> heaviest = new HashMap<>();
        for (Blend.Input in : inputs) {
            String origin = in.origin().split("#")[0];
            if (in.value() == null || in.weight() <= 0) continue;
            heaviest.merge(origin, in, (a, b) -> b.weight() > a.weight() ? b : a);
        }
        Blend.Result byOrigin = Blend.of(List.copyOf(heaviest.values().stream()
                .map(in -> new Blend.Input(in.origin().split("#")[0], in.value(), in.weight())).toList()));
        Map<String, Double> shares = new HashMap<>();
        for (Blend.Input in : heaviest.values()) {
            String key = in.origin().contains("#") ? "#" + in.origin().split("#")[1] : in.origin();
            shares.put(key, byOrigin.weights().get(in.origin().split("#")[0]));
        }
        return new Blend.Result(byOrigin.value(), shares);
    }

    /** A Club aggregate's weight: by origin and kind, scaled by how many sales it rests on and how recent they are. */
    static double weight(ClubMarketSummaries.Summary s, Instant now) {
        double base = switch (s.valueKind()) {
            case "realized" -> REALIZED_WEIGHT.getOrDefault(s.source(), OTHER_REALIZED_WEIGHT);
            case "estimate" -> ESTIMATE_WEIGHT;
            default -> 0; // Asking prices are what sellers hope for: shown, never blended.
        };
        double volume = s.valueKind().equals("realized") ? Math.min(1.0, s.observations() / (double) FULL_WEIGHT_SALES) : 1.0;
        double fresh = s.newest().isAfter(now.minus(Duration.ofDays(30))) ? 1.0 : 0.5;
        return base * volume * fresh;
    }

    private static String name(String origin) {
        return NAMES.getOrDefault(origin, origin.equals("tcgtracking") ? "TCG Tracking"
                : origin.isEmpty() ? origin : Character.toUpperCase(origin.charAt(0)) + origin.substring(1));
    }

    private static String kind(String valueKind) {
        return switch (valueKind) {
            case "realized" -> "sold";
            case "asking" -> "asking";
            default -> "estimate";
        };
    }

    private static String money(BigDecimal value) {
        return "$" + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** Club's summaries and other origins' latest recorded prices (within three days) for these cards. */
    private Map<Key, Extra> extras(List<UUID> ids) {
        Map<Key, Extra> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        club.forCards(ids).forEach((key, list) -> out.put(key, new Extra(list, new ArrayList<>())));
        UUID[] array = ids.toArray(UUID[]::new);
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT DISTINCT ON (card_id, finish, source) card_id, finish, source, market, day FROM price_history
                    WHERE card_id = ANY(?) AND source NOT IN ('tcgplayer', 'swu-db', 'cardmarket')
                      AND day >= current_date - 3
                    ORDER BY card_id, finish, source, day DESC""");
            ps.setArray(1, con.createArrayOf("uuid", array));
            return ps;
        }, rs -> {
            Key key = new Key(rs.getObject(1, UUID.class), rs.getString(2));
            Extra e = out.computeIfAbsent(key, k -> new Extra(List.of(), new ArrayList<>()));
            e.recorded().add(new Recorded(rs.getString(3), rs.getBigDecimal(4), rs.getDate(5).toLocalDate()));
        });
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT DISTINCT ON (card_id, finish, condition) card_id, finish, condition, market, low, listings,
                           observed_at
                    FROM tcg_sku_cards WHERE card_id = ANY(?) AND language = 'EN' AND observed_at > now() - interval '3 days'
                    ORDER BY card_id, finish, condition, observed_at DESC""");
            ps.setArray(1, con.createArrayOf("uuid", array));
            return ps;
        }, rs -> {
            Key key = new Key(rs.getObject(1, UUID.class), rs.getString(2));
            Extra e = out.computeIfAbsent(key, k -> new Extra(List.of(), new ArrayList<>()));
            int listings = rs.getInt(6);
            e.skus().add(new Sku(rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.wasNull() ? null : listings,
                    instant(rs.getTimestamp(7))));
        });
        return out;
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
                           tcgplayer_direct_low, tcgplayer_observed_at, market, low, price_observed_at, tcgplayer_id,
                           tcgplayer_source
                    FROM swu_cards WHERE id = ANY(?)""");
            ps.setArray(1, con.createArrayOf("uuid", array));
            return ps;
        }, rs -> {
            out.put(rs.getObject(1, UUID.class), new Swu(rs.getBoolean(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                    rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), instant(rs.getTimestamp(8)),
                    rs.getBigDecimal(9), rs.getBigDecimal(10), instant(rs.getTimestamp(11)), rs.getString(12),
                    rs.getString(13)));
        });
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT p.id, g.segment, p.sub_type ILIKE '%foil%', p.market, p.low, p.observed_at, p.product_id
                    FROM tcg_products p JOIN tcg_games g ON g.category_id = p.category_id WHERE p.id = ANY(?)""");
            ps.setArray(1, con.createArrayOf("uuid", array));
            return ps;
        }, rs -> {
            out.put(rs.getObject(1, UUID.class), new Tcg(rs.getString(2), rs.getBoolean(3), rs.getBigDecimal(4),
                    rs.getBigDecimal(5), instant(rs.getTimestamp(6)), rs.getInt(7)));
        });
        return out;
    }

    /**
     * TCGplayer's recorded market price per card and finish over the last {@code days}, oldest first, whichever mirror
     * served it (an SWU night that fell back to TCGCSV records under 'tcgplayer', a TCGTracking night under
     * 'tcgplayer via tcgtracking'; the same price either way).
     */
    private Map<Key, List<Day>> history(List<UUID> ids, int days) {
        Map<Key, List<Day>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        UUID[] array = ids.toArray(UUID[]::new);
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT card_id, finish, day, market FROM price_history
                    WHERE card_id = ANY(?) AND source IN ('tcgplayer', 'tcgplayer via tcgtracking')
                      AND day > current_date - ? ORDER BY day""");
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

package com.cardpricer.cloud.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CatalogRepository {
    private static final String COLUMNS =
            "id, name, set_code, set_name, collector_number, rarity, lang, usd, usd_foil, usd_etched, image_small";
    private static final RowMapper<CardRow> ROW = (rs, i) -> new CardRow(rs.getObject(1, UUID.class), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11));

    private static final int MAX_WORDS = 6;
    private static final String PRICED = " AND (usd IS NOT NULL OR usd_foil IS NOT NULL OR usd_etched IS NOT NULL)";

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Free-text search over a printing's name, set, collector number and card text (type line, rules text, flavor
     * text, artist). Every word must match one of them, so "bolt", "391", "0391", "DMU 391", "dmu #391",
     * "lightning bolt 2x2", "dominaria 391" and "bolt 3 damage" all work; collector numbers ignore leading zeros
     * the way they are printed on the card (and an O typed for the zero), a lone letter means the rarity printed
     * beside the number ("C 0116 SPM"), and a language code ("en") matches the printing's language. Ranking puts exact and prefix name matches first, then printings whose
     * name, set and number alone match every word, then card-text matches, newest printings first within each.
     * When nothing matches, falls back to a typo-tolerant name match ("lightnig bolt"). An optional set code
     * narrows the result further.
     */
    public List<CardRow> search(String query, String set, int limit) {
        String q = query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> words = Arrays.stream(q.split(" "))
                .map(w -> w.startsWith("#") ? w.substring(1) : w)
                .filter(w -> w.codePoints().anyMatch(Character::isLetterOrDigit) || w.contains("\u2605"))
                .limit(MAX_WORDS).toList();
        if (words.isEmpty()) return List.of();
        List<CardRow> exact = matchWords(q, words, set, limit);
        return exact.isEmpty() ? matchFuzzy(String.join(" ", words), set, limit) : exact;
    }

    private List<CardRow> matchWords(String q, List<String> words, String set, int limit) {
        StringBuilder where = new StringBuilder();
        StringBuilder identity = new StringBuilder("true");
        List<Object> whereArgs = new ArrayList<>();
        List<Object> identityArgs = new ArrayList<>();
        for (String word : words) {
            String like = "%" + escapeLike(word) + "%";
            String code = word.toUpperCase(Locale.ROOT);
            String number = "ltrim(collector_number, '0') = ?";
            String id;
            List<Object> idArgs;
            if (word.length() == 1) {
                // A lone letter is the rarity printed beside the number ("C 0116"), not a name fragment.
                id = "(set_code = ? OR " + number + " OR rarity LIKE ?)";
                idArgs = List.of(code, unpadded(word), escapeLike(word) + "%");
            } else {
                id = "(lower(name) LIKE ? OR set_code = ? OR lower(set_name) LIKE ? OR " + number + " OR lang = ?)";
                idArgs = List.of(like, code, like, unpadded(word), word);
            }
            where.append(" AND (").append(id);
            whereArgs.addAll(idArgs);
            if (word.length() > 1) {
                where.append(" OR card_text LIKE ?");
                whereArgs.add(like);
            }
            where.append(")");
            identity.append(" AND ").append(id);
            identityArgs.addAll(idArgs);
        }
        String sql = "SELECT " + COLUMNS + " FROM cards WHERE (? = '' OR set_code = ?)" + where + PRICED
                + " ORDER BY (lower(name) = ?) DESC, (lower(name) LIKE ?) DESC, (" + identity + ") DESC,"
                + " released_at DESC NULLS LAST, name, collector_number LIMIT ?";
        List<Object> args = new ArrayList<>(List.of(set, set));
        args.addAll(whereArgs);
        args.add(q);
        args.add(escapeLike(q) + "%");
        args.addAll(identityArgs);
        args.add(limit);
        return jdbc.query(sql, ROW, args.toArray());
    }

    /** Trigram word similarity tolerates typos and missing letters; pg_trgm's default cut-off is 0.6. */
    private List<CardRow> matchFuzzy(String q, String set, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM cards WHERE (? = '' OR set_code = ?) AND ? <% lower(name)" + PRICED
                + " ORDER BY word_similarity(?, lower(name)) DESC, released_at DESC NULLS LAST, name, collector_number LIMIT ?";
        return jdbc.query(sql, ROW, set, set, q, q, limit);
    }

    /**
     * A word as a collector number without its zero padding, the way the catalog stores it. Cards print "0116";
     * people also type the letter O for the zero ("o116").
     */
    static String unpadded(String word) {
        String digits = word.replaceFirst("^[o0]+(?=\\d)", "");
        return digits.matches("0+") ? "" : digits.replaceFirst("^0+", "");
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** A card trades and stock can take: a Magic card, or a Star Wars: Unlimited printing priced by its finish. */
    public Optional<CardRow> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cards WHERE id = ? UNION ALL SELECT " + COLUMNS
                + " FROM inventory_cards WHERE id = ? AND id IN (SELECT id FROM swu_cards WHERE id = ?)", ROW, id, id, id)
                .stream().findFirst();
    }

    public Optional<Instant> lastImport() {
        return jdbc.query("SELECT max(finished_at) FROM catalog_imports WHERE error IS NULL AND game = 'magic-the-gathering'",
                (rs, i) -> rs.getTimestamp(1)).stream().filter(java.util.Objects::nonNull).map(java.sql.Timestamp::toInstant).findFirst();
    }

    public static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}

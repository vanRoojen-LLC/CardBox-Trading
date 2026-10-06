package com.cardpricer.cloud.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class SwuCatalogRepository {
    /**
     * TCGplayer's price from TCGCSV is the price of record. swu-db's stands in when TCGCSV has none, or when TCGCSV's
     * is more than three days older than swu-db's (TCGCSV has stopped updating).
     */
    private static final String TCGCSV_CURRENT = "(tcgplayer_market IS NOT NULL AND (price_observed_at IS NULL"
            + " OR tcgplayer_observed_at > price_observed_at - interval '3 days'))";
    private static final String COLUMNS = "set_code, source_number, set_name, collector_number, treatment, variant, name,"
            + " subtitle, rarity, image, tcgplayer_id,"
            + " CASE WHEN " + TCGCSV_CURRENT + " THEN tcgplayer_market ELSE market END,"
            + " CASE WHEN " + TCGCSV_CURRENT + " THEN tcgplayer_observed_at ELSE price_observed_at END";
    private static final RowMapper<SwuCard> ROW = (rs, i) -> {
        Timestamp observed = rs.getTimestamp(13);
        return new SwuCard(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getBigDecimal(12), observed == null ? null : observed.toInstant());
    };
    private static final int MAX_WORDS = 6;
    /** Base numbers first, then variants, numerically: "10" before "87" before "351". */
    private static final String NUMBER_ORDER = "lpad(ltrim(collector_number, '0'), 6, '0')";

    private final JdbcTemplate jdbc;

    public SwuCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every word must match the name, subtitle, set code or name, printed number (leading zeros ignored) or variant, so
     * "vader", "darth vader dark lord", "SOR 10", "sor 010", "hyperspace vader" and "vader 351" all work. Exact and
     * prefix name matches come first. When nothing matches, falls back to a typo-tolerant name match.
     */
    public List<SwuCard> search(String query, String set, int limit) {
        String q = query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> words = Arrays.stream(q.split(" "))
                .map(w -> w.startsWith("#") ? w.substring(1) : w)
                .map(w -> w.replaceAll("[,]+$", ""))
                .filter(w -> w.codePoints().anyMatch(Character::isLetterOrDigit))
                .limit(MAX_WORDS).toList();
        if (words.isEmpty()) return List.of();
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>(List.of(set, set));
        for (String word : words) {
            String like = "%" + escapeLike(word) + "%";
            where.append(" AND (lower(name) LIKE ? OR lower(coalesce(subtitle, '')) LIKE ? OR set_code = ?")
                    .append(" OR lower(set_name) LIKE ? OR ltrim(collector_number, '0') = ? OR lower(variant) LIKE ?)");
            args.addAll(List.of(like, like, word.toUpperCase(Locale.ROOT), like, CatalogRepository.unpadded(word), like));
        }
        String sql = "SELECT " + COLUMNS + " FROM swu_cards WHERE (? = '' OR set_code = ?)" + where
                + " ORDER BY (lower(name) = ?) DESC, (lower(name) LIKE ?) DESC, name, subtitle NULLS FIRST, set_code, "
                + NUMBER_ORDER + ", source_number LIMIT ?";
        args.add(q);
        args.add(escapeLike(q) + "%");
        args.add(limit);
        List<SwuCard> exact = jdbc.query(sql, ROW, args.toArray());
        if (!exact.isEmpty()) return exact;
        String fuzzy = String.join(" ", words);
        return jdbc.query("SELECT " + COLUMNS + " FROM swu_cards WHERE (? = '' OR set_code = ?) AND ? <% lower(name)"
                        + " ORDER BY word_similarity(?, lower(name)) DESC, name, set_code, " + NUMBER_ORDER + " LIMIT ?",
                ROW, set, set, fuzzy, fuzzy, limit);
    }

    public Optional<Instant> lastImport() {
        return jdbc.query("SELECT max(finished_at) FROM catalog_imports WHERE error IS NULL AND game = ?",
                        (rs, i) -> rs.getTimestamp(1), SwuCatalogImporter.GAME).stream()
                .filter(java.util.Objects::nonNull).map(Timestamp::toInstant).findFirst();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

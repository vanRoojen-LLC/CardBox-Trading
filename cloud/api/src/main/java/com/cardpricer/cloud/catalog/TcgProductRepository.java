package com.cardpricer.cloud.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TcgProductRepository {
    private static final String COLUMNS = "p.id, p.product_id, p.name, p.set_code, p.set_name, p.collector_number,"
            + " p.rarity, p.sub_type, g.language, p.market, p.image_url, p.observed_at";
    private static final RowMapper<TcgProduct> ROW = (rs, i) -> {
        Timestamp observed = rs.getTimestamp(12);
        return new TcgProduct(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getBigDecimal(10), rs.getString(11),
                observed == null ? null : observed.toInstant());
    };
    private static final int MAX_WORDS = 6;
    /** "25/165" and "025" both sort and match as 25. */
    private static final String NUMBER = "ltrim(split_part(coalesce(p.collector_number, ''), '/', 1), '0')";

    private final JdbcTemplate jdbc;

    public TcgProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every word must match the name, set code or name, printed number (leading zeros and any "/total" ignored) or
     * price subtype, so "pikachu", "pikachu 25", "SV01 25", "charizard holofoil" all work. Exact and prefix name
     * matches come first. When nothing matches, falls back to a typo-tolerant name match, like the SWU search.
     */
    public List<TcgProduct> search(List<Integer> categories, String query, String set, int limit) {
        if (categories.isEmpty()) return List.of();
        String q = query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> words = Arrays.stream(q.split(" "))
                .map(w -> w.startsWith("#") ? w.substring(1) : w)
                .map(w -> w.replaceAll("[,]+$", ""))
                .filter(w -> w.codePoints().anyMatch(Character::isLetterOrDigit))
                .limit(MAX_WORDS).toList();
        if (words.isEmpty()) return List.of();
        String in = String.join(", ", Collections.nCopies(categories.size(), "?"));
        String from = " FROM tcg_products p JOIN tcg_games g ON g.category_id = p.category_id"
                + " WHERE p.category_id IN (" + in + ") AND (? = '' OR p.set_code = ?)";
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>(categories);
        args.addAll(List.of(set, set));
        for (String word : words) {
            String like = "%" + escapeLike(word) + "%";
            where.append(" AND (lower(p.name) LIKE ? OR p.set_code = ? OR lower(p.set_name) LIKE ? OR ")
                    .append(NUMBER).append(" = ? OR lower(p.sub_type) LIKE ?)");
            args.addAll(List.of(like, word.toUpperCase(Locale.ROOT), like, CatalogRepository.unpadded(word), like));
        }
        String order = " p.name, p.set_code, lpad(" + NUMBER + ", 6, '0'), p.sub_type LIMIT ?";
        String sql = "SELECT " + COLUMNS + from + where
                + " ORDER BY (lower(p.name) = ?) DESC, (lower(p.name) LIKE ?) DESC," + order;
        args.add(q);
        args.add(escapeLike(q) + "%");
        args.add(limit);
        List<TcgProduct> exact = jdbc.query(sql, ROW, args.toArray());
        if (!exact.isEmpty()) return exact;
        String fuzzy = String.join(" ", words);
        List<Object> fuzzyArgs = new ArrayList<>(categories);
        fuzzyArgs.addAll(List.of(set, set, fuzzy, fuzzy, limit));
        return jdbc.query("SELECT " + COLUMNS + from + " AND ? <% lower(p.name)"
                + " ORDER BY word_similarity(?, lower(p.name)) DESC," + order, ROW, fuzzyArgs.toArray());
    }

    /** When the newest price among these categories was observed: TCGTracking's last refresh we have. */
    public Optional<Instant> lastObserved(List<Integer> categories) {
        if (categories.isEmpty()) return Optional.empty();
        String in = String.join(", ", Collections.nCopies(categories.size(), "?"));
        return jdbc.query("SELECT max(observed_at) FROM tcg_products WHERE category_id IN (" + in + ")",
                        (rs, i) -> rs.getTimestamp(1), categories.toArray()).stream()
                .filter(java.util.Objects::nonNull).map(Timestamp::toInstant).findFirst();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

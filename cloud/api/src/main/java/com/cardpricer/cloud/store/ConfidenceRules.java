package com.cardpricer.cloud.store;

import com.cardpricer.cloud.catalog.Confidence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a store does with a trade line by how far its price can be trusted: scale the offer and/or hold the trade
 * until staff confirm they checked the price. A level without a rule keeps the offer and doesn't hold.
 */
@Repository
public class ConfidenceRules {
    public record Rule(Confidence.Level level, BigDecimal adjust, boolean review) {}

    static final List<Confidence.Level> LEVELS = List.of(Confidence.Level.high, Confidence.Level.medium, Confidence.Level.low);
    public static final Rule NEUTRAL = new Rule(null, BigDecimal.ONE, false);

    private final JdbcTemplate jdbc;

    public ConfidenceRules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One rule for each of high, medium and low, in that order. */
    public List<Rule> rules(UUID tenant) {
        Map<Confidence.Level, Rule> saved = new EnumMap<>(Confidence.Level.class);
        jdbc.query("SELECT level, adjust, review FROM confidence_rules WHERE tenant_id = ?", rs -> {
            Confidence.Level level = Confidence.Level.valueOf(rs.getString(1));
            saved.put(level, new Rule(level, rs.getBigDecimal(2), rs.getBoolean(3)));
        }, tenant);
        return LEVELS.stream().map(l -> saved.getOrDefault(l, new Rule(l, BigDecimal.ONE, false))).toList();
    }

    /** The rule for a line's level. A card with no price is never quoted, so "none" never reaches here. */
    public static Rule match(List<Rule> rules, Confidence.Level level) {
        return rules.stream().filter(r -> r.level() == level).findFirst().orElse(NEUTRAL);
    }

    @Transactional
    public void replace(UUID tenant, List<Rule> rules) {
        jdbc.update("DELETE FROM confidence_rules WHERE tenant_id = ?", tenant);
        jdbc.batchUpdate("INSERT INTO confidence_rules (tenant_id, level, adjust, review) VALUES (?, ?, ?, ?)",
                rules.stream().map(r -> new Object[]{tenant, r.level().name(), r.adjust(), r.review()}).toList());
    }
}

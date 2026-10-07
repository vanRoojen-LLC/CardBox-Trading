package com.cardpricer.cloud.store;

import com.cardpricer.model.BuyRateRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public class RateRepository {
    private final JdbcTemplate jdbc;

    public RateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The games a store can set its own rates for. Any other game, and a game without its own, uses the default. */
    public static final List<String> GAMES = List.of("magic-the-gathering", "star-wars-unlimited");
    public static final String DEFAULT = "";

    /** The store's default rates, ascending by threshold. */
    public List<BuyRateRule> rules(UUID tenant) {
        return own(tenant, DEFAULT);
    }

    /** The rates a game's cards are offered at: its own if the store set them, otherwise the store's default. */
    public List<BuyRateRule> rules(UUID tenant, String game) {
        List<BuyRateRule> own = game == null || game.isEmpty() ? List.of() : own(tenant, game);
        return own.isEmpty() ? own(tenant, DEFAULT) : own;
    }

    /** Only the rows saved for this game ("" for the default), ascending by threshold. */
    public List<BuyRateRule> own(UUID tenant, String game) {
        return jdbc.query("SELECT threshold_min, credit_rate, check_rate FROM buy_rate_rules WHERE tenant_id = ? AND game = ?"
                        + " ORDER BY threshold_min",
                (rs, i) -> new BuyRateRule(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), tenant, game);
    }

    @Transactional
    public void replace(UUID tenant, List<BuyRateRule> rules) {
        replace(tenant, DEFAULT, rules);
    }

    /** Replaces a game's rates. An empty list for a game puts it back on the store's default. */
    @Transactional
    public void replace(UUID tenant, String game, List<BuyRateRule> rules) {
        jdbc.update("DELETE FROM buy_rate_rules WHERE tenant_id = ? AND game = ?", tenant, game);
        jdbc.batchUpdate("INSERT INTO buy_rate_rules (tenant_id, game, threshold_min, credit_rate, check_rate) VALUES (?, ?, ?, ?, ?)",
                rules.stream().map(r -> new Object[]{tenant, game, r.thresholdMin, r.creditRate, r.checkRate}).toList());
    }

    /** Same lookup as the desktop BuyRateService: the highest matching threshold wins. */
    public static BuyRateRule match(List<BuyRateRule> ascending, BigDecimal value) {
        for (int i = ascending.size() - 1; i >= 0; i--) {
            if (ascending.get(i).matches(value)) return ascending.get(i);
        }
        return new BuyRateRule(BigDecimal.ZERO, new BigDecimal("0.50"), new BigDecimal("0.40"));
    }
}

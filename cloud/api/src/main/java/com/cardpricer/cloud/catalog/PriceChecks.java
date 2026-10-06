package com.cardpricer.cloud.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Counts free price checks per UTC day ({@code public_price_checks}), for the health dashboard. One upsert per
 * search; a failure is logged and never reaches the person searching. Answers are cacheable for an hour, so
 * searches a browser or CDN answers from its cache never get here and are not counted.
 */
@Component
public class PriceChecks {
    private static final Logger log = LoggerFactory.getLogger(PriceChecks.class);

    private final JdbcTemplate jdbc;

    public PriceChecks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void count() {
        try {
            jdbc.update("""
                    INSERT INTO public_price_checks (day, checks) VALUES ((now() AT TIME ZONE 'UTC')::date, 1)
                    ON CONFLICT (day) DO UPDATE SET checks = public_price_checks.checks + 1""");
        } catch (RuntimeException e) {
            log.warn("Could not count a price check: {}", e.toString());
        }
    }
}

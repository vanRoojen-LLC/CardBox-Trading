package com.cardpricer.cloud.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps {@code blended_values} current for every card and finish in any store's inventory, so the inventory list values a
 * line with the same blended price ({@link PriceEvidence}) a quote uses, not TCGplayer's price alone (one best path,
 * 2026-10-08). Lines whose value is missing or older than {@link #STALE} are recomputed in batches each pass.
 */
@Component
public class BlendedValues {
    static final Duration STALE = Duration.ofHours(1);
    static final int BATCH = 500;
    private static final Logger log = LoggerFactory.getLogger(BlendedValues.class);

    private final JdbcTemplate jdbc;
    private final PriceEvidence evidence;

    public BlendedValues(JdbcTemplate jdbc, PriceEvidence evidence) {
        this.jdbc = jdbc;
        this.evidence = evidence;
    }

    @Scheduled(initialDelayString = "${app.blended.initial-delay-ms:60000}", fixedDelayString = "${app.blended.delay-ms:600000}")
    public void scheduled() {
        try {
            refresh(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Refreshing blended values failed: {}", e.toString());
        }
    }

    /** Recomputes every stale or missing value; returns how many were written. */
    public int refresh(Instant now) {
        int written = 0;
        while (true) {
            List<PriceEvidence.Key> keys = jdbc.query("""
                    SELECT DISTINCT i.card_id, coalesce(i.finish, 'normal') AS finish FROM inventory_items i
                    LEFT JOIN blended_values b ON b.card_id = i.card_id AND b.finish = coalesce(i.finish, 'normal')
                    WHERE i.card_id IS NOT NULL AND (b.card_id IS NULL OR b.computed_at < ?) LIMIT ?""",
                    (rs, n) -> new PriceEvidence.Key(rs.getObject("card_id", UUID.class), rs.getString("finish")),
                    Timestamp.from(now.minus(STALE)), BATCH);
            if (keys.isEmpty()) return written;
            var found = evidence.evidence(keys, now);
            List<Object[]> rows = new ArrayList<>();
            for (var key : keys) {
                var e = found.get(key);
                rows.add(new Object[]{key.cardId(), key.finish(), e == null ? null : e.market(), e == null ? 0 : e.sources(),
                        Timestamp.from(now)});
            }
            jdbc.batchUpdate("""
                    INSERT INTO blended_values (card_id, finish, market, sources, computed_at) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (card_id, finish) DO UPDATE SET market = excluded.market, sources = excluded.sources,
                        computed_at = excluded.computed_at""", rows);
            written += rows.size();
            if (keys.size() < BATCH) return written;
        }
    }
}

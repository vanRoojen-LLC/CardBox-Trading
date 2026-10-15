package com.cardpricer.cloud.store;

import com.cardpricer.cloud.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** A store's decision to accept new trades in a segment, independent of its existing stock and rates. */
@Repository
public class TradeAcceptance {
    private final JdbcTemplate jdbc;

    public TradeAcceptance(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean accepting(UUID tenant, String segment) {
        return jdbc.queryForObject("""
                SELECT coalesce((SELECT accepting FROM segment_trade_acceptance
                                 WHERE tenant_id = ? AND segment = ?), true)""",
                Boolean.class, tenant, segment);
    }

    /** Read every explicit switch in one query when building the store's game picker. */
    public Map<String, Boolean> settings(UUID tenant) {
        return jdbc.query("SELECT segment, accepting FROM segment_trade_acceptance WHERE tenant_id = ?",
                rs -> {
                    var result = new java.util.HashMap<String, Boolean>();
                    while (rs.next()) result.put(rs.getString(1), rs.getBoolean(2));
                    return result;
                }, tenant);
    }

    /** Serializes a switch with trade saves on the tenant row. */
    @Transactional
    public void set(UUID tenant, String segment, boolean accepting) {
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, tenant);
        jdbc.update("""
                INSERT INTO segment_trade_acceptance (tenant_id, segment, accepting) VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, segment) DO UPDATE SET accepting = excluded.accepting""",
                tenant, segment, accepting);
    }

    public void requireAccepting(UUID tenant, String segment) {
        if (!accepting(tenant, segment))
            throw ApiException.badRequest("This store is not accepting trades for " + segment
                    + ". Remove those cards or ask the owner to reopen the segment.");
    }
}

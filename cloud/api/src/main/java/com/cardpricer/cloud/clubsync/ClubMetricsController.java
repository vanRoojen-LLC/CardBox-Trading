package com.cardpricer.cloud.clubsync;

import com.cardpricer.cloud.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Trading's figures for cardbox.club's health dashboard, called server to server with the same machine token as
 * Club sync (see {@link ClubSyncAuth}). Every figure is worked out on its own: one that fails is logged and comes
 * back null, and the rest still answer. Unlike the sync calls these answers use camelCase.
 */
@RestController
@RequestMapping("/api/partner/club-sync")
public class ClubMetricsController {
    private static final Logger log = LoggerFactory.getLogger(ClubMetricsController.class);

    private final ClubSyncAuth auth;
    private final JdbcTemplate jdbc;
    private final AzureCost azureCost;

    public ClubMetricsController(ClubSyncAuth auth, JdbcTemplate jdbc, AzureCost azureCost) {
        this.auth = auth;
        this.jdbc = jdbc;
        this.azureCost = azureCost;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(HttpServletRequest request) {
        if (!auth.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Collection sync is switched off on cardbox.trading");
        if (!auth.accepts(request.getHeader("Authorization")))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "A valid CardBox sync token is required");

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("measuredAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        metrics.put("tradesLast7Days", figure("tradesLast7Days", () ->
                jdbc.queryForObject("SELECT count(*) FROM trades WHERE created_at >= now() - interval '7 days'", Long.class)));
        metrics.put("clubLinkItems", figure("clubLinkItems", () ->
                jdbc.queryForObject("SELECT count(*) FROM club_link_items", Long.class)));
        metrics.put("activeStoresLast30Days", figure("activeStoresLast30Days", () -> jdbc.queryForObject("""
                SELECT count(DISTINCT tenant_id) FROM (
                    SELECT tenant_id FROM trades WHERE created_at >= now() - interval '30 days'
                    UNION ALL
                    SELECT tenant_id FROM inventory_adjustments WHERE created_at >= now() - interval '30 days') active""",
                Long.class)));
        // Today (UTC) and the six days before it; null until the counter has its first row.
        metrics.put("priceChecksLast7Days", figure("priceChecksLast7Days", () -> jdbc.queryForObject("""
                SELECT CASE WHEN count(*) = 0 THEN NULL
                            ELSE coalesce(sum(checks) FILTER (WHERE day >= (now() AT TIME ZONE 'UTC')::date - 6), 0) END
                FROM public_price_checks""", Long.class)));
        metrics.put("priceChecksCountingSince", figure("priceChecksCountingSince", () ->
                jdbc.queryForObject("SELECT to_char(min(day), 'YYYY-MM-DD') FROM public_price_checks", String.class)));
        AzureCost.Result cost;
        try {
            cost = azureCost.monthToDate();
        } catch (RuntimeException e) {
            log.warn("Metric azureMonthToDateUsd failed", e);
            cost = new AzureCost.Result(null, "Cost lookup failed");
        }
        metrics.put("azureMonthToDateUsd", cost.usd());
        metrics.put("azureCostError", cost.error());
        return metrics;
    }

    private static <T> T figure(String name, Supplier<T> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            log.warn("Metric {} failed: {}", name, e.toString());
            return null;
        }
    }

    // Club reads errors the way CardBox writes them, as on the sync calls.

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("detail", e.getMessage()));
    }
}

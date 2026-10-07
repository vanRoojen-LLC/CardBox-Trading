package com.cardpricer.cloud.clubsync;

import com.cardpricer.cloud.catalog.ClubMarketSummaries;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Where cardbox.club posts its market evidence (eBay sold and asking prices, research sales) for the games Trading
 * prices, with the same machine token as collection sync. Bodies use Club's snake_case.
 */
@RestController
@RequestMapping("/api/partner/club-sync")
public class ClubMarketController {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SummaryBody(String source, String valueKind, Integer observations, String median, String low,
                              String high, String newest) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PrintingBody(String printingId, Map<String, Object> externalIds, String treatment, Boolean foil,
                               List<SummaryBody> summaries) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PageBody(String segmentKey, Boolean firstPage, Boolean lastPage, List<PrintingBody> printings) {}

    static final int MAX_PRINTINGS = 500;

    private final ClubSyncAuth auth;
    private final ClubMarketSummaries summaries;

    public ClubMarketController(ClubSyncAuth auth, ClubMarketSummaries summaries) {
        this.auth = auth;
        this.summaries = summaries;
    }

    @PostMapping("/market-summaries")
    public Map<String, Object> receive(@RequestBody PageBody body, HttpServletRequest request) {
        if (!auth.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Collection sync is switched off on cardbox.trading");
        if (!auth.accepts(request.getHeader("Authorization")))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "A valid CardBox sync token is required");
        if (body == null || body.segmentKey() == null || !ClubMarketSummaries.GAMES.contains(body.segmentKey()))
            throw ApiException.badRequest("segment_key must be one of " + ClubMarketSummaries.GAMES);
        List<PrintingBody> printings = body.printings() == null ? List.of() : body.printings();
        if (printings.size() > MAX_PRINTINGS) throw ApiException.badRequest("At most " + MAX_PRINTINGS + " printings a page");
        var parsed = printings.stream().map(ClubMarketController::printing).toList();
        var result = summaries.ingest(body.segmentKey(), Boolean.TRUE.equals(body.firstPage()),
                Boolean.TRUE.equals(body.lastPage()), parsed);
        return Map.of("printings", result.printings(), "matched", result.matched(), "stored", result.stored(),
                "removed", result.removed());
    }

    private static ClubMarketSummaries.Printing printing(PrintingBody p) {
        if (p == null) throw ApiException.badRequest("A printing is empty");
        List<SummaryBody> raw = p.summaries() == null ? List.of() : p.summaries();
        if (raw.size() > 50) throw ApiException.badRequest("Too many summaries on one printing");
        var list = raw.stream().map(ClubMarketController::summary).toList();
        return new ClubMarketSummaries.Printing(p.printingId(), p.externalIds(), p.treatment(),
                Boolean.TRUE.equals(p.foil()), list);
    }

    private static ClubMarketSummaries.Summary summary(SummaryBody s) {
        if (s == null || s.source() == null || !s.source().matches("[a-z0-9-]{1,40}"))
            throw ApiException.badRequest("Each summary needs a source");
        if (s.valueKind() == null || !List.of("asking", "realized", "estimate").contains(s.valueKind()))
            throw ApiException.badRequest("value_kind must be asking, realized or estimate");
        if (s.observations() == null || s.observations() < 1) throw ApiException.badRequest("observations must be positive");
        BigDecimal median = money(s.median(), true);
        Instant newest;
        try {
            newest = OffsetDateTime.parse(s.newest()).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest("newest must be an ISO date and time");
        }
        return new ClubMarketSummaries.Summary(s.source(), s.valueKind(), s.observations(), median,
                money(s.low(), false), money(s.high(), false), newest);
    }

    private static BigDecimal money(String value, boolean required) {
        if (value == null) {
            if (required) throw ApiException.badRequest("median is required");
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(value);
            if (v.signum() < 0 || v.compareTo(new BigDecimal("10000000")) >= 0) throw new NumberFormatException();
            return v.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Prices must be dollar amounts");
        }
    }
}

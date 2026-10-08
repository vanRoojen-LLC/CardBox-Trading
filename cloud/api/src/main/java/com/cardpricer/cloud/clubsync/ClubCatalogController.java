package com.cardpricer.cloud.clubsync;

import com.cardpricer.cloud.catalog.CatalogPrintingLinks;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Where cardbox.club posts the catalog printings of the games Trading sells, with the same machine token as
 * collection sync. Bodies use Club's snake_case.
 */
@RestController
@RequestMapping("/api/partner/club-sync")
public class ClubCatalogController {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PrintingBody(String printingId, String setCode, String collectorNumber, String treatment,
                               String language, Boolean foil, Map<String, Object> externalIds) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PageBody(String segmentKey, Boolean firstPage, Boolean lastPage, List<PrintingBody> printings) {}

    static final int MAX_PRINTINGS = 500;

    private final ClubSyncAuth auth;
    private final CatalogPrintingLinks links;

    public ClubCatalogController(ClubSyncAuth auth, CatalogPrintingLinks links) {
        this.auth = auth;
        this.links = links;
    }

    @PostMapping("/catalog-printings")
    public Map<String, Object> receive(@RequestBody PageBody body, HttpServletRequest request) {
        if (!auth.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Collection sync is switched off on cardbox.trading");
        if (!auth.accepts(request.getHeader("Authorization")))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "A valid CardBox sync token is required");
        if (body == null || body.segmentKey() == null || !CatalogPrintingLinks.GAMES.contains(body.segmentKey()))
            throw ApiException.badRequest("segment_key must be one of " + CatalogPrintingLinks.GAMES);
        List<PrintingBody> printings = body.printings() == null ? List.of() : body.printings();
        if (printings.size() > MAX_PRINTINGS) throw ApiException.badRequest("At most " + MAX_PRINTINGS + " printings a page");
        var parsed = printings.stream().map(ClubCatalogController::printing).toList();
        var result = links.ingest(body.segmentKey(), Boolean.TRUE.equals(body.firstPage()),
                Boolean.TRUE.equals(body.lastPage()), parsed);
        return Map.of("printings", result.printings(), "linked", result.linked(), "removed", result.removed());
    }

    private static CatalogPrintingLinks.Printing printing(PrintingBody p) {
        if (p == null || p.printingId() == null || p.printingId().isBlank() || p.printingId().length() > 200)
            throw ApiException.badRequest("Each printing needs a printing_id");
        if (p.externalIds() != null && p.externalIds().size() > 50)
            throw ApiException.badRequest("Too many external ids on one printing");
        return new CatalogPrintingLinks.Printing(p.printingId(), cap(p.setCode()), cap(p.collectorNumber()),
                cap(p.treatment()), cap(p.language()), Boolean.TRUE.equals(p.foil()), p.externalIds());
    }

    private static String cap(String s) {
        return s == null ? null : s.length() > 100 ? s.substring(0, 100) : s;
    }
}

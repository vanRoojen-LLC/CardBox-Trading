package com.cardpricer.cloud.clubsync;

import com.cardpricer.cloud.trade.CompletedBuyinEvents;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Trusted Club M2M delivery only. Recording is unconditional; outbound transport is separately held. */
@RestController
@RequestMapping("/api/partner/club-sync/transaction-events")
public class ClubTransactionEventsController {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReceiptBody(String eventId, String payloadSha256, String receiverReceiptId) {}
    public record AckBody(List<ReceiptBody> receipts) {}
    private final ClubSyncAuth auth;
    private final CompletedBuyinEvents events;
    private final boolean transport;

    public ClubTransactionEventsController(ClubSyncAuth auth, CompletedBuyinEvents events,
            @Value("${app.club-sync.transaction-events-enabled:false}") boolean transport) {
        this.auth = auth;
        this.events = events;
        this.transport = transport;
    }

    private void authorize(HttpServletRequest request) {
        if (!auth.enabled() || !transport)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Buy-in event delivery is not enabled");
        if (!auth.accepts(request.getHeader("Authorization")))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "A valid CardBox sync token is required");
    }

    @GetMapping
    public Map<String, Object> pending(@RequestParam(defaultValue = "200") int limit, HttpServletRequest request) {
        authorize(request);
        return events.pending(limit);
    }

    @PostMapping("/ack")
    public Map<String, Object> acknowledge(@RequestBody AckBody body, HttpServletRequest request) {
        authorize(request);
        if (body == null || body.receipts() == null) throw ApiException.badRequest("Receipts are required");
        return events.acknowledge(body.receipts().stream().map(r -> r == null ? null
                : new CompletedBuyinEvents.Receipt(r.eventId(), r.payloadSha256(), r.receiverReceiptId())).toList());
    }
}

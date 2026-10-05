package com.cardpricer.cloud.clubsync;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Inventory page's view of CardBox collections synced into this store. Everyone on the store sees them; owners
 * choose where their cards sit and end links.
 */
@RestController
@RequestMapping("/api/app/club-links")
public class ClubLinksController {
    public record TargetBody(@NotNull UUID locationId, UUID storageId, String defaultCondition) {}
    public record EndBody(String cards) {}

    private final ClubSync sync;

    public ClubLinksController(ClubSync sync) {
        this.sync = sync;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        return sync.links(CurrentUser.of(request).tenantId());
    }

    @GetMapping("/{id}/not-matched")
    public List<Map<String, Object>> notMatched(@PathVariable UUID id, HttpServletRequest request) {
        return sync.notMatched(CurrentUser.of(request).tenantId(), id);
    }

    /** The Club scans (photo and detail of each card) behind a synced inventory line. */
    @GetMapping("/scans/{lineId}")
    public List<Map<String, Object>> scans(@PathVariable UUID lineId, HttpServletRequest request) {
        return sync.scans(CurrentUser.of(request).tenantId(), lineId);
    }

    @PutMapping("/{id}")
    public List<Map<String, Object>> retarget(@PathVariable UUID id, @Valid @RequestBody TargetBody body, HttpServletRequest request) {
        UUID tenant = requireOwner(request).tenantId();
        sync.retarget(tenant, id, body.locationId(), body.storageId(), body.defaultCondition() == null ? "NM" : body.defaultCondition());
        return sync.links(tenant);
    }

    /** Keeps the cards as the store's own stock, or takes them out of inventory, and stops the sync. */
    @PostMapping("/{id}/end")
    public List<Map<String, Object>> end(@PathVariable UUID id, @RequestBody EndBody body, HttpServletRequest request) {
        UUID tenant = requireOwner(request).tenantId();
        if (!"keep".equals(body.cards()) && !"remove".equals(body.cards())) throw ApiException.badRequest("Choose keep or remove");
        sync.endByStore(tenant, id, "keep".equals(body.cards()));
        return sync.links(tenant);
    }

    private static CurrentUser requireOwner(HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        if (!user.owner()) throw ApiException.forbidden("Only a store owner can change synced collections");
        return user;
    }
}

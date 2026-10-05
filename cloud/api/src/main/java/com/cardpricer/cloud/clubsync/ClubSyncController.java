package com.cardpricer.cloud.clubsync;

import com.cardpricer.cloud.cardbox.StoreNames;
import com.cardpricer.cloud.inventory.InventoryCounts;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What cardbox.club calls, server to server, to keep a linked collection in a store's inventory. Every call carries
 * Club's machine token (see {@link ClubSyncAuth}); nobody is signed in. Bodies and answers use Club's snake_case,
 * and errors come back as {@code {"detail": "..."}} like CardBox's own. 503 for everything while sync is off, never 404:
 * Club reads a 404 on a link as the store ending it.
 */
@RestController
@RequestMapping("/api/partner/club-sync")
public class ClubSyncController {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LinkBody(String storeId, String collectionName, ClubSync.LinkedBy linkedBy) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ItemsBody(UUID snapshotId, List<ClubSync.Upsert> upserts, List<ClubSync.Removal> removals) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SnapshotBody(Long asOfVersion) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CompleteBody(Integer itemCount) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CountItemsBody(String storeId, List<ClubSync.Upsert> upserts) {}

    public record UnlinkBody(String cards) {}

    public record PauseBody(String reason) {}

    public record StoreBody(String name) {}

    private static final String ID = "[A-Za-z0-9_:.-]{1,100}";
    private static final List<String> PAUSE_REASONS = List.of("role_revoked", "collection_deleted");

    private final ClubSyncAuth auth;
    private final ClubSync sync;
    private final InventoryCounts counts;
    private final StoreNames storeNames;

    public ClubSyncController(ClubSyncAuth auth, ClubSync sync, InventoryCounts counts, StoreNames storeNames) {
        this.auth = auth;
        this.sync = sync;
        this.counts = counts;
        this.storeNames = storeNames;
    }

    @PutMapping("/links/{collectionId}")
    public Map<String, Object> link(@PathVariable String collectionId, @RequestBody LinkBody body, HttpServletRequest request) {
        check(request, collectionId);
        if (body.storeId() == null || body.storeId().isBlank()) throw ApiException.badRequest("store_id is required");
        if (body.linkedBy() == null || body.linkedBy().auth0Sub() == null || body.linkedBy().auth0Sub().isBlank())
            throw ApiException.badRequest("linked_by.auth0_sub is required");
        return sync.link(collectionId, body.storeId().trim(), body.collectionName(), body.linkedBy());
    }

    @GetMapping("/links/{collectionId}")
    public Map<String, Object> get(@PathVariable String collectionId, HttpServletRequest request) {
        check(request, collectionId);
        return sync.view(collectionId);
    }

    @PostMapping("/links/{collectionId}/items")
    public Map<String, Object> items(@PathVariable String collectionId, @RequestBody ItemsBody body, HttpServletRequest request) {
        check(request, collectionId);
        return sync.apply(collectionId, body.snapshotId(), body.upserts() == null ? List.of() : body.upserts(),
                body.removals() == null ? List.of() : body.removals());
    }

    @PostMapping("/links/{collectionId}/snapshots")
    public Map<String, Object> snapshot(@PathVariable String collectionId, @RequestBody SnapshotBody body, HttpServletRequest request) {
        check(request, collectionId);
        if (body.asOfVersion() == null) throw ApiException.badRequest("as_of_version is required");
        return sync.startSnapshot(collectionId, body.asOfVersion());
    }

    @PostMapping("/links/{collectionId}/snapshots/{snapshotId}/complete")
    public Map<String, Object> complete(@PathVariable String collectionId, @PathVariable UUID snapshotId,
                                        @RequestBody CompleteBody body, HttpServletRequest request) {
        check(request, collectionId);
        if (body.itemCount() == null || body.itemCount() < 0) throw ApiException.badRequest("item_count is required");
        return sync.completeSnapshot(collectionId, snapshotId, body.itemCount());
    }

    @PostMapping("/links/{collectionId}/unlink")
    public Map<String, Object> unlink(@PathVariable String collectionId, @RequestBody UnlinkBody body, HttpServletRequest request) {
        check(request, collectionId);
        if (!"keep".equals(body.cards()) && !"remove".equals(body.cards()))
            throw ApiException.badRequest("cards must be keep or remove");
        return sync.unlink(collectionId, "keep".equals(body.cards()));
    }

    @PostMapping("/links/{collectionId}/pause")
    public Map<String, Object> pause(@PathVariable String collectionId, @RequestBody PauseBody body, HttpServletRequest request) {
        check(request, collectionId);
        if (!PAUSE_REASONS.contains(body.reason())) throw ApiException.badRequest("reason must be one of " + String.join(", ", PAUSE_REASONS));
        return sync.pause(collectionId, body.reason());
    }

    /**
     * A store was renamed on Club. Trading goes by the store's id everywhere, so only the name it shows changes.
     * 404 while no Trading store is tied to it yet; it takes Club's name when it is.
     */
    @PutMapping("/stores/{storeId}")
    public Map<String, Object> store(@PathVariable String storeId, @RequestBody StoreBody body, HttpServletRequest request) {
        check(request, storeId);
        if (body.name() == null || body.name().isBlank()) throw ApiException.badRequest("name is required");
        sync.tenantFor(storeId);
        storeNames.adopt(storeId, body.name());
        return Map.of("store_id", storeId, "name", sync.storeName(storeId));
    }

    /** The store's open locations and storage tree, so Club can tag what it scans to a spot. */
    @GetMapping("/stores/{storeId}/storage")
    public Map<String, Object> storage(@PathVariable String storeId, HttpServletRequest request) {
        check(request, storeId);
        return sync.storage(storeId);
    }

    /** Open re-inventory counts at the store, for Club's re-inventory picker. */
    @GetMapping("/stores/{storeId}/counts")
    public List<Map<String, Object>> counts(@PathVariable String storeId, HttpServletRequest request) {
        check(request, storeId);
        return counts.openForClub(storeId);
    }

    /** Cards scanned on Club for a count. {@code store_id} is the store Club checked the person's role at. */
    @PostMapping("/counts/{countId}/items")
    public Map<String, Object> countItems(@PathVariable UUID countId, @RequestBody CountItemsBody body, HttpServletRequest request) {
        check(request, countId.toString());
        if (body.storeId() == null || body.storeId().isBlank()) throw ApiException.badRequest("store_id is required");
        return counts.addScanned(body.storeId().trim(), countId, body.upserts() == null ? List.of() : body.upserts());
    }

    private void check(HttpServletRequest request, String id) {
        if (!auth.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Collection sync is switched off on cardbox.trading");
        if (!auth.accepts(request.getHeader("Authorization")))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "A valid CardBox sync token is required");
        if (!id.matches(ID)) throw ApiException.badRequest("Unexpected id");
    }

    // Club reads errors the way CardBox writes them.

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", "Malformed request body"));
    }
}

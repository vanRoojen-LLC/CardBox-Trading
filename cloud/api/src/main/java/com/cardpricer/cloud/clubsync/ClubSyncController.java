package com.cardpricer.cloud.clubsync;

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
 * and errors come back as {@code {"detail": "..."}} like CardBox's own. 404 for everything while sync is off.
 */
@RestController
@RequestMapping("/api/partner/club-sync/links/{collectionId}")
public class ClubSyncController {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LinkBody(String storeId, String collectionName, ClubSync.LinkedBy linkedBy) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ItemsBody(UUID snapshotId, List<ClubSync.Upsert> upserts, List<ClubSync.Removal> removals) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SnapshotBody(Long asOfVersion) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CompleteBody(Integer itemCount) {}

    public record UnlinkBody(String cards) {}

    public record PauseBody(String reason) {}

    private static final String ID = "[A-Za-z0-9_:.-]{1,100}";
    private static final List<String> PAUSE_REASONS = List.of("role_revoked", "collection_deleted");

    private final ClubSyncAuth auth;
    private final ClubSync sync;

    public ClubSyncController(ClubSyncAuth auth, ClubSync sync) {
        this.auth = auth;
        this.sync = sync;
    }

    @PutMapping
    public Map<String, Object> link(@PathVariable String collectionId, @RequestBody LinkBody body, HttpServletRequest request) {
        check(request, collectionId);
        if (body.storeId() == null || body.storeId().isBlank()) throw ApiException.badRequest("store_id is required");
        if (body.linkedBy() == null || body.linkedBy().auth0Sub() == null || body.linkedBy().auth0Sub().isBlank())
            throw ApiException.badRequest("linked_by.auth0_sub is required");
        return sync.link(collectionId, body.storeId().trim(), body.collectionName(), body.linkedBy());
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable String collectionId, HttpServletRequest request) {
        check(request, collectionId);
        return sync.view(collectionId);
    }

    @PostMapping("/items")
    public Map<String, Object> items(@PathVariable String collectionId, @RequestBody ItemsBody body, HttpServletRequest request) {
        check(request, collectionId);
        return sync.apply(collectionId, body.snapshotId(), body.upserts() == null ? List.of() : body.upserts(),
                body.removals() == null ? List.of() : body.removals());
    }

    @PostMapping("/snapshots")
    public Map<String, Object> snapshot(@PathVariable String collectionId, @RequestBody SnapshotBody body, HttpServletRequest request) {
        check(request, collectionId);
        if (body.asOfVersion() == null) throw ApiException.badRequest("as_of_version is required");
        return sync.startSnapshot(collectionId, body.asOfVersion());
    }

    @PostMapping("/snapshots/{snapshotId}/complete")
    public Map<String, Object> complete(@PathVariable String collectionId, @PathVariable UUID snapshotId,
                                        @RequestBody CompleteBody body, HttpServletRequest request) {
        check(request, collectionId);
        if (body.itemCount() == null || body.itemCount() < 0) throw ApiException.badRequest("item_count is required");
        return sync.completeSnapshot(collectionId, snapshotId, body.itemCount());
    }

    @PostMapping("/unlink")
    public Map<String, Object> unlink(@PathVariable String collectionId, @RequestBody UnlinkBody body, HttpServletRequest request) {
        check(request, collectionId);
        if (!"keep".equals(body.cards()) && !"remove".equals(body.cards()))
            throw ApiException.badRequest("cards must be keep or remove");
        return sync.unlink(collectionId, "keep".equals(body.cards()));
    }

    @PostMapping("/pause")
    public Map<String, Object> pause(@PathVariable String collectionId, @RequestBody PauseBody body, HttpServletRequest request) {
        check(request, collectionId);
        if (!PAUSE_REASONS.contains(body.reason())) throw ApiException.badRequest("reason must be one of " + String.join(", ", PAUSE_REASONS));
        return sync.pause(collectionId, body.reason());
    }

    private void check(HttpServletRequest request, String collectionId) {
        if (!auth.enabled()) throw ApiException.notFound("Not found");
        if (!auth.accepts(request.getHeader("Authorization")))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "A valid CardBox sync token is required");
        if (!collectionId.matches(ID)) throw ApiException.badRequest("Unexpected collection id");
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

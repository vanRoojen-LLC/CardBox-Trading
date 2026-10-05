package com.cardpricer.cloud.inventory;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Re-inventory counts. Anyone on the store can count; only an owner accepts or cancels a count. */
@RestController
@RequestMapping("/api/app/counts")
public class CountController {
    public record StartBody(@NotNull UUID locationId, UUID storageId) {}
    public record LineBody(@NotNull UUID cardId, String finish, String condition, @Min(1) @Max(9999) int quantity, UUID storageId) {}

    private final InventoryCounts counts;

    public CountController(InventoryCounts counts) {
        this.counts = counts;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        return counts.list(CurrentUser.of(request).tenantId());
    }

    @PostMapping
    public Map<String, Object> start(@Valid @RequestBody StartBody body, HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        UUID id = counts.start(user.tenantId(), user.userId(), body.locationId(), body.storageId());
        return counts.report(user.tenantId(), id);
    }

    @GetMapping("/{id}")
    public Map<String, Object> report(@PathVariable UUID id, HttpServletRequest request) {
        return counts.report(CurrentUser.of(request).tenantId(), id);
    }

    @PostMapping("/{id}/lines")
    public Map<String, Object> add(@PathVariable UUID id, @Valid @RequestBody LineBody body, HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        counts.addTyped(user.tenantId(), user.userId(), id, body.cardId(), body.finish(), body.condition(), body.quantity(), body.storageId());
        return counts.report(user.tenantId(), id);
    }

    @PostMapping("/{id}/lines/{line}/remove")
    public Map<String, Object> remove(@PathVariable UUID id, @PathVariable UUID line, HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        counts.removeLine(tenant, id, line);
        return counts.report(tenant, id);
    }

    @PostMapping("/{id}/reconcile")
    public Map<String, Object> reconcile(@PathVariable UUID id, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        counts.reconcile(user.tenantId(), user.userId(), id);
        return counts.report(user.tenantId(), id);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        counts.cancel(user.tenantId(), user.userId(), id);
        return counts.report(user.tenantId(), id);
    }

    private static CurrentUser requireOwner(HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        if (!user.owner()) throw ApiException.forbidden("Only a store owner can accept or cancel a count");
        return user;
    }
}

package com.cardpricer.cloud.admin;

import com.cardpricer.cloud.cardbox.CardBoxClient;
import com.cardpricer.cloud.store.StoreController;
import com.cardpricer.cloud.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The platform owner's view across every store: stores with their plan and trial, everyone with a login, and the
 * changes needed to support customers. AuthFilter lets only the platform owner reach /api/admin/**.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    /** Any field left null is unchanged. {@code trialEndsAt} is a date; the trial runs to the end of that day (UTC). */
    public record StoreBody(@Size(min = 1, max = 120) String name,
                            @Pattern(regexp = "trial|active|past_due|canceled") String planStatus,
                            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String trialEndsAt) {}
    public record MemberBody(@NotBlank @Size(max = 120) String name, @NotBlank @Email String email,
                             @Pattern(regexp = "owner|staff") String role) {}
    public record MembershipBody(@Pattern(regexp = "owner|staff") String role, Boolean removed) {}
    /** A CardBox store id, or null to unlink. */
    public record LinkBody(@Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String cardboxStoreId) {}

    private final JdbcTemplate jdbc;
    private final CardBoxClient cardbox;
    private final com.cardpricer.cloud.catalog.CatalogPrintingLinks catalogLinks;

    public AdminController(JdbcTemplate jdbc, CardBoxClient cardbox,
                           com.cardpricer.cloud.catalog.CatalogPrintingLinks catalogLinks) {
        this.jdbc = jdbc;
        this.cardbox = cardbox;
        this.catalogLinks = catalogLinks;
    }

    /** How much of Trading's catalog the catalog service's printings cover yet, per game. */
    @GetMapping("/catalog-links")
    public Map<String, Object> catalogLinks() {
        return catalogLinks.coverage();
    }

    @GetMapping("/stores")
    public List<Map<String, Object>> stores() {
        return jdbc.queryForList("""
                SELECT t.id, t.name, t.plan_status AS "planStatus", t.trial_ends_at AS "trialEndsAt", t.created_at AS "createdAt",
                       t.website, t.phone, t.contact_email AS "contactEmail", t.cardbox_store_id AS "cardboxStoreId",
                       t.plan_status = 'active' OR (t.plan_status = 'trial' AND t.trial_ends_at > now()) AS entitled,
                       (SELECT count(*) FROM users u WHERE u.tenant_id = t.id AND u.removed_at IS NULL) AS people,
                       (SELECT string_agg(u.name, ', ' ORDER BY u.name) FROM users u
                        WHERE u.tenant_id = t.id AND u.role = 'owner' AND u.removed_at IS NULL) AS owners,
                       (SELECT count(*) FROM locations l WHERE l.tenant_id = t.id AND l.archived_at IS NULL) AS locations,
                       (SELECT count(*) FROM trades tr WHERE tr.tenant_id = t.id) AS trades,
                       (SELECT max(tr.created_at) FROM trades tr WHERE tr.tenant_id = t.id) AS "lastTradeAt",
                       (SELECT coalesce(sum(i.quantity), 0) FROM inventory_items i WHERE i.tenant_id = t.id) AS cards
                FROM tenants t ORDER BY t.created_at DESC""");
    }

    @PutMapping("/stores/{id}")
    public List<Map<String, Object>> saveStore(@PathVariable UUID id, @Valid @RequestBody StoreBody body) {
        // A store tied to CardBox is renamed there (CardBox stores list above); one not tied yet is still Trading's own.
        if (body.name() != null && cardbox.enabled() && !jdbc.queryForList(
                "SELECT 1 FROM tenants WHERE id = ? AND cardbox_store_id IS NOT NULL", Integer.class, id).isEmpty())
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "This store is on CardBox; rename it in the CardBox stores list");
        Timestamp trialEnds = body.trialEndsAt() == null ? null
                : Timestamp.from(LocalDate.parse(body.trialEndsAt()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(1));
        int changed = jdbc.update("""
                UPDATE tenants SET name = coalesce(?, name), plan_status = coalesce(?, plan_status),
                                   trial_ends_at = coalesce(?, trial_ends_at) WHERE id = ?""",
                body.name() == null ? null : body.name().trim(), body.planStatus(), trialEnds, id);
        if (changed == 0) throw ApiException.notFound("Store not found");
        return stores();
    }

    /** Everyone's memberships: one row per person per store. Removed people are included and marked. */
    @GetMapping("/users")
    public List<Map<String, Object>> users(@RequestParam(value = "store", required = false) UUID store) {
        return jdbc.queryForList("""
                SELECT u.id, u.name, u.email, u.role, u.tenant_id AS "storeId", t.name AS store,
                       u.auth0_sub IS NOT NULL AS joined, u.removed_at IS NOT NULL AS removed,
                       u.created_at AS "createdAt", u.last_used_at AS "lastUsedAt"
                FROM users u JOIN tenants t ON t.id = u.tenant_id
                WHERE ?::uuid IS NULL OR u.tenant_id = ?
                ORDER BY lower(u.email), lower(t.name)""", store, store);
    }

    /** Puts anyone, new or with an existing login, on any store's team. */
    @PostMapping("/stores/{id}/members")
    public List<Map<String, Object>> addMember(@PathVariable UUID id, @Valid @RequestBody MemberBody body) {
        StoreController.refuseWhenCardBoxManaged(cardbox);
        Integer found = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, id);
        if (found == null || found == 0) throw ApiException.notFound("Store not found");
        StoreController.addMember(jdbc, id, body.name(), body.email(), body.role() == null ? "staff" : body.role());
        return users(id);
    }

    /** Changes a role, or removes or restores someone. A store always keeps an owner. */
    @PutMapping("/users/{id}")
    @Transactional
    public Map<String, Object> saveMembership(@PathVariable UUID id, @Valid @RequestBody MembershipBody body) {
        StoreController.refuseWhenCardBoxManaged(cardbox);
        var rows = jdbc.queryForList("SELECT tenant_id FROM users WHERE id = ?", UUID.class, id);
        if (rows.isEmpty()) throw ApiException.notFound("Person not found");
        UUID tenant = rows.getFirst();
        if ("staff".equals(body.role()) || Boolean.TRUE.equals(body.removed())) StoreController.keepAnOwner(jdbc, tenant, id);
        jdbc.update("""
                UPDATE users SET role = coalesce(?, role),
                    removed_at = CASE WHEN ?::boolean IS NULL THEN removed_at WHEN ?::boolean THEN coalesce(removed_at, now()) ELSE NULL END
                WHERE id = ?""", body.role(), body.removed(), body.removed(), id);
        return Map.of("ok", true);
    }

    /**
     * Says which CardBox store a Trading store's data belongs to. Sign-in links most stores by name on its own; this
     * fixes the rest. If sign-in already started an empty store for that CardBox store, the empty one gives way.
     */
    @PutMapping("/stores/{id}/cardbox")
    @Transactional
    public List<Map<String, Object>> linkStore(@PathVariable UUID id, @Valid @RequestBody LinkBody body) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, id);
        if (found == null || found == 0) throw ApiException.notFound("Store not found");
        if (body.cardboxStoreId() != null) {
            var other = jdbc.queryForList("SELECT id FROM tenants WHERE cardbox_store_id = ? AND id <> ?", UUID.class,
                    body.cardboxStoreId(), id);
            if (!other.isEmpty()) {
                Integer used = jdbc.queryForObject("""
                        SELECT (SELECT count(*) FROM trades WHERE tenant_id = ?) + (SELECT count(*) FROM inventory_items WHERE tenant_id = ?)""",
                        Integer.class, other.getFirst(), other.getFirst());
                if (used != null && used > 0)
                    throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                            "Another Trading store with trades or inventory already belongs to that CardBox store");
                jdbc.update("UPDATE tenants SET cardbox_store_id = NULL WHERE id = ?", other.getFirst());
            }
        }
        jdbc.update("UPDATE tenants SET cardbox_store_id = ? WHERE id = ?", body.cardboxStoreId(), id);
        return stores();
    }
}

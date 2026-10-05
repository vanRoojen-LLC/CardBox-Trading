package com.cardpricer.cloud.cardbox;

import com.cardpricer.cloud.auth.Auth0Client;
import com.cardpricer.cloud.clubsync.ClubSync;
import com.cardpricer.cloud.store.StoreController;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Sign-in with the CardBox link on. CardBox holds people, stores and roles; at each sign-in Trading asks CardBox
 * for the person's roles and copies them onto its own store rows, so trades, inventory and the session keep working
 * on Trading's ids while CardBox stays the one place roles are changed.
 */
@Component
public class CardBoxSignIn {
    /** One store role from CardBox. */
    public record StoreRole(String storeId, String storeName, String role) {}

    /** What CardBox said about the person. {@code known} is false when CardBox has no account for them (403). */
    public record Roles(boolean known, boolean platformOwner, List<StoreRole> stores) {}

    /** Sign-in can't go on; the message is shown on the sign-in page. */
    public static class Refused extends Exception {
        public Refused(String message) { super(message); }
    }

    private static final Logger log = LoggerFactory.getLogger(CardBoxSignIn.class);
    private final CardBoxClient cardbox;
    private final CardBoxTokens tokens;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ClubSync clubSync;
    private final int trialDays;

    public CardBoxSignIn(CardBoxClient cardbox, CardBoxTokens tokens, JdbcTemplate jdbc, TransactionTemplate transaction,
                         ClubSync clubSync, @Value("${app.trial-days:30}") int trialDays) {
        this.cardbox = cardbox;
        this.clubSync = clubSync;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.trialDays = trialDays;
    }

    /**
     * Links the person on CardBox, copies their store roles here, and returns the membership to sign them into:
     * the store they used last. Empty means they are on no store's team yet.
     */
    public Optional<UUID> signIn(Auth0Client.Identity identity, String email) throws Refused {
        if (identity.accessToken() == null) throw new Refused("Sign-in did not return CardBox access. Please try again.");
        CardBoxClient.Result result;
        try {
            result = cardbox.call(identity.accessToken(), "POST", "/api/partner/sign-in", null);
        } catch (CardBoxClient.Unavailable e) {
            throw new Refused(e.getMessage());
        }
        Roles roles;
        if (result.status() == 403) {
            // No CardBox account and CardBox's signup rule doesn't allow one: a plain user with no store.
            roles = new Roles(false, false, List.of());
        } else if (result.ok()) {
            roles = parse(result.body());
        } else {
            log.warn("CardBox partner sign-in answered {}: {}", result.status(), result.detail());
            throw new Refused("CardBox sign-in failed: " + result.detail());
        }
        Instant expires = identity.accessTokenExpiresAt() != null ? identity.accessTokenExpiresAt()
                : Instant.now().plus(Duration.ofHours(1));
        tokens.save(identity.sub(), identity.accessToken(), expires, roles.platformOwner());
        String name = identity.name() == null || identity.name().equalsIgnoreCase(email) ? email.split("@")[0] : identity.name();
        return transaction.execute(status -> copyRoles(identity.sub(), email, name.trim(), roles));
    }

    /**
     * Reads CardBox's roles answer (partner sign-in and account/roles):
     * {@code {"roles": ["user", "platform_owner", ...], "stores": [{"id", "name", "slug", "role"}]}},
     * with one {@code stores} entry per store role.
     */
    static Roles parse(JsonNode body) {
        boolean platformOwner = false;
        for (JsonNode role : body.path("roles")) platformOwner |= "platform_owner".equals(role.asText());
        // A person who is both manager and employee of a store counts as its manager.
        Map<String, StoreRole> byStore = new LinkedHashMap<>();
        for (JsonNode store : body.path("stores")) {
            String local = switch (store.path("role").asText("")) {
                case "store_manager" -> "owner";
                case "store_employee" -> "staff";
                default -> null;
            };
            String id = store.path("id").asText("");
            if (local == null || id.isEmpty()) continue;
            StoreRole previous = byStore.get(id);
            if (previous == null || "owner".equals(local)) byStore.put(id, new StoreRole(id, store.path("name").asText(""), local));
        }
        return new Roles(true, platformOwner, List.copyOf(byStore.values()));
    }

    private Optional<UUID> copyRoles(String sub, String email, String name, Roles roles) {
        List<UUID> kept = new ArrayList<>();
        for (StoreRole role : roles.stores()) {
            UUID tenant = tenantFor(role, sub);
            if (!role.storeName().isBlank())
                jdbc.update("UPDATE tenants SET name = ? WHERE id = ? AND name <> ?", role.storeName(), tenant, role.storeName());
            if (membership(tenant, sub, email, name, role.role())) kept.add(tenant);
        }
        // Roles taken away on CardBox end here too. Stores not linked to CardBox yet are left alone.
        jdbc.update("""
                UPDATE users SET removed_at = now() WHERE auth0_sub = ? AND removed_at IS NULL
                AND tenant_id IN (SELECT id FROM tenants WHERE cardbox_store_id IS NOT NULL) AND NOT (tenant_id = ANY (?))""",
                sub, kept.toArray(new UUID[0]));
        // So do the Club collections they synced into those stores, until an owner decides what happens to the cards.
        clubSync.pauseWithoutRole(sub, kept);
        var last = jdbc.queryForList("""
                SELECT u.id FROM users u JOIN tenants t ON t.id = u.tenant_id
                WHERE u.auth0_sub = ? AND u.removed_at IS NULL AND t.cardbox_store_id IS NOT NULL
                ORDER BY u.last_used_at DESC NULLS LAST, u.created_at LIMIT 1""", UUID.class, sub);
        if (last.isEmpty() && roles.platformOwner()) {
            // A platform owner needs no store role on CardBox: they open a Trading store they're already on, even
            // one not yet tied to CardBox, so they can reach the Admin tab and tie the stores up.
            last = jdbc.queryForList("""
                    SELECT id FROM users WHERE auth0_sub = ? AND removed_at IS NULL
                    ORDER BY last_used_at DESC NULLS LAST, created_at LIMIT 1""", UUID.class, sub);
        }
        if (last.isEmpty()) return Optional.empty();
        jdbc.update("UPDATE users SET last_used_at = now() WHERE id = ?", last.getFirst());
        return Optional.of(last.getFirst());
    }

    /**
     * Trading's row for a CardBox store. The first time a store is seen, a Trading store this person already runs
     * under the same name becomes its row, so its trades and inventory carry over; otherwise a new one starts in trial.
     */
    private UUID tenantFor(StoreRole role, String sub) {
        var linked = jdbc.queryForList("SELECT id FROM tenants WHERE cardbox_store_id = ?", UUID.class, role.storeId());
        if (!linked.isEmpty()) return linked.getFirst();
        var same = jdbc.queryForList("""
                SELECT DISTINCT t.id FROM tenants t JOIN users u ON u.tenant_id = t.id
                WHERE u.auth0_sub = ? AND u.removed_at IS NULL AND t.cardbox_store_id IS NULL
                AND lower(trim(t.name)) = lower(trim(?))""", UUID.class, sub, role.storeName());
        if (same.size() == 1
                && jdbc.update("UPDATE tenants SET cardbox_store_id = ? WHERE id = ? AND cardbox_store_id IS NULL",
                role.storeId(), same.getFirst()) == 1) return same.getFirst();
        UUID created = StoreController.openStore(jdbc, role.storeName().isBlank() ? "Store" : role.storeName(),
                Instant.now().plus(Duration.ofDays(trialDays)), role.storeId());
        // Someone else signed in to the same new store at the same moment and created it first.
        return created != null ? created
                : jdbc.queryForObject("SELECT id FROM tenants WHERE cardbox_store_id = ?", UUID.class, role.storeId());
    }

    /** Puts the person on the store with the role CardBox gave them. False if that store already has the email under another login. */
    private boolean membership(UUID tenant, String sub, String email, String name, String role) {
        // Writes that could break a unique index are guarded rather than caught: an error would end the transaction.
        if (jdbc.update("""
                UPDATE users SET role = ?, removed_at = NULL,
                    email = CASE WHEN EXISTS (SELECT 1 FROM users o WHERE o.tenant_id = users.tenant_id
                                              AND lower(o.email) = ? AND o.id <> users.id) THEN email ELSE ? END
                WHERE tenant_id = ? AND auth0_sub = ?""", role, email, email, tenant, sub) > 0) return true;
        if (jdbc.update("UPDATE users SET role = ?, auth0_sub = ?, removed_at = NULL WHERE tenant_id = ? AND lower(email) = ? AND auth0_sub IS NULL",
                role, sub, tenant, email) > 0) return true;
        if (jdbc.update("INSERT INTO users (id, tenant_id, email, name, auth0_sub, role) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(), tenant, email, name, sub, role) > 0) return true;
        log.warn("Store {} already has {} under another login; not copying the CardBox role", tenant, email);
        return false;
    }
}

package com.cardpricer.cloud.cardbox;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * The Team screen with the CardBox link on: the open store's team, kept on CardBox (cardbox.club).
 *
 * <p>Everything is scoped to the store the person has open here (a platform owner may name another store with
 * {@code ?store=}), so the screen never shows anyone who isn't on that store's team. New people join by email
 * invite: CardBox sends the email, and the person accepts on cardbox.club with a new or existing CardBox account
 * (whatever email it uses), which they then sign in here with. CardBox enforces who may do what: a store manager
 * handles employees, a platform owner handles managers too.
 *
 * <p>A change to a member is applied to this store's own membership row at once, so someone disabled or removed
 * is signed out here on their next request rather than at their next sign-in.
 */
@RestController
public class TeamController {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final CardBoxRelay relay;
    private final JdbcTemplate jdbc;

    public TeamController(CardBoxRelay relay, JdbcTemplate jdbc) {
        this.relay = relay;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/cardbox/team")
    public JsonNode team(@RequestParam(required = false) String store, HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        String storeId = cardboxStore(user, store);
        ObjectNode team = (ObjectNode) relay.call(user, "GET", "/api/stores/" + storeId + "/team", null).body();
        // The shared audit log, narrowed to this store; a platform owner's copy covers every store.
        ArrayNode events = JsonNodeFactory.instance.arrayNode();
        try {
            for (JsonNode event : relay.call(user, "GET", "/api/role-events?limit=300", null).body()) {
                if (storeId.equals(event.path("store_id").asText()) && events.size() < 60) events.add(event);
            }
        } catch (ApiException e) {
            // The history is a nicety; the team itself still shows.
        }
        team.set("events", events);
        return team;
    }

    @PostMapping("/api/cardbox/team/invites")
    public ResponseEntity<JsonNode> invite(@RequestBody JsonNode body, @RequestParam(required = false) String store,
                                           HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        var result = relay.call(user, "POST", "/api/stores/" + cardboxStore(user, store) + "/invites", body);
        return ResponseEntity.status(HttpStatus.CREATED).body(result.body());
    }

    @PostMapping("/api/cardbox/team/invites/{id}/resend")
    public JsonNode resend(@PathVariable String id, @RequestParam(required = false) String store, HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        return relay.call(user, "POST", "/api/stores/" + cardboxStore(user, store) + "/invites/" + id(id) + "/resend",
                JsonNodeFactory.instance.objectNode()).body();
    }

    @DeleteMapping("/api/cardbox/team/invites/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id, @RequestParam(required = false) String store,
                                       HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        relay.call(user, "DELETE", "/api/stores/" + cardboxStore(user, store) + "/invites/" + id(id), null);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/api/cardbox/team/members/{id}")
    public JsonNode update(@PathVariable String id, @RequestBody JsonNode body, @RequestParam(required = false) String store,
                           HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        String storeId = cardboxStore(user, store);
        JsonNode member = relay.call(user, "PATCH", "/api/stores/" + storeId + "/members/" + id(id), body).body();
        applyHere(storeId, member);
        return withoutIdentity(member);
    }

    @DeleteMapping("/api/cardbox/team/members/{id}")
    public JsonNode remove(@PathVariable String id, @RequestParam(required = false) String store, HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        String storeId = cardboxStore(user, store);
        JsonNode member = relay.call(user, "DELETE", "/api/stores/" + storeId + "/members/" + id(id), null).body();
        applyHere(storeId, member);
        return withoutIdentity(member);
    }

    /** The CardBox store behind the store open in this session, or (for a platform owner) the one asked for. */
    private String cardboxStore(CurrentUser user, String asked) {
        if (!relay.enabled()) throw ApiException.notFound("Not found");
        if (asked != null && !asked.isBlank()) {
            if (!user.admin()) throw ApiException.forbidden("Only a platform owner can manage another store's team");
            return id(asked);
        }
        var ids = jdbc.queryForList("SELECT cardbox_store_id FROM tenants WHERE id = ?", String.class, user.tenantId());
        if (ids.isEmpty() || ids.getFirst() == null)
            throw new ApiException(HttpStatus.CONFLICT, "This store isn't tied to a CardBox store yet. A platform owner can tie it on the Admin tab.");
        return id(ids.getFirst());
    }

    private static String id(String id) {
        if (id == null || !ID.matcher(id).matches()) throw ApiException.notFound("Not found");
        return id;
    }

    /** Mirrors CardBox's answer on this store's membership row: a role change, a disable, or a removal. */
    private void applyHere(String cardboxStoreId, JsonNode member) {
        String sub = member.path("auth0_sub").asText("");
        if (sub.isEmpty()) return;
        String status = member.path("status").asText("");
        if ("active".equals(status)) {
            String role = "store_manager".equals(member.path("role").asText()) ? "owner" : "staff";
            jdbc.update("""
                    UPDATE users SET role = ?, removed_at = NULL
                    WHERE auth0_sub = ? AND tenant_id = (SELECT id FROM tenants WHERE cardbox_store_id = ?)""",
                    role, sub, cardboxStoreId);
        } else if ("disabled".equals(status) || "removed".equals(status)) {
            jdbc.update("""
                    UPDATE users SET removed_at = now()
                    WHERE auth0_sub = ? AND removed_at IS NULL AND tenant_id = (SELECT id FROM tenants WHERE cardbox_store_id = ?)""",
                    sub, cardboxStoreId);
        }
    }

    private static JsonNode withoutIdentity(JsonNode member) {
        if (member instanceof ObjectNode object) object.remove("auth0_sub");
        return member;
    }
}

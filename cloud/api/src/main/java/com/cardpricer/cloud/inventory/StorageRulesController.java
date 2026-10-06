package com.cardpricer.cloud.inventory;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** "What goes here" on each storage spot. Everyone sees the rules and tries edits; owners save them. */
@RestController
@RequestMapping("/api/app/storage")
public class StorageRulesController {
    public record RuleBody(Map<String, List<String>> conditions) {}
    public record SettingsBody(boolean fileOnArrival) {}

    private static final InventoryQuery.Where ALL = new InventoryQuery.Where("true", List.of());

    private final StorageRules rules;
    private final InventoryRepository inventory;
    private final JdbcTemplate jdbc;
    private final PutAway putAway;

    public StorageRulesController(StorageRules rules, InventoryRepository inventory, JdbcTemplate jdbc, PutAway putAway) {
        this.rules = rules;
        this.putAway = putAway;
        this.inventory = inventory;
        this.jdbc = jdbc;
    }

    /** Every rule, with how many cards in stock it takes (in the spot or inside it) and how many of those aren't put away. */
    @GetMapping("/rules")
    public List<Map<String, Object>> list(HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        var counts = counts(tenant, rules.destinations(tenant, ALL, null, null));
        List<Map<String, Object>> out = new ArrayList<>();
        rules.rules(tenant).forEach((spot, conditions) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("spotId", spot);
            view.put("conditions", conditions);
            long[] c = counts.getOrDefault(spot, new long[2]);
            view.put("cards", c[0]);
            view.put("waiting", c[1]);
            out.add(view);
        });
        return out;
    }

    @PutMapping("/{id}/rule")
    public List<Map<String, Object>> save(@PathVariable UUID id, @RequestBody RuleBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        rules.save(user.tenantId(), id, user.userId(), body.conditions());
        return list(request);
    }

    @DeleteMapping("/{id}/rule")
    public List<Map<String, Object>> remove(@PathVariable UUID id, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        inventory.spot(user.tenantId(), id);
        rules.remove(user.tenantId(), id);
        return list(request);
    }

    /** How the store uses its rules: whether arriving cards are filed by them straight away. */
    @GetMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest request) {
        return Map.of("fileOnArrival", putAway.filesOnArrival(CurrentUser.of(request).tenantId()));
    }

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody SettingsBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        putAway.setFilesOnArrival(user.tenantId(), body.fileOnArrival());
        return settings(request);
    }

    /** What a spot would take with this rule (null conditions: with no rule), before it is saved. */
    @PostMapping("/{id}/rule/preview")
    public Map<String, Object> preview(@PathVariable UUID id, @RequestBody RuleBody body, HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        inventory.spot(tenant, id);
        long[] c = counts(tenant, rules.destinations(tenant, ALL, id, body.conditions() == null ? null : body.conditions()))
                .getOrDefault(id, new long[2]);
        return Map.of("cards", c[0], "waiting", c[1]);
    }

    /** Cards and not-put-away cards each spot takes, counting everything sent to spots inside it. */
    private Map<UUID, long[]> counts(UUID tenant, Map<UUID, UUID> destinations) {
        Map<UUID, UUID> parents = new HashMap<>();
        for (var spot : inventory.spots(tenant)) parents.put(spot.id(), spot.parentId());
        Map<UUID, long[]> out = new HashMap<>();
        jdbc.query("SELECT id, quantity, storage_id IS NULL FROM inventory_items WHERE tenant_id = ?", rs -> {
            UUID to = destinations.get(rs.getObject(1, UUID.class));
            for (UUID at = to; at != null; at = parents.get(at)) {
                long[] c = out.computeIfAbsent(at, k -> new long[2]);
                c[0] += rs.getInt(2);
                if (rs.getBoolean(3)) c[1] += rs.getInt(2);
            }
        }, tenant);
        return out;
    }

    private static CurrentUser requireOwner(HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        if (!user.owner()) throw ApiException.forbidden("Only a store owner can change what goes where");
        return user;
    }
}

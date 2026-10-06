package com.cardpricer.cloud.inventory;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inventory searches a store keeps by name: the filters, search and sort, shared by everyone on the store. Anyone
 * can save one; the person who saved it or an owner removes it.
 */
@RestController
@RequestMapping("/api/app/inventory/views")
public class InventoryViewsController {
    public record ViewBody(@NotBlank @Size(max = 60) String name, Map<String, List<String>> filters, String sort, String dir) {}

    private static final TypeReference<Map<String, List<String>>> FILTERS = new TypeReference<>() {};
    /** Saved views per store; enough for every way a store sorts, few enough to fit the tab bar's menu. */
    private static final int MAX = 50;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public InventoryViewsController(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        return jdbc.query("""
                SELECT v.id, v.name, v.filters::text, v.sort, v.dir, v.created_by, u.name AS by FROM inventory_views v
                LEFT JOIN users u ON u.id = v.created_by WHERE v.tenant_id = ? ORDER BY lower(v.name)""", (rs, i) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", rs.getObject(1, UUID.class));
            view.put("name", rs.getString(2));
            view.put("filters", parse(rs.getString(3)));
            view.put("sort", rs.getString(4));
            view.put("dir", rs.getString(5));
            view.put("by", rs.getString(7));
            view.put("canRemove", user.owner() || user.userId().equals(rs.getObject(6, UUID.class)));
            return view;
        }, user.tenantId());
    }

    /** Saves the current search under a name; saving again under the same name replaces it. */
    @PostMapping
    public List<Map<String, Object>> save(@Valid @RequestBody ViewBody body, HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        Map<String, List<String>> filters = new LinkedHashMap<>();
        if (body.filters() != null) body.filters().forEach((k, v) -> {
            if (v == null || List.of("ids", "offset", "limit", "sort", "dir").contains(k)) return;
            List<String> kept = v.stream().filter(x -> x != null && !x.isBlank()).toList();
            if (!kept.isEmpty()) filters.put(k, kept);
        });
        // Checks the filters make a valid search before keeping them.
        Map<String, List<String>> plain = new LinkedHashMap<>(filters);
        if (List.of("misplaced").equals(plain.remove("rules"))) filters.put("rules", List.of("misplaced"));
        else filters.remove("rules");
        new InventoryQuery(user.tenantId(), plain).where();
        InventoryQuery.orderBy(body.sort(), body.dir());
        Integer count = jdbc.queryForObject("SELECT count(*) FROM inventory_views WHERE tenant_id = ? AND lower(name) <> lower(?)",
                Integer.class, user.tenantId(), body.name().trim());
        if (count != null && count >= MAX) throw ApiException.badRequest("A store keeps up to " + MAX + " saved views; remove one first");
        String text;
        try {
            text = json.writeValueAsString(filters);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("That view can't be saved");
        }
        try {
            jdbc.update("""
                    INSERT INTO inventory_views (id, tenant_id, name, filters, sort, dir, created_by) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
                    ON CONFLICT (tenant_id, name) DO UPDATE SET filters = EXCLUDED.filters, sort = EXCLUDED.sort, dir = EXCLUDED.dir""",
                    UUID.randomUUID(), user.tenantId(), body.name().trim(), text,
                    body.sort() == null ? "name" : body.sort(), "desc".equalsIgnoreCase(body.dir()) ? "desc" : "asc", user.userId());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "There is already a view with that name");
        }
        return list(request);
    }

    @DeleteMapping("/{id}")
    public List<Map<String, Object>> remove(@PathVariable UUID id, HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        int removed = jdbc.update("DELETE FROM inventory_views WHERE id = ? AND tenant_id = ? AND (? OR created_by = ?)",
                id, user.tenantId(), user.owner(), user.userId());
        if (removed == 0) throw ApiException.forbidden("Only the person who saved a view, or an owner, can remove it");
        return list(request);
    }

    private Map<String, List<String>> parse(String text) {
        try {
            return json.readValue(text, FILTERS);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}

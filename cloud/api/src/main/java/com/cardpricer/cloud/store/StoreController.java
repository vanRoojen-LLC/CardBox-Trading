package com.cardpricer.cloud.store;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.cardbox.CardBoxClient;
import com.cardpricer.cloud.cardbox.CardBoxTokens;
import com.cardpricer.cloud.cardbox.StoreNames;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.cardpricer.cloud.web.ApiException;
import com.cardpricer.model.BuyRateRule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Store settings: profile, locations, buy rates and the people on the store. Changes are owner-only. */
@RestController
@RequestMapping("/api/app")
public class StoreController {
    public record Rule(@NotNull BigDecimal thresholdMin, @NotNull BigDecimal creditRate, @NotNull BigDecimal checkRate) {}
    public record RatesBody(@NotNull @Size(min = 1, max = 20) List<@Valid Rule> rules) {}
    /** {@code adjust} is the share of the normal offer paid at that level: 1 keeps it, 0.9 pays 90% of it. */
    public record ConfidenceRule(@NotNull @Pattern(regexp = "high|medium|low") String level, @NotNull BigDecimal adjust,
                                 boolean review) {}
    public record ConfidenceBody(@NotNull @Size(min = 1, max = 3) List<@Valid ConfidenceRule> rules) {}
    /** Staff sign in through Auth0 with this email; their account links on their first sign-in. */
    public record StaffBody(@NotBlank @Size(max = 120) String name, @NotBlank @Email String email,
                            @Pattern(regexp = "owner|staff") String role) {}
    public record RoleBody(@NotNull @Pattern(regexp = "owner|staff") String role) {}
    public record ProfileBody(@NotBlank @Size(max = 120) String name, @Size(max = 200) String website,
                              @Size(max = 40) String phone, @Email @Size(max = 200) String contactEmail) {}
    public record LocationBody(@NotBlank @Size(max = 80) String name, @Size(max = 300) String address,
                               @Size(max = 40) String phone, Boolean archived) {}

    private final RateRepository rates;
    private final ConfidenceRules confidenceRules;
    private final JdbcTemplate jdbc;
    private final CardBoxClient cardbox;
    private final CardBoxTokens tokens;
    private final StoreNames storeNames;

    public StoreController(RateRepository rates, ConfidenceRules confidenceRules, JdbcTemplate jdbc, CardBoxClient cardbox,
                           CardBoxTokens tokens, StoreNames storeNames) {
        this.rates = rates;
        this.confidenceRules = confidenceRules;
        this.jdbc = jdbc;
        this.cardbox = cardbox;
        this.tokens = tokens;
        this.storeNames = storeNames;
    }

    /**
     * Creates a store in trial with one location and default rates (50% credit, 40% check). With a CardBox store id,
     * returns null if that store already has a row here.
     */
    public static UUID openStore(JdbcTemplate jdbc, String name, Instant trialEnds, String cardboxStoreId) {
        UUID tenant = UUID.randomUUID();
        if (jdbc.update("INSERT INTO tenants (id, name, trial_ends_at, cardbox_store_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                tenant, name.trim(), Timestamp.from(trialEnds), cardboxStoreId) == 0) return null;
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Main')", UUID.randomUUID(), tenant);
        jdbc.update("INSERT INTO buy_rate_rules (tenant_id, threshold_min, credit_rate, check_rate) VALUES (?, ?, ?, ?)",
                tenant, BigDecimal.ZERO, new BigDecimal("0.50"), new BigDecimal("0.40"));
        return tenant;
    }

    /** With the CardBox link on, the team and the store's name are managed through CardBox. */
    public static void refuseWhenCardBoxManaged(CardBoxClient cardbox) {
        if (cardbox.enabled())
            throw new ApiException(HttpStatus.CONFLICT, "People and roles are managed through CardBox now. Reload to see the new Team screen.");
    }

    /** The store profile and its locations; every member reads it so a register can pick its location. */
    @GetMapping("/store")
    public Map<String, Object> store(HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        Map<String, Object> store = new HashMap<>(jdbc.queryForMap("""
                SELECT name, website, phone, contact_email AS "contactEmail", cardbox_store_id IS NOT NULL AS "onCardBox"
                FROM tenants WHERE id = ?""", tenant));
        // A store on CardBox is renamed there, which only a platform owner may do; Trading passes the rename on.
        store.put("canRename", !cardbox.enabled() || !(Boolean) store.get("onCardBox") || CurrentUser.of(request).admin());
        store.put("locations", jdbc.queryForList("""
                SELECT id, name, address, phone, archived_at IS NOT NULL AS archived FROM locations
                WHERE tenant_id = ? ORDER BY archived_at IS NOT NULL, created_at""", tenant));
        return store;
    }

    @PutMapping("/store")
    public Map<String, Object> saveStore(@Valid @RequestBody ProfileBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        boolean onCardBox = cardbox.enabled() && renameOnCardBox(user, body.name().trim());
        jdbc.update("UPDATE tenants SET name = CASE WHEN ? THEN name ELSE ? END, website = ?, phone = ?, contact_email = ? WHERE id = ?",
                onCardBox, body.name().trim(), website(body.website()), clean(body.phone()),
                clean(body.contactEmail()).toLowerCase(Locale.ROOT), user.tenantId());
        return store(request);
    }

    /**
     * A store tied to CardBox has CardBox's name: a platform owner's new one is sent there as them and copied back as
     * CardBox stored it. Links and sync go by the store's id, so a rename breaks nothing. False when the store isn't
     * on CardBox yet, so its name is Trading's own and changes here.
     */
    private boolean renameOnCardBox(CurrentUser user, String name) {
        var row = jdbc.queryForMap("SELECT name, cardbox_store_id FROM tenants WHERE id = ?", user.tenantId());
        String storeId = (String) row.get("cardbox_store_id");
        if (storeId == null) return false;
        // Others keep the name as it is, as the Store page shows them, and still save the rest of the details.
        if (name.equals(row.get("name")) || !user.admin()) return true;
        String token = tokens.find(user.auth0Sub()).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "Your CardBox sign-in has expired. Please sign out and sign in again."));
        CardBoxClient.Result result;
        try {
            result = cardbox.call(token, "PATCH", "/api/stores/" + URLEncoder.encode(storeId, StandardCharsets.UTF_8),
                    JsonNodeFactory.instance.objectNode().put("name", name));
        } catch (CardBoxClient.Unavailable e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        if (!result.ok()) {
            if (result.status() == 401) tokens.forget(user.auth0Sub());
            HttpStatus status = HttpStatus.resolve(result.status());
            throw new ApiException(status == null ? HttpStatus.BAD_GATEWAY : status, result.detail());
        }
        storeNames.adoptFrom(result.body());
        return true;
    }

    @PostMapping("/locations")
    public Map<String, Object> addLocation(@Valid @RequestBody LocationBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        try {
            jdbc.update("INSERT INTO locations (id, tenant_id, name, address, phone) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), user.tenantId(), body.name().trim(), clean(body.address()), clean(body.phone()));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "This store already has a location with that name");
        }
        return store(request);
    }

    /** Renames or edits a location; {@code archived} closes or reopens it. A store always keeps one open location. */
    @PutMapping("/locations/{id}")
    @Transactional
    public Map<String, Object> saveLocation(@PathVariable UUID id, @Valid @RequestBody LocationBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        // Lock the store so two owners can't each archive one of the last two open locations.
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, user.tenantId());
        var current = jdbc.queryForList("SELECT archived_at IS NOT NULL FROM locations WHERE id = ? AND tenant_id = ?",
                Boolean.class, id, user.tenantId());
        if (current.isEmpty()) throw ApiException.notFound("Location not found");
        boolean archive = body.archived() != null ? body.archived() : current.getFirst();
        if (archive && !current.getFirst()) {
            Integer open = jdbc.queryForObject("SELECT count(*) FROM locations WHERE tenant_id = ? AND archived_at IS NULL",
                    Integer.class, user.tenantId());
            if (open != null && open <= 1) throw ApiException.badRequest("A store needs at least one open location");
        }
        try {
            jdbc.update("""
                    UPDATE locations SET name = ?, address = ?, phone = ?,
                        archived_at = CASE WHEN ? THEN coalesce(archived_at, now()) ELSE NULL END
                    WHERE id = ? AND tenant_id = ?""",
                    body.name().trim(), clean(body.address()), clean(body.phone()), archive, id, user.tenantId());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "This store already has a location with that name");
        }
        return store(request);
    }

    @GetMapping("/rates")
    public Map<String, Object> rates(HttpServletRequest request) {
        return Map.of("rules", rates.rules(CurrentUser.of(request).tenantId()).stream()
                .map(r -> new Rule(r.thresholdMin, r.creditRate, r.checkRate)).toList());
    }

    @PutMapping("/rates")
    public Map<String, Object> saveRates(@Valid @RequestBody RatesBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        var seen = new HashSet<BigDecimal>();
        List<BuyRateRule> rules = body.rules().stream().map(r -> {
            var rule = new BuyRateRule(r.thresholdMin().setScale(2, java.math.RoundingMode.HALF_UP), r.creditRate(), r.checkRate());
            if (!seen.add(rule.thresholdMin)) throw ApiException.badRequest("Each threshold can only appear once");
            return rule;
        }).toList();
        if (!seen.contains(new BigDecimal("0.00")))
            throw ApiException.badRequest("Include a $0.00 threshold so every card has a rate");
        rates.replace(user.tenantId(), rules);
        return rates(request);
    }

    @GetMapping("/confidence-rules")
    public Map<String, Object> confidenceRules(HttpServletRequest request) {
        return Map.of("rules", confidenceRules.rules(CurrentUser.of(request).tenantId()).stream()
                .map(r -> new ConfidenceRule(r.level().name(), r.adjust(), r.review())).toList());
    }

    @PutMapping("/confidence-rules")
    public Map<String, Object> saveConfidenceRules(@Valid @RequestBody ConfidenceBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        var seen = new HashSet<String>();
        List<ConfidenceRules.Rule> rules = body.rules().stream().map(r -> {
            if (!seen.add(r.level())) throw ApiException.badRequest("Each confidence level can only appear once");
            if (r.adjust().compareTo(new BigDecimal("0.01")) < 0 || r.adjust().compareTo(BigDecimal.ONE) > 0)
                throw ApiException.badRequest("The offer share must be between 1% and 100%");
            return new ConfidenceRules.Rule(com.cardpricer.cloud.catalog.Confidence.Level.valueOf(r.level()),
                    r.adjust().setScale(4, java.math.RoundingMode.HALF_UP), r.review());
        }).toList();
        confidenceRules.replace(user.tenantId(), rules);
        return confidenceRules(request);
    }

    @GetMapping("/staff")
    public List<Map<String, Object>> staff(HttpServletRequest request) {
        return jdbc.queryForList("""
                SELECT id, name, email, role, auth0_sub IS NOT NULL AS joined FROM users
                WHERE tenant_id = ? AND removed_at IS NULL ORDER BY role, name""",
                CurrentUser.of(request).tenantId());
    }

    /**
     * Adds a person by email as staff or as another owner. Someone who already has a CardBox login (in another store,
     * say) joins with that login straight away; someone removed earlier is brought back.
     */
    @PostMapping("/staff")
    public List<Map<String, Object>> addStaff(@Valid @RequestBody StaffBody body, HttpServletRequest request) {
        refuseWhenCardBoxManaged(cardbox);
        CurrentUser user = requireOwner(request);
        String role = body.role() == null ? "staff" : body.role();
        addMember(jdbc, user.tenantId(), body.name(), body.email(), role);
        return staff(request);
    }

    /** Puts a person on a store's team by email; shared with the platform admin. */
    public static void addMember(JdbcTemplate jdbc, UUID tenant, String name, String rawEmail, String role) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        int restored = jdbc.update("""
                UPDATE users SET removed_at = NULL, name = ?, role = ?
                WHERE tenant_id = ? AND lower(email) = ? AND removed_at IS NOT NULL""", name.trim(), role, tenant, email);
        if (restored > 0) return;
        var login = jdbc.queryForList("SELECT auth0_sub FROM users WHERE lower(email) = ? AND auth0_sub IS NOT NULL LIMIT 1",
                String.class, email);
        try {
            jdbc.update("INSERT INTO users (id, tenant_id, email, name, role, auth0_sub) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenant, email, name.trim(), role, login.isEmpty() ? null : login.getFirst());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "That person is already on this store's team");
        }
    }

    /** Makes someone an owner or staff. The store always keeps at least one owner. */
    @PutMapping("/staff/{id}")
    @Transactional
    public List<Map<String, Object>> setRole(@PathVariable UUID id, @Valid @RequestBody RoleBody body, HttpServletRequest request) {
        refuseWhenCardBoxManaged(cardbox);
        CurrentUser user = requireOwner(request);
        if ("staff".equals(body.role())) keepAnOwner(jdbc, user.tenantId(), id);
        if (jdbc.update("UPDATE users SET role = ? WHERE id = ? AND tenant_id = ? AND removed_at IS NULL",
                body.role(), id, user.tenantId()) == 0) throw ApiException.notFound("Person not found");
        return staff(request);
    }

    /** Takes someone off the store. Their past trades still show their name. */
    @PostMapping("/staff/{id}/remove")
    @Transactional
    public List<Map<String, Object>> remove(@PathVariable UUID id, HttpServletRequest request) {
        refuseWhenCardBoxManaged(cardbox);
        CurrentUser user = requireOwner(request);
        keepAnOwner(jdbc, user.tenantId(), id);
        if (jdbc.update("UPDATE users SET removed_at = now() WHERE id = ? AND tenant_id = ? AND removed_at IS NULL",
                id, user.tenantId()) == 0) throw ApiException.notFound("Person not found");
        return staff(request);
    }

    /** Refuses a change that would leave the store without an owner when {@code leaving} stops being one. */
    public static void keepAnOwner(JdbcTemplate jdbc, UUID tenant, UUID leaving) {
        // Lock the store so two owners can't demote each other at the same moment.
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, tenant);
        Integer others = jdbc.queryForObject("""
                SELECT count(*) FROM users WHERE tenant_id = ? AND role = 'owner' AND removed_at IS NULL AND id <> ?""",
                Integer.class, tenant, leaving);
        if (others == null || others == 0) throw ApiException.badRequest("A store needs at least one owner. Make someone else an owner first.");
    }

    private static CurrentUser requireOwner(HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        if (!user.owner()) throw ApiException.forbidden("Only a store owner can change this");
        return user;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** Stores the website as a full https link so it can be shown as one; refuses anything but a web address. */
    static String website(String value) {
        String site = clean(value);
        if (site.isEmpty()) return "";
        if (!site.matches("(?i)https?://.*")) site = "https://" + site;
        try {
            var uri = java.net.URI.create(site);
            if (uri.getHost() == null || !uri.getHost().contains(".")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Enter the website as an address like example.com");
        }
        return site;
    }
}

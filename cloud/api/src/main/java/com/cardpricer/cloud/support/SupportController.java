package com.cardpricer.cloud.support;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Help & feedback" from the store app. Anyone signed in can send a report (AuthFilter guards /api/support/** with a
 * session only, so a store whose trial ended can still ask for help); the store and person come from the session.
 * Reports are private to the platform owner, who lists them under /api/admin. {@link SupportReportFiler} files a
 * sanitized copy as a GitHub issue when that is switched on.
 */
@RestController
public class SupportController {
    /** What the browser collects on its own. Only these keys are kept, each bounded. */
    public record Environment(@Size(max = 400) String userAgent,
                              @Pattern(regexp = "\\d{1,5}x\\d{1,5}") String viewport,
                              @Pattern(regexp = "[A-Za-z0-9._-]{1,40}") String build,
                              @Pattern(regexp = "[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}") String language,
                              @Size(max = 5) List<@NotNull @Size(max = 500) String> recentErrors) {}

    public record ReportBody(@NotBlank @Pattern(regexp = "bug|question|idea") String kind,
                             @NotBlank @Size(max = 5000) String description,
                             @Size(max = 500) String page,
                             @Valid Environment environment) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final int perHour;

    public SupportController(JdbcTemplate jdbc, ObjectMapper json, @Value("${app.support.reports-per-hour:10}") int perHour) {
        this.jdbc = jdbc;
        this.json = json;
        this.perHour = perHour;
    }

    @PostMapping("/api/support/reports")
    public ResponseEntity<Map<String, Object>> report(@Valid @RequestBody ReportBody body, HttpServletRequest request) throws Exception {
        CurrentUser user = CurrentUser.of(request);
        Integer recent = jdbc.queryForObject(
                "SELECT count(*) FROM support_reports WHERE user_id = ? AND created_at > now() - interval '1 hour'", Integer.class,
                user.userId());
        if (recent != null && recent >= perHour)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "You've sent a lot of reports this hour. Please try again later.");
        var environment = new LinkedHashMap<String, Object>();
        var env = body.environment();
        if (env != null) {
            if (env.userAgent() != null && !env.userAgent().isBlank()) environment.put("userAgent", oneLine(env.userAgent()));
            if (env.viewport() != null) environment.put("viewport", env.viewport());
            if (env.build() != null) environment.put("build", env.build());
            if (env.language() != null) environment.put("language", env.language());
            if (env.recentErrors() != null && !env.recentErrors().isEmpty())
                environment.put("recentErrors", env.recentErrors().stream().map(SupportController::oneLine).toList());
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO support_reports (id, tenant_id, user_id, kind, description, page, environment)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)""",
                id, user.tenantId(), user.userId(), body.kind(), body.description().strip(), page(body.page()),
                json.writeValueAsString(environment));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", id, "status", "saved"));
    }

    /** Newest first, a page at a time. */
    @GetMapping("/api/admin/support-reports")
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        int limit = Math.clamp(size, 1, 200);
        long offset = (long) Math.clamp(page, 0, 100_000) * limit;
        var reports = jdbc.queryForList("""
                SELECT r.id, r.kind, r.description, r.page, r.environment::text AS environment, r.status,
                       r.github_issue_number AS "githubIssueNumber", r.github_issue_url AS "githubIssueUrl",
                       r.github_state AS "githubState", r.attempt_count AS "attemptCount", r.last_error AS "lastError",
                       r.created_at AS "createdAt", r.tenant_id AS "storeId", t.name AS store,
                       u.name AS "reporterName", u.email AS "reporterEmail"
                FROM support_reports r JOIN tenants t ON t.id = r.tenant_id JOIN users u ON u.id = r.user_id
                ORDER BY r.created_at DESC, r.id LIMIT ? OFFSET ?""", limit, offset);
        for (var row : reports) {
            try {
                row.put("environment", json.readTree((String) row.get("environment")));
            } catch (Exception e) {
                row.put("environment", Map.of());
            }
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM support_reports", Long.class);
        return Map.of("reports", reports, "total", total == null ? 0 : total, "page", Math.clamp(page, 0, 100_000), "size", limit);
    }

    /** Just the route: no query string or fragment, which can carry search terms or phone numbers. */
    static String page(String page) {
        if (page == null) return "";
        String path = page.strip();
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        path = path.replaceAll("[^A-Za-z0-9/_.~-]", "");
        return path.length() > 200 ? path.substring(0, 200) : path;
    }

    static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}

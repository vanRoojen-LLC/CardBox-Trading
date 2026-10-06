package com.cardpricer.cloud.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Files saved support reports as GitHub issues, a few at a time. Each run claims due reports with
 * {@code FOR UPDATE SKIP LOCKED} and a short lease, so two replicas (or a run that overlaps a slow one) never file the
 * same report twice, and a replica that dies mid-call only delays a report until its lease ends. Failures back off
 * exponentially; after {@link #MAX_ATTEMPTS} tries, or a refusal GitHub won't change its mind about (401, 403, 404,
 * 422), the report is marked failed with a short error. The report itself is always kept.
 *
 * <p>The issue is public to anyone who can read the repo, so it carries no names, emails or store names: just the
 * kind, the build, the route, browser and viewport, internal ids, and the person's own words with email addresses
 * taken out.
 */
@Component
public class SupportReportFiler {
    static final int MAX_ATTEMPTS = 5;
    static final List<String> BASE_LABELS = List.of("product:trading", "surface:web", "source:user-report", "needs-triage");
    private static final Logger log = LoggerFactory.getLogger(SupportReportFiler.class);

    /** Scheduling is only used for this job. */
    @Configuration
    @EnableScheduling
    static class Scheduling {}

    record Claimed(UUID id, UUID tenantId, String kind, String description, String page, String environment, int attempt) {}

    private final JdbcTemplate jdbc;
    private final GitHubIssues github;
    private final ObjectMapper json = new ObjectMapper();
    private final String adminUrl;

    public SupportReportFiler(JdbcTemplate jdbc, GitHubIssues github,
                              @Value("${app.support.admin-url:https://cardbox.trading/app/admin}") String adminUrl) {
        this.jdbc = jdbc;
        this.github = github;
        this.adminUrl = adminUrl;
    }

    @Scheduled(initialDelayString = "${app.support.filer-initial-delay-ms:20000}", fixedDelayString = "${app.support.filer-delay-ms:30000}")
    public void scheduled() {
        try {
            fileDue();
        } catch (RuntimeException e) {
            log.warn("Filing support reports failed: {}", e.toString());
        }
    }

    /** Files what is due now. Returns how many reports were tried. Does nothing while filing is off. */
    public int fileDue() {
        if (!github.enabled()) return 0;
        var claimed = jdbc.query("""
                UPDATE support_reports SET attempt_count = attempt_count + 1, leased_until = now() + interval '5 minutes',
                                           updated_at = now()
                WHERE id IN (SELECT id FROM support_reports
                             WHERE status = 'saved' AND next_attempt_at <= now() AND (leased_until IS NULL OR leased_until < now())
                             ORDER BY next_attempt_at LIMIT 10 FOR UPDATE SKIP LOCKED)
                RETURNING id, tenant_id, kind, description, page, environment::text, attempt_count""",
                (rs, i) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7)));
        for (Claimed report : claimed) file(report);
        return claimed.size();
    }

    private void file(Claimed report) {
        String title = title(report.kind(), report.description());
        String body = body(report);
        var labels = new java.util.ArrayList<String>();
        labels.add(typeLabel(report.kind()));
        labels.addAll(BASE_LABELS);
        try {
            var result = github.create(title, body, labels);
            // The token may not be allowed to create missing labels; the issue matters more than its labels.
            if (result.status() == 422) result = github.create(title, body, List.of());
            if (result.created()) {
                jdbc.update("""
                        UPDATE support_reports SET status = 'filed', github_issue_number = ?, github_issue_url = ?, github_state = ?,
                                                   leased_until = NULL, last_error = NULL, updated_at = now()
                        WHERE id = ?""",
                        result.body().path("number").isNumber() ? result.body().path("number").asInt() : null,
                        result.body().path("html_url").asText(null), result.body().path("state").asText("open"), report.id());
                return;
            }
            String error = "GitHub answered " + result.status() + (result.message().isEmpty() ? "" : ": " + result.message());
            boolean terminal = !result.rateLimited() && List.of(401, 403, 404, 410, 422).contains(result.status());
            failed(report, error, terminal);
        } catch (GitHubIssues.Unavailable e) {
            failed(report, e.getMessage(), false);
        } catch (RuntimeException e) {
            log.warn("Filing support report {} failed: {}", report.id(), e.toString());
            failed(report, "Unexpected error", false);
        }
    }

    private void failed(Claimed report, String error, boolean terminal) {
        boolean giveUp = terminal || report.attempt() >= MAX_ATTEMPTS;
        // 1, 2, 4, 8 minutes between tries.
        long backoffSeconds = 60L << Math.min(report.attempt() - 1, 6);
        jdbc.update("""
                UPDATE support_reports SET status = ?, last_error = ?, leased_until = NULL,
                                           next_attempt_at = now() + make_interval(secs => ?), updated_at = now()
                WHERE id = ?""", giveUp ? "failed" : "saved", error.length() > 300 ? error.substring(0, 300) : error,
                (double) backoffSeconds, report.id());
        log.info("Support report {} not filed ({}): {}", report.id(), giveUp ? "giving up" : "will retry", error);
    }

    static String typeLabel(String kind) {
        return switch (kind) {
            case "bug" -> "type:bug";
            case "idea" -> "type:idea";
            default -> "type:support";
        };
    }

    static String title(String kind, String description) {
        String line = redact(description).replaceAll("\\s+", " ").strip();
        if (line.length() > 70) line = line.substring(0, 70).strip() + "…";
        return "[Trading web] " + kind + ": " + line;
    }

    String body(Claimed report) {
        JsonNode env;
        try {
            env = json.readTree(report.environment() == null ? "{}" : report.environment());
        } catch (Exception e) {
            env = json.createObjectNode();
        }
        var b = new StringBuilder();
        b.append("| | |\n|---|---|\n");
        row(b, "Kind", report.kind());
        row(b, "Product", "CardBox Trading");
        row(b, "Surface", "web");
        row(b, "Build", env.path("build").asText(""));
        row(b, "Page", report.page());
        row(b, "Browser", env.path("userAgent").asText(""));
        row(b, "Viewport", env.path("viewport").asText(""));
        row(b, "Report id", report.id().toString());
        row(b, "Store id", report.tenantId().toString());
        int errors = env.path("recentErrors").size();
        if (errors > 0) row(b, "Client errors", errors + " recent, on the admin page");
        b.append("\n### Description\n\n");
        for (String line : redact(report.description()).split("\\R", -1)) b.append("> ").append(line).append('\n');
        b.append("\nReporter, store and full details: ").append(adminUrl).append("?report=").append(report.id())
                .append("#support-reports (platform owner only)\n");
        return b.toString();
    }

    private static void row(StringBuilder b, String name, String value) {
        String cell = value == null ? "" : value.replaceAll("\\s+", " ").replace("|", "\\|").replace("`", "'");
        if (cell.length() > 300) cell = cell.substring(0, 300);
        b.append("| ").append(name).append(" | ").append(cell.isEmpty() ? "—" : "`" + cell + "`").append(" |\n");
    }

    /**
     * Takes email addresses out of what the person wrote (they may have typed their own) and breaks @mentions, so a
     * report can't ping anyone on GitHub.
     */
    static String redact(String text) {
        if (text == null) return "";
        return text.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[email removed]")
                .replaceAll("@(?=[A-Za-z0-9])", "@\u200B");
    }
}

package com.cardpricer.cloud;

import com.cardpricer.cloud.auth.SessionTokens;
import com.cardpricer.cloud.store.StoreController;
import com.cardpricer.cloud.support.GitHubIssues;
import com.cardpricer.cloud.support.SupportReportFiler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Help & feedback: reports saved from the store app, listed for the platform owner, filed on a stand-in GitHub. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SupportReportsIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final String OWNER_EMAIL = "platform-owner@example.com";
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpServer GITHUB;
    /** Every create-issue request the stand-in received: path, Authorization header, body. */
    static final List<String[]> REQUESTS = new CopyOnWriteArrayList<>();
    /** Answers to give, in order, as status + JSON; once empty, issues are created. */
    static final Queue<Object[]> ANSWERS = new ConcurrentLinkedQueue<>();
    static final AtomicInteger NUMBER = new AtomicInteger(100);

    static {
        POSTGRES.start();
        try {
            GITHUB = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            GITHUB.createContext("/", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                REQUESTS.add(new String[]{ex.getRequestMethod() + " " + ex.getRequestURI().getPath(),
                        ex.getRequestHeaders().getFirst("Authorization"), body});
                Object[] answer = ANSWERS.poll();
                int status;
                String reply;
                if (answer != null) {
                    status = (Integer) answer[0];
                    reply = (String) answer[1];
                } else {
                    int number = NUMBER.incrementAndGet();
                    status = 201;
                    reply = "{\"number\":" + number + ",\"html_url\":\"https://github.com/vanRoojen-LLC/CardBox/issues/" + number
                            + "\",\"state\":\"open\"}";
                }
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            GITHUB.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static String githubUrl() {
        return "http://localhost:" + GITHUB.getAddress().getPort();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.session-secret", () -> "test-secret-test-secret-test-secret-0123");
        registry.add("app.secure-cookie", () -> "false");
        registry.add("app.owner-email", () -> OWNER_EMAIL);
        registry.add("app.support.github.token", () -> "test-github-token");
        registry.add("app.support.github.api-url", SupportReportsIntegrationTest::githubUrl);
        // The tests run the filer themselves.
        registry.add("app.support.filer-initial-delay-ms", () -> "3600000");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokens sessions;
    @Autowired SupportReportFiler filer;
    @Autowired org.springframework.scheduling.config.ScheduledTaskHolder scheduler;
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    record Response(int status, JsonNode body, String raw) {}

    UUID tenant;
    String staffEmail;
    String staff;
    String admin;

    @BeforeEach
    void setUp() {
        // Earlier tests' reports are not this test's business.
        jdbc.update("UPDATE support_reports SET status = 'failed' WHERE status = 'saved'");
        REQUESTS.clear();
        ANSWERS.clear();
        tenant = StoreController.openStore(jdbc, "Shop " + UUID.randomUUID(), Instant.now().plusSeconds(86400), null);
        UUID user = UUID.randomUUID();
        staffEmail = "sam-" + user + "@example.com";
        jdbc.update("INSERT INTO users (id, tenant_id, email, name, auth0_sub, role) VALUES (?, ?, ?, 'Sam Staffer', ?, 'staff')",
                user, tenant, staffEmail, "auth0|" + user);
        staff = "occ_session=" + sessions.issue(user);
        var owners = jdbc.queryForList("SELECT id FROM users WHERE lower(email) = ?", UUID.class, OWNER_EMAIL);
        UUID ownerId;
        if (owners.isEmpty()) {
            UUID ownerTenant = StoreController.openStore(jdbc, "Owner Store " + UUID.randomUUID(), Instant.now().plusSeconds(86400), null);
            ownerId = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, tenant_id, email, name, auth0_sub, role) VALUES (?, ?, ?, 'Toby', ?, 'owner')",
                    ownerId, ownerTenant, OWNER_EMAIL, "auth0|" + ownerId);
        } else {
            ownerId = owners.getFirst();
        }
        admin = "occ_session=" + sessions.issue(ownerId);
    }

    Response call(String method, String path, String cookie, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (cookie != null) builder.header("Cookie", cookie);
        if (body != null) builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String raw = response.body();
        return new Response(response.statusCode(), raw.startsWith("{") || raw.startsWith("[") ? JSON.readTree(raw) : null, raw);
    }

    Map<String, Object> report(String kind, String description) {
        return Map.of("kind", kind, "description", description, "page", "/app/inventory?q=0412555123#top",
                "environment", Map.of("userAgent", "Mozilla/5.0 (Macintosh) Safari/605.1.15", "viewport", "1280x800",
                        "build", "abc123", "language", "en-US", "recentErrors", List.of("TypeError: x is undefined")));
    }

    UUID submit(String kind, String description) throws Exception {
        var r = call("POST", "/api/support/reports", staff, report(kind, description));
        assertEquals(202, r.status(), r.raw());
        assertEquals("saved", r.body().path("status").asText());
        return UUID.fromString(r.body().path("id").asText());
    }

    Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM support_reports WHERE id = ?", id);
    }

    @Test
    void aSignedInPersonSendsAReportForTheirOwnStore() throws Exception {
        var body = new HashMap<>(report("bug", "Totals are wrong after removing a line"));
        body.put("tenantId", UUID.randomUUID().toString()); // ignored: the store comes from the session
        var r = call("POST", "/api/support/reports", staff, body);
        assertEquals(202, r.status(), r.raw());
        var saved = row(UUID.fromString(r.body().path("id").asText()));
        assertEquals(tenant, saved.get("tenant_id"));
        assertEquals("bug", saved.get("kind"));
        assertEquals("saved", saved.get("status"));
        assertEquals("/app/inventory", saved.get("page"), "query string and fragment are dropped");
        assertTrue(saved.get("environment").toString().contains("1280x800"));

        assertEquals(401, call("POST", "/api/support/reports", null, report("bug", "hi")).status());
        assertEquals(400, call("POST", "/api/support/reports", staff, Map.of("kind", "rant", "description", "x")).status());
        assertEquals(400, call("POST", "/api/support/reports", staff, Map.of("kind", "bug", "description", " ")).status());
        assertEquals(400, call("POST", "/api/support/reports", staff, Map.of("kind", "bug", "description", "x".repeat(5001))).status());
        assertEquals(400, call("POST", "/api/support/reports", staff,
                Map.of("kind", "bug", "description", "x", "environment", Map.of("viewport", "huge"))).status());
    }

    @Test
    void theFilerIsScheduled() {
        assertTrue(scheduler.getScheduledTasks().stream().anyMatch(t -> t.toString().contains("SupportReportFiler.scheduled")),
                scheduler.getScheduledTasks().toString());
    }

    @Test
    void anEndedTrialCanStillAskForHelp() throws Exception {
        jdbc.update("UPDATE tenants SET plan_status = 'canceled' WHERE id = ?", tenant);
        submit("question", "How do I renew?");
    }

    @Test
    void reportsAreRateLimitedPerPerson() throws Exception {
        for (int i = 0; i < 10; i++) submit("idea", "Idea " + i);
        assertEquals(429, call("POST", "/api/support/reports", staff, report("idea", "one more")).status());
    }

    @Test
    void onlyThePlatformOwnerListsReports() throws Exception {
        UUID id = submit("question", "Where do I change buy rates?");
        assertEquals(403, call("GET", "/api/admin/support-reports", staff, null).status());
        assertEquals(401, call("GET", "/api/admin/support-reports", null, null).status());
        var list = call("GET", "/api/admin/support-reports?size=5", admin, null);
        assertEquals(200, list.status(), list.raw());
        assertTrue(list.body().path("total").asInt() >= 1);
        JsonNode first = list.body().path("reports").get(0);
        assertEquals(id.toString(), first.path("id").asText(), "newest first");
        assertEquals(staffEmail, first.path("reporterEmail").asText());
        assertEquals("Sam Staffer", first.path("reporterName").asText());
        assertEquals(tenant.toString(), first.path("storeId").asText());
        assertEquals("1280x800", first.path("environment").path("viewport").asText());
        assertEquals("saved", first.path("status").asText());
    }

    @Test
    void withoutATokenReportsAreOnlySaved() throws Exception {
        UUID id = submit("bug", "Nothing happens when I press save");
        var off = new SupportReportFiler(jdbc, new GitHubIssues("", "vanRoojen-LLC/CardBox", githubUrl()), "https://cardbox.trading/app/admin");
        assertEquals(0, off.fileDue());
        assertTrue(REQUESTS.isEmpty());
        assertEquals("saved", row(id).get("status"));
        assertEquals(0, row(id).get("attempt_count"));
    }

    @Test
    void filesASanitizedIssue() throws Exception {
        UUID id = submit("bug", "Save button @octocat ignores me, write to " + "me@shop.example" + "\nSecond line of detail that is long enough to pass seventy characters");
        assertEquals(1, filer.fileDue());
        var saved = row(id);
        assertEquals("filed", saved.get("status"));
        assertEquals(NUMBER.get(), saved.get("github_issue_number"));
        assertEquals("https://github.com/vanRoojen-LLC/CardBox/issues/" + NUMBER.get(), saved.get("github_issue_url"));
        assertEquals("open", saved.get("github_state"));

        assertEquals(1, REQUESTS.size());
        String[] request = REQUESTS.getFirst();
        assertEquals("POST /repos/vanRoojen-LLC/CardBox/issues", request[0]);
        assertEquals("Bearer test-github-token", request[1]);
        JsonNode issue = JSON.readTree(request[2]);
        String title = issue.path("title").asText();
        assertTrue(title.startsWith("[Trading web] bug: Save button"), title);
        assertFalse(title.contains("\n"));
        assertTrue(title.length() <= "[Trading web] bug: ".length() + 71, title);
        var labels = new ArrayList<String>();
        issue.path("labels").forEach(l -> labels.add(l.asText()));
        assertEquals(List.of("type:bug", "product:trading", "surface:web", "source:user-report", "needs-triage"), labels);
        String body = issue.path("body").asText();
        assertTrue(body.contains("CardBox Trading"));
        assertTrue(body.contains(id.toString()));
        assertTrue(body.contains(tenant.toString()));
        assertTrue(body.contains("abc123"));
        assertTrue(body.contains("/app/inventory"));
        assertTrue(body.contains("> Second line"));
        assertTrue(body.contains("[email removed]"));
        assertTrue(body.contains("https://cardbox.trading/app/admin?report=" + id));
        assertFalse(body.contains("@octocat"), "no mentions");
        for (String secret : List.of(staffEmail, "me@shop.example", "Sam Staffer", "Shop ", "0412555123", "TypeError", "en-US"))
            assertFalse(request[2].contains(secret), "issue must not contain " + secret);

        assertEquals(0, filer.fileDue(), "a filed report is not filed again");
    }

    @Test
    void questionsAreSupportAndLabelsAreDroppedWhenRefused() throws Exception {
        UUID id = submit("question", "How do I export?");
        ANSWERS.add(new Object[]{422, "{\"message\":\"Validation Failed\"}"});
        assertEquals(1, filer.fileDue());
        assertEquals(2, REQUESTS.size());
        JsonNode first = JSON.readTree(REQUESTS.get(0)[2]);
        assertEquals("type:support", first.path("labels").get(0).asText());
        assertFalse(JSON.readTree(REQUESTS.get(1)[2]).has("labels"), "second try has no labels");
        assertEquals("filed", row(id).get("status"));
    }

    @Test
    void anUnauthorizedTokenIsFinal() throws Exception {
        UUID id = submit("idea", "Dark mode for receipts");
        ANSWERS.add(new Object[]{401, "{\"message\":\"Bad credentials\"}"});
        assertEquals(1, filer.fileDue());
        var saved = row(id);
        assertEquals("failed", saved.get("status"));
        assertEquals("GitHub answered 401: Bad credentials", saved.get("last_error"));
        assertEquals(1, REQUESTS.size());
    }

    @Test
    void serverErrorsAreRetriedThenGivenUp() throws Exception {
        UUID id = submit("bug", "Flaky");
        for (int attempt = 1; attempt <= 5; attempt++) {
            ANSWERS.add(new Object[]{502, "{\"message\":\"Bad gateway\"}"});
            assertEquals(1, filer.fileDue(), "attempt " + attempt);
            var saved = row(id);
            assertEquals(attempt, saved.get("attempt_count"));
            assertEquals(attempt < 5 ? "saved" : "failed", saved.get("status"));
            assertEquals(0, filer.fileDue(), "backs off before trying again");
            jdbc.update("UPDATE support_reports SET next_attempt_at = now() WHERE id = ?", id);
        }
        assertEquals(0, filer.fileDue());
        assertEquals(5, REQUESTS.size());
    }
}

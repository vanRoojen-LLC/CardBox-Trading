package com.cardpricer.cloud;

import com.cardpricer.cloud.auth.SessionTokens;
import com.cardpricer.cloud.catalog.PriceChecks;
import com.cardpricer.cloud.clubsync.ClubSyncAuth;
import com.cardpricer.cloud.store.StoreController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The health-dashboard figures cardbox.club reads with its sync token, the free price check's daily counter, and the
 * month-to-date Azure cost, asked of stand-ins for Auth0, the Container Apps identity endpoint and Cost Management.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClubMetricsIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final String AUDIENCE = "https://cardbox.trading/api";
    static final String CLUB_CLIENT = "cardbox-club-m2m";
    static final String PATH = "/api/partner/club-sync/metrics";
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpServer SERVER;
    static final RSAKey KEY;
    static final AtomicInteger COST_QUERIES = new AtomicInteger();
    static final AtomicReference<String> COST_QUERY = new AtomicReference<>();

    static {
        POSTGRES.start();
        try {
            KEY = new RSAKeyGenerator(2048).keyID("club-key").generate();
            SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            SERVER.createContext("/.well-known/jwks.json", ex -> send(ex, 200, new JWKSet(KEY.toPublicJWK()).toString()));
            // The Container Apps identity endpoint: answers only with its secret header, for the app's identity.
            SERVER.createContext("/msi/token", ex -> {
                String query = ex.getRequestURI().getRawQuery();
                boolean ok = "identity-secret".equals(ex.getRequestHeaders().getFirst("X-IDENTITY-HEADER"))
                        && query.contains("client_id=app-identity-client")
                        && query.contains("resource=https%3A%2F%2Fmanagement.azure.com%2F");
                send(ex, ok ? 200 : 400, ok ? "{\"access_token\":\"arm-token\",\"token_type\":\"Bearer\"}" : "{}");
            });
            SERVER.createContext("/subscriptions/sub-1/resourceGroups/rg-1/providers/Microsoft.CostManagement/query", ex -> {
                COST_QUERIES.incrementAndGet();
                COST_QUERY.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                boolean ok = "Bearer arm-token".equals(ex.getRequestHeaders().getFirst("Authorization"))
                        && "api-version=2023-03-01".equals(ex.getRequestURI().getQuery()) && "POST".equals(ex.getRequestMethod());
                send(ex, ok ? 200 : 401, ok ? """
                        {"id":"x","properties":{"nextLink":null,
                         "columns":[{"name":"Cost","type":"Number"},{"name":"Currency","type":"String"}],
                         "rows":[[42.5,"USD"]]}}""" : "{}");
            });
            SERVER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static void send(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static String base() {
        return "http://localhost:" + SERVER.getAddress().getPort();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.session-secret", () -> "test-secret-test-secret-test-secret-0123");
        registry.add("app.secure-cookie", () -> "false");
        registry.add("app.rate-limit.public-per-minute", () -> "1000");
        registry.add("app.auth0.issuer", () -> base() + "/");
        registry.add("app.club-sync.enabled", () -> "true");
        registry.add("app.club-sync.audience", () -> AUDIENCE);
        registry.add("app.club-sync.client-ids", () -> CLUB_CLIENT);
        registry.add("app.azure-cost.subscription-id", () -> "sub-1");
        registry.add("app.azure-cost.resource-group", () -> "rg-1");
        registry.add("app.azure-cost.client-id", () -> "app-identity-client");
        registry.add("app.azure-cost.identity-endpoint", () -> base() + "/msi/token");
        registry.add("app.azure-cost.identity-header", () -> "identity-secret");
        registry.add("app.azure-cost.management-url", ClubMetricsIntegrationTest::base);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokens sessions;
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    record Response(int status, JsonNode body, String raw) {}

    static String token(String client, String audience, String scope) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(base() + "/").audience(audience).subject(client + "@clients")
                .claim("azp", client).claim("scope", scope).claim("gty", "client-credentials")
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 60_000)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    Response get(String path, String bearer, String cookie) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (cookie != null) builder.header("Cookie", cookie);
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String raw = response.body();
        return new Response(response.statusCode(), raw.startsWith("{") ? JSON.readTree(raw) : null, raw);
    }

    Response metrics() throws Exception {
        var r = get(PATH, token(CLUB_CLIENT, AUDIENCE, ClubSyncAuth.SCOPE), null);
        assertEquals(200, r.status(), r.raw());
        return r;
    }

    long priceChecksToday() {
        return jdbc.queryForList("SELECT checks FROM public_price_checks WHERE day = ?", Long.class,
                LocalDate.now(ZoneOffset.UTC)).stream().findFirst().orElse(0L);
    }

    @Test
    void onlyClubsSyncTokenReadsTheMetrics() throws Exception {
        assertEquals(401, get(PATH, null, null).status());
        var r = get(PATH, token("someone-else", AUDIENCE, ClubSyncAuth.SCOPE), null);
        assertEquals(401, r.status());
        assertEquals("A valid CardBox sync token is required", r.body().path("detail").asText());
        assertEquals(401, get(PATH, token(CLUB_CLIENT, "https://cardbox.club/api", ClubSyncAuth.SCOPE), null).status());
        assertEquals(401, get(PATH, token(CLUB_CLIENT, AUDIENCE, "read:other"), null).status());
        UUID tenant = StoreController.openStore(jdbc, "Metrics Shop " + UUID.randomUUID(), Instant.now().plusSeconds(86400), null);
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, email, name, role) VALUES (?, ?, ?, 'Olive', 'owner')",
                user, tenant, "olive-" + user + "@example.com");
        assertEquals(401, get(PATH, null, "occ_session=" + sessions.issue(user)).status(), "a store session is not a sync token");
    }

    @Test
    void metricsHaveTheirShapeAndCountWhatHappened() throws Exception {
        var before = metrics().body();
        List<String> fields = new ArrayList<>();
        before.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("measuredAt", "tradesLast7Days", "clubLinkItems", "activeStoresLast30Days", "priceChecksLast7Days",
                "priceChecksCountingSince", "azureMonthToDateUsd", "azureCostError"), fields);
        assertTrue(Instant.parse(before.path("measuredAt").asText()).isAfter(Instant.now().minusSeconds(60)));

        // Two stores: one trades today, one only adjusted inventory; an old trade counts for neither window.
        UUID trader = store();
        UUID counter = store();
        UUID old = store();
        trade(trader, "now()");
        trade(trader, "now() - interval '1 day'");
        trade(old, "now() - interval '40 days'");
        jdbc.update("""
                INSERT INTO inventory_adjustments (id, tenant_id, location_id, card_id, finish, condition, change, source)
                VALUES (?, ?, (SELECT id FROM locations WHERE tenant_id = ? LIMIT 1), ?, 'normal', 'NM', 1, 'test')""",
                UUID.randomUUID(), counter, counter, UUID.randomUUID());

        var after = metrics().body();
        assertEquals(before.path("tradesLast7Days").asLong() + 2, after.path("tradesLast7Days").asLong());
        assertEquals(before.path("activeStoresLast30Days").asLong() + 2, after.path("activeStoresLast30Days").asLong());
        assertTrue(after.path("clubLinkItems").isIntegralNumber());

        assertEquals(42.5, after.path("azureMonthToDateUsd").asDouble());
        assertTrue(after.path("azureCostError").isNull());
        JsonNode query = JSON.readTree(COST_QUERY.get());
        assertEquals("ActualCost", query.path("type").asText());
        assertEquals("MonthToDate", query.path("timeframe").asText());
        assertEquals("Sum", query.path("dataset").path("aggregation").path("totalCost").path("function").asText());
        assertEquals(1, COST_QUERIES.get(), "the cost is kept for an hour, not asked again on every call");
    }

    @Test
    void successfulPriceChecksAreCountedPerDay() throws Exception {
        long before = priceChecksToday();
        assertEquals(200, get("/api/public/cards?q=bolt", null, null).status());
        assertEquals(200, get("/api/public/cards?game=swu&q=vader", null, null).status());
        assertEquals(400, get("/api/public/cards?q=b", null, null).status(), "a refused search is not counted");
        assertEquals(before + 2, priceChecksToday());

        var m = metrics().body();
        assertTrue(m.path("priceChecksLast7Days").asLong() >= 2);
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), m.path("priceChecksCountingSince").asText());
    }

    @Test
    void aCounterThatCannotWriteNeverBreaksTheSearch() {
        var nowhere = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://localhost:1/none", "x", "x"));
        assertDoesNotThrow(() -> new PriceChecks(nowhere).count());
    }

    UUID store() {
        return StoreController.openStore(jdbc, "Metrics " + UUID.randomUUID(), Instant.now().plusSeconds(86400), null);
    }

    void trade(UUID tenant, String createdAt) {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, email, name, role) VALUES (?, ?, ?, 'Tess', 'owner')",
                user, tenant, "tess-" + user + "@example.com");
        jdbc.update("""
                INSERT INTO trades (id, tenant_id, number, created_by, payment, credit_total, check_total, market_total, location_id, created_at)
                VALUES (?, ?, (SELECT coalesce(max(number), 0) + 1 FROM trades WHERE tenant_id = ?), ?, 'credit', 1, 0, 2,
                        (SELECT id FROM locations WHERE tenant_id = ? LIMIT 1), %s)""".formatted(createdAt),
                UUID.randomUUID(), tenant, tenant, user, tenant);
    }
}

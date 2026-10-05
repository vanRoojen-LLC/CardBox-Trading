package com.cardpricer.cloud;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trading with the CardBox link switched on, against stand-ins for Auth0 and cardbox.club on one local server.
 * CardBox's answers are keyed on the access token Auth0 issued, the way the real one acts as the signed-in person.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardBoxLinkIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final String AUDIENCE = "https://cardbox.club/api";
    static final HttpServer SERVER;
    static final RSAKey KEY;
    static final ObjectMapper JSON = new ObjectMapper();
    /** Authorization code -> ID token claims. */
    static final Map<String, Map<String, Object>> CODES = new ConcurrentHashMap<>();
    /** Access token -> CardBox's roles answer for that person, or null for "no CardBox account" (403). */
    static final Map<String, Object> ROLES = new ConcurrentHashMap<>();
    /** The Authorization header CardBox saw on its last people request. */
    static volatile String lastPeopleAuth;
    /** The access token Auth0 issued at the latest sign-in. */
    static volatile String lastAccessToken;
    static final Object NO_ACCOUNT = new Object();
    /** CardBox store id -> the name CardBox's rename endpoint stored. */
    static final Map<String, String> RENAMED = new ConcurrentHashMap<>();

    static {
        POSTGRES.start();
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            SERVER.createContext("/.well-known/jwks.json", ex -> send(ex, 200, new JWKSet(KEY.toPublicJWK()).toString()));
            SERVER.createContext("/oauth/token", ex -> {
                var form = new HashMap<String, String>();
                for (String pair : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                    String[] kv = pair.split("=", 2);
                    form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                }
                var claims = CODES.remove(form.get("code"));
                if (claims == null) {
                    send(ex, 403, "{\"error\":\"invalid_grant\"}");
                    return;
                }
                try {
                    var builder = new JWTClaimsSet.Builder().issuer(issuer()).audience("test-client")
                            .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 60_000));
                    claims.forEach((k, v) -> { if (!k.equals("access_token")) builder.claim(k, v); });
                    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), builder.build());
                    jwt.sign(new RSASSASigner(KEY));
                    send(ex, 200, JSON.writeValueAsString(Map.of("id_token", jwt.serialize(),
                            "access_token", claims.get("access_token"), "expires_in", 86400, "token_type", "Bearer")));
                } catch (Exception e) {
                    throw new IOException(e);
                }
            });
            SERVER.createContext("/api/", ex -> {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
                String route = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
                if (!ROLES.containsKey(token)) {
                    send(ex, 401, "{\"detail\":\"Invalid token\"}");
                    return;
                }
                Object roles = ROLES.get(token);
                if (route.startsWith("PATCH /api/stores/")) {
                    // Like account_roles.rename_store: platform owners only, whitespace tidied, the id never changes.
                    boolean owner = roles instanceof Map<?, ?> m && ((List<?>) m.get("roles")).contains("platform_owner");
                    if (!owner) {
                        send(ex, 403, "{\"detail\":\"Only platform owners rename stores\"}");
                        return;
                    }
                    String name = String.join(" ", JSON.readTree(ex.getRequestBody()).path("name").asText().trim().split("\\s+"));
                    String id = ex.getRequestURI().getPath().substring("/api/stores/".length());
                    RENAMED.put(id, name);
                    send(ex, 200, JSON.writeValueAsString(Map.of("id", id, "name", name, "slug", "kept")));
                    return;
                }
                switch (route) {
                    case "POST /api/partner/sign-in", "GET /api/account/roles" -> {
                        if (roles == NO_ACCOUNT) send(ex, 403, "{\"detail\":\"Sign-up is closed\"}");
                        else send(ex, 200, JSON.writeValueAsString(roles));
                    }
                    case "GET /api/stores" -> {
                        var stores = new ArrayList<Object>();
                        RENAMED.forEach((id, name) -> stores.add(Map.of("id", id, "name", name, "slug", "kept")));
                        send(ex, 200, JSON.writeValueAsString(stores));
                    }
                    case "GET /api/people" -> {
                        lastPeopleAuth = auth;
                        send(ex, 200, "[{\"id\":\"u-1\",\"email\":\"someone@example.com\",\"display_name\":\"Someone\",\"roles\":[]}]");
                    }
                    case "POST /api/role-grants" -> {
                        JsonNode body = JSON.readTree(ex.getRequestBody());
                        if ("platform_owner".equals(body.path("role").asText()))
                            send(ex, 403, "{\"detail\":\"Only a platform owner can grant that role.\"}");
                        else send(ex, 201, "{\"id\":\"g-1\",\"email\":\"" + body.path("email").asText() + "\"}");
                    }
                    default -> send(ex, 404, "{\"detail\":\"Not found\"}");
                }
            });
            SERVER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static String issuer() {
        return "http://localhost:" + SERVER.getAddress().getPort() + "/";
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.session-secret", () -> "test-secret-test-secret-test-secret-0123");
        registry.add("app.secure-cookie", () -> "false");
        registry.add("app.rate-limit.auth-per-minute", () -> "1000");
        registry.add("app.auth0.issuer", CardBoxLinkIntegrationTest::issuer);
        registry.add("app.auth0.client-id", () -> "test-client");
        registry.add("app.auth0.client-secret", () -> "test-secret");
        registry.add("app.owner-email", () -> "nobody-here@example.com");
        registry.add("app.cardbox.enabled", () -> "true");
        registry.add("app.cardbox.base-url", () -> "http://localhost:" + SERVER.getAddress().getPort());
        registry.add("app.cardbox.audience", () -> AUDIENCE);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    record Response(int status, JsonNode body, String raw, String cookie, String location) {}

    Response call(String method, String path, String cookie, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (cookie != null) builder.header("Cookie", cookie);
        if (body != null) builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String setCookie = response.headers().allValues("Set-Cookie").stream().map(c -> c.split(";")[0])
                .filter(c -> !c.endsWith("=")).findFirst().orElse(null);
        JsonNode parsed = response.body().startsWith("{") || response.body().startsWith("[") ? JSON.readTree(response.body()) : null;
        return new Response(response.statusCode(), parsed, response.body(), setCookie,
                response.headers().firstValue("Location").orElse(null));
    }

    static String query(String url, String name) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    /** Signs in as {@code sub}; CardBox answers {@code roles} (or 403 for {@link #NO_ACCOUNT}). Returns the callback. */
    Response signIn(String sub, String email, Object roles) throws Exception {
        var start = call("GET", "/api/auth/login", null, null);
        assertEquals(AUDIENCE, query(start.location(), "audience"), "asks Auth0 for a CardBox API token");
        String code = UUID.randomUUID().toString();
        String accessToken = "at-" + UUID.randomUUID();
        lastAccessToken = accessToken;
        ROLES.put(accessToken, roles);
        CODES.put(code, Map.of("sub", sub, "email", email, "email_verified", true, "name", "Pat",
                "nonce", query(start.location(), "nonce"), "access_token", accessToken));
        return call("GET", "/api/auth/callback?code=" + code + "&state=" + query(start.location(), "state"), start.cookie(), null);
    }

    /** One entry of CardBox's {@code stores} list: a store role. */
    static Map<String, Object> storeRole(String role, String id, String name) {
        return Map.of("id", id, "name", name, "slug", name.toLowerCase().replace(' ', '-'), "role", role);
    }

    /**
     * CardBox's roles answer, as backend/cardbox/account_roles.py builds it: role names, then one {@code stores}
     * entry per store role. {@code Map.of("role", "platform_owner")} adds a platform role.
     */
    static Map<String, Object> roles(Object... entries) {
        var names = new LinkedHashSet<String>(List.of("user"));
        var stores = new ArrayList<Object>();
        for (Object entry : entries) {
            @SuppressWarnings("unchecked") var map = (Map<String, Object>) entry;
            names.add((String) map.get("role"));
            if (map.containsKey("id")) stores.add(map);
        }
        return Map.of("account_id", UUID.randomUUID().toString(), "roles", List.copyOf(names), "stores", stores);
    }

    @Test
    void signInCopiesCardBoxRolesAndKeepsAnExistingStoresData() throws Exception {
        String sub = "auth0|" + UUID.randomUUID();
        String email = "pat-" + UUID.randomUUID() + "@example.com";
        String shop = "Pat's Cards " + UUID.randomUUID();
        // A store Pat already runs on Trading, from before the link: its trades and inventory must stay with it.
        UUID legacy = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, trial_ends_at) VALUES (?, ?, ?)", legacy, shop, Timestamp.from(Instant.now().plusSeconds(86400)));
        jdbc.update("INSERT INTO users (id, tenant_id, email, name, auth0_sub, role) VALUES (?, ?, ?, 'Pat', ?, 'owner')", UUID.randomUUID(), legacy, email, sub);
        String main = "cb-" + UUID.randomUUID(), other = "cb-" + UUID.randomUUID();

        var callback = signIn(sub, email, roles(storeRole("store_manager", main, shop), storeRole("store_employee", other, "Other Shop")));
        assertEquals("/app", URI.create(callback.location()).getPath(), callback.location());
        assertEquals(legacy, jdbc.queryForObject("SELECT id FROM tenants WHERE cardbox_store_id = ?", UUID.class, main),
                "matched by name, so the existing store becomes the CardBox store's");
        var me = call("GET", "/api/auth/me", callback.cookie(), null).body();
        assertTrue(me.path("cardbox").asBoolean());
        assertFalse(me.path("admin").asBoolean());
        assertEquals(2, me.path("stores").size());
        var roleByStore = new HashMap<String, String>();
        me.path("stores").forEach(s -> roleByStore.put(s.path("name").asText(), s.path("role").asText()));
        assertEquals("owner", roleByStore.get(shop));
        assertEquals("staff", roleByStore.get("Other Shop"), "a store seen for the first time gets its own row");
        String token = lastAccessToken;
        assertFalse(callback.raw().contains(token) || callback.location().contains(token) || me.toString().contains(token),
                "the CardBox token stays on the server");
        assertNotEquals(token, new String(jdbc.queryForObject("SELECT token FROM cardbox_tokens WHERE auth0_sub = ?", byte[].class, sub),
                StandardCharsets.UTF_8), "and is stored encrypted");

        // CardBox takes Pat off the first store: the next sign-in ends that membership here too.
        var again = signIn(sub, email, roles(storeRole("store_employee", other, "Other Shop Renamed")));
        var after = call("GET", "/api/auth/me", again.cookie(), null).body();
        assertEquals(1, after.path("stores").size());
        assertEquals("Other Shop Renamed", after.path("store").asText(), "CardBox's store name is shown");
        assertEquals("staff", after.path("role").asText());
    }

    @Test
    void peopleWithoutACardBoxAccountOrStoreAreNotLetIn() throws Exception {
        var none = signIn("auth0|" + UUID.randomUUID(), "x-" + UUID.randomUUID() + "@example.com", NO_ACCOUNT);
        assertEquals("/login", URI.create(none.location()).getPath());
        var plain = signIn("auth0|" + UUID.randomUUID(), "y-" + UUID.randomUUID() + "@example.com", roles());
        assertEquals("/login", URI.create(plain.location()).getPath());
        assertTrue(URLDecoder.decode(plain.location(), StandardCharsets.UTF_8).contains("store manager"));
    }

    @Test
    void teamScreensCallCardBoxAsTheSignedInPerson() throws Exception {
        String store = "cb-" + UUID.randomUUID();
        var cookie = signIn("auth0|" + UUID.randomUUID(), "m-" + UUID.randomUUID() + "@example.com",
                roles(storeRole("store_manager", store, "Manager Shop " + store))).cookie();

        var people = call("GET", "/api/cardbox/people", cookie, null);
        assertEquals(200, people.status(), people.raw());
        assertEquals("someone@example.com", people.body().get(0).path("email").asText());
        assertTrue(lastPeopleAuth.startsWith("Bearer at-"));

        var grant = call("POST", "/api/cardbox/role-grants", cookie, Map.of("email", "new@example.com", "role", "store_employee", "store_id", store));
        assertEquals(201, grant.status(), grant.raw());
        var refused = call("POST", "/api/cardbox/role-grants", cookie, Map.of("email", "new@example.com", "role", "platform_owner"));
        assertEquals(403, refused.status());
        assertEquals("Only a platform owner can grant that role.", refused.body().path("error").asText(), "CardBox's detail, as it is");

        assertEquals(404, call("GET", "/api/cardbox/role-grants", cookie, null).status(), "only the contract's endpoints pass through");
        assertNotEquals(200, call("GET", "/api/cardbox/../partner/sign-in", cookie, null).status());
        assertEquals(401, call("GET", "/api/cardbox/people", null, null).status());

        // Trading no longer changes teams or makes stores itself.
        assertEquals(409, call("POST", "/api/app/staff", cookie, Map.of("name", "A", "email", "a@example.com")).status());
    }

    @Test
    void aPlatformOwnerWithNoStoreRoleOpensTheirExistingTradingStore() throws Exception {
        String sub = "auth0|" + UUID.randomUUID();
        String email = "boss-" + UUID.randomUUID() + "@example.com";
        UUID legacy = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, trial_ends_at) VALUES (?, ?, ?)", legacy, "Untied " + legacy,
                Timestamp.from(Instant.now().plusSeconds(86400)));
        jdbc.update("INSERT INTO users (id, tenant_id, email, name, auth0_sub, role) VALUES (?, ?, ?, 'Boss', ?, 'owner')",
                UUID.randomUUID(), legacy, email, sub);

        var callback = signIn(sub, email, roles(Map.of("role", "platform_owner")));
        assertEquals("/app", URI.create(callback.location()).getPath(), callback.location());
        var me = call("GET", "/api/auth/me", callback.cookie(), null).body();
        assertTrue(me.path("admin").asBoolean());
        assertEquals("Untied " + legacy, me.path("store").asText());
        assertEquals(200, call("GET", "/api/admin/stores", callback.cookie(), null).status());
        assertEquals(200, call("GET", "/api/app/store", callback.cookie(), null).status());

        // Someone who isn't a platform owner still can't use a store CardBox hasn't given them.
        String other = "auth0|" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, email, name, auth0_sub, role) VALUES (?, ?, ?, 'Sam', ?, 'staff')",
                UUID.randomUUID(), legacy, "sam-" + legacy + "@example.com", other);
        assertEquals("/login", URI.create(signIn(other, "sam-" + legacy + "@example.com", roles()).location()).getPath());
    }

    @Test
    void cardBoxPlatformOwnersGetTheAdminTab() throws Exception {
        String store = "cb-" + UUID.randomUUID();
        var cookie = signIn("auth0|" + UUID.randomUUID(), "o-" + UUID.randomUUID() + "@example.com",
                roles(Map.of("role", "platform_owner"), storeRole("store_manager", store, "Owner Shop " + store))).cookie();
        assertTrue(call("GET", "/api/auth/me", cookie, null).body().path("admin").asBoolean());
        var stores = call("GET", "/api/admin/stores", cookie, null);
        assertEquals(200, stores.status(), stores.raw());
        String id = null;
        for (JsonNode s : stores.body()) if (store.equals(s.path("cardboxStoreId").asText())) id = s.path("id").asText();
        assertNotNull(id);
        assertEquals(409, call("PUT", "/api/admin/stores/" + id, cookie, Map.of("name", "Renamed")).status(),
                "CardBox store names change on CardBox");
        assertEquals(200, call("PUT", "/api/admin/stores/" + id, cookie, Map.of("planStatus", "active")).status(),
                "plans are still Trading's");
    }

    @Test
    void aPlatformOwnerRenamesAStoreFromTheStorePageAndItChangesOnCardBox() throws Exception {
        String store = "cb-" + UUID.randomUUID();
        String sub = "auth0|" + UUID.randomUUID();
        var cookie = signIn(sub, "r-" + UUID.randomUUID() + "@example.com",
                roles(Map.of("role", "platform_owner"), storeRole("store_manager", store, "Old Name " + store))).cookie();
        UUID tenant = jdbc.queryForObject("SELECT id FROM tenants WHERE cardbox_store_id = ?", UUID.class, store);
        var before = call("GET", "/api/app/store", cookie, null).body();
        assertTrue(before.path("onCardBox").asBoolean());
        assertTrue(before.path("canRename").asBoolean());

        var saved = call("PUT", "/api/app/store", cookie, Map.of("name", "New   Name", "website", "", "phone", "555", "contactEmail", ""));
        assertEquals(200, saved.status(), saved.raw());
        assertEquals("New Name", RENAMED.get(store), "renamed on CardBox, by the store's id");
        assertEquals("New Name", saved.body().path("name").asText(), "as CardBox stored it");
        assertEquals("555", saved.body().path("phone").asText(), "the other details still save");
        assertEquals(store, jdbc.queryForObject("SELECT cardbox_store_id FROM tenants WHERE id = ?", String.class, tenant),
                "still the same CardBox store");
        assertEquals("New Name", call("GET", "/api/auth/me", cookie, null).body().path("store").asText());

        // Signing in again with CardBox's new name finds the same store rather than starting another.
        var again = signIn(sub, "r2-" + UUID.randomUUID() + "@example.com",
                roles(Map.of("role", "platform_owner"), storeRole("store_manager", store, "New Name")));
        assertEquals("/app", URI.create(again.location()).getPath());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tenants WHERE cardbox_store_id = ?", Integer.class, store));
    }

    @Test
    void aStoreManagerKeepsCardBoxsNameButSavesTheRest() throws Exception {
        String store = "cb-" + UUID.randomUUID();
        var cookie = signIn("auth0|" + UUID.randomUUID(), "k-" + UUID.randomUUID() + "@example.com",
                roles(storeRole("store_manager", store, "Kept " + store))).cookie();
        assertFalse(call("GET", "/api/app/store", cookie, null).body().path("canRename").asBoolean());
        var saved = call("PUT", "/api/app/store", cookie, Map.of("name", "Sneaky", "website", "", "phone", "123", "contactEmail", ""));
        assertEquals(200, saved.status(), saved.raw());
        assertEquals("Kept " + store, saved.body().path("name").asText());
        assertEquals("123", saved.body().path("phone").asText());
        assertNull(RENAMED.get(store), "nothing sent to CardBox");
    }

    @Test
    void aRenameMadeOnCardBoxShowsOnTradingBeforeAnyoneSignsInAgain() throws Exception {
        String store = "cb-" + UUID.randomUUID();
        var cookie = signIn("auth0|" + UUID.randomUUID(), "a-" + UUID.randomUUID() + "@example.com",
                roles(Map.of("role", "platform_owner"), storeRole("store_manager", store, "Before " + store))).cookie();
        RENAMED.put(store, "Renamed On Club");
        assertEquals(200, call("GET", "/api/cardbox/stores", cookie, null).status());
        assertEquals("Renamed On Club", jdbc.queryForObject("SELECT name FROM tenants WHERE cardbox_store_id = ?", String.class, store));
    }

    @Test
    void aStoreNotYetOnCardBoxIsRenamedOnTradingAlone() throws Exception {
        String sub = "auth0|" + UUID.randomUUID();
        String email = "u-" + UUID.randomUUID() + "@example.com";
        UUID legacy = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, trial_ends_at) VALUES (?, ?, ?)", legacy, "Untied " + legacy,
                Timestamp.from(Instant.now().plusSeconds(86400)));
        jdbc.update("INSERT INTO users (id, tenant_id, email, name, auth0_sub, role) VALUES (?, ?, ?, 'Boss', ?, 'owner')",
                UUID.randomUUID(), legacy, email, sub);
        var cookie = signIn(sub, email, roles(Map.of("role", "platform_owner"))).cookie();
        var store = call("GET", "/api/app/store", cookie, null).body();
        assertFalse(store.path("onCardBox").asBoolean());
        assertTrue(store.path("canRename").asBoolean());
        var saved = call("PUT", "/api/app/store", cookie, Map.of("name", "Untied Renamed", "website", "", "phone", "", "contactEmail", ""));
        assertEquals("Untied Renamed", saved.body().path("name").asText());
        assertEquals(200, call("PUT", "/api/admin/stores/" + legacy, cookie, Map.of("name", "Untied Again")).status(),
                "the Admin tab can rename it too until it is tied");
        // Tying it to a CardBox store afterwards goes by id, whatever either name is.
        String cb = "cb-" + UUID.randomUUID();
        assertEquals(200, call("PUT", "/api/admin/stores/" + legacy + "/cardbox", cookie, Map.of("cardboxStoreId", cb)).status());
        RENAMED.put(cb, "CardBox Name");
        call("GET", "/api/cardbox/stores", cookie, null);
        assertEquals("CardBox Name", jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, legacy));
    }
}

package com.cardpricer.cloud;

import com.cardpricer.cloud.auth.SessionTokens;
import com.cardpricer.cloud.clubsync.ClubSync;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * cardbox.club pushing a linked collection into a store's inventory, with machine tokens from a stand-in Auth0.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClubSyncIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final String AUDIENCE = "https://cardbox.trading/api";
    static final String CLUB_CLIENT = "cardbox-club-m2m";
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpServer SERVER;
    static final RSAKey KEY;
    static final String BOLT = "11111111-1111-1111-1111-111111111111";
    static final String RAGAVAN = "22222222-2222-2222-2222-222222222222";

    static {
        POSTGRES.start();
        try {
            KEY = new RSAKeyGenerator(2048).keyID("club-key").generate();
            SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            SERVER.createContext("/.well-known/jwks.json", ex -> {
                byte[] bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            SERVER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
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
        registry.add("app.auth0.issuer", ClubSyncIntegrationTest::issuer);
        registry.add("app.club-sync.enabled", () -> "true");
        registry.add("app.club-sync.audience", () -> AUDIENCE);
        registry.add("app.club-sync.client-ids", () -> CLUB_CLIENT);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionTokens sessions;
    @Autowired ClubSync clubSync;
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    record Response(int status, JsonNode body, String raw) {}

    String storeId;
    UUID tenant;
    String owner;
    String collection;

    @BeforeEach
    void store() {
        for (String[] card : List.of(new String[]{BOLT, "Lightning Bolt", "2x2", "117"}, new String[]{RAGAVAN, "Ragavan, Nimble Pilferer", "mh2", "138"}))
            jdbc.update("""
                    INSERT INTO cards (id, name, set_code, set_name, collector_number, rarity, lang)
                    VALUES (?::uuid, ?, ?, 'Set', ?, 'rare', 'en') ON CONFLICT DO NOTHING""", card[0], card[1], card[2], card[3]);
        storeId = "cb-" + UUID.randomUUID();
        tenant = StoreController.openStore(jdbc, "Shop " + storeId, Instant.now().plusSeconds(86400), storeId);
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, email, name, auth0_sub, role) VALUES (?, ?, ?, 'Olive', ?, 'owner')",
                user, tenant, "olive-" + user + "@example.com", "auth0|" + user);
        owner = "occ_session=" + sessions.issue(user);
        collection = UUID.randomUUID().toString();
    }

    static String token(String client, String audience, String scope) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer()).audience(audience).subject(client + "@clients")
                .claim("azp", client).claim("scope", scope).claim("gty", "client-credentials")
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 60_000)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    Response call(String method, String path, String bearer, String cookie, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (cookie != null) builder.header("Cookie", cookie);
        if (body != null) builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String raw = response.body();
        return new Response(response.statusCode(), raw.startsWith("{") || raw.startsWith("[") ? JSON.readTree(raw) : null, raw);
    }

    Response club(String method, String path, Object body) throws Exception {
        return call(method, "/api/partner/club-sync/links/" + collection + path, token(CLUB_CLIENT, AUDIENCE, ClubSyncAuth.SCOPE), null, body);
    }

    static Map<String, Object> item(String id, long version, String scryfall, String finish, int quantity) {
        return Map.of("item_id", id, "version", version, "game", "magic-the-gathering", "scryfall_id", scryfall,
                "finish", finish, "quantity", quantity, "name", "n", "set_code", "s", "collector_number", "1");
    }

    Response link() throws Exception {
        return club("PUT", "", Map.of("store_id", storeId, "collection_name", "Box 12",
                "linked_by", Map.of("account_id", "acc-1", "auth0_sub", "auth0|linker-" + collection, "email", "lee@example.com", "name", "Lee")));
    }

    /** Synced lines in inventory: card id + finish -> quantity. */
    Map<String, Integer> synced() {
        var lines = new HashMap<String, Integer>();
        jdbc.query("SELECT card_id, finish, quantity FROM inventory_items WHERE tenant_id = ? AND club_link_id IS NOT NULL",
                rs -> { lines.merge(rs.getString(1) + "/" + rs.getString(2), rs.getInt(3), Integer::sum); }, tenant);
        return lines;
    }

    @Test
    void onlyClubsMachineTokenIsAccepted() throws Exception {
        String path = "/api/partner/club-sync/links/" + collection;
        assertEquals(401, call("GET", path, null, null, null).status());
        assertEquals(401, call("GET", path, token("someone-else", AUDIENCE, "inventory:sync"), null, null).status());
        assertEquals(401, call("GET", path, token(CLUB_CLIENT, "https://cardbox.club/api", "inventory:sync"), null, null).status());
        var noScope = call("GET", path, token(CLUB_CLIENT, AUDIENCE, "read:other"), null, null);
        assertEquals(401, noScope.status());
        assertTrue(noScope.body().has("detail"), "errors read like CardBox's");
        assertEquals(404, club("GET", "", null).status(), "valid token, but nothing linked yet");
        assertEquals(401, call("GET", path, null, owner, null).status(), "a store session is not a sync token");
    }

    @Test
    void aLinkedCollectionBecomesSyncedInventoryAndStaysInStep() throws Exception {
        var linked = link();
        assertEquals(200, linked.status(), linked.raw());
        assertEquals("active", linked.body().path("state").asText());
        assertEquals("Main", linked.body().path("location").asText(), "lands at the store's first location, not put away");

        var first = club("POST", "/items", Map.of("upserts", List.of(item("c1", 10, BOLT, "nonfoil", 1), item("c2", 11, BOLT, "nonfoil", 2),
                item("c3", 12, RAGAVAN, "foil", 1),
                Map.of("item_id", "c4", "version", 13, "game", "pokemon", "name", "Pikachu"),
                item("c5", 14, "99999999-9999-9999-9999-999999999999", "nonfoil", 1))));
        assertEquals(200, first.status(), first.raw());
        assertEquals(5, first.body().path("applied").asInt());
        assertEquals(2, first.body().path("not_matched").size());
        assertEquals(Map.of(BOLT + "/normal", 3, RAGAVAN + "/foil", 1), synced());

        // A late or repeated delivery changes nothing; a newer one does.
        var stale = club("POST", "/items", Map.of("upserts", List.of(item("c2", 5, BOLT, "nonfoil", 9))));
        assertEquals(1, stale.body().path("skipped").asInt());
        club("POST", "/items", Map.of("removals", List.of(Map.of("item_id", "c1", "version", 20))));
        club("POST", "/items", Map.of("upserts", List.of(item("c1", 15, BOLT, "nonfoil", 1)))); // older than the removal
        assertEquals(Map.of(BOLT + "/normal", 2, RAGAVAN + "/foil", 1), synced());

        // The store's own stock of the same card stays a separate line, and synced lines can't be edited here.
        jdbc.update("""
                INSERT INTO inventory_items (id, tenant_id, location_id, card_id, name, set_code, collector_number, rarity, lang, finish, condition, quantity)
                SELECT gen_random_uuid(), ?, id, ?::uuid, 'Lightning Bolt', '2x2', '117', 'uncommon', 'en', 'normal', 'NM', 4 FROM locations WHERE tenant_id = ?""",
                tenant, BOLT, tenant);
        var list = call("GET", "/api/app/inventory", null, owner, null).body().path("items");
        String syncedLine = null;
        for (JsonNode line : list) if ("Box 12".equals(line.path("clubCollection").asText())) syncedLine = line.path("id").asText();
        assertNotNull(syncedLine);
        var edit = call("PUT", "/api/app/inventory/" + syncedLine, null, owner, Map.of("quantity", 1));
        assertEquals(409, edit.status());
        assertTrue(edit.body().path("error").asText().contains("Box 12"));

        // An owner moves the whole collection to a spot and sets its condition.
        UUID location = jdbc.queryForObject("SELECT id FROM locations WHERE tenant_id = ?", UUID.class, tenant);
        var spot = call("POST", "/api/app/storage", null, owner, Map.of("locationId", location, "label", "Box", "names", List.of("12")));
        String spotId = spot.body().get(0).path("id").asText();
        var links = call("GET", "/api/app/club-links", null, owner, null).body();
        String linkId = links.get(0).path("id").asText();
        assertEquals(2, links.get(0).path("notMatched").asInt());
        var moved = call("PUT", "/api/app/club-links/" + linkId, null, owner, Map.of("locationId", location, "storageId", spotId, "defaultCondition", "LP"));
        assertEquals(200, moved.status(), moved.raw());
        assertEquals(3, jdbc.queryForObject("SELECT sum(quantity) FROM inventory_items WHERE storage_id = ?::uuid AND condition = 'LP'",
                Integer.class, spotId));

        // Club's view of the link.
        var view = club("GET", "", null).body();
        assertEquals(List.of("Box", "12"), List.of(view.path("storage_path").get(0).path("label").asText(), view.path("storage_path").get(0).path("name").asText()));
        assertEquals(3, view.path("cards").asInt());
    }

    @Test
    void aSnapshotRemovesWhatClubNoLongerHasButKeepsNewerChanges() throws Exception {
        link();
        club("POST", "/items", Map.of("upserts", List.of(item("a", 1, BOLT, "nonfoil", 1), item("b", 2, BOLT, "nonfoil", 1), item("c", 3, RAGAVAN, "nonfoil", 1))));
        String snapshot = club("POST", "/snapshots", Map.of("as_of_version", 10)).body().path("snapshot_id").asText();
        club("POST", "/items", Map.of("snapshot_id", snapshot, "upserts", List.of(item("a", 1, BOLT, "nonfoil", 1))));
        // While the snapshot runs, a new card arrives as an ordinary change.
        club("POST", "/items", Map.of("upserts", List.of(item("d", 11, RAGAVAN, "foil", 1))));
        var short1 = club("POST", "/snapshots/" + snapshot + "/complete", Map.of("item_count", 2));
        assertEquals(409, short1.status(), "a page went missing: nothing is removed");
        assertEquals(4, synced().values().stream().mapToInt(Integer::intValue).sum());
        var done = club("POST", "/snapshots/" + snapshot + "/complete", Map.of("item_count", 1));
        assertEquals(200, done.status(), done.raw());
        assertEquals(2, done.body().path("removed").asInt());
        assertEquals(Map.of(BOLT + "/normal", 1, RAGAVAN + "/foil", 1), synced());
    }

    @Test
    void endingALinkKeepsOrRemovesItsCards() throws Exception {
        link();
        club("POST", "/items", Map.of("upserts", List.of(item("a", 1, BOLT, "nonfoil", 2))));
        var keep = club("POST", "/unlink", Map.of("cards", "keep"));
        assertEquals(200, keep.status(), keep.raw());
        assertEquals(2, keep.body().path("cards").asInt());
        assertEquals(2, jdbc.queryForObject("SELECT sum(quantity) FROM inventory_items WHERE tenant_id = ? AND club_link_id IS NULL",
                Integer.class, tenant), "now the store's own stock");
        assertEquals(404, club("GET", "", null).status());

        // Linked again, then the person's store role ends: the link pauses and refuses deliveries until an owner decides.
        link();
        club("POST", "/items", Map.of("upserts", List.of(item("a", 2, BOLT, "nonfoil", 1))));
        clubSync.pauseWithoutRole("auth0|linker-" + collection, List.of());
        var refused = club("POST", "/items", Map.of("upserts", List.of(item("a", 3, BOLT, "nonfoil", 5))));
        assertEquals(409, refused.status());
        String linkId = call("GET", "/api/app/club-links", null, owner, null).body().get(0).path("id").asText();
        assertEquals("paused", call("GET", "/api/app/club-links", null, owner, null).body().get(0).path("state").asText());
        var removed = call("POST", "/api/app/club-links/" + linkId + "/end", null, owner, Map.of("cards", "remove"));
        assertEquals(200, removed.status(), removed.raw());
        assertEquals(0, removed.body().size());
        assertEquals(2, jdbc.queryForObject("SELECT sum(quantity) FROM inventory_items WHERE tenant_id = ?", Integer.class, tenant),
                "only the earlier kept cards remain");
        assertEquals(404, club("POST", "/items", Map.of("upserts", List.of())).status(), "Club learns the store ended it");
    }

    @Test
    void aCollectionSyncsToOneStoreAtATime() throws Exception {
        link();
        String otherStore = "cb-" + UUID.randomUUID();
        StoreController.openStore(jdbc, "Other " + otherStore, Instant.now().plusSeconds(86400), otherStore);
        var conflict = club("PUT", "", Map.of("store_id", otherStore, "linked_by", Map.of("auth0_sub", "auth0|linker")));
        assertEquals(409, conflict.status());
        var unknown = call("PUT", "/api/partner/club-sync/links/" + UUID.randomUUID(), token(CLUB_CLIENT, AUDIENCE, "inventory:sync"), null,
                Map.of("store_id", "cb-nobody", "linked_by", Map.of("auth0_sub", "auth0|linker")));
        assertEquals(404, unknown.status());
    }
}

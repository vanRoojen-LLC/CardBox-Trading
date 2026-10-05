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
    static final String SOL_RING = "33333333-3333-3333-3333-333333333333";

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
        for (String[] card : List.of(new String[]{BOLT, "Lightning Bolt", "2x2", "117"}, new String[]{RAGAVAN, "Ragavan, Nimble Pilferer", "mh2", "138"},
                new String[]{SOL_RING, "Sol Ring", "cmm", "410"}))
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
        // And a late retry of an older change: written after the snapshot started, so it stays too.
        club("POST", "/items", Map.of("upserts", List.of(item("e", 5, SOL_RING, "nonfoil", 1))));
        var short1 = club("POST", "/snapshots/" + snapshot + "/complete", Map.of("item_count", 2));
        assertEquals(409, short1.status(), "a page went missing: nothing is removed");
        assertEquals(5, synced().values().stream().mapToInt(Integer::intValue).sum());
        var done = club("POST", "/snapshots/" + snapshot + "/complete", Map.of("item_count", 1));
        assertEquals(200, done.status(), done.raw());
        assertEquals(2, done.body().path("removed").asInt());
        assertEquals(Map.of(BOLT + "/normal", 1, RAGAVAN + "/foil", 1, SOL_RING + "/normal", 1), synced());
    }

    @Test
    void otherGamesGoInByClubsPrintingId() throws Exception {
        link();
        Map<String, Object> luke = new HashMap<>(Map.of("item_id", "s1", "version", 1, "game", "star-wars-unlimited",
                "club_printing_id", "swu-sor-005-hyper", "treatment", "Hyperspace", "quantity", 1, "name", "Luke Skywalker",
                "set_code", "SOR", "collector_number", "005"));
        Map<String, Object> again = new HashMap<>(luke);
        again.putAll(Map.of("item_id", "s2", "version", 2, "quantity", 2));
        var sent = club("POST", "/items", Map.of("upserts", List.of(luke, again,
                Map.of("item_id", "s3", "version", 3, "game", "star-wars-unlimited", "name", "No id"))));
        assertEquals(200, sent.status(), sent.raw());
        assertEquals(1, sent.body().path("not_matched").size(), "an item without a printing id can't go in");

        // Two scans of the same printing are one card and one line, priced by nobody yet.
        var items = call("GET", "/api/app/inventory", null, owner, null).body().path("items");
        assertEquals(1, items.size(), items.toString());
        var line = items.get(0);
        assertEquals("Luke Skywalker", line.path("name").asText());
        assertEquals("SOR", line.path("set").asText());
        assertEquals("hyperspace", line.path("finish").asText());
        assertEquals(3, line.path("quantity").asInt());
        assertTrue(line.path("market").isNull());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM club_cards WHERE club_printing_id = 'swu-sor-005-hyper'", Integer.class));
        // Club's cards stay out of the shared catalog behind the free price check.
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cards WHERE name = 'Luke Skywalker'", Integer.class));
    }

    @Test
    void aCardThatCouldNotGoInBeforeGoesInWhenResentAtTheSameVersion() throws Exception {
        link();
        Map<String, Object> before = Map.of("item_id", "s1", "version", 7, "game", "star-wars-unlimited", "quantity", 1,
                "name", "Luke Skywalker", "set_code", "SOR", "collector_number", "005");
        assertEquals(1, club("POST", "/items", Map.of("upserts", List.of(before))).body().path("not_matched").size());
        assertTrue(synced().isEmpty());

        // The nightly snapshot resends it unchanged, and now Trading can place it.
        Map<String, Object> resent = new HashMap<>(before);
        resent.put("club_printing_id", "swu-sor-005");
        var again = club("POST", "/items", Map.of("upserts", List.of(resent)));
        assertEquals(1, again.body().path("applied").asInt(), again.raw());
        assertEquals(1, synced().values().stream().mapToInt(Integer::intValue).sum());

        // A matched card resent at the same version is still a no-op.
        assertEquals(1, club("POST", "/items", Map.of("upserts", List.of(resent))).body().path("skipped").asInt());
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

    @Test
    void aStoreRenamedOnClubKeepsSyncingUnderItsNewName() throws Exception {
        link();
        club("POST", "/items", Map.of("upserts", List.of(item("a", 1, BOLT, "nonfoil", 2))));
        String path = "/api/partner/club-sync/stores/" + storeId;
        String sync = token(CLUB_CLIENT, AUDIENCE, ClubSyncAuth.SCOPE);
        var renamed = call("PUT", path, sync, null, Map.of("name", "  Renamed Shop  "));
        assertEquals(200, renamed.status(), renamed.raw());
        assertEquals("Renamed Shop", renamed.body().path("name").asText());
        assertEquals("Renamed Shop", jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenant));
        assertEquals("Renamed Shop", call("GET", "/api/app/store", null, owner, null).body().path("name").asText());

        // The link and its cards go by the store's id, so they carry on as before.
        assertEquals(200, link().status());
        assertEquals(200, club("POST", "/items", Map.of("upserts", List.of(item("b", 2, BOLT, "nonfoil", 1)))).status());
        assertEquals(3, synced().values().stream().mapToInt(Integer::intValue).sum());

        assertEquals(400, call("PUT", path, sync, null, Map.of("name", " ")).status());
        assertEquals(401, call("PUT", path, null, owner, Map.of("name", "X")).status(), "only Club's machine token");
        assertEquals(404, call("PUT", "/api/partner/club-sync/stores/cb-nobody", sync, null, Map.of("name", "X")).status(),
                "no Trading store tied to it yet");
    }

    /** Adds a storage spot (or several) and returns the new spot's id. */
    String spot(UUID location, String parentId, String label, String name) throws Exception {
        var body = new HashMap<String, Object>(Map.of("locationId", location, "label", label, "names", List.of(name)));
        if (parentId != null) body.put("parentId", parentId);
        var spots = call("POST", "/api/app/storage", null, owner, body).body();
        for (JsonNode s : spots) if (name.equals(s.path("name").asText()) && label.equals(s.path("label").asText())) return s.path("id").asText();
        throw new AssertionError(spots.toString());
    }

    @Test
    void theStorePutsSyncedCardsAwayAndClubDeliveriesKeepThemThere() throws Exception {
        link();
        UUID location = jdbc.queryForObject("SELECT id FROM locations WHERE tenant_id = ?", UUID.class, tenant);
        String shelf = spot(location, null, "Shelf", "2");
        String box = spot(location, null, "Box", "9");
        club("POST", "/items", Map.of("upserts", List.of(item("p1", 1, BOLT, "normal", 2), item("p2", 2, BOLT, "normal", 1),
                item("p3", 3, RAGAVAN, "foil", 1))));
        String boltLine = jdbc.queryForObject("SELECT id::text FROM inventory_items WHERE club_link_id IS NOT NULL AND card_id = ?::uuid AND tenant_id = ?",
                String.class, BOLT, tenant);

        // A synced line moves whole, but not part of it: Club decides how many there are.
        assertEquals(400, call("POST", "/api/app/inventory/" + boltLine + "/move", null, owner, Map.of("storageId", shelf, "quantity", 1)).status());
        var moved = call("POST", "/api/app/inventory/move", null, owner, Map.of("ids", List.of(boltLine), "storageId", shelf));
        assertEquals(200, moved.status(), moved.raw());
        assertEquals(3, moved.body().path("cards").asInt());
        assertEquals(3, call("GET", "/api/app/inventory?storage=" + shelf, null, owner, null).body().path("cards").asInt());

        // Club's next deliveries and snapshots change counts, not where the store put the cards.
        club("POST", "/items", Map.of("upserts", List.of(item("p1", 4, BOLT, "normal", 3))));
        assertEquals(4, call("GET", "/api/app/inventory?storage=" + shelf, null, owner, null).body().path("cards").asInt());
        String snapshot = club("POST", "/snapshots", Map.of("as_of_version", 4)).body().path("snapshot_id").asText();
        club("POST", "/items", Map.of("snapshot_id", snapshot, "upserts", List.of(item("p1", 4, BOLT, "normal", 3),
                item("p2", 2, BOLT, "normal", 1), item("p3", 3, RAGAVAN, "foil", 1))));
        assertEquals(200, club("POST", "/snapshots/" + snapshot + "/complete", Map.of("item_count", 3)).status());
        assertEquals(4, call("GET", "/api/app/inventory?storage=" + shelf, null, owner, null).body().path("cards").asInt());

        // Moving the collection's target leaves placed cards alone; a new spot scanned on Club is the latest choice.
        UUID linkId = jdbc.queryForObject("SELECT id FROM club_links WHERE collection_id = ?", UUID.class, collection);
        clubSync.retarget(tenant, linkId, location, UUID.fromString(box), "NM");
        assertEquals(4, call("GET", "/api/app/inventory?storage=" + shelf, null, owner, null).body().path("cards").asInt());
        var rescanned = new HashMap<String, Object>(item("p2", 5, BOLT, "normal", 1));
        rescanned.put("storage_id", box);
        club("POST", "/items", Map.of("upserts", List.of(rescanned)));
        assertEquals(3, call("GET", "/api/app/inventory?storage=" + shelf, null, owner, null).body().path("cards").asInt());
        assertEquals(2, call("GET", "/api/app/inventory?storage=" + box, null, owner, null).body().path("cards").asInt());

        // Back to "not put away" works at the collection's own location, and filters pick synced lines too.
        var all = call("POST", "/api/app/inventory/move", null, owner, Map.of("filter", Map.of("source", List.of(linkId.toString()))));
        assertEquals(200, all.status(), all.raw());
        assertEquals(5, call("GET", "/api/app/inventory?storage=none", null, owner, null).body().path("cards").asInt());
        // Removing a spot sends cards placed in it to the spot above, which here is the top: not put away.
        call("POST", "/api/app/inventory/move", null, owner, Map.of("ids", List.of(
                jdbc.queryForObject("SELECT id::text FROM inventory_items WHERE club_link_id = ? AND card_id = ?::uuid", String.class, linkId, RAGAVAN)),
                "storageId", shelf));
        assertEquals(200, call("POST", "/api/app/storage/" + shelf + "/remove", null, owner, Map.of()).status());
        assertEquals(5, call("GET", "/api/app/inventory?storage=none", null, owner, null).body().path("cards").asInt());
    }

    @Test
    void scansCarryTheirSpotPhotoAndDetail() throws Exception {
        link();
        UUID location = jdbc.queryForObject("SELECT id FROM locations WHERE tenant_id = ?", UUID.class, tenant);
        String box = spot(location, null, "Box", "7");
        var tree = call("GET", "/api/partner/club-sync/stores/" + storeId + "/storage", token(CLUB_CLIENT, AUDIENCE, ClubSyncAuth.SCOPE), null, null);
        assertEquals(200, tree.status(), tree.raw());
        assertEquals(box, tree.body().path("locations").get(0).path("spots").get(0).path("id").asText());

        var scanned = new HashMap<String, Object>(item("s1", 1, BOLT, "foil", 1));
        scanned.put("storage_id", box);
        scanned.put("image_url", "https://images.cardbox.club/scan/s1.jpg");
        scanned.put("details", Map.of("serial_number", "12/50"));
        var unsafe = new HashMap<String, Object>(item("s2", 2, BOLT, "foil", 1));
        unsafe.put("storage_id", UUID.randomUUID().toString()); // not this store's: lands at the target instead
        unsafe.put("image_url", "javascript:alert(1)");
        club("POST", "/items", Map.of("upserts", List.of(scanned, unsafe)));

        String inBox = jdbc.queryForObject("SELECT id::text FROM inventory_items WHERE club_link_id IS NOT NULL AND storage_id = ?::uuid", String.class, box);
        var scans = call("GET", "/api/app/club-links/scans/" + inBox, null, owner, null).body();
        assertEquals(1, scans.size());
        assertEquals("https://images.cardbox.club/scan/s1.jpg", scans.get(0).path("image").asText());
        assertTrue(scans.get(0).path("details").asText().contains("12/50"));
        String atTarget = jdbc.queryForObject("SELECT id::text FROM inventory_items WHERE club_link_id IS NOT NULL AND storage_id IS NULL AND tenant_id = ?",
                String.class, tenant);
        assertTrue(call("GET", "/api/app/club-links/scans/" + atTarget, null, owner, null).body().get(0).path("image").isNull(),
                "only https images are kept");
    }

    @Test
    void reInventoryReportsTheDeltaAndReconcilesIt() throws Exception {
        UUID location = jdbc.queryForObject("SELECT id FROM locations WHERE tenant_id = ?", UUID.class, tenant);
        String box = spot(location, null, "Box", "12");
        String front = spot(location, box, "Section", "Front");
        for (Object[] stock : List.of(new Object[]{BOLT, 4, box}, new Object[]{RAGAVAN, 1, box}))
            assertEquals(200, call("POST", "/api/app/inventory", null, owner,
                    Map.of("cardId", stock[0], "quantity", stock[1], "locationId", location, "storageId", stock[2])).status());

        var started = call("POST", "/api/app/counts", null, owner, Map.of("locationId", location, "storageId", box));
        assertEquals(200, started.status(), started.raw());
        String count = started.body().path("id").asText();

        // Typed on Trading: 3 Bolts (one missing) and a Sol Ring nobody knew about.
        call("POST", "/api/app/counts/" + count + "/lines", null, owner, Map.of("cardId", BOLT, "quantity", 3));
        call("POST", "/api/app/counts/" + count + "/lines", null, owner, Map.of("cardId", SOL_RING, "quantity", 1));
        // Scanned on Club: the Ragavan, found in the front section of the same box.
        var listed = call("GET", "/api/partner/club-sync/stores/" + storeId + "/counts", token(CLUB_CLIENT, AUDIENCE, ClubSyncAuth.SCOPE), null, null);
        assertEquals(count, listed.body().get(0).path("count_id").asText());
        var rag = new HashMap<String, Object>(item("r1", 1, RAGAVAN, "nonfoil", 1));
        rag.put("storage_id", front);
        var scan = call("POST", "/api/partner/club-sync/counts/" + count + "/items", token(CLUB_CLIENT, AUDIENCE, ClubSyncAuth.SCOPE), null,
                Map.of("store_id", storeId, "upserts", List.of(rag, rag)));
        assertEquals(200, scan.status(), scan.raw());

        var report = call("GET", "/api/app/counts/" + count, null, owner, null).body();
        Map<String, String> status = new HashMap<>();
        for (JsonNode row : report.path("rows")) status.merge(row.path("name").asText(), row.path("status").asText(), (a, b) -> a + "," + b);
        assertEquals("missing", status.get("Lightning Bolt"));
        assertEquals("extra", status.get("Sol Ring"));
        assertTrue(status.get("Ragavan, Nimble Pilferer").matches("moved,moved"), status.toString());
        assertEquals(1, report.path("missing").asInt());

        // Two more Bolts come in while the count runs; reconciling keeps them.
        call("POST", "/api/app/inventory", null, owner, Map.of("cardId", BOLT, "quantity", 2, "locationId", location, "storageId", box));
        var done = call("POST", "/api/app/counts/" + count + "/reconcile", null, owner, Map.of());
        assertEquals(200, done.status(), done.raw());
        assertEquals("reconciled", done.body().path("state").asText());
        Map<String, Integer> stock = new HashMap<>();
        jdbc.query("SELECT name, storage_id::text, quantity FROM inventory_items WHERE tenant_id = ?",
                rs -> { stock.put(rs.getString(1) + "@" + rs.getString(2), rs.getInt(3)); }, tenant);
        assertEquals(Map.of("Lightning Bolt@" + box, 5, "Sol Ring@" + box, 1, "Ragavan, Nimble Pilferer@" + front, 1), stock);
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM inventory_adjustments WHERE count_id = ?::uuid", Integer.class, count));
        assertEquals(409, call("POST", "/api/app/counts/" + count + "/lines", null, owner, Map.of("cardId", BOLT, "quantity", 1)).status());
    }
}

package com.cardpricer.cloud;

import com.cardpricer.cloud.catalog.CatalogImporter;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CloudApiIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final String CLIENT_ID = "test-client";
    static final String OWNER_EMAIL = "platform-owner@example.com";
    /** A stand-in for the Auth0 tenant: serves its JWKS and answers the code exchange with a signed ID token. */
    static final HttpServer AUTH0;
    static final RSAKey KEY;
    /** Authorization code -> ID token claims the stand-in returns for it. */
    static final Map<String, Map<String, Object>> CODES = new ConcurrentHashMap<>();
    /** Authorization code -> the PKCE code_challenge it was issued for. */
    static final Map<String, String> CHALLENGES = new ConcurrentHashMap<>();

    static {
        // Started before the Spring context; Testcontainers' Ryuk removes it when the JVM exits.
        POSTGRES.start();
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            AUTH0 = HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
            AUTH0.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            AUTH0.createContext("/oauth/token", exchange -> {
                var form = new java.util.HashMap<String, String>();
                for (String pair : new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("&")) {
                    String[] kv = pair.split("=", 2);
                    form.put(java.net.URLDecoder.decode(kv[0], java.nio.charset.StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
                }
                var claims = CODES.remove(form.get("code"));
                byte[] body;
                int status = 200;
                try {
                    if (claims == null || !"test-secret".equals(form.get("client_secret"))
                            || !s256(form.get("code_verifier")).equals(CHALLENGES.remove(form.get("code")))) {
                        status = 403;
                        body = "{\"error\":\"invalid_grant\"}".getBytes();
                    } else {
                        var builder = new JWTClaimsSet.Builder().issuer(issuer()).audience(CLIENT_ID)
                                .issueTime(new java.util.Date()).expirationTime(new java.util.Date(System.currentTimeMillis() + 60_000));
                        claims.forEach(builder::claim);
                        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), builder.build());
                        jwt.sign(new RSASSASigner(KEY));
                        body = ("{\"id_token\":\"" + jwt.serialize() + "\"}").getBytes();
                    }
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            AUTH0.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static String s256(String verifier) {
        try {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String issuer() {
        return "http://localhost:" + AUTH0.getAddress().getPort() + "/";
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.session-secret", () -> "test-secret-test-secret-test-secret-0123");
        registry.add("app.secure-cookie", () -> "false");
        registry.add("app.rate-limit.auth-per-minute", () -> "1000");
        registry.add("app.auth0.issuer", CloudApiIntegrationTest::issuer);
        registry.add("app.auth0.client-id", () -> CLIENT_ID);
        registry.add("app.auth0.client-secret", () -> "test-secret");
        registry.add("app.owner-email", () -> OWNER_EMAIL);
    }

    @LocalServerPort int port;
    @Autowired CatalogImporter importer;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    record Response(int status, JsonNode body, String raw, String cookie, String location) {}

    @BeforeAll
    void loadCatalog() throws Exception {
        try (var in = getClass().getResourceAsStream("/cards-fixture.json")) {
            assertEquals(4, importer.importStream(in, "fixture"), "digital-only printing is skipped");
        }
    }

    Response call(String method, String path, String cookie, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (cookie != null) builder.header("Cookie", cookie);
        if (body != null) builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        // The cookie the response actually sets (sign-in responses also clear the finished transaction cookie).
        String setCookie = response.headers().allValues("Set-Cookie").stream().map(c -> c.split(";")[0])
                .filter(c -> !c.endsWith("=")).findFirst().orElse(null);
        JsonNode parsed = response.body().startsWith("{") || response.body().startsWith("[") ? json.readTree(response.body()) : null;
        return new Response(response.statusCode(), parsed, response.body(), setCookie,
                response.headers().firstValue("Location").orElse(null));
    }

    static String query(String url, String name) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Runs Universal Login end to end against the stand-in: /login redirects to the authorize URL, the "user" signs in
     * as {@code sub}/{@code email}, and the callback answers. Returns the callback's response.
     */
    Response auth0SignIn(String sub, String email, boolean verified) throws Exception {
        var start = call("GET", "/api/auth/login", null, null);
        assertEquals(302, start.status(), start.raw());
        assertTrue(start.location().startsWith(issuer() + "authorize?"), start.location());
        assertEquals("S256", query(start.location(), "code_challenge_method"));
        assertNull(query(start.location(), "prompt"));
        String code = UUID.randomUUID().toString();
        CODES.put(code, Map.of("sub", sub, "email", email, "email_verified", verified, "name", email,
                "nonce", query(start.location(), "nonce")));
        CHALLENGES.put(code, query(start.location(), "code_challenge"));
        return call("GET", "/api/auth/callback?code=" + code + "&state=" + query(start.location(), "state"), start.cookie(), null);
    }

    String signup(String store, String email) throws Exception {
        var callback = auth0SignIn("auth0|" + UUID.randomUUID(), email, true);
        assertEquals(302, callback.status(), callback.raw());
        assertEquals("/signup", URI.create(callback.location()).getPath(), "no account yet, so name the store");
        assertEquals(email, call("GET", "/api/auth/pending", callback.cookie(), null).body().path("email").asText());
        var r = call("POST", "/api/auth/signup", callback.cookie(), Map.of("storeName", store, "name", "Owner"));
        assertEquals(200, r.status(), r.raw());
        assertTrue(r.body().path("entitled").asBoolean());
        return r.cookie();
    }

    @Test
    void importsGzippedJsonLines() throws Exception {
        // Scryfall's bulk files are now gzipped JSON Lines; re-importing the fixture that way upserts the same rows.
        JsonNode cards;
        try (var in = getClass().getResourceAsStream("/cards-fixture.json")) {
            cards = json.readTree(in);
        }
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
            for (JsonNode card : cards) gzip.write((json.writeValueAsString(card) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(4, importer.importStream(new java.io.ByteArrayInputStream(bytes.toByteArray()), "fixture.jsonl.gz"));
    }

    @Test
    void freePriceCheckNeedsNoAccountAndShowsOnlyMarketPrices() throws Exception {
        var r = call("GET", "/api/public/cards?q=bolt", null, null);
        assertEquals(200, r.status());
        assertEquals("Scryfall", r.body().path("source").asText());
        var cards = r.body().path("cards");
        assertEquals(2, cards.size());
        assertEquals("2X2", cards.get(0).path("set").asText(), "newest printing first");
        assertEquals("1.37", cards.get(0).path("usd").asText());
        assertFalse(cards.get(0).has("credit"), "no store offers on the free page");
    }

    @Test
    void searchMatchesSetCodeAndCollectorNumber() throws Exception {
        for (String q : new String[]{"410", "CMM 410", "cmm 410", "cmm #410", "sol cmm", "ring 410"}) {
            var cards = call("GET", "/api/public/cards?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8), null, null)
                    .body().path("cards");
            assertEquals(1, cards.size(), q);
            assertEquals("Sol Ring", cards.get(0).path("name").asText(), q);
        }
        var bolt = call("GET", "/api/public/cards?q=bolt%202x2", null, null).body().path("cards");
        assertEquals(1, bolt.size());
        assertEquals("117", bolt.get(0).path("number").asText());
        assertEquals(0, call("GET", "/api/public/cards?q=cmm%20117", null, null).body().path("cards").size());
    }

    private JsonNode search(String q) throws Exception {
        return call("GET", "/api/public/cards?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8), null, null)
                .body().path("cards");
    }

    @Test
    void searchIgnoresLeadingZerosOnCollectorNumbers() throws Exception {
        // Cards print numbers zero-padded ("C 0116 / SPM"); the catalog stores them unpadded.
        // Also the whole printed line (rarity letter, number, set, language) and an O typed for the zero.
        for (String q : new String[]{"0410", "00410", "CMM 0410", "cmm #0410", "o410", "u 0410", "U 0410 / CMM \u2022 EN", "u o410"}) {
            var cards = search(q);
            assertEquals(1, cards.size(), q);
            assertEquals("Sol Ring", cards.get(0).path("name").asText(), q);
        }
        assertEquals("220s", search("0220s").get(0).path("number").asText());
        assertEquals(0, search("0117 cmm").size());
        assertEquals(0, search("c 0410").size(), "Sol Ring is uncommon, not common");
    }

    @Test
    void searchCoversSetNameAndCardText() throws Exception {
        for (String q : new String[]{"commander masters", "artifact", "bierek", "add {c}{c}"}) {
            var cards = search(q);
            assertEquals(1, cards.size(), q);
            assertEquals("Sol Ring", cards.get(0).path("name").asText(), q);
        }
        // Text on either face of a multi-face card counts.
        assertEquals("Ragavan, Nimble Pilferer", search("dash").get(0).path("name").asText());
        // A name match outranks a card that only mentions the word in its text.
        var ring = search("ring");
        assertEquals(2, ring.size());
        assertEquals("Sol Ring", ring.get(0).path("name").asText());
        assertEquals("Ragavan, Nimble Pilferer", ring.get(1).path("name").asText());
    }

    @Test
    void searchToleratesTyposWhenNothingMatchesExactly() throws Exception {
        var bolt = search("lightnig bolt");
        assertEquals(2, bolt.size());
        assertEquals("Lightning Bolt", bolt.get(0).path("name").asText());
        assertEquals("Ragavan, Nimble Pilferer", search("ragavn").get(0).path("name").asText());
        assertEquals(0, search("zzzz qqqq").size());
    }

    @Test
    void storeWorkflowRequiresSignIn() throws Exception {
        assertEquals(401, call("GET", "/api/app/trades", null, null).status());
        assertEquals(401, call("GET", "/api/app/rates", "occ_session=forged.123.abc", null).status());
    }

    @Test
    void quoteSaveHistoryAndPosExport() throws Exception {
        String owner = signup("OCC", "owner-" + UUID.randomUUID() + "@example.com");
        var lines = java.util.List.of(
                java.util.Map.of("cardId", "22222222-2222-2222-2222-222222222222", "finish", "normal", "condition", "NM", "quantity", 1),
                java.util.Map.of("cardId", "11111111-1111-1111-1111-111111111111", "finish", "foil", "condition", "LP", "quantity", 3));
        var quote = call("POST", "/api/app/trades/quote", owner, java.util.Map.of("lines", lines, "payment", "credit"));
        assertEquals(200, quote.status(), quote.raw());
        // Ragavan $48.20 -> $48 base; 50% credit = 24.00, 40% check = 19.20.
        // Foil bolt $3.12 -> $3.00 base, LP 0.8 -> $2.50; credit 1.25 x3, check 1.00 x3.
        assertEquals(0, new java.math.BigDecimal("27.75").compareTo(quote.body().path("creditOffer").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("22.20").compareTo(quote.body().path("checkOffer").decimalValue()));

        var split = call("POST", "/api/app/trades/quote", owner, java.util.Map.of("lines", lines, "payment", "partial", "credit", 10));
        assertEquals(200, split.status(), split.raw());
        double check = split.body().path("settlement").path("check").asDouble();
        assertEquals(14.20, check, 0.001);

        var missingCheck = call("POST", "/api/app/trades", owner, java.util.Map.of("lines", lines, "payment", "partial", "credit", 10));
        assertEquals(400, missingCheck.status());

        var saved = call("POST", "/api/app/trades", owner, java.util.Map.of("lines", lines, "payment", "partial", "credit", 10,
                "customerPhone", "(555) 010-2030", "customerName", "Pat", "checkNumber", "1001"));
        assertEquals(200, saved.status(), saved.raw());
        assertEquals(1, saved.body().path("number").asInt());
        assertEquals("+15550102030", saved.body().path("customer_phone").asText());
        assertFalse(saved.raw().toLowerCase().contains("license"));

        var history = call("GET", "/api/app/trades?phone=555-010-2030", owner, null);
        assertEquals(1, history.body().size());

        String id = saved.body().path("id").asText();
        var csv = call("GET", "/api/app/trades/" + id + "/pos.csv", owner, null);
        assertEquals(200, csv.status());
        String[] rows = csv.raw().split("\r\n");
        assertEquals("LINE NO,DEPARTMENT,CATEGORY,TYPE,CODE,ITEM TYPE,ORDER NO,DESCRIPTION,UOM,QTY ON ORD,RESTOCK LEVEL,"
                + "REORDER POINT,QTY ON HAND,COST,DISCOUNT,BID,EXTENDED COST,TAX CODE,PRICE", rows[0]);
        assertTrue(rows[1].contains("MH2 138") && rows[1].contains("Ragavanɕ Nimble Pilferer"), rows[1]);
        assertTrue(rows[2].contains(" 117F,"), rows[2]);
        // Extended costs add up to the amount paid out.
        double extended = 0;
        for (int i = 1; i < rows.length; i++) extended += Double.parseDouble(rows[i].split(",")[16]);
        assertEquals(24.20, extended, 0.001);
    }

    @Test
    void storesCannotSeeEachOthersTrades() throws Exception {
        String a = signup("Store A", "a-" + UUID.randomUUID() + "@example.com");
        String b = signup("Store B", "b-" + UUID.randomUUID() + "@example.com");
        var saved = call("POST", "/api/app/trades", a, java.util.Map.of("payment", "credit", "lines", java.util.List.of(
                java.util.Map.of("cardId", "33333333-3333-3333-3333-333333333333", "finish", "etched", "condition", "NM", "quantity", 1))));
        assertEquals(200, saved.status(), saved.raw());
        String id = saved.body().path("id").asText();
        assertEquals(404, call("GET", "/api/app/trades/" + id, b, null).status());
        assertEquals(404, call("GET", "/api/app/trades/" + id + "/pos.csv", b, null).status());
        assertEquals(0, call("GET", "/api/app/trades", b, null).body().size());
    }

    @Test
    void onlyOwnersChangeRatesAndRatesDriveOffers() throws Exception {
        String owner = signup("Rates Store", "r-" + UUID.randomUUID() + "@example.com");
        String staffEmail = "s-" + UUID.randomUUID() + "@example.com";
        assertEquals(200, call("POST", "/api/app/staff", owner, java.util.Map.of("name", "Sam", "email", staffEmail)).status());
        // Staff join by signing in with the verified email the owner added.
        var staffSignIn = auth0SignIn("auth0|" + UUID.randomUUID(), staffEmail.toUpperCase(), true);
        assertEquals("/app", URI.create(staffSignIn.location()).getPath());
        String staff = staffSignIn.cookie();
        assertNotNull(staff);
        var rules = java.util.Map.of("rules", java.util.List.of(
                java.util.Map.of("thresholdMin", 0, "creditRate", 0.5, "checkRate", 0.4),
                java.util.Map.of("thresholdMin", 20, "creditRate", 0.7, "checkRate", 0.6)));
        assertEquals(403, call("PUT", "/api/app/rates", staff, rules).status());
        assertEquals(200, call("PUT", "/api/app/rates", owner, rules).status());
        var quote = call("POST", "/api/app/trades/quote", staff, java.util.Map.of("payment", "credit", "lines", java.util.List.of(
                java.util.Map.of("cardId", "22222222-2222-2222-2222-222222222222", "finish", "normal", "condition", "NM", "quantity", 1))));
        assertEquals(0, new java.math.BigDecimal("33.60").compareTo(quote.body().path("creditOffer").decimalValue()));
    }

    @Test
    void ownersEditTheStoreProfileAndLocations() throws Exception {
        String owner = signup("Profile Store", "p-" + UUID.randomUUID() + "@example.com");
        var store = call("GET", "/api/app/store", owner, null);
        assertEquals(200, store.status(), store.raw());
        assertEquals(1, store.body().path("locations").size(), "signup creates one location");
        assertEquals("Main", store.body().path("locations").get(0).path("name").asText());

        var saved = call("PUT", "/api/app/store", owner, Map.of("name", "Renamed Store", "website", "renamed.example.com",
                "phone", "555-0100", "contactEmail", "Hello@Renamed.example.com"));
        assertEquals(200, saved.status(), saved.raw());
        assertEquals("https://renamed.example.com", saved.body().path("website").asText());
        assertEquals("hello@renamed.example.com", saved.body().path("contactEmail").asText());
        assertEquals("Renamed Store", call("GET", "/api/auth/me", owner, null).body().path("store").asText());
        assertEquals(400, call("PUT", "/api/app/store", owner, Map.of("name", "X", "website", "not a site")).status());

        var added = call("POST", "/api/app/locations", owner, Map.of("name", "Downtown", "address", "1 Main St"));
        assertEquals(200, added.status(), added.raw());
        assertEquals(2, added.body().path("locations").size());
        assertEquals(409, call("POST", "/api/app/locations", owner, Map.of("name", "downtown")).status());
        String main = added.body().path("locations").get(0).path("id").asText();
        String downtown = added.body().path("locations").get(1).path("id").asText();

        var archived = call("PUT", "/api/app/locations/" + main, owner, Map.of("name", "Main", "archived", true));
        assertEquals(200, archived.status(), archived.raw());
        assertTrue(archived.body().path("locations").get(1).path("archived").asBoolean(), "archived locations sort last");
        var last = call("PUT", "/api/app/locations/" + downtown, owner, Map.of("name", "Downtown", "archived", true));
        assertEquals(400, last.status(), "a store keeps one open location");

        String staffEmail = "ps-" + UUID.randomUUID() + "@example.com";
        call("POST", "/api/app/staff", owner, Map.of("name", "Sam", "email", staffEmail));
        String staff = auth0SignIn("auth0|" + UUID.randomUUID(), staffEmail, true).cookie();
        assertEquals(200, call("GET", "/api/app/store", staff, null).status(), "staff read locations for the register picker");
        assertEquals(403, call("PUT", "/api/app/store", staff, Map.of("name", "Mine now")).status());
        assertEquals(403, call("POST", "/api/app/locations", staff, Map.of("name", "Annex")).status());

        String other = signup("Other Profile Store", "op-" + UUID.randomUUID() + "@example.com");
        assertEquals(404, call("PUT", "/api/app/locations/" + downtown, other, Map.of("name", "Taken")).status());
    }

    @Test
    void tradesAreTaggedToALocation() throws Exception {
        String owner = signup("Two Shops", "l-" + UUID.randomUUID() + "@example.com");
        String main = call("GET", "/api/app/store", owner, null).body().path("locations").get(0).path("id").asText();
        String annex = call("POST", "/api/app/locations", owner, Map.of("name", "Annex")).body().path("locations").get(1).path("id").asText();
        var lines = java.util.List.of(Map.of("cardId", "33333333-3333-3333-3333-333333333333", "finish", "etched", "condition", "NM", "quantity", 1));

        var defaulted = call("POST", "/api/app/trades", owner, Map.of("payment", "credit", "lines", lines));
        assertEquals(200, defaulted.status(), defaulted.raw());
        assertEquals("Main", defaulted.body().path("location").asText(), "no location means the first open one");
        var atAnnex = call("POST", "/api/app/trades", owner, Map.of("payment", "credit", "lines", lines, "locationId", annex));
        assertEquals("Annex", atAnnex.body().path("location").asText());

        var filtered = call("GET", "/api/app/trades?location=" + annex, owner, null);
        assertEquals(1, filtered.body().size());
        assertEquals("Annex", filtered.body().get(0).path("location").asText());
        assertEquals(2, call("GET", "/api/app/trades", owner, null).body().size());

        call("PUT", "/api/app/locations/" + annex, owner, Map.of("name", "Annex", "archived", true));
        assertEquals(400, call("POST", "/api/app/trades", owner, Map.of("payment", "credit", "lines", lines, "locationId", annex)).status());
        String other = signup("Not Mine", "nm-" + UUID.randomUUID() + "@example.com");
        assertEquals(400, call("POST", "/api/app/trades", other, Map.of("payment", "credit", "lines", lines, "locationId", main)).status(),
                "another store's location is refused");
    }

    @Test
    void inventoryLivesInACustomStorageTree() throws Exception {
        String owner = signup("Stock Shop", "inv-" + UUID.randomUUID() + "@example.com");
        String main = call("GET", "/api/app/store", owner, null).body().path("locations").get(0).path("id").asText();
        String bolt = "11111111-1111-1111-1111-111111111111";

        // A trade's cards land in stock at its location, not put away yet; a second trade merges into the same line.
        var lines = java.util.List.of(Map.of("cardId", bolt, "finish", "foil", "condition", "LP", "quantity", 3));
        call("POST", "/api/app/trades", owner, Map.of("payment", "credit", "lines", lines));
        call("POST", "/api/app/trades", owner, Map.of("payment", "credit", "lines", lines));
        var stock = call("GET", "/api/app/inventory?location=" + main + "&storage=none", owner, null);
        assertEquals(200, stock.status(), stock.raw());
        assertEquals(1, stock.body().path("items").size());
        assertEquals(6, stock.body().path("items").get(0).path("quantity").asInt());
        assertEquals("Lightning Bolt", stock.body().path("items").get(0).path("name").asText());

        // The store designs its own tiers: a room, shelves A-B in it, boxes on shelf A.
        var tree = call("POST", "/api/app/storage", owner, Map.of("locationId", main, "label", "Store room", "names", java.util.List.of("Back")));
        assertEquals(200, tree.status(), tree.raw());
        String back = tree.body().get(0).path("id").asText();
        tree = call("POST", "/api/app/storage", owner, Map.of("locationId", main, "parentId", back, "label", "Shelf", "names", java.util.List.of("A", "B")));
        String shelfA = null;
        for (var spot : tree.body()) if (spot.path("name").asText().equals("A")) shelfA = spot.path("id").asText();
        tree = call("POST", "/api/app/storage", owner, Map.of("locationId", main, "parentId", shelfA, "label", "Box", "names", java.util.List.of("1", "2")));
        assertEquals(5, tree.body().size());
        String box1 = null;
        for (var spot : tree.body()) if (spot.path("name").asText().equals("1")) box1 = spot.path("id").asText();
        assertEquals(409, call("POST", "/api/app/storage", owner, Map.of("locationId", main, "parentId", back, "label", "shelf", "names", java.util.List.of("a"))).status());

        // Put four away in box 1, leave two unsorted. Searching the room finds what's in the box under it.
        String item = stock.body().path("items").get(0).path("id").asText();
        assertEquals(200, call("POST", "/api/app/inventory/" + item + "/move", owner, Map.of("storageId", box1, "quantity", 4)).status());
        var inRoom = call("GET", "/api/app/inventory?storage=" + back, owner, null).body();
        assertEquals(1, inRoom.path("items").size());
        assertEquals(4, inRoom.path("items").get(0).path("quantity").asInt());
        assertEquals("Shelf", inRoom.path("items").get(0).path("path").get(1).path("label").asText());
        assertEquals("1", inRoom.path("items").get(0).path("path").get(2).path("name").asText());
        assertEquals(2, call("GET", "/api/app/inventory?storage=none", owner, null).body().path("items").get(0).path("quantity").asInt());
        assertEquals(6, call("GET", "/api/app/inventory?q=bolt", owner, null).body().path("cards").asInt());

        // Staff count and move stock but can't change the layout.
        String staffEmail = "is-" + UUID.randomUUID() + "@example.com";
        call("POST", "/api/app/staff", owner, Map.of("name", "Sam", "email", staffEmail));
        String staff = auth0SignIn("auth0|" + UUID.randomUUID(), staffEmail, true).cookie();
        assertEquals(403, call("POST", "/api/app/storage", staff, Map.of("locationId", main, "label", "Case", "names", java.util.List.of("1"))).status());
        assertEquals(200, call("POST", "/api/app/inventory", staff, Map.of("cardId", bolt, "finish", "normal", "condition", "NM",
                "quantity", 2, "locationId", main, "storageId", box1)).status());
        String boxed = call("GET", "/api/app/inventory?storage=" + box1 + "&q=bolt", staff, null).body().path("items").get(0).path("id").asText();
        assertEquals(200, call("PUT", "/api/app/inventory/" + boxed, staff, Map.of("quantity", 0)).status());

        // Removing a box with cards in it moves them up to the shelf; a shelf with boxes can't be removed.
        assertEquals(400, call("POST", "/api/app/storage/" + shelfA + "/remove", owner, Map.of()).status());
        assertEquals(200, call("POST", "/api/app/storage/" + box1 + "/remove", owner, Map.of()).status());
        var onShelf = call("GET", "/api/app/inventory?storage=" + shelfA, owner, null).body().path("items");
        assertEquals(1, onShelf.size());
        assertEquals(2, onShelf.get(0).path("path").size());

        // Other stores see none of it.
        String other = signup("Other Stock", "io-" + UUID.randomUUID() + "@example.com");
        assertEquals(0, call("GET", "/api/app/inventory", other, null).body().path("items").size());
        assertEquals(404, call("PUT", "/api/app/inventory/" + item, other, Map.of("quantity", 1)).status());
        assertEquals(400, call("POST", "/api/app/inventory", other, Map.of("cardId", bolt, "quantity", 1, "locationId", main)).status());
    }

    @Test
    void inventoryFiltersSortsAndMovesInBulk() throws Exception {
        String owner = signup("Bulk Shop", "bulk-" + UUID.randomUUID() + "@example.com");
        String main = call("GET", "/api/app/store", owner, null).body().path("locations").get(0).path("id").asText();
        String bolt = "11111111-1111-1111-1111-111111111111", ragavan = "22222222-2222-2222-2222-222222222222",
                solRing = "33333333-3333-3333-3333-333333333333";
        call("POST", "/api/app/inventory", owner, Map.of("cardId", bolt, "finish", "normal", "condition", "NM", "quantity", 4, "locationId", main));
        call("POST", "/api/app/inventory", owner, Map.of("cardId", ragavan, "finish", "normal", "condition", "NM", "quantity", 1, "locationId", main));
        call("POST", "/api/app/inventory", owner, Map.of("cardId", solRing, "finish", "normal", "condition", "LP", "quantity", 2, "locationId", main));

        // Card details the catalog now keeps: color, year, type and treatment.
        var red = call("GET", "/api/app/inventory?color=R&sort=name", owner, null);
        assertEquals(200, red.status(), red.raw());
        assertEquals(5, red.body().path("cards").asInt());
        assertEquals("Lightning Bolt", red.body().path("items").get(0).path("name").asText());
        assertEquals("R", red.body().path("items").get(0).path("colors").asText());
        assertEquals(2022, red.body().path("items").get(0).path("year").asInt());
        assertEquals("Double Masters 2022", red.body().path("items").get(0).path("setName").asText());
        assertEquals(2, call("GET", "/api/app/inventory?color=C", owner, null).body().path("cards").asInt());
        assertEquals(1, call("GET", "/api/app/inventory?treatment=showcase&treatment=borderless", owner, null).body().path("lines").asInt());
        assertEquals(1, call("GET", "/api/app/inventory?type=Creature", owner, null).body().path("lines").asInt());
        assertEquals(2, call("GET", "/api/app/inventory?year=2022&year=2023", owner, null).body().path("lines").asInt());
        assertEquals(1, call("GET", "/api/app/inventory?condition=LP", owner, null).body().path("lines").asInt());
        assertEquals(1, call("GET", "/api/app/inventory?priceMin=10", owner, null).body().path("lines").asInt());
        assertEquals(1, call("GET", "/api/app/inventory?q=monkey", owner, null).body().path("lines").asInt(), "type line is searched");

        // Any column sorts, either way; pages carry on where the last one stopped.
        for (String sort : java.util.List.of("name", "set", "number", "year", "rarity", "color", "type", "finish", "condition",
                "where", "market", "quantity", "updated"))
            for (String dir : java.util.List.of("asc", "desc"))
                assertEquals(3, call("GET", "/api/app/inventory?sort=" + sort + "&dir=" + dir, owner, null).body().path("items").size(), sort);
        var byPrice = call("GET", "/api/app/inventory?sort=market&dir=desc", owner, null).body().path("items");
        assertEquals("Ragavan, Nimble Pilferer", byPrice.get(0).path("name").asText());
        var page1 = call("GET", "/api/app/inventory?sort=name&limit=2", owner, null).body();
        assertTrue(page1.path("more").asBoolean());
        var page2 = call("GET", "/api/app/inventory?sort=name&limit=2&offset=2", owner, null).body();
        assertEquals("Sol Ring", page2.path("items").get(0).path("name").asText());
        assertFalse(page2.path("more").asBoolean());

        // Each filter counts its values under the other filters, not its own.
        var facets = call("GET", "/api/app/inventory/facets?color=R", owner, null);
        assertEquals(200, facets.status(), facets.raw());
        Map<String, Integer> colors = new java.util.HashMap<>();
        for (var v : facets.body().path("color")) colors.put(v.path("value").asText(), v.path("cards").asInt());
        assertEquals(Map.of("R", 5, "C", 2), colors);
        assertEquals(2, facets.body().path("rarity").size(), "uncommon and mythic: Sol Ring isn't red");
        assertEquals("mythic", facets.body().path("rarity").get(1).path("value").asText());
        assertEquals("magic-the-gathering", facets.body().path("game").get(0).path("value").asText());
        assertEquals("Double Masters 2022", facets.body().path("set").get(0).path("label").asText(), "newest set first");

        // Everything red goes to Shelf 2 in one move, picked by the filter rather than line by line.
        var spots = call("POST", "/api/app/storage", owner, Map.of("locationId", main, "label", "Shelf", "names", java.util.List.of("2")));
        String shelf = spots.body().get(0).path("id").asText();
        var moved = call("POST", "/api/app/inventory/move", owner, Map.of("filter", Map.of("color", java.util.List.of("R")), "storageId", shelf));
        assertEquals(200, moved.status(), moved.raw());
        assertEquals(2, moved.body().path("lines").asInt());
        assertEquals(5, moved.body().path("cards").asInt());
        assertEquals(5, call("GET", "/api/app/inventory?storage=" + shelf, owner, null).body().path("cards").asInt());
        assertEquals(2, call("GET", "/api/app/inventory?storage=none", owner, null).body().path("cards").asInt());
        assertEquals(5, call("GET", "/api/app/inventory?storage=any", owner, null).body().path("cards").asInt());

        // Or by picked lines, back to "not put away"; another store's ids are ignored.
        String sol = call("GET", "/api/app/inventory?q=sol%20ring", owner, null).body().path("items").get(0).path("id").asText();
        String boltLine = call("GET", "/api/app/inventory?q=bolt", owner, null).body().path("items").get(0).path("id").asText();
        String other = signup("Not Mine", "nm-" + UUID.randomUUID() + "@example.com");
        assertEquals(0, call("POST", "/api/app/inventory/move", other, Map.of("ids", java.util.List.of(boltLine))).body().path("lines").asInt());
        var back = call("POST", "/api/app/inventory/move", owner, Map.of("ids", java.util.List.of(boltLine, sol)));
        assertEquals(1, back.body().path("lines").asInt(), "Sol Ring is already not put away");
        assertEquals(6, call("GET", "/api/app/inventory?storage=none", owner, null).body().path("cards").asInt());
        assertEquals(400, call("POST", "/api/app/inventory/move", owner, Map.of("storageId", shelf)).status());
    }

    @Test
    void storesCanHaveSeveralOwners() throws Exception {
        String firstEmail = "f-" + UUID.randomUUID() + "@example.com";
        String first = signup("Partners", firstEmail);
        String partnerEmail = "pa-" + UUID.randomUUID() + "@example.com";
        var team = call("POST", "/api/app/staff", first, Map.of("name", "Pat", "email", partnerEmail, "role", "owner"));
        assertEquals(200, team.status(), team.raw());
        String partner = auth0SignIn("auth0|" + UUID.randomUUID(), partnerEmail, true).cookie();
        assertEquals("owner", call("GET", "/api/auth/me", partner, null).body().path("role").asText());

        // The partner can change owner-only settings, and can demote the first owner while still an owner.
        assertEquals(200, call("POST", "/api/app/locations", partner, Map.of("name", "Second")).status());
        String firstId = null, partnerId = null;
        for (var person : call("GET", "/api/app/staff", partner, null).body()) {
            if (person.path("name").asText().equals("Owner")) firstId = person.path("id").asText();
            if (person.path("name").asText().equals("Pat")) partnerId = person.path("id").asText();
        }
        assertEquals(200, call("PUT", "/api/app/staff/" + firstId, partner, Map.of("role", "staff")).status());
        assertEquals("staff", call("GET", "/api/auth/me", first, null).body().path("role").asText());
        // Now the only owner, the partner cannot step down or be removed.
        assertEquals(400, call("PUT", "/api/app/staff/" + partnerId, partner, Map.of("role", "staff")).status());
        assertEquals(400, call("POST", "/api/app/staff/" + partnerId + "/remove", partner, Map.of()).status());
        assertEquals(403, call("PUT", "/api/app/staff/" + partnerId, first, Map.of("role", "staff")).status());

        // Removing someone ends their access and hides them from the list; adding them again restores them.
        assertEquals(200, call("POST", "/api/app/staff/" + firstId + "/remove", partner, Map.of()).status());
        assertEquals(401, call("GET", "/api/app/trades", first, null).status());
        assertEquals(1, call("GET", "/api/app/staff", partner, null).body().size());
        assertEquals("/login", URI.create(auth0SignIn("auth0|" + UUID.randomUUID(), firstEmail, true).location()).getPath());
        var back = call("POST", "/api/app/staff", partner, Map.of("name", "Owner", "email", firstEmail));
        assertEquals(200, back.status(), back.raw());
        assertEquals(2, back.body().size());
        assertEquals(200, call("GET", "/api/app/trades", first, null).status());
    }

    @Test
    void signInIsKeyedOnAuth0SubWithVerifiedEmailAsTheFallback() throws Exception {
        String email = "k-" + UUID.randomUUID() + "@example.com";
        String sub = "auth0|" + UUID.randomUUID();
        var first = auth0SignIn(sub, email, true);
        call("POST", "/api/auth/signup", first.cookie(), Map.of("storeName", "Keyed", "name", "Kim"));

        // Same sub with a changed email still finds the account, and picks up the new address.
        String newEmail = "k2-" + UUID.randomUUID() + "@example.com";
        var again = auth0SignIn(sub, newEmail, true);
        assertEquals("/app", URI.create(again.location()).getPath());
        assertEquals(newEmail, call("GET", "/api/auth/me", again.cookie(), null).body().path("email").asText());

        // A different sub with the same, already linked, email is not let in.
        var other = auth0SignIn("google-oauth2|" + UUID.randomUUID(), newEmail, true);
        assertEquals("/login", URI.create(other.location()).getPath());
        assertTrue(other.cookie() == null || !other.cookie().startsWith("occ_session="));
    }

    @Test
    void unverifiedEmailsAndForgedCallbacksAreRefused() throws Exception {
        var unverified = auth0SignIn("auth0|" + UUID.randomUUID(), "u-" + UUID.randomUUID() + "@example.com", false);
        assertEquals("/login", URI.create(unverified.location()).getPath());
        assertTrue(query(unverified.location(), "error").contains("verify"));

        var start = call("GET", "/api/auth/login", null, null);
        var wrongState = call("GET", "/api/auth/callback?code=x&state=not-the-state", start.cookie(), null);
        assertEquals("/login", URI.create(wrongState.location()).getPath());
        var noCookie = call("GET", "/api/auth/callback?code=x&state=" + query(start.location(), "state"), null, null);
        assertEquals("/login", URI.create(noCookie.location()).getPath());
        assertEquals(404, call("POST", "/api/auth/signup", null, Map.of("storeName", "X", "name", "Y")).status());
        // A sign-in transaction cookie is not a session.
        assertEquals(401, call("GET", "/api/app/rates", "occ_session=" + start.cookie().split("=", 2)[1], null).status());
    }

    @Test
    void chooseAccountAsksAuth0ForCredentialsAgain() throws Exception {
        var start = call("GET", "/api/auth/login?chooseAccount=true", null, null);
        assertEquals("login", query(start.location(), "prompt"));
    }

    /** The platform owner's session; their email can only sign up once, so every test shares it. */
    private String platformOwner;

    synchronized String platformOwner() throws Exception {
        if (platformOwner == null) platformOwner = signup("Owner Store", OWNER_EMAIL);
        return platformOwner;
    }

    JsonNode me(String cookie) throws Exception {
        return call("GET", "/api/auth/me", cookie, null).body();
    }

    String storeId(String cookie, String name) throws Exception {
        for (var store : me(cookie).path("stores")) if (store.path("name").asText().equals(name)) return store.path("tenantId").asText();
        return null;
    }

    @Test
    void anExistingLoginCanJoinAnotherStore() throws Exception {
        String joeEmail = "joe-" + UUID.randomUUID() + "@example.com";
        String sub = "auth0|" + UUID.randomUUID();
        var first = auth0SignIn(sub, joeEmail, true);
        String joe = call("POST", "/api/auth/signup", first.cookie(), Map.of("storeName", "Joe's Cards", "name", "Joe")).cookie();
        String toby = signup("Toby's Cards", "t-" + UUID.randomUUID() + "@example.com");

        // Adding someone who already has a login in another store works, and they can switch to it at once.
        var added = call("POST", "/api/app/staff", toby, Map.of("name", "Joe", "email", joeEmail.toUpperCase(), "role", "owner"));
        assertEquals(200, added.status(), added.raw());
        assertEquals(409, call("POST", "/api/app/staff", toby, Map.of("name", "Joe", "email", joeEmail)).status(), "already on the team");
        assertEquals(2, me(joe).path("stores").size());
        String tobys = storeId(joe, "Toby's Cards");
        var switched = call("POST", "/api/auth/switch", joe, Map.of("tenantId", tobys));
        assertEquals(200, switched.status(), switched.raw());
        assertEquals("Toby's Cards", switched.body().path("store").asText());
        assertEquals("owner", switched.body().path("role").asText());
        String joeAtTobys = switched.cookie();
        assertEquals(200, call("POST", "/api/app/locations", joeAtTobys, Map.of("name", "Joe's corner")).status());
        assertEquals(403, call("POST", "/api/auth/switch", joe, Map.of("tenantId", UUID.randomUUID().toString())).status());

        // Signing in again opens the store used last; once removed there, sign-in falls back to the other store.
        assertEquals("Toby's Cards", me(auth0SignIn(sub, joeEmail, true).cookie()).path("store").asText());
        String joeId = null;
        for (var person : call("GET", "/api/app/staff", toby, null).body()) if (person.path("email").asText().equals(joeEmail)) joeId = person.path("id").asText();
        assertEquals(200, call("POST", "/api/app/staff/" + joeId + "/remove", toby, Map.of()).status());
        assertEquals(401, call("GET", "/api/app/trades", joeAtTobys, null).status());
        assertEquals(403, call("POST", "/api/auth/switch", joe, Map.of("tenantId", tobys)).status());
        var back = me(auth0SignIn(sub, joeEmail, true).cookie());
        assertEquals("Joe's Cards", back.path("store").asText());
        assertEquals(1, back.path("stores").size());
    }

    @Test
    void platformOwnerAdministersEveryStore() throws Exception {
        String admin = platformOwner();
        String shopEmail = "shop-" + UUID.randomUUID() + "@example.com";
        String shop = signup("Admin Target", shopEmail);
        assertEquals(403, call("GET", "/api/admin/stores", shop, null).status());
        assertEquals(401, call("GET", "/api/admin/stores", null, null).status());

        var stores = call("GET", "/api/admin/stores", admin, null);
        assertEquals(200, stores.status(), stores.raw());
        String id = null;
        for (var store : stores.body()) if (store.path("name").asText().equals("Admin Target")) id = store.path("id").asText();
        assertNotNull(id);

        // Ending a store's plan locks its workflow; reactivating it opens it again.
        assertEquals(200, call("PUT", "/api/admin/stores/" + id, admin, Map.of("planStatus", "canceled")).status());
        assertEquals(402, call("GET", "/api/app/trades", shop, null).status());
        var renewed = call("PUT", "/api/admin/stores/" + id, admin, Map.of("planStatus", "trial", "trialEndsAt", "2099-01-31", "name", "Renamed Target"));
        assertEquals(200, renewed.status(), renewed.raw());
        assertEquals(200, call("GET", "/api/app/trades", shop, null).status());
        assertEquals("Renamed Target", me(shop).path("store").asText());
        assertEquals(400, call("PUT", "/api/admin/stores/" + id, admin, Map.of("planStatus", "free forever")).status());

        // Admin brings anyone onto any store, changes roles, and removes people, but never the last owner.
        var people = call("POST", "/api/admin/stores/" + id + "/members", admin, Map.of("name", "Helper", "email", "h-" + UUID.randomUUID() + "@example.com"));
        assertEquals(200, people.status(), people.raw());
        assertEquals(2, people.body().size());
        String helper = null, owner = null;
        for (var p : people.body()) {
            if (p.path("name").asText().equals("Helper")) helper = p.path("id").asText();
            else owner = p.path("id").asText();
        }
        assertEquals(400, call("PUT", "/api/admin/users/" + owner, admin, Map.of("removed", true)).status());
        assertEquals(200, call("PUT", "/api/admin/users/" + helper, admin, Map.of("role", "owner")).status());
        assertEquals(200, call("PUT", "/api/admin/users/" + owner, admin, Map.of("removed", true)).status());
        assertEquals(401, call("GET", "/api/app/trades", shop, null).status());
        assertEquals(200, call("PUT", "/api/admin/users/" + owner, admin, Map.of("removed", false)).status());
        assertEquals(200, call("GET", "/api/app/trades", shop, null).status());
        var all = call("GET", "/api/admin/users", admin, null).body();
        assertTrue(all.size() >= 3);
    }

    @Test
    void platformOwnerIsTheVerifiedOwnerEmail() throws Exception {
        String owner = platformOwner();
        assertTrue(call("GET", "/api/auth/me", owner, null).body().path("admin").asBoolean());
        String other = signup("Other Store", "o-" + UUID.randomUUID() + "@example.com");
        assertFalse(call("GET", "/api/auth/me", other, null).body().path("admin").asBoolean());
        var out = call("POST", "/api/auth/logout", owner, Map.of());
        assertTrue(out.body().path("logoutUrl").asText().startsWith(issuer() + "v2/logout?client_id=" + CLIENT_ID));
    }

    @Test
    void theCardBoxLinkStaysOffUntilSwitchedOn() throws Exception {
        assertNull(query(call("GET", "/api/auth/login", null, null).location(), "audience"), "no CardBox API token requested");
        String cookie = signup("Unlinked", "unlinked-" + UUID.randomUUID() + "@example.com");
        assertFalse(call("GET", "/api/auth/me", cookie, null).body().path("cardbox").asBoolean());
        assertEquals(404, call("GET", "/api/cardbox/people", cookie, null).status());
    }

    @Test
    void collectionSyncAnswers503WhileSwitchedOff() throws Exception {
        // Not 404: Club reads a 404 on a link as the store ending it.
        var off = call("POST", "/api/partner/club-sync/links/c1/items", null, Map.of("upserts", List.of()));
        assertEquals(503, off.status(), off.raw());
        assertEquals("Collection sync is switched off on cardbox.trading", off.body().path("detail").asText());
    }

    @Test
    void mutatingRequestsMustBeJson() throws Exception {
        String owner = signup("Csrf Store", "c-" + UUID.randomUUID() + "@example.com");
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/app/rates"))
                .header("Cookie", owner).header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(415, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private String cacheControl(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path);
        return response.headers().firstValue("Cache-Control").orElse("");
    }

    @Test
    void newReleasesAreNeverHiddenByTheBrowserCache() throws Exception {
        // The page is always rechecked; the hashed bundles it points at are cached for good.
        assertEquals("no-cache", cacheControl("/"));
        assertEquals("no-cache", cacheControl("/app/trades"));
        assertTrue(cacheControl("/assets/index-test.js").contains("immutable"));
    }
}

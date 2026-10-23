package com.cardpricer.cloud.trade;

import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Saved store purchases, with actual consideration kept apart from guide prices. No valuation admission. */
@Repository
public class CompletedBuyinEvents {
    public static final String ORIGIN = "cardbox.trading";
    public static final int MAX_BATCH = 200;
    private final JdbcTemplate jdbc;
    private final String revision;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public record Receipt(String eventId, String payloadSha256, String receiverReceiptId) {}

    public CompletedBuyinEvents(JdbcTemplate jdbc, @Value("${app.source-revision:unknown}") String revision) {
        this.jdbc = jdbc;
        this.revision = revision.matches("[a-f0-9]{40}") ? revision : "unknown";
    }

    /** Mandatory joins TradeService.save; an event failure rolls back header, lines and inventory too. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID tenant, UUID trade) {
        var header = jdbc.queryForMap("SELECT payment, created_at FROM trades WHERE id=? AND tenant_id=?", trade, tenant);
        var lines = jdbc.queryForList("SELECT * FROM trade_lines WHERE trade_id=? AND tenant_id=? ORDER BY line_no", trade, tenant);
        for (var line : lines) {
            int number = ((Number) line.get("line_no")).intValue();
            String event = "cardbox.trading:buyin:" + trade + ":" + number + ":v1";
            var body = new TreeMap<String, Object>();
            body.put("event_id", event);
            body.put("event_kind", "completed_buyin");
            body.put("schema_version", 1);
            body.put("origin", ORIGIN);
            body.put("deployment_revision", revision);
            body.put("trade_id", trade.toString());
            body.put("store_tenant_id", tenant.toString());
            body.put("line_no", number);
            body.put("completed_at", ((java.sql.Timestamp) header.get("created_at")).toInstant().toString());
            body.put("currency", null);
            body.put("currency_state", "unrecorded");
            body.put("payment", header.get("payment"));
            body.put("quantity", line.get("quantity"));
            body.put("condition", line.get("condition"));
            body.put("finish", line.get("finish"));
            body.put("language", line.get("lang"));
            body.put("set_code", line.get("set_code"));
            body.put("collector_number", line.get("collector_number"));
            body.put("trading_card_id", line.get("card_id").toString());
            body.put("identity", identity((UUID) line.get("card_id"), (String) line.get("finish"), (String) line.get("lang")));
            // Both allocations are NOT NULL numeric(12,2) in trade_lines; unknown currency is separate.
            body.put("consideration", Map.of("store_credit_total", money(line.get("credit_alloc")),
                                             "check_total", money(line.get("check_alloc"))));
            var guide = new TreeMap<String, Object>();
            for (String field : List.of("market_unit", "valuation_unit", "tcgplayer_unit"))
                guide.put(field, money(line.get(field)));
            body.put("guide", guide);
            body.put("valuation_admission", "held_buyin_and_currency_not_a_retail_sale");
            String serialized = serialize(body);
            String digest = digest(serialized);
            int inserted = jdbc.update("""
                    INSERT INTO completed_buyin_events(event_id,tenant_id,trade_id,line_no,schema_version,payload_sha256,payload)
                    VALUES (?,?,?,?,1,?,?::jsonb) ON CONFLICT(event_id) DO NOTHING""", event, tenant, trade, number, digest, serialized);
            if (inserted == 0 && !digest.equals(jdbc.queryForObject(
                    "SELECT payload_sha256 FROM completed_buyin_events WHERE event_id=?", String.class, event)))
                throw conflict("Saved buy-in event facts conflict with its original event");
            jdbc.update("""
                    INSERT INTO completed_buyin_event_queue(event_id)
                    SELECT ? WHERE NOT EXISTS (SELECT 1 FROM completed_buyin_event_receipts WHERE event_id=?)
                    ON CONFLICT(event_id) DO NOTHING""", event, event);
        }
    }

    private Map<String, Object> identity(UUID card, String finish, String language) {
        var candidates = jdbc.queryForList("""
                SELECT printing_id FROM catalog_printing_links WHERE card_id=? AND finish=? AND language=?
                UNION SELECT club_printing_id FROM club_cards WHERE id=? ORDER BY 1""", String.class, card, finish, language, card);
        return Map.of("scope", candidates.size() == 1 ? "exact_printing" : candidates.isEmpty() ? "unresolved" : "candidate_printings",
                "printing_id", candidates.size() == 1 ? candidates.getFirst() : "",
                "candidate_printing_ids", candidates.size() > 1 ? candidates : List.of(),
                "mapping_source", "trading_catalog_links_at_save");
    }

    public Map<String, Object> pending(int limit) {
        if (limit < 1 || limit > MAX_BATCH) throw ApiException.badRequest("Read at most 200 buy-in events");
        var events = jdbc.query("""
                SELECT e.event_id,e.payload_sha256,e.payload::text
                FROM completed_buyin_event_queue q JOIN completed_buyin_events e ON e.event_id=q.event_id
                ORDER BY q.enqueued_at,q.event_id LIMIT ?""", (rs, i) -> Map.of(
                    "event_id", rs.getString(1), "payload_sha256", rs.getString(2), "payload", parse(rs.getString(3))), limit);
        return Map.of("events", events);
    }

    @Transactional
    public Map<String, Object> acknowledge(List<Receipt> receipts) {
        if (receipts == null || receipts.isEmpty() || receipts.size() > MAX_BATCH)
            throw ApiException.badRequest("Acknowledge 1 to 200 buy-in events");
        var unique = new TreeMap<String, Receipt>();
        for (var r : receipts) {
            if (r == null || r.eventId() == null || r.eventId().length() > 200 || r.payloadSha256() == null
                    || !r.payloadSha256().matches("[a-f0-9]{64}") || r.receiverReceiptId() == null
                    || r.receiverReceiptId().isBlank() || r.receiverReceiptId().length() > 300)
                throw ApiException.badRequest("Every acknowledgment needs its event id, digest and receiver receipt");
            if (unique.putIfAbsent(r.eventId(), r) != null && !unique.get(r.eventId()).equals(r))
                throw conflict("Conflicting receipts in acknowledgment batch");
        }
        for (var r : unique.values()) {
            var found = jdbc.queryForList("SELECT payload_sha256 FROM completed_buyin_events WHERE event_id=? FOR UPDATE", r.eventId());
            if (found.isEmpty() || !r.payloadSha256().equals(found.getFirst().get("payload_sha256")))
                throw conflict("Unknown buy-in event or digest mismatch");
            int inserted = jdbc.update("""
                    INSERT INTO completed_buyin_event_receipts(event_id,payload_sha256,receiver_receipt_id)
                    VALUES (?,?,?) ON CONFLICT(event_id) DO NOTHING""", r.eventId(), r.payloadSha256(), r.receiverReceiptId());
            if (inserted == 0 && !r.receiverReceiptId().equals(jdbc.queryForObject(
                    "SELECT receiver_receipt_id FROM completed_buyin_event_receipts WHERE event_id=?", String.class, r.eventId())))
                throw conflict("Buy-in event was acknowledged with a different receiver receipt");
            jdbc.update("DELETE FROM completed_buyin_event_queue WHERE event_id=?", r.eventId());
        }
        return Map.of("acknowledged", unique.size());
    }

    private static String money(Object amount) { return amount == null ? null : ((java.math.BigDecimal) amount).toPlainString(); }
    private String serialize(Object body) {
        try { return json.writeValueAsString(body); }
        catch (JsonProcessingException e) { throw new IllegalStateException("buyin_event_serialization_failed"); }
    }
    private Object parse(String body) {
        try { return json.readTree(body); }
        catch (JsonProcessingException e) { throw new IllegalStateException("buyin_event_read_failed"); }
    }
    private static String digest(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("sha256_unavailable"); }
    }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
}

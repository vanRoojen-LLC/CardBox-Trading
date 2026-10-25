package com.cardpricer.cloud.trade;

import com.cardpricer.cloud.auth.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

import static com.cardpricer.cloud.trade.TransactionCopyFacts.*;

/** Pure preparation only: no database writer, save hook, pending queue or outbound transport. */
public final class TransactionCopyFactEvents {
    public static final String ORIGIN="cardbox.trading";
    public static final int MAX_PARENT_BYTES=512_000;
    public static final int MAX_EVENT_BYTES=576_000;
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> PARENT_FIELDS=Set.of("event_id","event_kind","schema_version","origin",
            "deployment_revision","trade_id","store_tenant_id","line_no","completed_at","currency","currency_state",
            "payment","quantity","condition","finish","language","set_code","collector_number","trading_card_id",
            "identity","consideration","guide","valuation_admission");
    private final String revision;

    public TransactionCopyFactEvents(String revision) {
        if (revision == null || !(revision.equals("unknown") || revision.matches("[a-f0-9]{40}")))
            throw invalid("A server source revision is required");
        this.revision=revision;
    }

    /** The eventual save adapter must supply its stored original event and guarded CurrentUser.
     * This function checks their context, but cannot authenticate a historical actor or commit data. */
    public Event build(JsonNode parentEnvelope, int lineNo, TradeService.PricedLine line,
                       CurrentUser actor, Selection selection) {
        String context=contextDigest(lineNo,line,actor);
        if (actor == null || actor.userId() == null || actor.tenantId() == null)
            throw invalid("An authenticated save actor and store are required");
        if (selection == null || !context.equals(selection.contextSha256()))
            throw invalid("Copy facts refer to a different saved line");
        validate(selection.facts(),line);
        object(parentEnvelope,Set.of("event_id","payload_sha256","payload"));
        JsonNode parent=parentEnvelope.get("payload"); object(parent,PARENT_FIELDS);
        String parentCanonical=canonicalJson(parent);
        if (parentCanonical.getBytes(StandardCharsets.UTF_8).length > MAX_PARENT_BYTES)
            throw invalid("Original buy-in exceeds the receiver bound");
        String parentHash=text(parentEnvelope.get("payload_sha256"),64);
        if (!parentHash.matches("[a-f0-9]{64}") || !parentHash.equals(digest(parent)))
            throw invalid("Original buy-in payload digest differs");
        UUID trade=uuid(parent.get("trade_id"));
        String parentId=ORIGIN+":buyin:"+trade+":"+lineNo+":v1";
        if (!parentId.equals(text(parentEnvelope.get("event_id"),200))
                || !parentId.equals(text(parent.get("event_id"),200))
                || !ORIGIN.equals(text(parent.get("origin"),40))
                || !"completed_buyin".equals(text(parent.get("event_kind"),40))
                || integer(parent.get("schema_version"),1,1)!=1)
            throw invalid("Unsupported original buy-in source");
        if (!uuid(parent.get("store_tenant_id")).equals(actor.tenantId())
                || !uuid(parent.get("trading_card_id")).equals(line.cardId())
                || integer(parent.get("line_no"),1,500)!=lineNo
                || integer(parent.get("quantity"),1,999)!=line.quantity()
                || !text(parent.get("condition"),3).equals(line.condition())
                || !text(parent.get("finish"),40).equals(line.finish())
                || !text(parent.get("language"),30).equals(line.lang()))
            throw invalid("Original buy-in differs from the authenticated saved line");
        validateParent(parent);
        String stamp=text(parent.get("completed_at"),300);
        try { OffsetDateTime.parse(stamp); }
        catch (DateTimeParseException e) { throw invalid("Original buy-in needs a timezone-qualified completion time"); }
        String eventId=ORIGIN+":buyin-copy-facts:"+trade+":"+lineNo+":v1";
        Map<String,Object> body=new TreeMap<>();
        body.put("event_id",eventId); body.put("event_kind","completed_buyin_copy_facts");
        body.put("schema_version",1); body.put("origin",ORIGIN); body.put("deployment_revision",revision);
        body.put("parent",Map.of("event_id",parentId,"payload_sha256",parentHash));
        for (String field:List.of("store_tenant_id","trade_id","line_no","trading_card_id","quantity","finish","language","identity","completed_at"))
            body.put(field,parent.get(field));
        // Persisted database time is stable on replay and cannot suffer application/DB clock skew.
        body.put("captured_at",stamp); body.put("actor_user_id",actor.userId().toString());
        body.put("recorded_condition",Map.of("value",line.condition(),"provenance","saved_trade_line_may_include_legacy_default"));
        body.put("copy_scope",line.quantity()==1 ? "single_unit" : "grouped_line");
        body.put("facts",selection.facts().payload());
        body.put("confirmation",selection.facts().known() ? Map.of("actor_user_id",actor.userId().toString(),
                "confirmed_at",stamp,"scope","explicit_trade_save_confirmation") : null);
        body.put("supersedes_event_id",null); body.put("valuation_admission","held_buyin_not_retail_sale");
        String canonical=canonicalJson(body);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES)
            throw invalid("Copy fact event exceeds the receiver bound");
        return new Event(eventId,digest(body),canonical);
    }

    /** Immutable serialized bytes are the source of truth; returned JSON nodes are detached copies. */
    public static final class Event {
        private final String eventId, payloadSha256, canonicalJson;
        private Event(String eventId,String payloadSha256,String canonicalJson) {
            this.eventId=eventId; this.payloadSha256=payloadSha256; this.canonicalJson=canonicalJson;
        }
        public String eventId() { return eventId; }
        public String payloadSha256() { return payloadSha256; }
        public String canonicalJson() { return canonicalJson; }
        public JsonNode payload() {
            try { return JSON.readTree(canonicalJson); }
            catch (JsonProcessingException e) { throw new IllegalStateException("copy_fact_serialization_failed"); }
        }
        public ObjectNode envelope() {
            ObjectNode node=JSON.createObjectNode(); node.put("event_id",eventId);node.put("payload_sha256",payloadSha256);
            node.set("payload",payload());return node;
        }
    }

    private static void validateParent(JsonNode parent) {
        if (!parent.get("currency").isNull() || !"unrecorded".equals(text(parent.get("currency_state"),30))
                || !"held_buyin_and_currency_not_a_retail_sale".equals(text(parent.get("valuation_admission"),100))
                || !Set.of("credit","check","partial").contains(text(parent.get("payment"),20)))
            throw invalid("Unsupported original monetary domain");
        String revision=text(parent.get("deployment_revision"),40);
        if (!(revision.equals("unknown") || revision.matches("[a-f0-9]{40}"))) throw invalid("Invalid original source revision");
        for (String field:List.of("set_code","collector_number")) {
            JsonNode value=parent.get(field);
            if (!value.isTextual() || value.textValue().codePointCount(0,value.textValue().length())>300)
                throw invalid("Invalid original printing label");
        }
        JsonNode consideration=parent.get("consideration"),guide=parent.get("guide");
        object(consideration,Set.of("store_credit_total","check_total"));
        object(guide,Set.of("market_unit","valuation_unit","tcgplayer_unit"));
        consideration.forEach(n -> money(n,false)); guide.forEach(n -> money(n,true));
        JsonNode identity=parent.get("identity");object(identity,Set.of("scope","printing_id","candidate_printing_ids","mapping_source"));
        if (!"trading_catalog_links_at_save".equals(text(identity.get("mapping_source"),100))
                || !identity.get("printing_id").isTextual() || !identity.get("candidate_printing_ids").isArray())
            throw invalid("Unsupported original identity provenance");
        String scope=text(identity.get("scope"),30),printing=identity.get("printing_id").textValue();
        if (printing.codePointCount(0,printing.length())>300) throw invalid("Original printing identifier is too long");
        JsonNode candidates=identity.get("candidate_printing_ids"); Set<String> ids=new HashSet<>();
        if (candidates.size()>10_000) throw invalid("Too many original printing candidates");
        for (JsonNode candidate:candidates) if (!ids.add(text(candidate,300))) throw invalid("Repeated original printing candidate");
        if (!(scope.equals("exact_printing") && !printing.isEmpty() && candidates.isEmpty()
                || scope.equals("candidate_printings") && printing.isEmpty() && candidates.size()>1
                || scope.equals("unresolved") && printing.isEmpty() && candidates.isEmpty()))
            throw invalid("Inconsistent original identity scope");
    }
    private static void money(JsonNode node,boolean nullable) {
        if (nullable && node.isNull()) return;
        if (!node.isTextual() || !node.textValue().matches("[0-9]{1,10}\\.[0-9]{2}")) throw invalid("Original amounts must remain decimal strings");
    }
    private static int integer(JsonNode node,int minimum,int maximum) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue()<minimum || node.intValue()>maximum)
            throw invalid("Invalid original integer field");
        return node.intValue();
    }
    private static UUID uuid(JsonNode node) {
        String value=text(node,36);
        try { UUID id=UUID.fromString(value);if (id.toString().equals(value)) return id; }
        catch (IllegalArgumentException ignored) { }
        throw invalid("Original identifiers must be canonical UUIDs");
    }

    public static String canonicalJson(Object value) {
        try { return JSON.writeValueAsString(normalize(value,0)); }
        catch (JsonProcessingException e) { throw invalid("Copy facts must be canonical JSON"); }
    }
    public static String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson(value).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("sha256_unavailable"); }
    }
    private static Object normalize(Object value,int depth) {
        if (depth>32) throw invalid("Copy facts are nested too deeply");
        if (value instanceof JsonNode node) {
            if (node.isNull()) return null;
            if (node.isTextual()) return normalize(node.textValue(),depth+1);
            if (node.isBoolean()) return node.booleanValue();
            if (node.isIntegralNumber()) return node.bigIntegerValue();
            if (node.isObject()) {
                Map<String,Object> result=new TreeMap<>();
                node.fields().forEachRemaining(e -> { validString(e.getKey());result.put(e.getKey(),normalize(e.getValue(),depth+1)); });
                return result;
            }
            if (node.isArray()) { List<Object> result=new ArrayList<>();node.forEach(n -> result.add(normalize(n,depth+1)));return result; }
            throw invalid("Floating point values cannot enter copy facts");
        }
        if (value==null || value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof BigInteger) return value;
        if (value instanceof String text) { validString(text);return text; }
        if (value instanceof Map<?,?> map) {
            Map<String,Object> result=new TreeMap<>();
            map.forEach((key,item) -> { if (!(key instanceof String)) throw invalid("JSON keys must be strings");validString((String) key);result.put((String) key,normalize(item,depth+1)); });
            return result;
        }
        if (value instanceof List<?> list) { List<Object> result=new ArrayList<>();list.forEach(n -> result.add(normalize(n,depth+1)));return result; }
        throw invalid("Copy facts must be canonical JSON values");
    }
    static void validString(String value) {
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (c==0) throw invalid("NUL is not a copy fact character");
            if (Character.isHighSurrogate(c)) {
                if (i+1>=value.length() || !Character.isLowSurrogate(value.charAt(++i))) throw invalid("Unpaired Unicode surrogate");
            } else if (Character.isLowSurrogate(c)) throw invalid("Unpaired Unicode surrogate");
        }
    }
}

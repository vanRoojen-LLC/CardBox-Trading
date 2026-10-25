package com.cardpricer.cloud.trade;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.catalog.Confidence;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

import static com.cardpricer.cloud.trade.TransactionCopyFacts.*;
import static org.junit.jupiter.api.Assertions.*;

class TransactionCopyFactsTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final UUID TENANT=UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    private static final UUID USER=UUID.fromString("12345678-1234-4234-8234-123456789abc");
    private final TransactionCopyFactEvents events=new TransactionCopyFactEvents("a".repeat(40));

    private static JsonNode resource(String name) throws IOException {
        try (var in=TransactionCopyFactsTest.class.getResourceAsStream("/transaction-copy-facts/"+name)) {
            assertNotNull(in);return JSON.readTree(in);
        }
    }
    private static String hashResource(String name) throws IOException {
        try (var in=TransactionCopyFactsTest.class.getResourceAsStream("/transaction-copy-facts/"+name)) {
            assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8).trim();
        }
    }
    static TradeService.PricedLine line(int quantity) {
        return new TradeService.PricedLine(UUID.fromString("99999999-8888-4777-8666-555555555555"),"Card","Café Ω","Set","∞/250",
                "rare","ja","normal","NM",quantity,new BigDecimal("12.30"),new BigDecimal("10.00"),
                new BigDecimal("0.50"),new BigDecimal("0.40"),new BigDecimal("5.00"),new BigDecimal("4.00"),
                Confidence.Level.low,BigDecimal.ONE,false,List.of(),null,"magic");
    }
    private static CurrentUser user(UUID tenant,UUID id) {
        return new CurrentUser(id,tenant,"staff","Not copied","not-copied@example.invalid",false,"not-copied",false);
    }
    private static Selection parse(JsonNode input,int number,TradeService.PricedLine line) {
        return TransactionCopyFacts.parse(input,number,line,user(TENANT,USER));
    }
    private static String contextDigest(int number,TradeService.PricedLine line) {
        return TransactionCopyFacts.contextDigest(number,line,user(TENANT,USER));
    }
    private static ObjectNode envelope(JsonNode payload) {
        ObjectNode envelope=JSON.createObjectNode();envelope.set("payload",payload);
        envelope.put("event_id",payload.get("event_id").textValue());
        envelope.put("payload_sha256",TransactionCopyFactEvents.digest(payload));return envelope;
    }
    private static ObjectNode input(JsonNode facts,TradeService.PricedLine line) {
        ObjectNode input=JSON.createObjectNode();input.put("context_sha256",contextDigest(1,line));
        input.put("confirmed",true);input.set("facts",facts);return input;
    }
    private static ApiException rejected(Runnable call) {
        ApiException error=assertThrows(ApiException.class,call::run);assertEquals(HttpStatus.BAD_REQUEST,error.status());return error;
    }

    @Test void canonicalBuilderMatchesSettledPythonReceiverFixtureExactly() throws Exception {
        JsonNode expected=resource("payload.json"),parent=resource("parent.json");
        var line=line(1);var selected=parse(input(expected.get("facts"),line),1,line);
        var result=events.build(envelope(parent),1,line,user(TENANT,USER),selected);
        assertEquals(expected,result.payload());
        assertEquals(hashResource("payload-sha256.txt"),result.payloadSha256());
        assertEquals(hashResource("parent-sha256.txt"),TransactionCopyFactEvents.digest(parent));
        assertEquals(hashResource("context-sha256.txt"),contextDigest(1,line));
        assertEquals(hashResource("context-sha256.txt"),TransactionCopyFactEvents.digest(resource("context.json")));
        assertEquals("9.50",result.payload().at("/facts/grading/label").textValue());
        assertTrue(result.payload().at("/facts/grading/certificate").isNull());
        assertFalse(result.payload().has("currency"));assertFalse(result.payload().has("consideration"));
        assertFalse(result.canonicalJson().contains("not-copied"));
    }

    @Test void omittedFactsAutomaticallyRemainUnknownDespiteNmNormalLegacyLabels() throws Exception {
        var line=line(1);var selected=parse(null,1,line);
        JsonNode result=events.build(envelope(resource("parent.json")),1,line,user(TENANT,USER),selected).payload();
        assertEquals("NM",result.at("/recorded_condition/value").textValue());
        assertEquals("saved_trade_line_may_include_legacy_default",result.at("/recorded_condition/provenance").textValue());
        assertTrue(result.at("/facts/condition").isNull());assertEquals("unknown",result.at("/facts/form").textValue());
        assertEquals("unknown",result.at("/facts/signature").textValue());assertEquals("unknown",result.at("/facts/serial/state").textValue());
        assertTrue(result.get("confirmation").isNull());assertEquals("held_buyin_not_retail_sale",result.get("valuation_admission").textValue());
    }

    @Test void explicitRawFactsAreBoundToSaveActorAndPersistedDatabaseTime() throws Exception {
        var line=line(1);var facts=JSON.valueToTree(new Facts("NM",Form.raw,null,Signature.unsigned,
                new Serial(SerialState.unnumbered,null),ValueVariant.absent).payload());
        var result=events.build(envelope(resource("parent.json")),1,line,user(TENANT,USER),parse(input(facts,line),1,line));
        assertEquals(USER.toString(),result.payload().at("/confirmation/actor_user_id").textValue());
        assertEquals(result.payload().get("completed_at"),result.payload().get("captured_at"));
        assertEquals(result.payload().get("captured_at"),result.payload().at("/confirmation/confirmed_at"));
        assertEquals(result.canonicalJson(),events.build(envelope(resource("parent.json")),1,line,user(TENANT,USER),parse(input(facts,line),1,line)).canonicalJson());
    }

    @Test void groupedLineRecordsUnknownsAndCannotAssignOnePhysicalCopiesGradeOrSerial() throws Exception {
        var line=line(3);ObjectNode parent=(ObjectNode) resource("parent.json");parent.put("quantity",3);
        JsonNode result=events.build(envelope(parent),1,line,user(TENANT,USER),parse(null,1,line)).payload();
        assertEquals("grouped_line",result.get("copy_scope").textValue());assertTrue(result.get("confirmation").isNull());
        rejected(() -> parse(input(resourceUnchecked("payload.json").get("facts"),line),1,line));
        rejected(() -> parse(null,1,line(0)));rejected(() -> parse(null,1,line(1000)));
        rejected(() -> parse(null,501,line(1)));
    }

    @Test void sourceInputCannotSpoofActorOriginTimeOrExactContext() throws Exception {
        var line=line(1);JsonNode facts=resource("payload.json").get("facts");
        for (String field:List.of("actor_user_id","origin","confirmed_at","private_copy_id")) {
            ObjectNode request=input(facts,line);request.put(field,"caller");rejected(() -> parse(request,1,line));
        }
        for (JsonNode confirmation:List.<JsonNode>of(JSON.valueToTree("true"),JSON.valueToTree(1),JSON.valueToTree(false))) {
            ObjectNode request=input(facts,line);request.set("confirmed",confirmation);rejected(() -> parse(request,1,line));
        }
        ObjectNode request=input(facts,line);request.put("context_sha256","0".repeat(64));rejected(() -> parse(request,1,line));
        var selected=parse(null,1,line);rejected(() -> events.build(envelope(resourceUnchecked("parent.json")),2,line,user(TENANT,USER),selected));
    }

    @Test void typedFactsRefuseCoercionUnknownFieldsAndInconsistentTuples() throws Exception {
        var line=line(1);
        List<Consumer<ObjectNode>> changes=List.of(
                f -> f.put("signature",false),f -> f.put("form",1),f -> f.put("condition","LP"),
                f -> f.put("known_value_variant","guessed"),f -> f.put("form","raw"),f -> f.remove("serial"),
                f -> f.put("notes","looks mint"),f -> ((ObjectNode) f.get("grading")).put("label",9.5),
                f -> ((ObjectNode) f.get("serial")).put("state","unknown"));
        for (var change:changes) {
            ObjectNode facts=(ObjectNode) resource("payload.json").get("facts").deepCopy();change.accept(facts);
            rejected(() -> parse(input(facts,line),1,line));
        }
        rejected(() -> parse(input(JSON.valueToTree(unknown().payload()),line),1,line));
        rejected(() -> validate(new Facts(null,null,null,Signature.unknown,new Serial(SerialState.unknown,null),ValueVariant.unknown),line));
        rejected(() -> events.build(envelope(resourceUnchecked("parent.json")),1,line,user(TENANT,USER),null));
    }

    @Test void actorAndParentContextsCannotBorrowAnotherStoresOrLinesFacts() throws Exception {
        JsonNode parent=resource("parent.json");var line=line(1);var selection=parse(null,1,line);
        rejected(() -> events.build(envelope(parent),1,line,null,selection));
        rejected(() -> events.build(envelope(parent),1,line,user(TENANT,null),selection));
        rejected(() -> events.build(envelope(parent),1,line,user(UUID.randomUUID(),USER),selection));
        rejected(() -> events.build(envelope(parent),1,line,user(TENANT,UUID.randomUUID()),selection));
        List<Consumer<ObjectNode>> changes=List.of(p -> p.put("quantity",2),p -> p.put("quantity",true),
                p -> p.put("line_no",1.0),p -> p.put("trading_card_id",UUID.randomUUID().toString()),p -> p.put("finish","foil"),
                p -> p.put("language","en"),p -> p.put("condition","LP"),p -> p.put("schema_version",2),
                p -> p.put("currency","USD"),p -> p.put("event_kind","retail_sale"),p -> p.put("completed_at","2026-10-23"),
                p -> ((ObjectNode)p.get("consideration")).put("check_total",4.56));
        for (var change:changes) {
            ObjectNode changed=parent.deepCopy();change.accept(changed);
            rejected(() -> events.build(envelope(changed),1,line,user(TENANT,USER),selection));
        }
        ObjectNode forged=envelope(parent);forged.put("payload_sha256","0".repeat(64));
        rejected(() -> events.build(forged,1,line,user(TENANT,USER),selection));
    }

    @Test void fullLargeCandidateIdentityIsRetainedAndReturnedNodesCannotMutateFrozenBytes() throws Exception {
        ObjectNode parent=(ObjectNode) resource("parent.json");ObjectNode identity=(ObjectNode)parent.get("identity");
        identity.put("scope","candidate_printings");identity.put("printing_id","");var candidates=identity.putArray("candidate_printing_ids");
        for (int i=0;i<4000;i++) candidates.add(String.format("p:%05d:",i)+"x".repeat(115));
        assertTrue(TransactionCopyFactEvents.canonicalJson(parent).getBytes(StandardCharsets.UTF_8).length>500_000);
        var line=line(1);var result=events.build(envelope(parent),1,line,user(TENANT,USER),parse(null,1,line));
        assertEquals(identity,result.payload().get("identity"));String frozen=result.canonicalJson();
        ((ObjectNode)result.payload()).put("actor_user_id","mutated");result.envelope().put("event_id","mutated");
        identity.put("scope","mutated");assertEquals(frozen,result.canonicalJson());
        assertEquals(4000,result.payload().at("/identity/candidate_printing_ids").size());
    }

    @Test void candidateAndUnresolvedIdentityStayDistinctAndInconsistentIdentityCannotClaimExactness() throws Exception {
        var line=line(1);var selection=parse(null,1,line);
        for (String scope:List.of("candidate_printings","unresolved")) {
            ObjectNode parent=(ObjectNode)resource("parent.json"),identity=(ObjectNode)parent.get("identity");
            identity.put("scope",scope);identity.put("printing_id","");var candidates=identity.putArray("candidate_printing_ids");
            if (scope.equals("candidate_printings")) candidates.add("retired-a").add("unresolved-b");
            assertEquals(identity,events.build(envelope(parent),1,line,user(TENANT,USER),selection).payload().get("identity"));
        }
        List<Consumer<ObjectNode>> changes=List.of(i -> i.put("mapping_source","guessed_from_name"),
                i -> i.putArray("candidate_printing_ids").add("extra"),
                i -> { i.put("scope","candidate_printings");i.put("printing_id","");i.putArray("candidate_printing_ids").add("same").add("same"); },
                i -> { i.put("scope","unresolved");i.put("printing_id","");i.putArray("candidate_printing_ids").add("extra"); });
        for (var change:changes) {
            ObjectNode parent=(ObjectNode)resource("parent.json");change.accept((ObjectNode)parent.get("identity"));
            rejected(() -> events.build(envelope(parent),1,line,user(TENANT,USER),selection));
        }
    }

    @Test void canonicalJsonRejectsNoncanonicalNumbersAndUnicodeRatherThanSilentlyReplacingThem() throws Exception {
        for (Object value:Arrays.asList(1.5,Double.NaN,Map.of("nested",List.of(1.2)),Map.of(1,"key"),"bad\u0000text","\ud800"))
            rejected(() -> TransactionCopyFactEvents.canonicalJson(value));
        JsonNode parent=resource("parent.json");Map<String,Object> reversed=new LinkedHashMap<>();
        List<String> fields=new ArrayList<>();parent.fieldNames().forEachRemaining(fields::add);Collections.reverse(fields);
        for (String field:fields) reversed.put(field,parent.get(field));
        assertEquals(TransactionCopyFactEvents.digest(parent),TransactionCopyFactEvents.digest(reversed));
        assertEquals("{\"control\":\"\\u001B\",\"literal\":\"\\\\u001b\"}",
                TransactionCopyFactEvents.canonicalJson(Map.of("control","\u001b","literal","\\u001b")));
    }

    @Test void textBoundsCountUnicodeCodePointsAsTheReceiverDoes() throws Exception {
        var line=line(1);ObjectNode facts=(ObjectNode)resource("payload.json").get("facts").deepCopy();
        ((ObjectNode)facts.get("grading")).put("certificate","🂡".repeat(200));
        assertNotNull(parse(input(facts,line),1,line));
        ((ObjectNode)facts.get("grading")).put("certificate","🂡".repeat(201));rejected(() -> parse(input(facts,line),1,line));
    }

    private static JsonNode resourceUnchecked(String name) {
        try { return resource(name); } catch (IOException e) { throw new AssertionError(e); }
    }
}

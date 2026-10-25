package com.cardpricer.cloud.trade;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/** Prospective explicit save facts. Legacy NM/normal labels do not establish copy condition/form. */
public final class TransactionCopyFacts {
    private TransactionCopyFacts() {}

    public enum Form { unknown, raw, graded }
    public enum Signature { unknown, signed, unsigned }
    public enum SerialState { unknown, numbered, unnumbered }
    public enum ValueVariant { unknown, present, absent }
    public record Grading(String company, String label, String certificate) {}
    public record Serial(SerialState state, String number) {}
    public record Facts(String condition, Form form, Grading grading, Signature signature,
                        Serial serial, ValueVariant knownValueVariant) {
        public boolean known() {
            return condition != null || form != Form.unknown || signature != Signature.unknown
                    || serial.state() != SerialState.unknown || knownValueVariant != ValueVariant.unknown;
        }
        public Map<String, Object> payload() {
            var result = new TreeMap<String, Object>();
            result.put("condition", condition); result.put("form", form.name());
            result.put("grading", grading == null ? null : nullableMap("company", grading.company(),
                    "label", grading.label(), "certificate", grading.certificate()));
            result.put("signature", signature.name());
            result.put("serial", nullableMap("state", serial.state().name(), "number", serial.number()));
            result.put("known_value_variant", knownValueVariant.name());
            return Collections.unmodifiableMap(result);
        }
    }
    public static final class Selection {
        private final Facts facts;
        private final String contextSha256;
        private Selection(Facts facts,String contextSha256) { this.facts=facts;this.contextSha256=contextSha256; }
        public Facts facts() { return facts; }
        public String contextSha256() { return contextSha256; }
    }

    /** Missing input creates an explicit unknown tuple; no confirmation or negative facts inferred. */
    public static Selection parse(JsonNode input, int lineNo, TradeService.PricedLine line, CurrentUser actor) {
        String context = contextDigest(lineNo, line, actor);
        if (input == null || input.isNull()) return new Selection(unknown(), context);
        object(input, Set.of("context_sha256", "confirmed", "facts"));
        if (!input.get("confirmed").isBoolean() || !input.get("confirmed").booleanValue()
                || !input.get("context_sha256").isTextual() || !context.equals(input.get("context_sha256").textValue()))
            throw invalid("Confirm copy facts for this exact saved line");
        Facts facts = parseFacts(input.get("facts"));
        validate(facts, line);
        if (!facts.known()) throw invalid("Omit confirmation when copy facts are unknown");
        return new Selection(facts, context);
    }

    public static Facts unknown() {
        return new Facts(null, Form.unknown, null, Signature.unknown,
                new Serial(SerialState.unknown, null), ValueVariant.unknown);
    }

    public static String contextDigest(int lineNo, TradeService.PricedLine line, CurrentUser actor) {
        if (actor == null || actor.userId() == null || actor.tenantId() == null)
            throw invalid("An authenticated save actor and store are required");
        if (line == null || line.cardId() == null || lineNo < 1 || lineNo > 500
                || line.quantity() < 1 || line.quantity() > 999) throw invalid("A valid saved line is required");
        condition(line.condition()); text(line.finish(), 40); text(line.lang(), 30);
        return TransactionCopyFactEvents.digest(Map.of("context_version",1,"store_tenant_id",actor.tenantId().toString(),
                "actor_user_id",actor.userId().toString(),"line_no", lineNo, "trading_card_id", line.cardId().toString(),
                "quantity", line.quantity(), "condition", line.condition(), "finish", line.finish(), "language", line.lang()));
    }

    static void validate(Facts facts, TradeService.PricedLine line) {
        if (facts == null || facts.form() == null || facts.signature() == null || facts.serial() == null
                || facts.serial().state() == null || facts.knownValueVariant() == null)
            throw invalid("Copy fact states must be explicit");
        if (facts.condition() != null) {
            condition(facts.condition());
            if (!facts.condition().equals(line.condition())) throw invalid("Copy condition differs from the saved line");
        }
        if (facts.form() == Form.graded) {
            if (facts.grading() == null) throw invalid("Graded form requires grading facts");
            text(facts.grading().company(),100); text(facts.grading().label(),100);
            if (facts.grading().certificate() != null) text(facts.grading().certificate(),200);
        } else if (facts.grading() != null) throw invalid("Only graded form may carry grading facts");
        if (facts.serial().state() == SerialState.numbered) text(facts.serial().number(),100);
        else if (facts.serial().number() != null) throw invalid("Only numbered copies may carry a serial number");
        if (line.quantity() != 1 && facts.known()) throw invalid("Grouped lines cannot confirm one physical copy's facts");
    }

    private static Facts parseFacts(JsonNode node) {
        object(node, Set.of("condition","form","grading","signature","serial","known_value_variant"));
        String condition = nullableText(node.get("condition"),3);
        Form form = choice(node.get("form"),Form.class);
        Grading grading = null;
        if (!node.get("grading").isNull()) {
            JsonNode grade=node.get("grading"); object(grade,Set.of("company","label","certificate"));
            grading=new Grading(text(grade.get("company"),100),text(grade.get("label"),100),nullableText(grade.get("certificate"),200));
        }
        JsonNode serial=node.get("serial"); object(serial,Set.of("state","number"));
        return new Facts(condition,form,grading,choice(node.get("signature"),Signature.class),
                new Serial(choice(serial.get("state"),SerialState.class),nullableText(serial.get("number"),100)),
                choice(node.get("known_value_variant"),ValueVariant.class));
    }

    static void object(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject()) throw invalid("Copy fact objects must have the exact typed fields");
        Set<String> actual=new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw invalid("Copy fact objects must have the exact typed fields");
    }
    static String text(JsonNode node, int maximum) {
        if (node == null || !node.isTextual()) throw invalid("Copy fact text must be a string");
        return text(node.textValue(),maximum);
    }
    static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.codePointCount(0,value.length()) > maximum)
            throw invalid("Copy fact text is missing or too long");
        TransactionCopyFactEvents.validString(value);
        return value;
    }
    private static String nullableText(JsonNode node, int maximum) {
        if (node == null) throw invalid("Unknown copy facts must be explicit nulls");
        return node.isNull() ? null : text(node,maximum);
    }
    private static <E extends Enum<E>> E choice(JsonNode node, Class<E> type) {
        String value=text(node,30);
        try { return Enum.valueOf(type,value); }
        catch (IllegalArgumentException e) { throw invalid("Unknown copy fact state"); }
    }
    private static Map<String,Object> nullableMap(Object... entries) {
        Map<String,Object> result=new TreeMap<>();
        for (int i=0;i<entries.length;i+=2) result.put((String) entries[i],entries[i+1]);
        return Collections.unmodifiableMap(result);
    }
    static void condition(String value) {
        if (value == null || !Set.of("NM","LP","MP","HP","DMG").contains(value)) throw invalid("Unknown saved condition");
    }
    static ApiException invalid(String message) { return ApiException.badRequest(message); }
}

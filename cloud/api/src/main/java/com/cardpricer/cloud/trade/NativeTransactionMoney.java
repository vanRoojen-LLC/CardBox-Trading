package com.cardpricer.cloud.trade;

import com.cardpricer.cloud.web.ApiException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Explicit prospective facts only. Does not convert money or infer a guide's currency. */
public final class NativeTransactionMoney {
    private NativeTransactionMoney() {}

    public record Confirmation(String currency, UUID confirmedBy, Instant confirmedAt) {}

    public static Confirmation confirm(String code, Boolean confirmed, UUID actor, Instant at,
                                       BigDecimal credit, BigDecimal check) {
        if (code == null || code.isBlank()) {
            if (Boolean.TRUE.equals(confirmed)) throw ApiException.badRequest("Choose the payment currency to confirm it");
            return null;
        }
        if (!Boolean.TRUE.equals(confirmed)) throw ApiException.badRequest("Confirm the actual payment currency before saving");
        Currency currency;
        try {
            if (!code.matches("[A-Z]{3}")) throw new IllegalArgumentException();
            currency = Currency.getInstance(code);
            if (currency.getDefaultFractionDigits() < 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw ApiException.badRequest("Use a recognized three-letter payment currency code");
        }
        for (BigDecimal amount : new BigDecimal[]{credit, check}) {
            if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits())
                throw ApiException.badRequest("The payment amounts do not fit this currency's precision; no conversion or rounding was made");
        }
        if (actor == null || at == null) throw new IllegalArgumentException("Authenticated confirmation identity and time required");
        return new Confirmation(code, actor, at);
    }

    /** Draft v2 contract for future producer wiring, never an upgrade of a stored v1 event. */
    public static Map<String, Object> version2Domains(Confirmation confirmation, String credit, String check,
                                                     Map<String, Object> guideAmounts) {
        decimal(credit, false); decimal(check, false);
        if (!guideAmounts.keySet().equals(java.util.Set.of("market_unit", "valuation_unit", "tcgplayer_unit")))
            throw new IllegalArgumentException("Exact recorded guide fields required");
        guideAmounts.values().forEach(value -> {
            if (value != null && !(value instanceof String)) throw new IllegalArgumentException("Guide amounts require decimal strings or null");
            decimal((String)value, true);
        });
        if (confirmation != null) confirm(confirmation.currency(), true, confirmation.confirmedBy(),
                confirmation.confirmedAt(), new BigDecimal(credit), new BigDecimal(check));
        var consideration = new TreeMap<String, Object>();
        consideration.put("store_credit_total", credit);
        consideration.put("check_total", check);
        consideration.put("currency", confirmation == null ? null : confirmation.currency());
        consideration.put("currency_state", confirmation == null ? "unrecorded" : "transaction_confirmed");
        consideration.put("confirmation", confirmation == null ? null : Map.of(
                "source", "explicit_transaction_confirmation", "confirmed_by", confirmation.confirmedBy().toString(),
                "confirmed_at", confirmation.confirmedAt().toString()));
        // Existing saved guides contain no currency. A payment confirmation is not evidence about a guide.
        var guide = new TreeMap<String, Object>(guideAmounts);
        guide.put("currency", null);
        guide.put("currency_state", "unrecorded");
        return Map.of("schema_version", 2, "consideration", java.util.Collections.unmodifiableMap(consideration),
                      "guide", java.util.Collections.unmodifiableMap(guide),
                      "valuation_admission", "held_buyin_not_a_retail_sale");
    }

    private static void decimal(String value, boolean nullable) {
        if (value == null && nullable) return;
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,9})(?:\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Recorded amounts require unsigned plain decimal strings at existing numeric(12,2) precision");
    }
}

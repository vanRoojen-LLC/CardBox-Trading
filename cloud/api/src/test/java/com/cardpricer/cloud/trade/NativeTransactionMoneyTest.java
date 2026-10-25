package com.cardpricer.cloud.trade;

import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeTransactionMoneyTest {
    final UUID actor = UUID.fromString("11111111-1111-1111-1111-111111111111");
    final Instant at = Instant.parse("2026-10-23T23:00:00Z");
    NativeTransactionMoney.Confirmation confirm(String code, Boolean yes, String amount) {
        return NativeTransactionMoney.confirm(code, yes, actor, at, new BigDecimal(amount), BigDecimal.ZERO);
    }

    @Test void legacyUnknownIsNeverDefaultedAndRequiresAnExplicitConfirmationForKnownCurrency() {
        assertNull(confirm(null, null, "12.50"));
        assertNull(confirm("", false, "12.50"));
        assertThrows(ApiException.class, () -> confirm(null, true, "12.50"));
        assertThrows(ApiException.class, () -> confirm("EUR", false, "12.50"));
        var known = confirm("EUR", true, "12.50");
        assertEquals("EUR", known.currency());assertEquals(actor,known.confirmedBy());assertEquals(at,known.confirmedAt());
    }

    @Test void malformedNonMonetaryAndUnsupportedPrecisionAreRejectedWithoutConversion() {
        for (String code : new String[]{"usd"," USD","ZZZ","XXX","XTS","US","EURO"})
            assertThrows(ApiException.class, () -> confirm(code, true, "12.50"));
        assertThrows(ApiException.class, () -> confirm("JPY", true, "12.50"));
        assertEquals("JPY",confirm("JPY",true,"12.00").currency());
        assertThrows(ApiException.class, () -> confirm("EUR",true,"12.501"));
        assertThrows(ApiException.class, () -> confirm("EUR",true,"-1.00"));
    }

    @Test void version2RefusesNoncanonicalNestedOrOutOfPrecisionAmounts() {
        var guide=Map.<String,Object>of("market_unit","12.50","valuation_unit","12.00","tcgplayer_unit","12.50");
        for (String bad : new String[]{"1e2","+1.00","01.00","1.234","10000000000.00","-1.00"})
            assertThrows(IllegalArgumentException.class,()->NativeTransactionMoney.version2Domains(null,bad,"0.00",guide));
        var nested=new java.util.HashMap<String,Object>(guide);nested.put("market_unit",Map.of("amount","12.50"));
        assertThrows(IllegalArgumentException.class,()->NativeTransactionMoney.version2Domains(null,"1.00","0.00",nested));
        nested.put("market_unit",12.5);
        assertThrows(IllegalArgumentException.class,()->NativeTransactionMoney.version2Domains(null,"1.00","0.00",nested));
    }

    @Test void version2KeepsActualAndGuideCurrenciesSeparateWithoutMutatingLegacyFacts() throws Exception {
        var json = new ObjectMapper();
        var guide = new java.util.TreeMap<String,Object>();guide.put("market_unit","12.50");guide.put("valuation_unit","12.00");guide.put("tcgplayer_unit",null);
        String before=json.writeValueAsString(guide);
        var domains=NativeTransactionMoney.version2Domains(confirm("EUR",true,"7.25"),"7.25","0.00",guide);
        assertEquals(2,domains.get("schema_version"));
        var consideration=(Map<?,?>)domains.get("consideration");
        assertEquals("EUR",consideration.get("currency"));assertEquals("7.25",consideration.get("store_credit_total"));
        assertEquals("transaction_confirmed",consideration.get("currency_state"));
        var frozenGuide=(Map<?,?>)domains.get("guide");assertNull(frozenGuide.get("currency"));
        assertEquals("unrecorded",frozenGuide.get("currency_state"));assertEquals("12.50",frozenGuide.get("market_unit"));
        assertEquals(before,json.writeValueAsString(guide));
        var unknown=(Map<?,?>)NativeTransactionMoney.version2Domains(null,"7.25","0.00",guide).get("consideration");
        assertNull(unknown.get("currency"));assertNull(unknown.get("confirmation"));assertEquals("unrecorded",unknown.get("currency_state"));
    }
}

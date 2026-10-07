package com.cardpricer.cloud.catalog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfidenceTest {
    static final Instant NOW = Instant.parse("2026-10-07T20:00:00Z");

    static BigDecimal $(String v) {
        return new BigDecimal(v);
    }

    static List<BigDecimal> steady(String price, int days) {
        return Collections.nCopies(days, $(price));
    }

    static Confidence.Result assess(String market, Duration age, String other, String low, String mid, List<BigDecimal> history) {
        return Confidence.assess(new Confidence.Inputs(market == null ? null : $(market), NOW.minus(age), "swu-db's copy",
                other == null ? null : $(other), low == null ? null : $(low), mid == null ? null : $(mid), history), NOW);
    }

    @Test
    void freshAgreeingSteadyPriceIsHigh() {
        var r = assess("40.00", Duration.ofHours(10), "41.00", "36.00", "42.00", steady("40.00", 10));
        assertEquals(Confidence.Level.high, r.level(), r.reasons().toString());
    }

    @Test
    void noPriceIsNone() {
        assertEquals(Confidence.Level.none, assess(null, Duration.ZERO, null, null, null, List.of()).level());
    }

    @Test
    void aStalePriceIsLow() {
        var r = assess("40.00", Duration.ofDays(5), "41.00", null, null, steady("40.00", 10));
        assertEquals(Confidence.Level.low, r.level());
        assertTrue(r.reasons().getFirst().text().contains("5 days old"));
    }

    @Test
    void sourcesFarApartAreLowButCheapCardsGetSlack() {
        assertEquals(Confidence.Level.low, assess("40.25", Duration.ofHours(2), "14.88", null, null, steady("40", 10)).level());
        assertEquals(Confidence.Level.high, assess("0.40", Duration.ofHours(2), "0.20", null, null, steady("0.40", 10)).level(),
                "20 cents apart is noise");
    }

    @Test
    void cheapListingsUnderMarketWarn() {
        var r = assess("40.00", Duration.ofHours(2), "40.00", "20.00", null, steady("40.00", 10));
        assertEquals(Confidence.Level.medium, r.level());
        assertTrue(r.reasons().stream().anyMatch(x -> x.text().contains("50% under market")), r.reasons().toString());
    }

    @Test
    void fastMovesWarn() {
        List<BigDecimal> rising = List.of($("20"), $("22"), $("25"), $("27"), $("30"), $("33"), $("36"));
        var r = assess("36.00", Duration.ofHours(2), "36.00", null, null, rising);
        assertEquals(Confidence.Level.medium, r.level());
        assertTrue(r.reasons().stream().anyMatch(x -> x.text().contains("up 80% over 7 days")), r.reasons().toString());
    }

    @Test
    void oneSourceWithoutHistoryIsMedium() {
        var r = Confidence.assess(new Confidence.Inputs($("48.20"), NOW.minus(Duration.ofHours(3)), null, null, null, null,
                List.of()), NOW);
        assertEquals(Confidence.Level.medium, r.level());
    }
}

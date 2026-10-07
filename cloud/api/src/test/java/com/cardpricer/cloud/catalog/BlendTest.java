package com.cardpricer.cloud.catalog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlendTest {
    static Blend.Input in(String origin, String value, double weight) {
        return new Blend.Input(origin, new BigDecimal(value), weight);
    }

    @Test
    void tcgplayerDecidesAgainstOneOtherSource() {
        assertEquals(new BigDecimal("48.20"), Blend.of(List.of(in("tcgplayer", "48.20", 1), in("ebay", "40", 0.9))).value());
    }

    @Test
    void twoSourcesThatAgreeMoveTheValue() {
        var r = Blend.of(List.of(in("tcgplayer", "48.20", 1), in("ebay", "40", 0.9), in("pricecharting", "41", 0.6)));
        assertEquals(new BigDecimal("41.00"), r.value());
        assertEquals(0.4, r.weights().get("tcgplayer"), 1e-9);
    }

    @Test
    void copiesOfOneOriginCountOnce() {
        var r = Blend.of(List.of(in("tcgplayer", "10", 1), in("tcgplayer", "30", 0.8), in("ebay", "12", 0.9)));
        assertEquals(new BigDecimal("10.00"), r.value(), "the heavier TCGplayer copy keeps its place; the other is dropped");
        assertEquals(2, r.weights().size());
    }

    @Test
    void anExactHalfAveragesTheTwoMiddleValues() {
        assertEquals(new BigDecimal("15.00"), Blend.of(List.of(in("a", "10", 1), in("b", "20", 1))).value());
    }

    @Test
    void zeroWeightsAndMissingValuesAreIgnored() {
        assertNull(Blend.of(List.of(in("ebay", "60", 0))).value());
        assertEquals(new BigDecimal("5.00"), Blend.of(List.of(in("tcgplayer", "5", 1), new Blend.Input("x", null, 1))).value());
    }

    @Test
    void sourcesParseTheirOrigin() {
        assertEquals("manapool", Blend.origin("manapool via tcgtracking"));
        assertEquals("tcgplayer", Blend.origin("tcgplayer via tcgtracking"));
        assertEquals("tcgplayer", Blend.origin("swu-db"));
        assertEquals("ebay", Blend.origin("eBay"));
    }
}

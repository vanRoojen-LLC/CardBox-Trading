package com.cardpricer.cloud.catalog;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** One Star Wars: Unlimited printing as the free price check shows it. */
public record SwuCard(String setCode, String sourceNumber, String setName, String collectorNumber, String treatment,
                      String variant, String name, String subtitle, String rarity, String image, String tcgplayerId,
                      BigDecimal market, Instant priceObservedAt) {

    /** The card id trades and stock use for this printing; the same as swu_cards.id (V23). */
    public UUID tradingId() {
        return tradingId(setCode, sourceNumber);
    }

    static UUID tradingId(String setCode, String sourceNumber) {
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(("swu:" + setCode + "/" + sourceNumber).getBytes(StandardCharsets.UTF_8)));
            return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                    + hex.substring(16, 20) + "-" + hex.substring(20));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

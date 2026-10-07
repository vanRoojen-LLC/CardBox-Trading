package com.cardpricer.cloud.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One TCGTracking product in one price subtype, as search shows it. Its one market price sits in the Normal or Foil
 * column by {@link TcgTrackingCatalog#foil}, like an SWU printing's.
 */
public record TcgProduct(UUID id, int productId, String name, String setCode, String setName, String collectorNumber,
                         String rarity, String subType, String language, BigDecimal market, String image,
                         Instant observedAt) {

    public boolean foil() {
        return TcgTrackingCatalog.foil(subType);
    }

    /** In the shape trades and stock price from: the market price under the finish it is sold as. */
    public CardRow toCardRow() {
        return new CardRow(id, name, setCode, setName, collectorNumber == null ? "" : collectorNumber,
                rarity == null ? "" : rarity.toLowerCase(java.util.Locale.ROOT), language,
                foil() ? null : market, foil() ? market : null, null, image);
    }
}

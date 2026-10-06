package com.cardpricer.cloud.catalog;

import java.math.BigDecimal;
import java.time.Instant;

/** One Star Wars: Unlimited printing as the free price check shows it. */
public record SwuCard(String setCode, String sourceNumber, String setName, String collectorNumber, String treatment,
                      String variant, String name, String subtitle, String rarity, String image, String tcgplayerId,
                      BigDecimal market, Instant priceObservedAt) {
}

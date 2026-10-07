package com.cardpricer.service;

import com.cardpricer.util.CardConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Centralises card pricing logic: rarity minimums, condition multipliers, and rounding rules.
 *
 * <p>Rounding rules:
 * <ul>
 *   <li>Below $10 — round to the nearest $0.50</li>
 *   <li>$10 and above — round to the nearest $1.00</li>
 * </ul>
 *
 * <p>Rarity minimums:
 * <ul>
 *   <li>Rare / Mythic — $0.50</li>
 *   <li>Uncommon — $0.25</li>
 *   <li>Common / other — $0.10</li>
 * </ul>
 */
public class PricingService {

    private static final BigDecimal NINE_FIFTY = new BigDecimal("9.50");

    /**
     * Returns the minimum buylist price for the given rarity string.
     *
     * @param rarity card rarity (case-insensitive); {@code null} defaults to the common minimum
     * @return minimum price as a {@link BigDecimal}
     */
    public BigDecimal getMinimumByRarity(String rarity) {
        if (rarity == null) {
            return CardConstants.RARITY_MIN_COMMON;
        }

        String rarityLower = rarity.toLowerCase();
        // Star Wars: Unlimited's Legendary and Special sit at or above Rare, as Magic's Mythic and Special do.
        if (rarityLower.equals("rare") || rarityLower.equals("mythic") || rarityLower.equals("legendary")
                || rarityLower.equals("special")) {
            return CardConstants.RARITY_MIN_RARE_MYTHIC;
        } else if (rarityLower.equals("uncommon")) {
            return CardConstants.RARITY_MIN_UNCOMMON;
        } else {
            return CardConstants.RARITY_MIN_COMMON;
        }
    }

    /**
     * Applies the rarity minimum floor and rounding rules to a raw market price.
     *
     * @param price  raw price (must not be {@code null})
     * @param rarity card rarity string (case-insensitive; may be {@code null})
     * @return adjusted, rounded price
     */
    public BigDecimal applyPricingRules(BigDecimal price, String rarity) {
        BigDecimal minimum = getMinimumByRarity(rarity);
        BigDecimal priceToRound = price.max(minimum);

        if (priceToRound.compareTo(CardConstants.ROUNDING_THRESHOLD) < 0) {
            BigDecimal rounded = priceToRound
                    .divide(CardConstants.ROUNDING_STEP_LOW, 0, RoundingMode.HALF_UP)
                    .multiply(CardConstants.ROUNDING_STEP_LOW);
            if (rounded.compareTo(NINE_FIFTY) == 0) rounded = BigDecimal.TEN;
            return rounded.max(minimum);
        } else {
            return priceToRound.setScale(0, RoundingMode.HALF_UP);
        }
    }

    /**
     * Multiplies a base price by the condition-tier multiplier and re-applies rounding rules.
     *
     * @param basePrice already-rounded base price (NM equivalent)
     * @param condition condition string (e.g. "NM", "LP"); unrecognised values default to NM
     * @return condition-adjusted, rounded price
     */
    public BigDecimal applyConditionMultiplier(BigDecimal basePrice, String condition) {
        if (basePrice == null || basePrice.signum() < 0) throw new IllegalArgumentException("Invalid base price");
        if ("NM".equals(condition)) return basePrice;
        int conditionIndex = getConditionIndex(condition);
        double multiplier = CardConstants.CONDITION_MULTIPLIERS[conditionIndex];

        BigDecimal adjustedPrice = basePrice.multiply(BigDecimal.valueOf(multiplier));

        if (adjustedPrice.compareTo(CardConstants.ROUNDING_THRESHOLD) < 0) {
            BigDecimal rounded = adjustedPrice
                    .divide(CardConstants.ROUNDING_STEP_LOW, 0, RoundingMode.HALF_UP)
                    .multiply(CardConstants.ROUNDING_STEP_LOW);
            if (rounded.compareTo(NINE_FIFTY) == 0) rounded = BigDecimal.TEN;
            return rounded.max(basePrice.min(CardConstants.RARITY_MIN_COMMON)).min(basePrice);
        } else {
            return adjustedPrice.setScale(0, RoundingMode.HALF_UP);
        }
    }

    /**
     * Returns the zero-based index of a condition in {@link CardConstants#CONDITIONS}.
     * Unrecognised condition strings default to {@code 0} (NM).
     *
     * @param condition condition string to look up
     * @return index in the CONDITIONS array, or {@code 0} if not found
     */
    public int getConditionIndex(String condition) {
        for (int i = 0; i < CardConstants.CONDITIONS.length; i++) {
            if (CardConstants.CONDITIONS[i].equals(condition)) {
                return i;
            }
        }
        return 0; // Default to NM
    }
}

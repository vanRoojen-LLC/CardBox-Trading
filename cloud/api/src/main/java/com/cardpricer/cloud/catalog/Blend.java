package com.cardpricer.cloud.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One trade value from several sources. Copies of one origin (TCGplayer's price read from TCGCSV, swu-db or TCG
 * Tracking) collapse to the copy with the most weight, so one source never counts twice; the value is then the
 * weighted median across origins.
 * <p>
 * TCGplayer's market price carries weight 1, more than any one other source, so with two origins it decides; a third
 * origin that agrees with the second moves the value. A median, not an average, so one wild source can't drag it.
 */
public final class Blend {
    public record Input(String origin, BigDecimal value, double weight) {}

    public record Result(BigDecimal value, Map<String, Double> weights) {}

    private Blend() {}

    /** The origin a price_history source belongs to: "manapool via tcgtracking" is Manapool's. */
    public static String origin(String source) {
        int via = source.indexOf(" via ");
        String origin = (via < 0 ? source : source.substring(0, via)).trim().toLowerCase();
        return origin.equals("swu-db") || origin.equals("scryfall") || origin.equals("tcgcsv") ? "tcgplayer" : origin;
    }

    public static Result of(List<Input> inputs) {
        Map<String, Input> best = new LinkedHashMap<>();
        for (Input in : inputs) {
            if (in.value() == null || in.value().signum() <= 0 || in.weight() <= 0) continue;
            best.merge(in.origin(), in, (a, b) -> b.weight() > a.weight() ? b : a);
        }
        if (best.isEmpty()) return new Result(null, Map.of());
        List<Input> sorted = new ArrayList<>(best.values());
        sorted.sort(Comparator.comparing(Input::value));
        double total = sorted.stream().mapToDouble(Input::weight).sum();
        double running = 0;
        BigDecimal value = sorted.getLast().value();
        for (int i = 0; i < sorted.size(); i++) {
            running += sorted.get(i).weight();
            if (Math.abs(running - total / 2) < 1e-9 && i + 1 < sorted.size()) {
                value = sorted.get(i).value().add(sorted.get(i + 1).value()).divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
                break;
            }
            if (running > total / 2) {
                value = sorted.get(i).value();
                break;
            }
        }
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Input in : best.values()) weights.put(in.origin(), in.weight() / total);
        return new Result(value.setScale(2, RoundingMode.HALF_UP), weights);
    }
}

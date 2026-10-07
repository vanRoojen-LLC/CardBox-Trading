package com.cardpricer.cloud.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * How far a card's price can be trusted, from the evidence Trading holds: how fresh it is, whether a second copy of
 * it agrees, how the cheapest and typical listings sit against it, and how it has moved lately.
 * <p>
 * Each check adds a reason that is good, a warning, bad, or plain information. Any bad reason, or two warnings, makes
 * the price low confidence; one warning makes it medium; otherwise it is high. No price at all is "none".
 */
public final class Confidence {
    public enum Level { high, medium, low, none }

    public enum Kind { good, warn, bad, info }

    public record Reason(Kind kind, String text) {}

    public record Result(Level level, List<Reason> reasons) {}

    /**
     * What one card and finish's price rests on. {@code other} is a second copy of the same price (swu-db's), null when
     * there is none; {@code low} and {@code mid} are the cheapest and median listings; {@code history} is the
     * recorded daily market price, oldest first, over the last 30 days. {@code independent} are other origins'
     * prices for the same card (eBay sold, Manapool, PriceCharting), each checked against the market price.
     */
    public record Inputs(BigDecimal market, Instant observedAt, String otherSource, BigDecimal other, BigDecimal low,
                         BigDecimal mid, List<BigDecimal> history, List<Check> independent) {
        public Inputs(BigDecimal market, Instant observedAt, String otherSource, BigDecimal other, BigDecimal low,
                      BigDecimal mid, List<BigDecimal> history) {
            this(market, observedAt, otherSource, other, low, mid, history, List.of());
        }
    }

    /** Another origin's price: "eBay sold", its median, and how many sales or listings it rests on (null: one price). */
    public record Check(String label, BigDecimal value, Integer observations) {
        boolean solid() {
            return observations == null || observations >= MIN_OBSERVATIONS;
        }
    }

    /** Fewer sales than this say something but not enough to confirm or doubt a price. */
    static final int MIN_OBSERVATIONS = 3;

    /** Below this, listing spreads and small disagreements say little: a $0.25 card listed at $0.10 is normal. */
    static final BigDecimal SPREAD_FLOOR = new BigDecimal("2");
    static final int SETTLED_DAYS = 7;

    private Confidence() {}

    public static Result assess(Inputs in, Instant now) {
        List<Reason> reasons = new ArrayList<>();
        if (in.market() == null) {
            reasons.add(new Reason(Kind.bad, "No market price for this finish."));
            return new Result(Level.none, reasons);
        }
        // Freshness. Every source refreshes nightly, so a day and a half is normal and three days means it stopped.
        if (in.observedAt() == null) {
            reasons.add(new Reason(Kind.warn, "We don't know when this price was last updated."));
        } else {
            long hours = Duration.between(in.observedAt(), now).toHours();
            if (hours > 72) reasons.add(new Reason(Kind.bad, "Price is " + (hours / 24) + " days old."));
            else if (hours > 36) reasons.add(new Reason(Kind.warn, "Price is " + hours + " hours old."));
            else reasons.add(new Reason(Kind.good, "Updated " + Math.max(hours, 0) + (hours == 1 ? " hour ago." : " hours ago.")));
        }
        boolean worthSpread = in.market().compareTo(SPREAD_FLOOR) >= 0;
        // Agreement with the second copy of the price.
        if (in.other() != null) {
            BigDecimal gap = in.market().subtract(in.other()).abs();
            BigDecimal smaller = in.market().min(in.other());
            double ratio = smaller.signum() == 0 ? 1 : gap.doubleValue() / smaller.doubleValue();
            String pct = percent(ratio);
            if (ratio <= 0.15 || gap.compareTo(new BigDecimal("0.50")) <= 0)
                reasons.add(new Reason(Kind.good, in.otherSource() + " agrees (" + money(in.other()) + ")."));
            else if (ratio > 0.5 && gap.compareTo(SPREAD_FLOOR) > 0)
                reasons.add(new Reason(Kind.bad, in.otherSource() + " says " + money(in.other()) + ", " + pct + " apart."));
            else reasons.add(new Reason(Kind.warn, in.otherSource() + " says " + money(in.other()) + ", " + pct + " apart."));
        }
        // Independent origins: the strongest corroboration, since they aren't copies of TCGplayer's number.
        List<Check> independent = in.independent() == null ? List.of() : in.independent();
        for (Check c : independent) {
            if (c.value() == null || c.value().signum() <= 0) continue;
            BigDecimal gap = in.market().subtract(c.value()).abs();
            BigDecimal smaller = in.market().min(c.value());
            double ratio = smaller.signum() == 0 ? 1 : gap.doubleValue() / smaller.doubleValue();
            String said = c.label() + " says " + money(c.value()) + basis(c);
            if (!c.solid()) reasons.add(new Reason(Kind.info, said + "; too few to weigh."));
            else if (ratio <= 0.2 || gap.compareTo(new BigDecimal("0.50")) <= 0)
                reasons.add(new Reason(Kind.good, said + ", in line with TCGplayer."));
            else if (ratio > 0.5 && gap.compareTo(SPREAD_FLOOR) > 0)
                reasons.add(new Reason(Kind.warn, said + ", " + percent(ratio) + " from TCGplayer."));
            else reasons.add(new Reason(Kind.info, said + ", " + percent(ratio) + " from TCGplayer."));
        }
        boolean corroborated = independent.stream().anyMatch(c -> c.value() != null && c.solid());
        // Listings against recent sales.
        if (worthSpread && in.low() != null && in.low().compareTo(in.market().multiply(new BigDecimal("0.6"))) < 0) {
            double below = 1 - in.low().doubleValue() / in.market().doubleValue();
            reasons.add(new Reason(Kind.warn, "Cheapest listing (" + money(in.low()) + ") is " + percent(below)
                    + " under market: the price may be falling."));
        }
        if (worthSpread && in.mid() != null && in.mid().compareTo(in.market().multiply(new BigDecimal("1.5"))) > 0) {
            reasons.add(new Reason(Kind.warn, "Typical listing (" + money(in.mid()) + ") is well above recent sales."));
        }
        // Recent movement.
        List<BigDecimal> history = in.history() == null ? List.of() : in.history();
        if (history.size() >= SETTLED_DAYS) {
            BigDecimal first = history.getFirst(), last = history.getLast();
            double change = first.signum() == 0 ? 0 : (last.doubleValue() - first.doubleValue()) / first.doubleValue();
            String moved = (change >= 0 ? "up " : "down ") + percent(Math.abs(change)) + " over " + history.size() + " days";
            if (Math.abs(change) > 0.3 && last.subtract(first).abs().compareTo(SPREAD_FLOOR) > 0)
                reasons.add(new Reason(Kind.warn, "Price moved fast: " + moved + "."));
            else reasons.add(new Reason(Kind.good, "Steady: " + moved + "."));
        } else if (in.other() == null && in.low() == null && !corroborated) {
            reasons.add(new Reason(Kind.warn, "One source and " + history.size() + (history.size() == 1 ? " day" : " days")
                    + " of history so far: nothing to check this price against yet."));
        } else {
            reasons.add(new Reason(Kind.info, history.size() + (history.size() == 1 ? " day" : " days")
                    + " of price history so far; trends show after " + SETTLED_DAYS + "."));
        }
        long bad = reasons.stream().filter(r -> r.kind() == Kind.bad).count();
        long warn = reasons.stream().filter(r -> r.kind() == Kind.warn).count();
        Level level = bad > 0 || warn >= 2 ? Level.low : warn == 1 ? Level.medium : Level.high;
        return new Result(level, reasons);
    }

    private static String basis(Check c) {
        if (c.observations() == null) return "";
        return " (" + c.observations() + (c.observations() == 1 ? " sale" : " sales") + ")";
    }

    private static String percent(double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    private static String money(BigDecimal value) {
        return "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

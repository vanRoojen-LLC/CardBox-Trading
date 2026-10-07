package com.cardpricer.cloud.trade;

import com.cardpricer.cloud.catalog.CardRow;
import com.cardpricer.cloud.catalog.CatalogRepository;
import com.cardpricer.cloud.catalog.Confidence;
import com.cardpricer.cloud.catalog.PriceEvidence;
import com.cardpricer.cloud.store.ConfidenceRules;
import com.cardpricer.cloud.inventory.InventoryRepository;
import com.cardpricer.cloud.store.RateRepository;
import com.cardpricer.cloud.web.ApiException;
import com.cardpricer.model.BuyRateRule;
import com.cardpricer.model.Card;
import com.cardpricer.model.TradeItem;
import com.cardpricer.service.PricingService;
import com.cardpricer.service.SettlementEngine;
import com.cardpricer.service.TradePosEncoder;
import com.cardpricer.util.CardConstants;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prices and settles trades with the desktop app's own PricingService and SettlementEngine,
 * so the web and desktop apps produce the same offers and cent allocations.
 */
@Service
public class TradeService {
    public record LineInput(UUID cardId, String finish, String condition, int quantity) {}

    public record PricedLine(UUID cardId, String name, String setCode, String setName, String collectorNumber,
                             String rarity, String lang, String finish, String condition, int quantity,
                             BigDecimal marketUnit, BigDecimal valuationUnit, BigDecimal creditRate, BigDecimal checkRate,
                             BigDecimal creditUnit, BigDecimal checkUnit, Confidence.Level confidence,
                             BigDecimal confidenceAdjust, boolean review, List<Confidence.Reason> reasons,
                             BigDecimal tcgplayerUnit, String game) {
        SettlementEngine.Line settlementLine(int index) {
            BigDecimal qty = BigDecimal.valueOf(quantity);
            return new SettlementEngine.Line(String.valueOf(index), quantity, valuationUnit.multiply(qty),
                    creditUnit.multiply(qty), checkUnit.multiply(qty), true);
        }
    }

    /** {@code review} is true when a line's confidence rule holds the trade until staff confirm they checked it. */
    public record Quote(List<PricedLine> lines, BigDecimal marketTotal, BigDecimal creditOffer, BigDecimal checkOffer,
                        SettlementEngine.Settlement settlement, boolean review) {}

    private final CatalogRepository catalog;
    private final RateRepository rates;
    private final JdbcTemplate jdbc;
    private final InventoryRepository inventory;
    private final PriceEvidence evidence;
    private final ConfidenceRules confidenceRules;
    private final PricingService pricing = new PricingService();
    private final SettlementEngine engine = new SettlementEngine();

    public TradeService(CatalogRepository catalog, RateRepository rates, JdbcTemplate jdbc, InventoryRepository inventory,
                        PriceEvidence evidence, ConfidenceRules confidenceRules) {
        this.evidence = evidence;
        this.confidenceRules = confidenceRules;
        this.catalog = catalog;
        this.rates = rates;
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    /**
     * Prices every line and settles the trade. For a split payment, pass the credit amount and a null check
     * to receive the matching check amount.
     */
    public Quote quote(UUID tenant, List<LineInput> inputs, String payment, BigDecimal credit, BigDecimal check) {
        if (inputs == null || inputs.isEmpty()) throw ApiException.badRequest("Add at least one card");
        if (inputs.size() > 500) throw ApiException.badRequest("A trade can have at most 500 lines");
        // Each game's buy rates and confidence rules, read once per trade.
        Map<String, List<BuyRateRule>> rules = new java.util.HashMap<>();
        Map<String, List<ConfidenceRules.Rule>> trust = new java.util.HashMap<>();
        java.util.function.Function<String, List<BuyRateRule>> ratesFor = g -> rules.computeIfAbsent(g, x -> rates.rules(tenant, x));
        java.util.function.Function<String, List<ConfidenceRules.Rule>> trustFor =
                g -> trust.computeIfAbsent(g, x -> confidenceRules.rules(tenant, x));
        var evidenceByLine = evidence.evidence(inputs.stream().filter(i -> i.cardId() != null)
                .map(i -> new PriceEvidence.Key(i.cardId(), i.finish() == null ? "normal" : i.finish())).toList(), Instant.now());
        List<PricedLine> lines = inputs.stream().map(input -> price(input, ratesFor, trustFor, evidenceByLine)).toList();
        List<SettlementEngine.Line> settlementLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) settlementLines.add(lines.get(i).settlementLine(i));
        var creditOnly = engine.settle(settlementLines, "credit", BigDecimal.ZERO, BigDecimal.ZERO);
        var checkOnly = engine.settle(settlementLines, "check", BigDecimal.ZERO, BigDecimal.ZERO);
        SettlementEngine.Settlement settlement = switch (payment == null ? "credit" : payment) {
            case "credit" -> creditOnly;
            case "check" -> checkOnly;
            case "partial" -> {
                if (credit == null) throw ApiException.badRequest("Enter the store credit amount for a split payment");
                BigDecimal splitCheck = check != null ? check
                        : SettlementEngine.remainingCheck(creditOnly.credit(), checkOnly.check(), credit.setScale(2, RoundingMode.HALF_UP));
                yield engine.settle(settlementLines, "partial", credit, splitCheck);
            }
            default -> throw ApiException.badRequest("Payment must be credit, check or partial");
        };
        return new Quote(lines, creditOnly.market(), creditOnly.credit(), checkOnly.check(), settlement,
                lines.stream().anyMatch(PricedLine::review));
    }

    private PricedLine price(LineInput input, java.util.function.Function<String, List<BuyRateRule>> rules,
                             java.util.function.Function<String, List<ConfidenceRules.Rule>> trust,
                             Map<PriceEvidence.Key, PriceEvidence.Evidence> evidenceByLine) {
        if (input.cardId() == null) throw ApiException.badRequest("Each line needs a card");
        if (input.quantity() < 1 || input.quantity() > 999) throw ApiException.badRequest("Quantity must be between 1 and 999");
        String condition = input.condition() == null ? "NM" : input.condition();
        if (!Arrays.asList(CardConstants.CONDITIONS).contains(condition))
            throw ApiException.badRequest("Condition must be one of " + String.join(", ", CardConstants.CONDITIONS));
        String finish = input.finish() == null ? "normal" : input.finish();
        CardRow card = catalog.find(input.cardId()).orElseThrow(() -> ApiException.badRequest("Unknown card"));
        BigDecimal tcgplayer = card.marketFor(finish);
        if (tcgplayer == null) throw ApiException.badRequest(card.name() + " has no " + finish + " price");
        // How far the price can be trusted, and the blended value of every source Trading holds for it.
        var found = evidenceByLine.get(new PriceEvidence.Key(card.id(), finish));
        BigDecimal market = found != null && found.market() != null ? found.market() : tcgplayer;
        String game = found == null ? RateRepository.DEFAULT : found.game();
        BigDecimal base = pricing.applyPricingRules(market, card.rarity());
        BigDecimal valuation = pricing.applyConditionMultiplier(base, condition).setScale(2, RoundingMode.HALF_UP);
        BuyRateRule rule = RateRepository.match(rules.apply(game), valuation);
        Confidence.Level level = found == null ? Confidence.Level.medium : found.confidence();
        List<Confidence.Reason> reasons = found == null ? List.of() : found.reasons();
        ConfidenceRules.Rule matched = ConfidenceRules.match(trust.apply(game), level);
        BigDecimal adjust = matched.adjust();
        return new PricedLine(card.id(), card.name(), card.setCode(), card.setName(), card.collectorNumber(),
                card.rarity(), card.lang(), finish, condition, input.quantity(), market, valuation,
                rule.creditRate, rule.checkRate,
                valuation.multiply(rule.creditRate).multiply(adjust).setScale(2, RoundingMode.HALF_UP),
                valuation.multiply(rule.checkRate).multiply(adjust).setScale(2, RoundingMode.HALF_UP),
                level, adjust, matched.review(), reasons, tcgplayer, game);
    }

    /**
     * The open location a trade's cards go into: the one the register asked for, or the store's first open
     * location when it didn't say.
     */
    public UUID location(UUID tenant, UUID requested) {
        var ids = jdbc.queryForList("""
                SELECT id FROM locations WHERE tenant_id = ? AND archived_at IS NULL AND (?::uuid IS NULL OR id = ?)
                ORDER BY created_at LIMIT 1""", UUID.class, tenant, requested, requested);
        if (ids.isEmpty()) throw ApiException.badRequest(requested == null ? "This store has no open location"
                : "That location is closed or not part of this store. Pick this register's location again.");
        return ids.getFirst();
    }

    @Transactional
    public UUID save(UUID tenant, UUID user, UUID location, Quote quote, String phone, String customerName, String checkNumber) {
        UUID customer = upsertCustomer(tenant, phone, customerName);
        // Lock the tenant row so trade numbers stay sequential per store.
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, tenant);
        long number = jdbc.queryForObject("SELECT coalesce(max(number), 0) + 1 FROM trades WHERE tenant_id = ?", Long.class, tenant);
        UUID id = UUID.randomUUID();
        var s = quote.settlement();
        jdbc.update("""
                INSERT INTO trades (id, tenant_id, number, customer_id, created_by, payment, credit_total, check_total,
                                    market_total, check_number, location_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, tenant, number, customer, user, s.payment(), s.credit(), s.check(), s.market(),
                checkNumber == null ? "" : checkNumber.trim(), location);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < quote.lines().size(); i++) {
            PricedLine l = quote.lines().get(i);
            var allocation = s.lines().get(i);
            rows.add(new Object[]{id, i + 1, tenant, l.cardId(), l.name(), l.setCode(), l.collectorNumber(), l.rarity(),
                    l.lang(), l.finish(), l.condition(), l.quantity(), l.marketUnit(), l.valuationUnit(),
                    l.creditRate(), l.checkRate(), allocation.credit(), allocation.check(), l.confidence().name(),
                    l.confidenceAdjust(), l.tcgplayerUnit()});
        }
        jdbc.batchUpdate("""
                INSERT INTO trade_lines (trade_id, line_no, tenant_id, card_id, name, set_code, collector_number, rarity,
                    lang, finish, condition, quantity, market_unit, valuation_unit, credit_rate, check_rate,
                    credit_alloc, check_alloc, confidence, confidence_adjust, tcgplayer_unit)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
        // The cards bought in go into stock at the trade's location, waiting to be put away.
        for (PricedLine l : quote.lines()) {
            inventory.add(tenant, location, null, new InventoryRepository.Stock(l.cardId(), l.name(), l.setCode(),
                    l.collectorNumber(), l.rarity(), l.lang(), l.finish(), l.condition(), l.quantity()));
        }
        return id;
    }

    /** Customers are keyed by phone number within a store until real customer accounts exist. */
    UUID upsertCustomer(UUID tenant, String phone, String name) {
        if (phone == null || phone.isBlank()) return null;
        String normalized = normalizePhone(phone);
        String cleanName = name == null ? "" : name.trim();
        return jdbc.queryForObject("""
                INSERT INTO customers (id, tenant_id, phone, name) VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, phone) DO UPDATE
                    SET name = CASE WHEN EXCLUDED.name = '' THEN customers.name ELSE EXCLUDED.name END
                RETURNING id""", UUID.class, UUID.randomUUID(), tenant, normalized, cleanName);
    }

    /** Digits only, with a leading + kept; US 10-digit numbers get +1. */
    public static String normalizePhone(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() == 10 && !phone.trim().startsWith("+")) digits = "1" + digits;
        if (digits.length() < 8 || digits.length() > 15) throw ApiException.badRequest("Enter a valid phone number");
        return "+" + digits;
    }

    /** The provisional 19-column receiving CSV, produced by the desktop encoder from the saved allocation. */
    public String posCsv(UUID tenant, UUID trade) {
        var header = jdbc.queryForList("SELECT payment, credit_total, check_total FROM trades WHERE id = ? AND tenant_id = ?", trade, tenant);
        if (header.isEmpty()) throw ApiException.notFound("Trade not found");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM trade_lines WHERE trade_id = ? AND tenant_id = ? ORDER BY line_no", trade, tenant);
        List<TradeItem> items = new ArrayList<>();
        List<BigDecimal> prices = new ArrayList<>();
        List<SettlementEngine.Line> lines = new ArrayList<>();
        for (var row : rows) {
            Card card = new Card((String) row.get("name"), (String) row.get("set_code"), (String) row.get("collector_number"));
            card.setProviderId(row.get("card_id").toString());
            card.setLanguage((String) row.get("lang"));
            card.setRarity((String) row.get("rarity"));
            String finish = (String) row.get("finish");
            int qty = (Integer) row.get("quantity");
            items.add(new TradeItem(card, !"normal".equals(finish), qty, switch (finish) {
                case "foil" -> "F";
                case "etched" -> "E";
                default -> "";
            }));
            prices.add((BigDecimal) row.get("valuation_unit"));
            // Allocations are stored per line; replaying them as a fixed split reproduces them exactly.
            BigDecimal credit = (BigDecimal) row.get("credit_alloc");
            BigDecimal check = (BigDecimal) row.get("check_alloc");
            BigDecimal valuation = ((BigDecimal) row.get("valuation_unit")).multiply(BigDecimal.valueOf(qty));
            lines.add(new SettlementEngine.Line(String.valueOf(row.get("line_no")), qty, valuation, credit, check, true));
        }
        var h = header.getFirst();
        BigDecimal credit = (BigDecimal) h.get("credit_total");
        BigDecimal check = (BigDecimal) h.get("check_total");
        var allocations = new ArrayList<SettlementEngine.Allocation>();
        for (var line : lines) allocations.add(new SettlementEngine.Allocation(line, line.credit(), line.check()));
        var settlement = new SettlementEngine.Settlement((String) h.get("payment"), allocations, credit, check,
                lines.stream().map(SettlementEngine.Line::market).reduce(BigDecimal.ZERO, BigDecimal::add));
        StringWriter out = new StringWriter();
        try {
            TradePosEncoder.write(out, items, prices, settlement);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }
}

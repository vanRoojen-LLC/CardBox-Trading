package com.cardpricer.cloud.trade;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.catalog.CatalogRepository;
import com.cardpricer.cloud.catalog.PublicCardController;
import com.cardpricer.cloud.catalog.SwuCatalogRepository;
import com.cardpricer.cloud.inventory.PutAway;
import com.cardpricer.cloud.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** The paid store workflow: card lookup with offers, quoting, saving, history and POS export. */
@RestController
@RequestMapping("/api/app")
public class TradeController {
    public record QuoteRequest(List<TradeService.LineInput> lines, String payment, BigDecimal credit, BigDecimal check) {}
    public record SaveRequest(List<TradeService.LineInput> lines, String payment, BigDecimal credit, BigDecimal check,
                              @Size(max = 40) String customerPhone, @Size(max = 120) String customerName,
                              @Size(max = 40) String checkNumber, UUID locationId) {}

    private final TradeService trades;
    private final CatalogRepository catalog;
    private final SwuCatalogRepository swu;
    private final JdbcTemplate jdbc;
    private final PutAway putAway;

    public TradeController(TradeService trades, CatalogRepository catalog, SwuCatalogRepository swu, JdbcTemplate jdbc,
                           PutAway putAway) {
        this.trades = trades;
        this.putAway = putAway;
        this.catalog = catalog;
        this.swu = swu;
        this.jdbc = jdbc;
    }

    @GetMapping("/cards")
    public List<Map<String, Object>> cards(@RequestParam("q") String q, @RequestParam(value = "set", defaultValue = "") String set,
                                           @RequestParam(value = "game", defaultValue = "mtg") String game) {
        if (q.trim().length() < 2) throw ApiException.badRequest("Type at least 2 characters");
        if (q.length() > 100) throw ApiException.badRequest("Search is too long");
        // A Star Wars: Unlimited printing in the same shape, under the id trades and stock know it by.
        if (game.equals("swu")) return swu.search(q, set.trim().toUpperCase(Locale.ROOT), 40).stream().map(card -> {
            Map<String, Object> view = PublicCardController.view(card);
            view.put("id", card.tradingId());
            return view;
        }).toList();
        if (!game.equals("mtg")) throw ApiException.badRequest("Unknown game");
        return catalog.search(q, set.trim().toUpperCase(Locale.ROOT), 40).stream().map(card -> {
            Map<String, Object> view = new HashMap<>();
            view.put("id", card.id());
            view.put("name", card.name());
            view.put("set", card.setCode());
            view.put("setName", card.setName());
            view.put("number", card.collectorNumber());
            view.put("rarity", card.rarity());
            view.put("image", card.imageSmall());
            view.put("usd", card.usd());
            view.put("usdFoil", card.usdFoil());
            view.put("usdEtched", card.usdEtched());
            return view;
        }).toList();
    }

    @PostMapping("/trades/quote")
    public TradeService.Quote quote(@RequestBody QuoteRequest body, HttpServletRequest request) {
        return trades.quote(CurrentUser.of(request).tenantId(), body.lines(), body.payment(), body.credit(), body.check());
    }

    @PostMapping("/trades")
    public Map<String, Object> save(@RequestBody @jakarta.validation.Valid SaveRequest body, HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        if ("check".equals(body.payment()) || "partial".equals(body.payment())) {
            if (body.checkNumber() == null || body.checkNumber().isBlank())
                throw ApiException.badRequest("Enter the check number");
        }
        var quote = trades.quote(user.tenantId(), body.lines(), body.payment(), body.credit(), body.check());
        UUID location = trades.location(user.tenantId(), body.locationId());
        UUID id = trades.save(user.tenantId(), user.userId(), location, quote, body.customerPhone(), body.customerName(), body.checkNumber());
        putAway.arrived(() -> putAway.tradeArrived(user.tenantId(), location, quote.lines().stream().map(TradeService.PricedLine::cardId).distinct().toList()));
        return trade(id, request);
    }

    @GetMapping("/trades")
    public List<Map<String, Object>> history(@RequestParam(value = "phone", defaultValue = "") String phone,
                                             @RequestParam(value = "before", required = false) Long before,
                                             @RequestParam(value = "location", required = false) UUID location,
                                             HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        String normalized = phone.isBlank() ? "" : TradeService.normalizePhone(phone);
        return jdbc.queryForList("""
                SELECT t.id, t.number, t.created_at, t.payment, t.credit_total, t.check_total, t.market_total,
                       c.phone AS customer_phone, c.name AS customer_name, u.name AS created_by,
                       t.location_id, loc.name AS location,
                       (SELECT sum(quantity) FROM trade_lines l WHERE l.trade_id = t.id) AS cards
                FROM trades t LEFT JOIN customers c ON c.id = t.customer_id JOIN users u ON u.id = t.created_by
                     JOIN locations loc ON loc.id = t.location_id
                WHERE t.tenant_id = ? AND (? = '' OR c.phone = ?) AND (?::bigint IS NULL OR t.number < ?)
                      AND (?::uuid IS NULL OR t.location_id = ?)
                ORDER BY t.number DESC LIMIT 50""", tenant, normalized, normalized, before, before, location, location);
    }

    @GetMapping("/trades/{id}")
    public Map<String, Object> trade(@PathVariable UUID id, HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        var rows = jdbc.queryForList("""
                SELECT t.id, t.number, t.created_at, t.payment, t.credit_total, t.check_total, t.market_total,
                       t.check_number, c.phone AS customer_phone, c.name AS customer_name, u.name AS created_by,
                       t.location_id, loc.name AS location
                FROM trades t LEFT JOIN customers c ON c.id = t.customer_id JOIN users u ON u.id = t.created_by
                     JOIN locations loc ON loc.id = t.location_id
                WHERE t.id = ? AND t.tenant_id = ?""", id, tenant);
        if (rows.isEmpty()) throw ApiException.notFound("Trade not found");
        Map<String, Object> trade = new HashMap<>(rows.getFirst());
        trade.put("lines", jdbc.queryForList("""
                SELECT line_no, name, set_code, collector_number, finish, condition, quantity, market_unit,
                       valuation_unit, credit_rate, check_rate, credit_alloc, check_alloc
                FROM trade_lines WHERE trade_id = ? AND tenant_id = ? ORDER BY line_no""", id, tenant));
        return trade;
    }

    @GetMapping("/trades/{id}/pos.csv")
    public ResponseEntity<byte[]> posCsv(@PathVariable UUID id, HttpServletRequest request) {
        String csv = trades.posCsv(CurrentUser.of(request).tenantId(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"trade-" + id + "-pos.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/customers")
    public List<Map<String, Object>> customer(@RequestParam("phone") String phone, HttpServletRequest request) {
        return jdbc.queryForList("SELECT phone, name FROM customers WHERE tenant_id = ? AND phone = ?",
                CurrentUser.of(request).tenantId(), TradeService.normalizePhone(phone));
    }
}

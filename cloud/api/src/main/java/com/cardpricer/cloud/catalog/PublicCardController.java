package com.cardpricer.cloud.catalog;

import com.cardpricer.cloud.web.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The free price check. No account, no payment: Scryfall's terms require its price data to stay free.
 * Deliberately minimal: card identity and market price only, never a store's buy offers.
 */
@RestController
@RequestMapping("/api/public")
public class PublicCardController {
    private final CatalogRepository catalog;
    private final SwuCatalogRepository swu;
    private final TcgGames games;
    private final TcgProductRepository tcg;
    private final PriceChecks priceChecks;

    public PublicCardController(CatalogRepository catalog, SwuCatalogRepository swu, TcgGames games, TcgProductRepository tcg,
                                PriceChecks priceChecks) {
        this.catalog = catalog;
        this.swu = swu;
        this.games = games;
        this.tcg = tcg;
        this.priceChecks = priceChecks;
    }

    @GetMapping("/cards")
    public ResponseEntity<Map<String, Object>> search(@RequestParam("q") String q,
                                                      @RequestParam(value = "set", defaultValue = "") String set,
                                                      @RequestParam(value = "game", defaultValue = "mtg") String game) {
        if (q.trim().length() < 2) throw ApiException.badRequest("Type at least 2 characters");
        if (q.length() > 100) throw ApiException.badRequest("Search is too long");
        // Preview games are never on the free page.
        if (games.find(game, false).isEmpty()) throw ApiException.badRequest("Unknown game");
        var response = game.equals("swu") ? swu(q, set) : game.equals("mtg") ? mtg(q, set) : tcg(game, q, set);
        // Counted only once the search has succeeded. Answers are cacheable for an hour (below), so searches a
        // browser or CDN serves from its cache never reach us and are not counted.
        priceChecks.count();
        return response;
    }

    /**
     * "Did you mean another game?": the page asks this only after a search in its own game came back empty, so the
     * search itself is never slowed. Answers which other games hold matches, with a few names, and is not counted
     * as a price check.
     */
    @GetMapping("/cards/elsewhere")
    public ResponseEntity<Map<String, Object>> elsewhere(@RequestParam("q") String q,
                                                         @RequestParam(value = "game", defaultValue = "mtg") String game) {
        if (q.trim().length() < 2) throw ApiException.badRequest("Type at least 2 characters");
        if (q.length() > 100) throw ApiException.badRequest("Search is too long");
        if (games.find(game, false).isEmpty()) throw ApiException.badRequest("Unknown game");
        List<Map<String, Object>> found = new ArrayList<>();
        if (!game.equals("mtg")) suggestion("mtg", catalog.search(q, "", ELSEWHERE_LIMIT).stream().map(CardRow::name).toList())
                .ifPresent(found::add);
        if (!game.equals("swu")) suggestion("swu", swu.search(q, "", ELSEWHERE_LIMIT).stream()
                .map(c -> c.subtitle() == null ? c.name() : c.name() + ", " + c.subtitle()).toList())
                .ifPresent(found::add);
        // Only games out of preview are asked, so the hint stays cheap however many preview games there are.
        for (TcgGames.Game other : games.list(false)) {
            if (other.nativeGame() || other.key().equals(game)) continue;
            suggestion(other.key(), tcg.search(games.categories(other.key(), false), q, "", ELSEWHERE_LIMIT).stream()
                    .map(TcgProduct::name).toList()).ifPresent(found::add);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(Map.of("games", found));
    }

    /** Matches counted up to this many; more reads as "40+". */
    static final int ELSEWHERE_LIMIT = 40;

    private static Optional<Map<String, Object>> suggestion(String game, List<String> names) {
        if (names.isEmpty()) return Optional.empty();
        return Optional.of(Map.of("game", game, "count", names.size(), "more", names.size() >= ELSEWHERE_LIMIT,
                "names", names.stream().distinct().limit(3).toList()));
    }

    private ResponseEntity<Map<String, Object>> mtg(String q, String set) {
        List<Map<String, Object>> cards = catalog.search(q, set.trim().toUpperCase(Locale.ROOT), 40).stream()
                .map(PublicCardController::view).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("cards", cards);
        body.put("pricesUpdatedAt", catalog.lastImport().map(Object::toString).orElse(null));
        body.put("source", "Scryfall");
        // Prices change once a day; let browsers and any CDN reuse results.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(body);
    }

    private ResponseEntity<Map<String, Object>> swu(String q, String set) {
        List<Map<String, Object>> cards = swu.search(q, set.trim().toUpperCase(Locale.ROOT), 40).stream()
                .map(PublicCardController::view).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("cards", cards);
        body.put("pricesUpdatedAt", swu.lastImport().map(Object::toString).orElse(null));
        body.put("source", "TCGplayer");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(body);
    }

    private ResponseEntity<Map<String, Object>> tcg(String game, String q, String set) {
        List<Integer> categories = games.categories(game, false);
        List<Map<String, Object>> cards = tcg.search(categories, q, set.trim().toUpperCase(Locale.ROOT), 40).stream()
                .map(PublicCardController::view).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("cards", cards);
        body.put("pricesUpdatedAt", tcg.lastObserved(categories).map(Object::toString).orElse(null));
        body.put("source", "TCGplayer via TCGTracking");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(body);
    }

    /**
     * A TCGTracking product in the Magic row's shape: its card id (the same on the free page and in trades), its one
     * market price in the Normal or Foil column by subtype, and any subtype other than Normal or Foil named as the
     * variant ("Reverse Holofoil", "1st Edition").
     */
    public static Map<String, Object> view(TcgProduct card) {
        Map<String, Object> view = view(card.toCardRow());
        view.put("variant", card.subType().equals("Normal") || card.subType().equals("Foil") ? null : card.subType());
        view.put("priceObservedAt", card.observedAt() == null ? null : card.observedAt().toString());
        view.put("url", "https://www.tcgplayer.com/product/" + card.productId());
        return view;
    }

    /**
     * An SWU printing in the Magic row's shape: its one market price sits in the Normal or Foil column by its finish,
     * and the variant (Hyperspace, Showcase, a promo) is named beside it.
     */
    public static Map<String, Object> view(SwuCard card) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", card.setCode() + "-" + card.sourceNumber());
        view.put("name", card.subtitle() == null ? card.name() : card.name() + ", " + card.subtitle());
        view.put("set", card.setCode());
        view.put("setName", card.setName());
        view.put("number", card.collectorNumber());
        view.put("rarity", card.rarity().toLowerCase(Locale.ROOT));
        view.put("image", card.image());
        view.put("variant", card.treatment().equals("normal") || card.treatment().equals("foil") ? null : card.variant());
        boolean foil = SwuCatalogImporter.foil(card.treatment());
        view.put("usd", foil ? null : card.market());
        view.put("usdFoil", foil ? card.market() : null);
        view.put("usdEtched", null);
        view.put("priceObservedAt", card.priceObservedAt() == null ? null : card.priceObservedAt().toString());
        view.put("url", card.tcgplayerId() == null ? null : "https://www.tcgplayer.com/product/" + card.tcgplayerId());
        return view;
    }

    public static Map<String, Object> view(CardRow card) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", card.id());
        view.put("name", card.name());
        view.put("set", card.setCode());
        view.put("setName", card.setName());
        view.put("number", card.collectorNumber());
        view.put("rarity", card.rarity());
        view.put("lang", card.lang());
        view.put("image", card.imageSmall());
        view.put("usd", card.usd());
        view.put("usdFoil", card.usdFoil());
        view.put("usdEtched", card.usdEtched());
        return view;
    }
}

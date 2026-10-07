package com.cardpricer.cloud.catalog;

import com.cardpricer.cloud.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The games a card search can look in. The free price check lists only games out of preview; a signed-in store sees
 * the same, and a platform owner also sees the preview games (every TCGTracking game, until the owner promotes it).
 */
@RestController
public class GamesController {
    private final TcgGames games;

    public GamesController(TcgGames games) {
        this.games = games;
    }

    @GetMapping("/api/public/games")
    public ResponseEntity<Map<String, Object>> publicGames() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(Map.of("games", view(games.list(false))));
    }

    @GetMapping("/api/app/games")
    public Map<String, Object> appGames(HttpServletRequest request) {
        return Map.of("games", view(games.list(CurrentUser.of(request).admin())));
    }

    private static List<Map<String, Object>> view(List<TcgGames.Game> list) {
        return list.stream().map(g -> Map.<String, Object>of("key", g.key(), "segment", g.segment(), "name", g.name(),
                "preview", g.preview())).toList();
    }
}

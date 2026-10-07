package com.cardpricer.cloud.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The games Trading offers: its two native catalogs, Magic ("mtg") and Star Wars: Unlimited ("swu"), and every
 * TCGTracking game with products, keyed by its segment (CardBox Club's game key). A TCGTracking game is a preview until
 * the owner turns preview off in {@code tcg_games}; previews are offered only to the Preview list (shared with CardBox
 * Club), never on the free price check. A segment with several categories (Pokemon and Pokemon Japan) is one game, named after its first
 * category, and is a preview only while every category the viewer may see is.
 */
@Repository
public class TcgGames {
    /** {@code key} is what the API and the web pass as ?game=; {@code segment} is the game inventory files cards under. */
    public record Game(String key, String segment, String name, boolean preview) {
        public boolean nativeGame() {
            return key.equals("mtg") || key.equals("swu");
        }
    }

    public static final Game MAGIC = new Game("mtg", "magic-the-gathering", "Magic", false);
    public static final Game SWU = new Game("swu", SwuCatalogImporter.GAME, "Star Wars: Unlimited", false);

    private final JdbcTemplate jdbc;

    public TcgGames(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Magic and SWU first, then the TCGTracking games by the owner's sort, then by how many products they have. */
    public List<Game> list(boolean includePreview) {
        List<Game> games = new ArrayList<>(List.of(MAGIC, SWU));
        games.addAll(jdbc.query("""
                SELECT segment, (array_agg(name ORDER BY category_id))[1], bool_and(preview)
                FROM tcg_games g
                WHERE enabled AND (? OR NOT preview)
                  AND EXISTS (SELECT 1 FROM tcg_products p WHERE p.category_id = g.category_id)
                GROUP BY segment
                ORDER BY min(sort) NULLS LAST, sum(product_count) DESC NULLS LAST, 2""",
                (rs, i) -> new Game(rs.getString(1), rs.getString(1), rs.getString(2), rs.getBoolean(3)), includePreview));
        return games;
    }

    /** The game behind a ?game= key, if this viewer may search it. */
    public Optional<Game> find(String key, boolean includePreview) {
        if (key.equals(MAGIC.key())) return Optional.of(MAGIC);
        if (key.equals(SWU.key())) return Optional.of(SWU);
        return list(includePreview).stream().filter(g -> !g.nativeGame() && g.key().equals(key)).findFirst();
    }

    /** The TCGTracking categories a segment's search covers for this viewer; empty for an unknown or hidden game. */
    public List<Integer> categories(String segment, boolean includePreview) {
        if (segment.equals(MAGIC.key()) || segment.equals(SWU.key())) return Collections.emptyList();
        return jdbc.queryForList("SELECT category_id FROM tcg_games WHERE segment = ? AND enabled AND (? OR NOT preview)"
                + " ORDER BY category_id", Integer.class, segment, includePreview);
    }
}

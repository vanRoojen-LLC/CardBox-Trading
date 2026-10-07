package com.cardpricer.cloud.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tonight's prices into {@code price_history}, after the nightly imports. Only prices refreshed in the last two days
 * are written, so a source that stopped updating never looks like a steady price. Every SWU printing is recorded; for
 * Magic and the TCGTracking games, the cards some store holds or has traded (TCGTracking's ~600,000 product prices a
 * night would be hundreds of millions of rows a year). TCGplayer's price served by TCGTracking is source
 * 'tcgplayer via tcgtracking'; served by TCGCSV or Scryfall it is 'tcgplayer'. Mana Pool's near-mint English price from
 * TCGTracking's SKU files ({@link TcgSkuPrices}, held and traded cards only) is 'manapool via tcgtracking', an
 * independent market the blend weighs beside TCGplayer's.
 */
@Service
public class PriceHistory {
    private static final Logger log = LoggerFactory.getLogger(PriceHistory.class);
    private static final String RECORD = """
            INSERT INTO price_history (card_id, finish, source, day, market, low)
            SELECT id, CASE WHEN treatment LIKE '%foil%' THEN 'foil' ELSE 'normal' END,
                   CASE WHEN tcgplayer_source = 'tcgtracking' THEN 'tcgplayer via tcgtracking' ELSE 'tcgplayer' END,
                   current_date, tcgplayer_market, tcgplayer_low
            FROM swu_cards WHERE tcgplayer_market IS NOT NULL AND tcgplayer_observed_at > now() - interval '2 days'
            UNION ALL
            SELECT id, CASE WHEN sub_type ILIKE '%foil%' THEN 'foil' ELSE 'normal' END, 'tcgplayer via tcgtracking',
                   current_date, market, low
            FROM tcg_products WHERE market IS NOT NULL AND observed_at > now() - interval '2 days'
              AND id IN (SELECT card_id FROM inventory_items UNION SELECT card_id FROM trade_lines)
            UNION ALL
            SELECT id, CASE WHEN treatment LIKE '%foil%' THEN 'foil' ELSE 'normal' END, 'swu-db', current_date, market, low
            FROM swu_cards WHERE market IS NOT NULL AND price_observed_at > now() - interval '2 days'
            UNION ALL
            SELECT c.id, p.finish, p.source, current_date, p.market, NULL
            FROM cards c
            CROSS JOIN LATERAL (VALUES ('normal', 'tcgplayer', c.usd), ('foil', 'tcgplayer', c.usd_foil),
                                       ('etched', 'tcgplayer', c.usd_etched), ('normal', 'cardmarket', c.eur),
                                       ('foil', 'cardmarket', c.eur_foil)) AS p(finish, source, market)
            WHERE p.market IS NOT NULL AND c.updated_at > now() - interval '2 days'
              AND c.id IN (SELECT card_id FROM inventory_items UNION SELECT card_id FROM trade_lines)
            UNION ALL
            SELECT DISTINCT ON (card_id, finish) card_id, finish, 'manapool via tcgtracking', current_date, manapool, NULL
            FROM tcg_sku_cards WHERE manapool IS NOT NULL AND condition = 'NM' AND language = 'EN'
              AND observed_at > now() - interval '2 days'
            ON CONFLICT (card_id, finish, source, day) DO UPDATE SET market = EXCLUDED.market, low = EXCLUDED.low""";

    private final JdbcTemplate jdbc;

    public PriceHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int record() {
        int rows = jdbc.update(RECORD);
        jdbc.update("DELETE FROM price_history WHERE day < current_date - 400");
        log.info("Recorded {} prices in price_history", rows);
        return rows;
    }
}

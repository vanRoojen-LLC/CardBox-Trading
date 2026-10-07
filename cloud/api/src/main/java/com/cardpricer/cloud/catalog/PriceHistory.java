package com.cardpricer.cloud.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tonight's prices into {@code price_history}, after the nightly imports. Only prices refreshed in the last two days
 * are written, so a source that stopped updating never looks like a steady price. Every SWU printing is recorded; for
 * Magic, the cards some store holds or has traded.
 */
@Service
public class PriceHistory {
    private static final Logger log = LoggerFactory.getLogger(PriceHistory.class);
    private static final String RECORD = """
            INSERT INTO price_history (card_id, finish, source, day, market, low)
            SELECT id, CASE WHEN treatment LIKE '%foil%' THEN 'foil' ELSE 'normal' END, 'tcgplayer', current_date,
                   tcgplayer_market, tcgplayer_low
            FROM swu_cards WHERE tcgplayer_market IS NOT NULL AND tcgplayer_observed_at > now() - interval '2 days'
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

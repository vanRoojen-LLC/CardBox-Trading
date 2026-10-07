-- CardBox Club's market evidence per Trading card and finish: eBay sold and asking prices, research sales and other
-- independent sources, as aggregates (no listings). Club pushes these twice a day; each full push replaces a game's rows.
CREATE TABLE club_market_summaries (
    card_id      uuid NOT NULL,
    finish       text NOT NULL CHECK (finish IN ('normal', 'foil', 'etched')),
    game         text NOT NULL,
    source       text NOT NULL,
    value_kind   text NOT NULL CHECK (value_kind IN ('asking', 'realized', 'estimate')),
    observations integer NOT NULL CHECK (observations > 0),
    median       numeric(12,2) NOT NULL,
    low          numeric(12,2),
    high         numeric(12,2),
    newest       timestamptz NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (card_id, finish, source, value_kind)
);
CREATE INDEX club_market_summaries_game ON club_market_summaries (game, received_at);

-- When Club's current push for a game began, so its last page can drop rows the push no longer carries.
CREATE TABLE club_market_pushes (
    game        text PRIMARY KEY,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz
);

-- Buy rates and confidence rules per game. '' is the store's default, used by a game without its own rows.
ALTER TABLE buy_rate_rules ADD COLUMN game text NOT NULL DEFAULT '';
ALTER TABLE buy_rate_rules DROP CONSTRAINT buy_rate_rules_pkey;
ALTER TABLE buy_rate_rules ADD PRIMARY KEY (tenant_id, game, threshold_min);
ALTER TABLE confidence_rules ADD COLUMN game text NOT NULL DEFAULT '';
ALTER TABLE confidence_rules DROP CONSTRAINT confidence_rules_pkey;
ALTER TABLE confidence_rules ADD PRIMARY KEY (tenant_id, game, level);

-- The value a trade line was priced from, when it differs from TCGplayer's: the blend of independent sources.
ALTER TABLE trade_lines ADD COLUMN tcgplayer_unit numeric(12,2);

-- Star Wars: Unlimited for the free price check, refreshed nightly from swu-db (api.swu-db.com), the same source
-- CardBox Club builds its SWU catalog from. Each variant (Foil, Hyperspace, Showcase, Prestige, promos) is its own
-- printing with its own number, so a row is one swu-db record, keyed by set and swu-db's number (which carries an
-- F suffix on foils). Prices are TCGplayer's, as swu-db reports them; a run that has no price for a card keeps the
-- last one, and price_observed_at says how old it is.
CREATE TABLE swu_cards (
    set_code          text NOT NULL,
    source_number     text NOT NULL,
    set_name          text NOT NULL,
    collector_number  text NOT NULL,
    treatment         text NOT NULL,
    variant           text NOT NULL,
    name              text NOT NULL,
    subtitle          text,
    card_type         text,
    rarity            text NOT NULL DEFAULT '',
    image             text,
    tcgplayer_id      text,
    swu_cid           text,
    market            numeric(12,2),
    low               numeric(12,2),
    price_observed_at timestamptz,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (set_code, source_number)
);
CREATE INDEX swu_cards_name_trgm ON swu_cards USING gin (lower(name) gin_trgm_ops);

-- Imports of every game share the log; the free check reports each game's last good run.
ALTER TABLE catalog_imports ADD COLUMN game text NOT NULL DEFAULT 'magic-the-gathering';

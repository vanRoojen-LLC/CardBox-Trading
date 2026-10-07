-- The evidence behind a price, so staff can see how far to trust it, and the store's rules for what to do when the
-- evidence is thin.

-- Everything TCGCSV publishes per SWU product and finish, beside the market and low it already gave us: the median
-- listing (mid), the highest listing (high) and the lowest TCGplayer Direct listing. TCGCSV has no listing counts,
-- seller counts or sales history; those need the official TCGplayer API.
ALTER TABLE swu_cards ADD COLUMN tcgplayer_mid        numeric(12,2);
ALTER TABLE swu_cards ADD COLUMN tcgplayer_high       numeric(12,2);
ALTER TABLE swu_cards ADD COLUMN tcgplayer_direct_low numeric(12,2);

-- Scryfall's Cardmarket (EUR) trend for Magic, a second marketplace beside TCGplayer's USD market, and the TCGplayer
-- product id Scryfall gives each printing, for the link to it.
ALTER TABLE cards ADD COLUMN eur          numeric(12,2);
ALTER TABLE cards ADD COLUMN eur_foil     numeric(12,2);
ALTER TABLE cards ADD COLUMN tcgplayer_id text;

-- Each night's price, one row per card, finish, source and day, so a price can be read against its own recent past.
-- Nobody publishes this history for free, so it starts the day this migration ships. SWU records every printing; Magic
-- records the cards stores hold or have traded (the whole Magic catalog nightly would be millions of rows a year).
CREATE TABLE price_history (
    card_id uuid    NOT NULL,
    finish  text    NOT NULL,
    source  text    NOT NULL,
    day     date    NOT NULL,
    market  numeric(12,2) NOT NULL,
    low     numeric(12,2),
    PRIMARY KEY (card_id, finish, source, day)
);

-- What a store does with a line by how much its price can be trusted: scale the offer (1.0 keeps it) and/or hold the
-- trade until staff confirm they checked the price. No row means "keep the offer, don't hold".
CREATE TABLE confidence_rules (
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    level     text NOT NULL CHECK (level IN ('high', 'medium', 'low')),
    adjust    numeric(6,4) NOT NULL DEFAULT 1 CHECK (adjust > 0 AND adjust <= 1),
    review    boolean NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, level)
);

-- The confidence each traded line was priced at, for history and for tuning the rules later.
ALTER TABLE trade_lines ADD COLUMN confidence text;
ALTER TABLE trade_lines ADD COLUMN confidence_adjust numeric(6,4);

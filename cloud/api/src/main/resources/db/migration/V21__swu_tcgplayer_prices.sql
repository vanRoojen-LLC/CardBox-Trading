-- TCGplayer's own prices for SWU, read nightly from TCGCSV (tcgcsv.com, a daily mirror of TCGplayer's catalog,
-- category 79) by product id and finish. They are the price of record; swu-db's price (market, low) stays as the
-- fallback for a card TCGCSV has no price for. Where both exist and differ by more than half and more than $2,
-- price_disagrees marks the row so the mismatch is noticed instead of one source winning silently.
ALTER TABLE swu_cards ADD COLUMN tcgplayer_market      numeric(12,2);
ALTER TABLE swu_cards ADD COLUMN tcgplayer_low         numeric(12,2);
ALTER TABLE swu_cards ADD COLUMN tcgplayer_observed_at timestamptz;
ALTER TABLE swu_cards ADD COLUMN price_disagrees       boolean NOT NULL DEFAULT false;

-- The catalog's value of each printing, relayed by Club with every catalog push (CardBox
-- docs/printing-values.md): a blend of every source CardBox collects (eBay sales, TCGplayer, Scryfall, SWU-DB,
-- PriceCharting, research). Price evidence shows it beside Trading's own prices, and it is the trade value of a card
-- Trading has no price for. NULL until a push carries a value for the printing.
ALTER TABLE catalog_printing_links
    ADD COLUMN value_amount     numeric(14, 2),
    ADD COLUMN value_low        numeric(14, 2),
    ADD COLUMN value_high       numeric(14, 2),
    ADD COLUMN value_confidence text,
    ADD COLUMN value_sources    integer,
    ADD COLUMN value_newest     timestamptz;

-- TCGplayer's price per condition, printing and language, with its listing count and Mana Pool's price beside it, from
-- TCGTracking's per-set /skus files (owner, 2026-10-07: "their pricing data needs to be used too"). Only the products a
-- store holds or has traded are kept (the files cover ~7.3 million SKUs), so this stays small, and only the sets that
-- hold them are fetched. Keyed as TCGTracking files them: category, product, variant (TCGplayer's price subtype: Normal,
-- Foil, Holofoil, ...), condition (NM, LP, MP, HP, DMG) and language (EN, JP, ...). A card is matched to its rows on
-- read: Magic by cards.tcgplayer_id, SWU by swu_cards.tcgplayer_id and finish, the other games by tcg_products.
CREATE TABLE tcg_sku_prices (
    category_id integer NOT NULL,
    product_id  integer NOT NULL,
    variant     text    NOT NULL,
    condition   text    NOT NULL,
    language    text    NOT NULL,
    market      numeric(12,2),
    low         numeric(12,2),
    high        numeric(12,2),
    listings    integer,
    manapool    numeric(12,2),
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (category_id, product_id, variant, condition, language)
);
CREATE INDEX tcg_sku_prices_product ON tcg_sku_prices (product_id);

-- The SKU rows as Trading's cards and finishes: a Magic card by its TCGplayer product (Normal, Foil, Foil Etched), an SWU
-- printing by its product and whether it is foil, any other game's printing by its product and subtype.
CREATE VIEW tcg_sku_cards AS
SELECT c.id AS card_id,
       CASE WHEN k.variant ILIKE '%etched%' THEN 'etched' WHEN k.variant ILIKE '%foil%' THEN 'foil' ELSE 'normal' END AS finish,
       k.condition, k.language, k.market, k.low, k.high, k.listings, k.manapool, k.observed_at
FROM tcg_sku_prices k JOIN cards c ON k.category_id = 1 AND c.tcgplayer_id = k.product_id::text
UNION ALL
SELECT s.id, CASE WHEN k.variant ILIKE '%foil%' THEN 'foil' ELSE 'normal' END,
       k.condition, k.language, k.market, k.low, k.high, k.listings, k.manapool, k.observed_at
FROM tcg_sku_prices k JOIN swu_cards s ON k.category_id = 79 AND s.tcgplayer_id = k.product_id::text
     AND (s.treatment LIKE '%foil%') = (k.variant ILIKE '%foil%')
UNION ALL
SELECT p.id, CASE WHEN k.variant ILIKE '%foil%' THEN 'foil' ELSE 'normal' END,
       k.condition, k.language, k.market, k.low, k.high, k.listings, k.manapool, k.observed_at
FROM tcg_sku_prices k JOIN tcg_products p ON p.category_id = k.category_id AND p.product_id = k.product_id
     AND p.sub_type = k.variant;

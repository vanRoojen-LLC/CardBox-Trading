-- Every other game TCGplayer lists, from TCGTracking's Open TCG API (openapi.tcgtracking.com: free, no key, static JSON
-- on Cloudflare's edge, re-hosting TCGplayer's catalog and prices). Magic and Star Wars: Unlimited keep their own
-- catalogs (cards, swu_cards) and are not in these tables.
--
-- One row per TCGplayer category. segment is the game key CardBox Club uses too, so both apps file a card under the
-- same game: a fixed map for the games Club already knows (Pokemon and Pokemon Japan are both "pokemon", told apart by
-- language), the category name slugged for the rest. So segment is not unique; a search for a segment covers all of
-- its categories. A game starts as a preview (only a platform owner sees it) until someone turns preview off here;
-- enabled = false drops it from the nightly sync and from search. sort is left to the owner (the import never sets it).
CREATE TABLE tcg_games (
    category_id     integer PRIMARY KEY,
    segment         text NOT NULL,
    name            text NOT NULL,
    language        text NOT NULL DEFAULT 'en',
    preview         boolean NOT NULL DEFAULT true,
    enabled         boolean NOT NULL DEFAULT true,
    product_count   integer,
    sort            integer,
    sets_etag       text,
    sets_fetched_at timestamptz,
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tcg_games_segment ON tcg_games (segment);

-- Each set as TCGTracking's /sets listing last described it, and how far our copy has got, so the sync can stop when
-- its nightly time is spent and pick up where it left off. products_modified and pricing_modified are the listing's;
-- cards_version and pricing_version are the values they had when we last read the set's /cards and /pricing, so a set
-- is fetched again only when TCGTracking says it changed (or, for prices, once our copy is old: see TcgTrackingCatalog).
CREATE TABLE tcg_sets (
    set_id             integer PRIMARY KEY,
    category_id        integer NOT NULL REFERENCES tcg_games ON DELETE CASCADE,
    name               text NOT NULL,
    abbreviation       text,
    released           date,
    product_count      integer,
    products_modified  timestamptz,
    pricing_modified   timestamptz,
    cards_version      timestamptz,
    cards_fetched_at   timestamptz,
    cards_etag         text,
    pricing_version    timestamptz,
    pricing_fetched_at timestamptz,
    pricing_etag       text,
    last_error         text,
    last_error_at      timestamptz
);
CREATE INDEX tcg_sets_category ON tcg_sets (category_id);

-- One row per product per TCGplayer price subtype (Normal, Foil, Holofoil, Reverse Holofoil, 1st Edition, ...): each is
-- its own printing to trade and stock, like an SWU finish. A product with no price yet has one 'Normal' row with no
-- price. The card id is derived from the key (md5 of "tcg:CATEGORY/PRODUCT/SUBTYPE", read as a uuid), so it stays the
-- same across imports, the way V23 keys SWU printings. image_url is TCGTracking's 1000px image.
CREATE TABLE tcg_products (
    category_id      integer NOT NULL REFERENCES tcg_games ON DELETE CASCADE,
    product_id       integer NOT NULL,
    sub_type         text NOT NULL,
    set_id           integer NOT NULL,
    set_code         text NOT NULL,
    set_name         text NOT NULL,
    name             text NOT NULL,
    collector_number text,
    rarity           text,
    image_url        text,
    market           numeric(12,2),
    low              numeric(12,2),
    observed_at      timestamptz,
    cardmarket_id    integer,
    cardtrader_id    integer,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    id               uuid GENERATED ALWAYS AS
                         (md5('tcg:' || category_id::text || '/' || product_id::text || '/' || sub_type)::uuid) STORED,
    PRIMARY KEY (category_id, product_id, sub_type)
);
CREATE UNIQUE INDEX tcg_products_id ON tcg_products (id);
CREATE INDEX tcg_products_name_trgm ON tcg_products USING gin (lower(name) gin_trgm_ops);
CREATE INDEX tcg_products_set ON tcg_products (category_id, set_code);
CREATE INDEX tcg_products_set_id ON tcg_products (set_id);

-- Every card inventory can hold: Trading's Magic catalog, its SWU catalog (priced, by finish), Club's, and now every
-- TCGTracking game. The first three branches are V23's, unchanged. A TCGTracking subtype whose name says foil
-- (Foil, Holofoil, Reverse Holofoil, Cold Foil, 1st Edition Holofoil, ...) is priced in the foil column, every other
-- subtype (Normal, 1st Edition, Unlimited, Limited) in the normal one; a subtype other than Normal or Foil is named as
-- the printing's treatment so stock and storage rules can tell, say, 1st Edition from Unlimited.
DROP VIEW inventory_cards;
CREATE VIEW inventory_cards AS
SELECT id, name, set_code, collector_number, rarity, lang, usd, usd_foil, usd_etched, image_small,
       'magic-the-gathering'::text AS game, set_name, released_at, type_line, colors, color_identity, mana_value,
       set_type, treatments
FROM cards
UNION ALL
SELECT s.id, CASE WHEN s.subtitle IS NULL THEN s.name ELSE s.name || ', ' || s.subtitle END, s.set_code, s.collector_number,
       lower(s.rarity), 'en', p.normal, p.foil, NULL::numeric(12,2), s.image,
       'star-wars-unlimited'::text, s.set_name, NULL::date, s.card_type, NULL::text[], NULL::text[], NULL::numeric, NULL::text,
       NULL::text[]
FROM swu_cards s
CROSS JOIN LATERAL (SELECT CASE WHEN s.tcgplayer_market IS NOT NULL AND (s.price_observed_at IS NULL
                                     OR s.tcgplayer_observed_at > s.price_observed_at - interval '3 days')
                                THEN s.tcgplayer_market ELSE s.market END AS market) m
CROSS JOIN LATERAL (SELECT CASE WHEN s.treatment LIKE '%foil%' THEN NULL ELSE m.market END AS normal,
                           CASE WHEN s.treatment LIKE '%foil%' THEN m.market ELSE NULL END AS foil) p
UNION ALL
SELECT id, name, set_code, collector_number, '', 'en', NULL::numeric(12,2), NULL::numeric(12,2), NULL::numeric(12,2), NULL,
       game, ''::text, NULL::date, NULL::text, NULL::text[], NULL::text[], NULL::numeric, NULL::text, NULL::text[]
FROM club_cards
UNION ALL
SELECT t.id, t.name, t.set_code, coalesce(t.collector_number, ''), lower(coalesce(t.rarity, '')), g.language,
       CASE WHEN t.sub_type ILIKE '%foil%' THEN NULL ELSE t.market END,
       CASE WHEN t.sub_type ILIKE '%foil%' THEN t.market ELSE NULL END, NULL::numeric(12,2), t.image_url,
       g.segment, t.set_name, ts.released, NULL::text, NULL::text[], NULL::text[], NULL::numeric, NULL::text,
       CASE WHEN t.sub_type IN ('Normal', 'Foil') THEN NULL
            ELSE ARRAY[trim(BOTH '-' FROM regexp_replace(lower(t.sub_type), '[^a-z0-9]+', '-', 'g'))] END
FROM tcg_products t
JOIN tcg_games g ON g.category_id = t.category_id
LEFT JOIN tcg_sets ts ON ts.set_id = t.set_id;

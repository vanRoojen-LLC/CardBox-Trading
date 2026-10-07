-- Star Wars: Unlimited cards can be traded in and stocked, not only price-checked. Each printing gets a card id
-- derived from its key (md5 of "swu:SET/NUMBER", read as a uuid), so the same printing keeps the same id across
-- nightly imports, and trade lines and stock can point at it like they do at a Magic card.
ALTER TABLE swu_cards ADD COLUMN id uuid GENERATED ALWAYS AS (md5('swu:' || set_code || '/' || source_number)::uuid) STORED;
CREATE UNIQUE INDEX swu_cards_id ON swu_cards (id);

-- Every card inventory can hold: Trading's Magic catalog, its SWU catalog (priced, by finish), and Club's.
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
FROM club_cards;

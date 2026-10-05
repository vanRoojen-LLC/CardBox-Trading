-- numeric(6,1) can't hold every Scryfall mana value: Gleemax (UNH) costs 1,000,000, which stopped the catalog import.
-- The inventory view reads the column, so it is rebuilt around the new type.
DROP VIEW inventory_cards;
ALTER TABLE cards ALTER COLUMN mana_value TYPE numeric;
CREATE VIEW inventory_cards AS
SELECT id, name, set_code, collector_number, rarity, lang, usd, usd_foil, usd_etched, image_small,
       'magic-the-gathering'::text AS game, set_name, released_at, type_line, colors, color_identity, mana_value,
       set_type, treatments
FROM cards
UNION ALL
SELECT id, name, set_code, collector_number, '', 'en', NULL::numeric(12,2), NULL::numeric(12,2), NULL::numeric(12,2), NULL,
       game, ''::text, NULL::date, NULL::text, NULL::text[], NULL::text[], NULL::numeric, NULL::text, NULL::text[]
FROM club_cards;

-- Card details stores sort and file by: colors, mana value, the kind of set, and the printing's treatment
-- (showcase, borderless, extended art...). Filled by the next nightly catalog import.
ALTER TABLE cards ADD COLUMN colors         text[];
ALTER TABLE cards ADD COLUMN color_identity text[];
ALTER TABLE cards ADD COLUMN mana_value     numeric(6,1);
ALTER TABLE cards ADD COLUMN set_type       text;
ALTER TABLE cards ADD COLUMN treatments     text[];

-- Every card inventory can hold, now with the details inventory filters on. Club's cards only know their game.
DROP VIEW inventory_cards;
CREATE VIEW inventory_cards AS
SELECT id, name, set_code, collector_number, rarity, lang, usd, usd_foil, usd_etched, image_small,
       'magic-the-gathering'::text AS game, set_name, released_at, type_line, colors, color_identity, mana_value,
       set_type, treatments
FROM cards
UNION ALL
SELECT id, name, set_code, collector_number, '', 'en', NULL::numeric(12,2), NULL::numeric(12,2), NULL::numeric(12,2), NULL,
       game, ''::text, NULL::date, NULL::text, NULL::text[], NULL::text[], NULL::numeric(6,1), NULL::text, NULL::text[]
FROM club_cards;

-- Where the store put a synced card. Club owns what is in a collection; Trading owns where it sits, so this survives
-- every delivery. When placed, placed_storage_id is the spot (null: not put away at the collection's location). A
-- new spot sent from a Club scan is a fresh choice by a person and clears it.
ALTER TABLE club_link_items ADD COLUMN placed            boolean NOT NULL DEFAULT false;
ALTER TABLE club_link_items ADD COLUMN placed_storage_id uuid;

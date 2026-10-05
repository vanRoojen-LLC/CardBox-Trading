-- Cards from games Trading's own catalog doesn't carry (Scryfall is Magic only), such as Star Wars: Unlimited, as
-- Club describes them. They only come in through Club scans and stay out of the shared catalog, so the free price
-- check and trades are unchanged. The id is derived from Club's printing id, so the same printing is always the
-- same card here. No prices yet.
CREATE TABLE club_cards (
    id                uuid PRIMARY KEY,
    club_printing_id  text NOT NULL UNIQUE,
    game              text NOT NULL,
    name              text NOT NULL,
    set_code          text NOT NULL DEFAULT '',
    collector_number  text NOT NULL DEFAULT '',
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- Every card inventory can hold: Trading's catalog plus Club's.
CREATE VIEW inventory_cards AS
SELECT id, name, set_code, collector_number, rarity, lang, usd, usd_foil, usd_etched, image_small
FROM cards
UNION ALL
SELECT id, name, set_code, collector_number, '', 'en', NULL::numeric(12,2), NULL::numeric(12,2), NULL::numeric(12,2), NULL
FROM club_cards;

-- Which catalog printing (CardBox's catalog service, relayed by Club) each of Trading's cards is. The first step of
-- Trading reading cards and prices from the catalog instead of its own nightly imports: nothing reads card identity
-- from here yet. A printing Trading has no card for is kept with card_id NULL, so the gap can be counted.
CREATE TABLE catalog_printing_links (
    printing_id      text PRIMARY KEY,
    game             text NOT NULL,
    card_id          uuid,
    finish           text NOT NULL CHECK (finish IN ('normal', 'foil', 'etched')),
    matched_by       text CHECK (matched_by IN ('scryfall', 'swu-db', 'tcgplayer')),
    set_code         text NOT NULL DEFAULT '',
    collector_number text NOT NULL DEFAULT '',
    treatment        text NOT NULL DEFAULT '',
    language         text NOT NULL DEFAULT 'en',
    external_ids     jsonb NOT NULL DEFAULT '{}',
    received_at      timestamptz NOT NULL DEFAULT now(),
    CHECK ((card_id IS NULL) = (matched_by IS NULL))
);
CREATE INDEX catalog_printing_links_card ON catalog_printing_links (card_id, finish) WHERE card_id IS NOT NULL;
CREATE INDEX catalog_printing_links_game ON catalog_printing_links (game, received_at);

-- When Club's current push for a game began, so its last page can drop printings the catalog no longer carries.
CREATE TABLE catalog_printing_pushes (
    game        text PRIMARY KEY,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz
);

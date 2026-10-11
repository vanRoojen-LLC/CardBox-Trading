-- Club's catalog holds starwarsunlimited.com ids for SWU printings, not swu-db's, so most find Trading's card by the
-- printed set, number and treatment instead (CatalogCardMatch.swuByNumber).
ALTER TABLE catalog_printing_links DROP CONSTRAINT catalog_printing_links_matched_by_check;
ALTER TABLE catalog_printing_links ADD CONSTRAINT catalog_printing_links_matched_by_check
    CHECK (matched_by IN ('scryfall', 'swu-db', 'tcgplayer', 'number'));

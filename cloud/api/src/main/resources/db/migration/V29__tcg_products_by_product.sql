-- Club's inventory sync finds a synced card's TCGplayer product here by product id alone (Club's catalog holds the
-- product and subtype, not TCGplayer's category).
CREATE INDEX tcg_products_product ON tcg_products (product_id);

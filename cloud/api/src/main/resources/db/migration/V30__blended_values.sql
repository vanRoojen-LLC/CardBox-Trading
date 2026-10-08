-- The blended trade value of each card and finish Trading stocks (PriceEvidence's weighted blend of TCGplayer,
-- CardBox Club's eBay sold prices and the other origins), so the inventory list, its filters, totals and storage
-- rules value a line the same way a quote does. Refreshed by BlendedValues; a NULL market falls back to the card's
-- own price column.
CREATE TABLE blended_values (
    card_id     uuid        NOT NULL,
    finish      text        NOT NULL,
    market      numeric(12, 2),
    sources     integer     NOT NULL DEFAULT 0,
    computed_at timestamptz NOT NULL,
    PRIMARY KEY (card_id, finish)
);
CREATE INDEX blended_values_computed ON blended_values (computed_at);

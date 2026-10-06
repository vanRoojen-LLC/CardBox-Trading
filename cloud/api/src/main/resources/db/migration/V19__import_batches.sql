-- The import batch a card came in with (a CardBox upload or scan session today; Trading's own imports later), so
-- staff can see, sort and filter stock by it. Club sends batch_id and batch_name with each card; '' is no batch.
ALTER TABLE club_link_items ADD COLUMN batch_id   text NOT NULL DEFAULT '';
ALTER TABLE club_link_items ADD COLUMN batch_name text NOT NULL DEFAULT '';

-- A synced line holds the cards of one batch, so cards from two batches never share a line.
ALTER TABLE inventory_items ADD COLUMN source_batch_id   text NOT NULL DEFAULT '';
ALTER TABLE inventory_items ADD COLUMN source_batch_name text NOT NULL DEFAULT '';
ALTER TABLE inventory_items DROP CONSTRAINT inventory_items_line;
ALTER TABLE inventory_items ADD CONSTRAINT inventory_items_line
    UNIQUE NULLS NOT DISTINCT (location_id, storage_id, card_id, finish, condition, club_link_id, source_batch_id);

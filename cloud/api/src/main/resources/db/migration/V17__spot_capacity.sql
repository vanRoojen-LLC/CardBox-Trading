-- How many cards a storage spot holds, counting everything inside it. A spot whose rule fits a card but has no room
-- left passes it to the next spot that fits, so a run of boxes fills in order. Null: no limit.
ALTER TABLE storage_spots ADD COLUMN capacity integer CHECK (capacity > 0);

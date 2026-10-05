-- Club scans carry more than the catalog: the spot in the store the card was scanned into, a photo of that exact
-- card, and detail such as grading or a serial number. storage_id is a Trading spot, checked when lines are built,
-- so a spot removed later just sends its cards to the collection's target.
ALTER TABLE club_link_items ADD COLUMN storage_id uuid;
ALTER TABLE club_link_items ADD COLUMN image_url  text;
ALTER TABLE club_link_items ADD COLUMN details    jsonb;

-- Re-inventory: a recount of a location, or of one spot and everything under it. What the store expected there is
-- copied when the count starts; counted cards come from typing on Trading or scanning on Club. Reconciling applies
-- counted minus expected to the store's own stock, so stock that moved while the count ran isn't undone.
CREATE TABLE inventory_counts (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants(id),
    location_id uuid NOT NULL REFERENCES locations(id),
    storage_id  uuid,
    state       text NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'reconciled', 'cancelled')),
    started_by  uuid NOT NULL REFERENCES users(id),
    started_at  timestamptz NOT NULL DEFAULT now(),
    closed_by   uuid REFERENCES users(id),
    closed_at   timestamptz
);
CREATE INDEX inventory_counts_tenant ON inventory_counts (tenant_id, state);

CREATE TABLE inventory_count_expected (
    count_id     uuid NOT NULL REFERENCES inventory_counts(id) ON DELETE CASCADE,
    storage_id   uuid,
    card_id      uuid NOT NULL,
    finish       text NOT NULL,
    condition    text NOT NULL,
    quantity     integer NOT NULL,
    club_link_id uuid
);
CREATE INDEX inventory_count_expected_count ON inventory_count_expected (count_id);

CREATE TABLE inventory_count_lines (
    id           uuid PRIMARY KEY,
    count_id     uuid NOT NULL REFERENCES inventory_counts(id) ON DELETE CASCADE,
    storage_id   uuid,
    card_id      uuid NOT NULL,
    finish       text NOT NULL,
    condition    text NOT NULL,
    quantity     integer NOT NULL CHECK (quantity > 0),
    source       text NOT NULL CHECK (source IN ('typed', 'club')),
    club_item_id text,
    image_url    text,
    details      jsonb,
    added_by     uuid REFERENCES users(id),
    added_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (count_id, club_item_id)
);
CREATE INDEX inventory_count_lines_count ON inventory_count_lines (count_id);

-- Every change a count makes to stock, so a line's history can say where its cards came from. Bulk upload and
-- other inventory systems will write here too, each with its own source.
CREATE TABLE inventory_adjustments (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants(id),
    location_id uuid NOT NULL REFERENCES locations(id),
    storage_id  uuid,
    card_id     uuid NOT NULL,
    finish      text NOT NULL,
    condition   text NOT NULL,
    change      integer NOT NULL,
    source      text NOT NULL,
    count_id    uuid REFERENCES inventory_counts(id),
    user_id     uuid REFERENCES users(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX inventory_adjustments_tenant ON inventory_adjustments (tenant_id, created_at DESC);

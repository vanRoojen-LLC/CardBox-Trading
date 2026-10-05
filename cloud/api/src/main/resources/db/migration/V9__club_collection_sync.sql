-- A cardbox.club collection synced into a store's inventory. Club owns what is in the collection and pushes it here
-- (server to server); Trading owns where the cards sit. One collection syncs to one store at a time. A paused link
-- (the person's store role ended, or the collection was deleted on Club) takes no deliveries until an owner keeps
-- or removes its cards, or the collection is linked again.
CREATE TABLE club_links (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenants(id),
    collection_id     text NOT NULL UNIQUE,
    collection_name   text NOT NULL,
    linked_by_sub     text NOT NULL,
    linked_by_account text NOT NULL DEFAULT '',
    linked_by_name    text NOT NULL DEFAULT '',
    linked_by_email   text NOT NULL DEFAULT '',
    location_id       uuid NOT NULL REFERENCES locations(id),
    storage_id        uuid REFERENCES storage_spots(id),
    default_condition text NOT NULL DEFAULT 'NM',
    state             text NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'paused')),
    paused_reason     text,
    last_synced_at    timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX club_links_tenant ON club_links (tenant_id);
CREATE INDEX club_links_linked_by ON club_links (linked_by_sub);

-- Every Club card in a linked collection as Club last described it. version is Club's, and only a newer one is
-- applied, so repeated or late deliveries change nothing. A removal keeps its row (removed) for the same reason.
-- card_id is null when the card can't go into inventory here; unmatched_reason says why.
CREATE TABLE club_link_items (
    link_id          uuid NOT NULL REFERENCES club_links(id) ON DELETE CASCADE,
    item_id          text NOT NULL,
    version          bigint NOT NULL,
    removed          boolean NOT NULL DEFAULT false,
    card_id          uuid,
    finish           text NOT NULL DEFAULT 'normal',
    condition        text,
    quantity         integer NOT NULL DEFAULT 1,
    game             text NOT NULL DEFAULT '',
    name             text NOT NULL DEFAULT '',
    set_code         text NOT NULL DEFAULT '',
    collector_number text NOT NULL DEFAULT '',
    unmatched_reason text,
    snapshot_id      uuid,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (link_id, item_id)
);

-- A full resend of a collection. Items not sent in it, and not changed since it started (version at or below
-- as_of_version), are removed when it completes.
CREATE TABLE club_link_snapshots (
    id            uuid PRIMARY KEY,
    link_id       uuid NOT NULL REFERENCES club_links(id) ON DELETE CASCADE,
    as_of_version bigint NOT NULL,
    started_at    timestamptz NOT NULL DEFAULT now(),
    completed_at  timestamptz
);

-- Synced cards are their own inventory lines, never merged with the store's own stock of the same card.
ALTER TABLE inventory_items ADD COLUMN club_link_id uuid REFERENCES club_links(id);
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint WHERE conrelid = 'inventory_items'::regclass AND contype = 'u';
    EXECUTE format('ALTER TABLE inventory_items DROP CONSTRAINT %I', c);
END $$;
ALTER TABLE inventory_items ADD CONSTRAINT inventory_items_line
    UNIQUE NULLS NOT DISTINCT (location_id, storage_id, card_id, finish, condition, club_link_id);
CREATE INDEX inventory_items_club_link ON inventory_items (club_link_id) WHERE club_link_id IS NOT NULL;

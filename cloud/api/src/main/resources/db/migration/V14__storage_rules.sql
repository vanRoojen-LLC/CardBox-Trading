-- What goes in a storage spot, as the store describes it: the same card details inventory filters on, as
-- {"field": ["value", ...]} (any of the values; every field must match). {} takes everything that reaches the spot.
-- A card finds its spot by walking down the tree: at each level the first spot (in the store's order) whose rule fits
-- wins, then its children are tried. Spots without a rule are never picked by rules.
CREATE TABLE storage_rules (
    spot_id    uuid PRIMARY KEY REFERENCES storage_spots(id) ON DELETE CASCADE,
    tenant_id  uuid NOT NULL REFERENCES tenants(id),
    conditions jsonb NOT NULL DEFAULT '{}',
    updated_by uuid REFERENCES users(id),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX storage_rules_tenant ON storage_rules (tenant_id);

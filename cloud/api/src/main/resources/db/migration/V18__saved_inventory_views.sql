-- Inventory searches a store keeps by name ("Red rares to sort", "Bulk commons"), shared by everyone on the store.
CREATE TABLE inventory_views (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name       text NOT NULL,
    filters    jsonb NOT NULL,
    sort       text NOT NULL DEFAULT 'name',
    dir        text NOT NULL DEFAULT 'asc',
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

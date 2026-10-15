-- A store may keep stock in a segment while declining new trades in it.
-- Missing rows mean accepting, preserving every existing store's behavior.
CREATE TABLE segment_trade_acceptance (
    tenant_id uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    segment text NOT NULL,
    accepting boolean NOT NULL,
    PRIMARY KEY (tenant_id, segment)
);

-- First-party completed store buy-in facts, not retail sales or generated market estimates.
-- Additive only; no old-trade backfill and no activation of outbound transport.
-- Event and receipt history have no cascading FK to mutable catalog/trade records.
CREATE TABLE completed_buyin_events (
    event_id text PRIMARY KEY,
    tenant_id uuid NOT NULL,
    trade_id uuid NOT NULL,
    line_no integer NOT NULL CHECK (line_no > 0),
    schema_version integer NOT NULL CHECK (schema_version = 1),
    payload_sha256 text NOT NULL CHECK (payload_sha256 ~ '^[a-f0-9]{64}$'),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (trade_id, line_no, schema_version),
    CHECK (payload ?& ARRAY['event_id','event_kind','origin','trade_id','store_tenant_id','line_no',
                           'schema_version','quantity','currency','currency_state','identity','consideration','guide']),
    CHECK (payload->>'event_id' = event_id),
    CHECK (event_id = 'cardbox.trading:buyin:' || trade_id || ':' || line_no || ':v1'),
    CHECK (payload->>'trade_id' = trade_id::text),
    CHECK (payload->>'store_tenant_id' = tenant_id::text),
    CHECK ((payload->>'line_no')::integer = line_no),
    CHECK ((payload->>'schema_version')::integer = schema_version),
    CHECK ((payload->>'quantity')::integer > 0),
    CHECK (jsonb_typeof(payload->'identity') = 'object'),
    CHECK (payload->'identity' ?& ARRAY['scope','printing_id','candidate_printing_ids','mapping_source']),
    CHECK (jsonb_typeof(payload#>'{identity,scope}') = 'string'),
    CHECK (jsonb_typeof(payload#>'{identity,printing_id}') = 'string'),
    CHECK (jsonb_typeof(payload#>'{identity,candidate_printing_ids}') = 'array'),
    CHECK ((payload#>>'{identity,scope}' = 'exact_printing' AND payload#>>'{identity,printing_id}' <> ''
             AND jsonb_array_length(payload#>'{identity,candidate_printing_ids}') = 0)
        OR (payload#>>'{identity,scope}' = 'candidate_printings' AND payload#>>'{identity,printing_id}' = ''
             AND jsonb_array_length(payload#>'{identity,candidate_printing_ids}') > 1)
        OR (payload#>>'{identity,scope}' = 'unresolved' AND payload#>>'{identity,printing_id}' = ''
             AND jsonb_array_length(payload#>'{identity,candidate_printing_ids}') = 0)),
    CHECK (jsonb_typeof(payload->'consideration') = 'object'),
    CHECK (jsonb_typeof(payload->'guide') = 'object'),
    CHECK (jsonb_typeof(payload->'currency') = 'null' AND payload->>'currency_state' = 'unrecorded'),
    CHECK (payload->>'event_kind' = 'completed_buyin'),
    CHECK (payload->>'origin' = 'cardbox.trading')
);
-- Only this small mutable queue is scanned by pending reads. Acknowledged history grows independently.
CREATE TABLE completed_buyin_event_queue (
    event_id text PRIMARY KEY REFERENCES completed_buyin_events(event_id),
    enqueued_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX completed_buyin_event_queue_order ON completed_buyin_event_queue(enqueued_at, event_id);

CREATE TABLE completed_buyin_event_receipts (
    event_id text PRIMARY KEY REFERENCES completed_buyin_events(event_id),
    payload_sha256 text NOT NULL CHECK (payload_sha256 ~ '^[a-f0-9]{64}$'),
    receiver_receipt_id text NOT NULL CHECK (length(receiver_receipt_id) BETWEEN 1 AND 300),
    acknowledged_at timestamptz NOT NULL DEFAULT now()
);

-- The current datasource also runs Flyway; enforce history against ordinary accidental DML, rather
-- than pretending a runtime-only GRANT boundary exists in this repository. Migration owners retain
-- explicit DDL authority. Compatible rollback keeps these tables and their history.
CREATE FUNCTION reject_completed_buyin_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'completed_buyin_history_is_immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER completed_buyin_events_immutable BEFORE UPDATE OR DELETE ON completed_buyin_events
    FOR EACH ROW EXECUTE FUNCTION reject_completed_buyin_history_mutation();
CREATE TRIGGER completed_buyin_events_no_truncate BEFORE TRUNCATE ON completed_buyin_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_completed_buyin_history_mutation();
CREATE TRIGGER completed_buyin_receipts_immutable BEFORE UPDATE OR DELETE ON completed_buyin_event_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_completed_buyin_history_mutation();
CREATE TRIGGER completed_buyin_receipts_no_truncate BEFORE TRUNCATE ON completed_buyin_event_receipts
    FOR EACH STATEMENT EXECUTE FUNCTION reject_completed_buyin_history_mutation();

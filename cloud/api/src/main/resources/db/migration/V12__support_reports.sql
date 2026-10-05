-- "Help & feedback" from the store app. Reports stay here, private to the platform owner; a scheduled job files a
-- sanitized copy (no names, emails or store names) as a GitHub issue when GITHUB_ISSUES_TOKEN is set. Without a token
-- a report just stays 'saved'.
CREATE TABLE support_reports (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenants(id),
    user_id             uuid NOT NULL REFERENCES users(id),
    kind                text NOT NULL CHECK (kind IN ('bug', 'question', 'idea')),
    description         text NOT NULL CHECK (length(description) BETWEEN 1 AND 5000),
    page                text NOT NULL DEFAULT '' CHECK (length(page) <= 500),
    environment         jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (octet_length(environment::text) <= 8000),
    -- saved: waiting to be filed (or filing is off); filed: issue created; failed: gave up after retries or a refusal.
    status              text NOT NULL DEFAULT 'saved' CHECK (status IN ('saved', 'filed', 'failed')),
    github_issue_number integer,
    github_issue_url    text,
    github_state        text,
    attempt_count       integer NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz NOT NULL DEFAULT now(),
    -- A claim lasts until this time, so a filer that dies mid-call doesn't hold a report forever.
    leased_until        timestamptz,
    last_error          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX support_reports_due ON support_reports (next_attempt_at) WHERE status = 'saved';
CREATE INDEX support_reports_created ON support_reports (created_at DESC);
CREATE INDEX support_reports_user_created ON support_reports (user_id, created_at);

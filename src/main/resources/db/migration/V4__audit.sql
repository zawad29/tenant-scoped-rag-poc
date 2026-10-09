-- Audit trail: uploads, replacements, deletions and security events.
--
-- Security events matter most here. If the post-retrieval tenant assertion ever
-- fires, the request is aborted and a row lands in this table, so a filter bug
-- leaves evidence instead of silently serving the wrong tenant's text.
--
-- tenant_id is nullable because some events (a failed login attempt) happen
-- before a tenant is known.

CREATE TABLE audit_event (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid,
    user_id    uuid,
    type       text        NOT NULL,
    detail     text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT audit_event_type_known CHECK (
        type IN (
            'DOCUMENT_UPLOADED',
            'DOCUMENT_REPLACED',
            'DOCUMENT_DELETED',
            'INGESTION_FAILED',
            'CHAT_SESSION_DELETED',
            'SECURITY_TENANT_MISMATCH',
            'SECURITY_ACCESS_DENIED',
            'LOGIN_FAILED'
        ))
);

CREATE INDEX audit_event_tenant_idx ON audit_event (tenant_id, created_at DESC);
CREATE INDEX audit_event_type_idx ON audit_event (type, created_at DESC);

-- Baseline schema.
--
-- Structured data only. Vectors and chunk text live in Qdrant; there is no
-- chunk table here.
--
-- Every table that belongs to a company carries cid_id. A user belongs to
-- exactly one cid, and there is deliberately no cid-free account that could act
-- across companies.

CREATE TABLE cid (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL,
    address    text,
    email      text,
    status     text        NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT cid_status_known CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT cid_name_not_blank CHECK (length(btrim(name)) > 0)
);

CREATE INDEX cid_name_lower_idx ON cid (lower(name));

CREATE TABLE app_user (
    id            uuid        PRIMARY KEY,
    cid_id        uuid        NOT NULL REFERENCES cid (id) ON DELETE CASCADE,
    email         text        NOT NULL,
    password_hash text        NOT NULL,
    role          text        NOT NULL,
    enabled       boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_user_role_known CHECK (role IN ('CID_ADMIN', 'USER')),
    CONSTRAINT app_user_email_not_blank CHECK (length(btrim(email)) > 0)
);

-- Login is by email alone, so email is unique across the whole system.
CREATE UNIQUE INDEX app_user_email_unique_idx ON app_user (lower(email));
CREATE INDEX app_user_cid_idx ON app_user (cid_id);

CREATE TABLE document (
    id                uuid        PRIMARY KEY,
    cid_id            uuid        NOT NULL REFERENCES cid (id) ON DELETE CASCADE,
    title             text        NOT NULL,
    original_filename text        NOT NULL,
    -- Hash of the currently active version, used to skip redundant re-ingestion.
    content_hash      text,
    status            text        NOT NULL DEFAULT 'UPLOADED',
    -- 0 means "no version has ever been activated".
    current_version   integer     NOT NULL DEFAULT 0,
    page_count        integer,
    uploaded_by       uuid        REFERENCES app_user (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_status_known CHECK (
        status IN ('UPLOADED', 'PROCESSING', 'ACTIVE', 'FAILED', 'DELETING')),
    CONSTRAINT document_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT document_current_version_non_negative CHECK (current_version >= 0)
);

CREATE INDEX document_cid_idx ON document (cid_id, created_at DESC);
CREATE INDEX document_cid_status_idx ON document (cid_id, status);

CREATE TABLE document_version (
    id              uuid        PRIMARY KEY,
    document_id     uuid        NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    version         integer     NOT NULL,
    storage_key     text        NOT NULL,
    content_hash    text        NOT NULL,
    embedding_model text,
    embedding_dim   integer,
    chunk_count     integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_version_unique UNIQUE (document_id, version),
    CONSTRAINT document_version_positive CHECK (version > 0)
);

CREATE INDEX document_version_document_idx ON document_version (document_id);

CREATE TABLE audit_event (
    id         uuid        PRIMARY KEY,
    cid_id     uuid,
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
            'SECURITY_CID_MISMATCH',
            'SECURITY_ACCESS_DENIED',
            'LOGIN_FAILED'
        ))
);

CREATE INDEX audit_event_cid_idx ON audit_event (cid_id, created_at DESC);
CREATE INDEX audit_event_type_idx ON audit_event (type, created_at DESC);

-- Documents, their versions, and the ingestion job queue.

CREATE TABLE document (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
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

CREATE INDEX document_tenant_idx ON document (tenant_id, created_at DESC);
-- The admin list filters on status, and the ingestion worker scans for work.
CREATE INDEX document_tenant_status_idx ON document (tenant_id, status);

CREATE TABLE document_version (
    id              uuid        PRIMARY KEY,
    document_id     uuid        NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    -- Denormalised on purpose: every tenant check then needs no join, so a
    -- forgotten join cannot become a forgotten tenant filter.
    tenant_id       uuid        NOT NULL,
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

CREATE INDEX document_version_tenant_idx ON document_version (tenant_id, document_id);

CREATE TABLE ingestion_job (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    document_id uuid        NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    version     integer     NOT NULL,
    state       text        NOT NULL DEFAULT 'QUEUED',
    error       text,
    attempts    integer     NOT NULL DEFAULT 0,
    claimed_at  timestamptz,
    started_at  timestamptz,
    finished_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ingestion_job_state_known CHECK (
        state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ingestion_job_attempts_non_negative CHECK (attempts >= 0)
);

-- The worker claims work with SELECT ... FOR UPDATE SKIP LOCKED; this partial
-- index keeps that claim cheap and ordered as the queue grows.
CREATE INDEX ingestion_job_queued_idx
    ON ingestion_job (created_at)
    WHERE state = 'QUEUED';

-- The admin page lists the latest job per document.
CREATE INDEX ingestion_job_document_idx ON ingestion_job (document_id, created_at DESC);

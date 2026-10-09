-- pgvector schema for the chunk index.
--
-- Lives in a separate Flyway location (classpath:db/vector/pgvector) mounted only
-- when the pgvector adapter is active, and uses the V1xx range so it can never
-- collide with the relational migrations in V1..V9.
--
-- ${vectorDimension} comes from rag.embedding.dimension via
-- spring.flyway.placeholders. The pgvector adapter asserts at startup that the
-- value baked in here still matches the configured embedding model; a mismatch
-- stops the application rather than mixing incompatible geometry in one index.

-- The extension must exist before the vector column is declared below.
-- CREATE EXTENSION ... IF NOT EXISTS is a no-op where an operator already
-- provisioned it (docker/postgres/init/01-extensions.sql), and creates it where
-- it has not been (Testcontainers, whose user is a superuser).
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE chunk (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    document_id     uuid        NOT NULL,
    version         integer     NOT NULL,
    chunk_index     integer     NOT NULL,
    page_start      integer     NOT NULL,
    page_end        integer     NOT NULL,
    section_title   text,
    doc_title       text        NOT NULL,
    -- Clean display text. The text that was actually embedded also included the
    -- document and section titles (specification 8.7); it is not stored twice.
    text            text        NOT NULL,
    embedding_model text        NOT NULL,
    embedding       vector(${vectorDimension}) NOT NULL,
    -- Keyword half of hybrid retrieval. Generated, so it can never drift from
    -- the text it indexes.
    tsv             tsvector    GENERATED ALWAYS AS (to_tsvector('english', text)) STORED,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- Nearest-neighbour search. Cosine distance matches the normalised vectors the
-- embedding adapter produces.
CREATE INDEX chunk_embedding_hnsw_idx
    ON chunk USING hnsw (embedding vector_cosine_ops);

-- Keyword search.
CREATE INDEX chunk_tsv_idx ON chunk USING gin (tsv);

-- The tenant pre-filter runs before ranking. Without an index on the filter
-- column, a highly selective filter makes HNSW return fewer rows than asked for
-- even though the query is correct; this index plus iterative scans (pgvector
-- >= 0.8) is what keeps recall stable. Covered by RecallsUnderFilterIT.
CREATE INDEX chunk_tenant_idx ON chunk (tenant_id);

-- Serving the active version of a document, and verifying deletions.
CREATE INDEX chunk_tenant_document_version_idx
    ON chunk (tenant_id, document_id, version);

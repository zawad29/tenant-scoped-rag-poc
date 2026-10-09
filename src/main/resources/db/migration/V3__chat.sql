-- Chat sessions and messages.
--
-- Memory is conversational context only, never a knowledge source: answers must
-- be grounded in chunks retrieved during the current turn. Storing the citations
-- and the retrieval trace per assistant message is what makes an answer
-- auditable after the fact.

CREATE TABLE chat_session (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    user_id         uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    title           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_message_at timestamptz
);

-- A user sees only their own sessions, always filtered by both tenant and user.
CREATE INDEX chat_session_owner_idx
    ON chat_session (tenant_id, user_id, created_at DESC);

CREATE TABLE chat_message (
    id                  uuid        PRIMARY KEY,
    session_id          uuid        NOT NULL REFERENCES chat_session (id) ON DELETE CASCADE,
    -- Denormalised for the same reason as document_version: no join required to
    -- enforce the tenant, so the check cannot be lost.
    tenant_id           uuid        NOT NULL,
    role                text        NOT NULL,
    content             text        NOT NULL,
    -- Citations actually shown to the user, after output validation.
    citations_json      jsonb,
    -- Chunk ids, dense/keyword/rerank scores, gate decision, timings: what the
    -- system did, so a bad answer can be explained rather than guessed at.
    retrieval_trace_json jsonb,
    refused             boolean     NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chat_message_role_known CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM'))
);

CREATE INDEX chat_message_session_idx ON chat_message (session_id, created_at);

# Decisions

Architecture decisions for the cid-scoped RAG PoC. Supersedes the earlier
tenant/pgvector decision log.

---

## R1 — Company concept is "cid", not "tenant"

A company is a `cid`. A user belongs to exactly one cid. The column is `cid_id`
everywhere and the cid-scoped value object is `CidContext`. `Cid` carries generic
contact fields (`name`, `address`, `email`) plus `status`.

## R2 — Module-per-domain layout

Code is grouped by module, each with `entity` / `repository` / `service` /
`controller` layers where they apply (see `README.md`). This replaced the earlier
layer-first packages (`tenant`, `ingest`, `web`, `adapter`, …).

## R3 — Spring Data JPA for structured data

All relational persistence uses JPA entities and Spring Data repositories. The
previous hand-written `JdbcClient` SQL was replaced; there is no hand-written SQL
left in the application.

## R4 — Qdrant for vectors; PostgreSQL for structured data

`pgvector` and the `chunk` table were removed. Chunks (vector + payload text and
metadata) live in Qdrant; PostgreSQL holds cids, users, documents, versions,
ingestion jobs and audit events. The cid filter is built inside
`QdrantVectorStore` from the `CidContext` argument, so callers cannot omit it.

## R5 — Only the active document version is searchable

Qdrant has no cross-store transaction with PostgreSQL, so the old pgvector join
(`document.current_version = chunk.version`) cannot be reproduced. Each point
carries an `active` flag. A replacement writes the new version's points inactive,
promotes them, flips `document.current_version` in PostgreSQL, then deletes the
superseded points. A brief both-active window is preferred over an availability
gap.

## R6 — Dense-only retrieval

Keyword/full-text search, `tsvector` and RRF fusion were removed. Retrieval is:
input guard → embed → Qdrant dense search (cid pre-filter) → cid verification →
cross-encoder rerank → relevance gate → context assembly.

## R7 — Local ONNX models are kept

Embedding and reranking still run in-process on local ONNX graphs (no API key,
no data egress). The port interfaces were removed; `EmbeddingService` and
`RerankerService` are concrete. Because the models are large and gitignored,
model-dependent tests are skipped with an assumption when `models/` is absent;
the ML beans are `@Lazy` so the application context can start without them.

## R8 — Tests rewritten

The suite was rewritten around the new design. It covers chunking/cleaning/split,
the input guard, the relevance gate, context assembly, the Qdrant cid filter
(synthetic vectors, no models needed), document cid scoping, and authentication /
cid resolution.

## R9 — Lombok, with `@Slf4j` for logging

Lombok is on the compile classpath and loggers are declared with `@Slf4j`
rather than a hand-written `LoggerFactory` field. This keeps logger
declarations out of the source while still using SLF4J under the hood.

## R10 — Ingestion is synchronous

Uploads run the full pipeline (extract → clean → chunk → embed → Qdrant upsert →
activate) on the request thread, and return when the document is active or has
been rejected. The database-backed queue, scheduled poller, worker threads and
the `ingestion_job` table were removed.

The trade-off is accepted for a PoC: the upload request blocks for the duration
of indexing, and there is no retry or durable progress across restarts. A
rejection is reported straight back on the redirect. `IngestionService` is
`@Lazy` and injected via `ObjectProvider`, so the ONNX pipeline is built only
when a document is actually ingested, not at context startup.

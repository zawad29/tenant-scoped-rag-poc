# Tenant-Scoped RAG PoC

A multi-tenant RAG chatbot where each organisation (tenant) chats with **only its
own** uploaded PDFs, gets cited answers, and gets a refusal when the documents
don't support an answer.

Built to [`spec.md`](spec.md). Design decisions, with the evidence behind each
one, are in [`docs/decisions.md`](docs/decisions.md) — **read that first**; it is
the map of why things are the way they are.

---

## Status

**Steps 0–8 of the plan are complete.** The core acceptance criterion — that no
tenant can ever retrieve, see, or be answered from another tenant's content — is
implemented and covered by a passing test suite.

Working: authentication and tenant resolution · PDF ingestion (validate, extract,
clean, chunk, embed, index) · a database-backed ingestion queue · document
admin (upload, replace, delete, with status polling) · hybrid retrieval with RRF
fusion and ONNX cross-encoder reranking · a relevance gate that refuses without
calling the model · tenant-checked document download and page rendering ·
the isolation contract suite.

**Not built yet:** the chat/answer layer (grounded generation with citations,
SSE streaming, chat UI), the evaluation harness and threshold calibration,
row-level security, and the second (Qdrant) vector-store adapter. In-memory
threshold values are starting points, not calibrated ones — see "Known gaps".

156 tests pass: `./mvnw verify`.

---

## Prerequisites

- **JDK 21** (bytecode target; JDK 21–25 accepted by the enforcer)
- **Docker** with Compose
- Network access **once**, to download the ONNX models
- A DeepSeek API key for anything that calls the model (ingestion and retrieval
  run entirely offline)

---

## Setup

```bash
# 1. Download the local ONNX models into ./models (~500 MB, gitignored).
./scripts/fetch-models.sh

# 2. Start PostgreSQL 17 + pgvector 0.8.7 (host port 5433 — see decisions.md D9).
docker compose up -d

# 3. Provide the API key. DEEPSEEK_API_KEY is read first, then ANTHROPIC_AUTH_TOKEN.
export DEEPSEEK_API_KEY=...        # https://api.deepseek.com
```

`JAVA_HOME` matters on a machine whose default JDK is not 21:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

---

## Build and test

```bash
./mvnw verify          # everything: unit tests + Testcontainers integration tests
./mvnw test            # fast: unit tests only, no containers
./mvnw spring-boot:run # start the app on :8080
```

`*Test` classes run under Surefire and need nothing but a JDK. `*IT` classes run
under Failsafe and need Docker: they start a real PostgreSQL 17 with pgvector.
**`./mvnw verify` is the gate** — run it before trusting a change.

Tests that need the ONNX models are skipped with an explicit assumption if
`scripts/fetch-models.sh` has not been run, so a fresh clone still builds.

---

## Configuration

Everything tunable lives under `rag.*` in
[`src/main/resources/application.yml`](src/main/resources/application.yml):
chunk sizing, candidate counts, and the relevance thresholds. Secrets come from
the environment only and are never written to a tracked file.

The two switches the design was built around:

| Setting | Values | Meaning |
| --- | --- | --- |
| `rag.vector.provider` | `pgvector` (default), `qdrant` | which `VectorIndexPort` adapter is active |
| `rag.rerank.enabled` | `true` (default), `false` | disabling it measures how much the reranker contributes |

Provider-specific classes are only reachable from their adapter, and architecture
tests fail the build if that stops being true.

---

## How tenant isolation is enforced

Five independent layers, so that no single mistake is enough to leak data:

1. **The tenant comes from the principal**, never from a request body, query
   string, header or model output.
2. **`VectorIndexPort` has no method without a `TenantContext`**, and the filter
   is built inside the adapter, so a caller cannot forget it. A reflective test
   fails the build if an overload without a tenant is ever added.
3. **Search is a single choke point.** Only `RetrievalService` may call
   `denseSearch`/`keywordSearch`; an ArchUnit rule enforces it.
4. **Every returned chunk's tenant is verified** against the caller's. A mismatch
   aborts the request and writes a `SECURITY_TENANT_MISMATCH` audit event, so a
   filter bug fails loudly instead of answering from the wrong documents.
5. **Identifiers from URLs are resolved with `WHERE id = ? AND tenant_id = ?`**,
   and a miss returns 404 rather than 403, so another tenant's document ids
   cannot be probed for existence.

There is deliberately **no `ThreadLocal` tenant context in the request path**.
Background ingestion carries `tenant_id` on the job row and claims work with
`SELECT ... FOR UPDATE SKIP LOCKED`, so the tenant travels with the work instead
of being inherited from a thread.

The isolation suite is a contract, not a test class:
`AbstractTenantIsolationContractTest` holds the assertions and
`PgVectorIsolationIT` only chooses the storage backend, so a second adapter
cannot pass a weaker suite than the first.

---

## Known gaps

- **No seeded users yet.** Tenant and user setup (spec step 13) is not built, so
  there is nothing to log in with. The schema and flows are exercised by the
  integration tests (`./mvnw verify`), which is the reliable path right now.
- **Thresholds are uncalibrated.** `rag.retrieval.min-relevance` and
  `min-chunk-relevance` are starting values. Spec §11 forbids shipping guessed
  thresholds, so refusal behaviour is unproven until the evaluation step sweeps
  them against a golden set.
- **The chat layer is not built**, so there is no grounded generation, citation
  validation or SSE streaming yet; retrieval is complete and tested.
- **Row-level security is not enabled.** It is the planned second database-level
  layer.
- **No re-index job.** Changing the embedding model requires it; the adapter
  refuses to start on a dimension mismatch rather than mixing vector geometry.

---

## Measured on a development machine

Useful for sizing, and all measured rather than assumed:

| Operation | Cost |
| --- | --- |
| Boot | ~2.6 s |
| Embedding | ~30 ms per chunk (bge-base-en-v1.5, in-process ONNX, CPU) |
| Reranking | ~4.8 ms per pair (30 candidates in ~145 ms) |
| Ingestion, ~50k chunks | ~25 minutes of background work |

Reranking is far cheaper than expected, so if recall is short the candidate pool
is the thing to widen, not the reranker.

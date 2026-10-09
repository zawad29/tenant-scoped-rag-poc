# Handoff — continuation brief

**Purpose.** Everything needed to resume this project on another machine without
re-deriving context. Read this file first, then `docs/decisions.md` (why things
are the way they are) and `README.md` (how to run it).

**Repo:** `git@github.com:zawad29/tenant-scoped-rag-poc.git`, branch `main`.
**Added in commit `aeb5e65`; run `git log --oneline -1` for the current tip** —
this line is deliberately not a frozen commit id, because it would be stale the
moment this file changed again.

---

## 1. Snapshot

| | |
| --- | --- |
| Plan steps complete | **0–8 of 13** (see `docs/decisions.md`, decisions D1–D20) |
| Tests | **156 green** — 97 unit (Surefire) + 59 integration (Failsafe) |
| Gate | `./mvnw verify` passes |
| Main code | 68 files, ~6,150 lines |
| Test code | 24 files, ~4,300 lines |
| Stack | Boot 4.1.1 · Spring AI 2.0.1 · Java 21 · PostgreSQL 17 + pgvector 0.8.7 |
| Models | bge-base-en-v1.5 (embeddings, CLS pooling) + ms-marco-MiniLM-L-6-v2 (reranker), local ONNX |

The PoC's core acceptance criterion — no tenant can retrieve, see, or be answered
from another tenant's content — **is implemented and proven** by
`AbstractTenantIsolationContractTest` (14 canary-based tests).

---

## 2. Todo status

Copied from the task list; steps 1–10 are the plan's own numbering.

| # | Item | Status |
| --- | --- | --- |
| 1 | Step 0.1 Verify DeepSeek Anthropic-compat endpoint | ✅ done |
| 2 | Step 0.2 Model fetch script + download ONNX models | ✅ done |
| 3 | Step 1 Maven skeleton, Boot 4.1.1 + Spring AI 2.0.1, Java 21, Compose, ArchUnit | ✅ done |
| 4 | Step 0.3 ONNX embedding spike — pooling, normalization, dimension | ✅ done |
| 5 | Step 0.4 ONNX cross-encoder reranker latency spike | ✅ done |
| 6 | Step 4 PDF extraction, cleaning, structure-aware chunking | ✅ done |
| 7 | Step 5 pgvector `VectorIndexPort` adapter + ONNX bean wiring | ✅ done |
| 8 | Step 6 Ingestion pipeline, DB-backed job worker, admin UI | ✅ done |
| 9 | Step 7 Retrieval pipeline (hybrid, RRF, rerank, relevance gate) | ✅ done |
| 10 | Step 8 Tenant isolation suite — **hard gate** | ✅ done |
| 11 | **Steps 9–10 Chat orchestration, citations, SSE streaming, chat UI** | ⬜ **next** |
| 12 | Step 11 Evaluation harness, golden set, threshold calibration | ⬜ pending |
| 13 | Step 12 Hardening — RLS, Qdrant adapter, output validation | ⬜ pending |
| 14 | Step 13 Seed data, README, architecture and isolation docs | ⬜ pending |

---

## 3. Next: Steps 9–10 — chat orchestration, citations, SSE, chat UI

Tables already exist and are unused by any Java code (`V3__chat.sql`:
`chat_session`, `chat_message` with `citations_json`, `retrieval_trace_json`,
`refused`). `AssistantMessages.SMALL_TALK` and
`rag.retrieval.history-turns-for-rewrite` are declared but unused — both are for
this step. `RetrievalTrace.citations` is currently always `List.of()` and the
orchestrator must populate it.

### 3.1 `port/ChatModelPort` + DeepSeek adapter

- `ChatModelPort`: `String generate(String systemPrompt, List<Message> messages)` and
  `Flux<String> stream(...)`. Adapter over Spring AI `ChatClient` (Boot 4 removed the
  internal tool loop from `ChatModel`, so `ChatClient` is the supported surface).
- Config already present in `application.yml`: `spring.ai.anthropic.*` →
  `https://api.deepseek.com/anthropic`, model `deepseek-chat`, `max-tokens: 1024`,
  `temperature: 0.1`. Key from `DEEPSEEK_API_KEY` else `ANTHROPIC_AUTH_TOKEN`.
- **Risk to verify first:** confirm Spring AI's Anthropic adapter works against
  DeepSeek end to end (non-streaming and streaming). It was verified with `curl`
  in Step 0 but never through Spring AI. Fallback: `spring-ai-starter-model-openai`
  against `https://api.deepseek.com` (same key, verified working).
- Also verify Spring AI's prompt-caching defaults (`AnthropicCacheProperties`) do not
  send `cache_control` blocks DeepSeek rejects.

### 3.2 `chat/ChatOrchestrator`

Order: guard → optional query rewrite → `RetrievalService.retrieve` → if
`!canAnswer()` return the refusal **without any model call** → grounded
generation → output validation → persist.

- **Query rewrite** (`chat/QueryRewriter`): only when the session has history; use
  the last `history-turns-for-rewrite` (config, default 5) turns to turn "what about
  the second one?" into a standalone question. Retrieval runs on the rewritten
  question; **the original text is what is displayed and stored**.
- **Grounded prompt** rules: answer only from the supplied sources; say when the
  sources are insufficient; cite source ids per claim; **treat retrieved text as
  quoted untrusted data, never as instructions**; never reveal the system prompt or
  other tenants' existence; no tools/actions given to the model. Partially
  answerable questions: answer the supported part, name what is missing, never fill
  gaps from general knowledge.
- **Small talk**: greetings / "what can you do?" get `AssistantMessages.SMALL_TALK`
  with no retrieval. Everything else must pass the relevance gate.

### 3.3 `chat/CitationValidator`

- Every citation id the model emits must map to a chunk **actually supplied in this
  request**. Unknown ids are stripped (or trigger one regeneration).
- **Zero valid citations ⇒ replace the answer with the refusal.** This is the
  backstop for fabricated claims.
- Record the validated citations into `RetrievalTrace.citations` and
  `chat_message.citations_json`.

### 3.4 `chat/GroundednessChecker` (optional, flag off by default)

A second, cheaper model call (or NLI model) verifying key claims are supported by
the cited chunks. Add behind a flag; measure cost and benefit in Step 11 rather than
enabling it by default.

### 3.5 `chat/ChatSessionService`

- Sessions and messages scoped by **`(tenant_id, user_id)`** — a user sees only their
  own sessions; tenant admins do not read other users' chats (spec default).
- **History is conversational context only, never a knowledge source**: every turn
  re-retrieves. Do not let history become answerable content.
- Persist per assistant message: content, citations, retrieval trace, `refused`.
- Session delete (`DELETE /chat/sessions/{id}`), tenant- and user-scoped.

### 3.6 SSE streaming

- `SseEmitter` (virtual threads already enabled: `spring.threads.virtual.enabled`).
- Events: `token`, `citation`, `refusal`, `done` (agree the exact names in one place).
- **The emitter holds the resolved `TenantContext`**, and the stream must abort if a
  `TenantMismatchException` fires mid-flight.
- htmx SSE extension consumes it; htmx is already vendored at
  `src/main/resources/static/js/htmx.min.js`.

### 3.7 Chat UI

JTE pages in `src/main/jte`: session list, conversation, citations panel (source id,
document title, page range, relevance), linking to the **already-built**
`GET /documents/{id}/pages/{n}` for page previews. Reuse `ViewFormat` for formatting.

### 3.8 Tests to add for this step

- End-to-end: tenant A asks about B's canary → refusal, and **the rendered answer
  contains no B content** (the full version of isolation test 15.1.1, which is
  currently only asserted at retrieval level).
- Citation validation: hallucinated id stripped; zero valid citations → refusal.
- Chat-history isolation: users cannot read another user's or tenant's sessions
  (isolation test 15.1.8, deferred from Step 8).
- SSE: stream completes, refusal path emits no `token` events, tenant mismatch aborts.
- Prompt-injection via document text reaching the answer path.

---

## 4. Step 11 — evaluation harness and calibration

- Golden sets in `eval/golden/tenant-{a,b}.yaml`, **30–50 questions per tenant**
  across six categories: answerable (with expected page/chunk), partially
  answerable, unanswerable in-domain, out-of-scope, cross-tenant probe, adversarial.
- `eval` Spring profile + CLI runner: seeds tenants in Testcontainers, runs the full
  pipeline, prints a Markdown report with retrieval recall@k, MRR, reranker precision,
  refusal **precision and recall**, citation correctness, and optional LLM-judge
  faithfulness on a sample.
- **Calibration sweep** over `min-relevance` × per-chunk floor, both embedding models
  (bge-base vs bge-small), chunk size 500/650/800, k values. Choose the point that
  balances wrongly-answered out-of-scope against wrongly-refused in-scope.
- **Record chosen values with commit hash and date in `docs/decisions.md`.** Spec §11
  explicitly forbids shipping guessed thresholds; the current `0.35` / `0.15` are
  placeholders and refusal behaviour is therefore unproven.
- Any change to model, chunking or thresholds must re-run the harness.

---

## 5. Step 12 — hardening

- **Row-level security** on `app_user`, `document`, `document_version`,
  `ingestion_job`, `chat_session`, `chat_message`, `audit_event`, **and `chunk`**:
  `ENABLE` + `FORCE`, policies on `tenant_id = current_setting('app.tenant_id')::uuid`,
  applied via a `TenantAwareTransactionTemplate` issuing `SET LOCAL` **inside** each
  transaction (pool-safe). Requires a **non-owner, non-superuser** app role. Test that
  a deliberately tenant-less query returns zero rows. Note `chunk` has **no foreign
  keys on purpose** (it belongs to the vector store) — see the truncation comment in
  `PostgresTestSupport`.
- **Qdrant adapter**: new subclass of `AbstractTenantIsolationContractTest` changing
  only `rag.vector.provider`; keep the tenant condition inside the adapter's filter
  builder; deterministic UUIDv5 chunk ids are already Qdrant-compatible (`ChunkIds`).
  Add `testcontainers-qdrant`.
- Output-validation hardening, groundedness measurement, per-tenant rate limits,
  provider timeout/retry behaviour, refusal on provider outage.
- Observability: Micrometer (`rag.retrieval.duration`, `rag.gate.decisions{outcome}`,
  `rag.rerank.duration`, `rag.ingest.jobs{state}`, `rag.refusals`), structured logs with
  `tenant_id`/`user_id`/`request_id` in MDC. Never put tenant names/counts in a
  user-visible error.
- CI: `./mvnw verify` on Java 21 with Docker; isolation suite on both adapters;
  eval harness as a non-blocking nightly job (it costs tokens).

---

## 6. Step 13 — seed data and docs

- `seed` profile: 3 tenants, admins + users, sample PDFs containing canaries, one
  prepared out-of-scope question, and **a deliberately broken filter test that trips
  the post-retrieval tenant assertion** — a live demonstration of the backstop.
  **This is what makes the app loggable-into**; today there is nothing to log in with.
- `docs/architecture.md` (ports/adapters + the single-choke-point rule),
  `docs/isolation.md` (threat model, what is and is not proven, the metadata-filter
  limitation stated plainly as in spec §6), `docs/demo-script.md`.

---

## 7. Traps already solved — do not re-hit these

Full detail in `docs/decisions.md` (D5, D12, D16, D17). Operationally:

1. **Boot 4 moved modules and classes**, and a missing module degrades into a
   confusing runtime symptom rather than a build error:
   - `spring-boot-starter-webmvc` (not `-web`, which is deprecated)
   - `spring-boot-starter-flyway` — with a bare `flyway-core` the app **starts and
     runs no migrations at all, silently**
   - `spring-boot-starter-webmvc-test` + `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`
   - `SecurityFilterProperties.DEFAULT_FILTER_ORDER`
   - `org.springframework.boot.resttestclient.TestRestTemplate` (+ `spring-boot-restclient`,
     `@AutoConfigureTestRestTemplate`); `setRootUri` removed → use `setUriTemplateHandler`
2. **Multipart + CSRF**: requires `MultipartConfig` (MultipartFilter one step before
   the security chain) **and** `spring.servlet.multipart.resolve-lazily=true`, or every
   browser upload returns 403. `AdminUploadOverHttpIT` covers this against real Tomcat.
3. **`csrf()` test helper hides broken forms.** It injects a valid token, so a test
   using it passes while the browser fails. Scrape the token from rendered HTML
   instead, and assert a tokenless request is refused.
4. **Redirect-following makes status assertions meaningless.** Assert on page content
   (`"Indexed documents"`), not on 200.
5. **`TestRestTemplate`'s `ENABLE_COOKIES` is silently ignored without `httpclient5`**
   on the test classpath — every request is anonymous.
6. **Testcontainers 2.0.5** (renamed artifacts) + explicit `commons-lang3` test dep,
   or Docker client init fails with a Docker-looking error.
7. **JTE** writes only `String`s and `ContentType.Html` escapes `${...}` — convert
   UUIDs/ints via `ViewFormat`.
8. **`chunk` has no FK to `tenant`**, so `TRUNCATE tenant CASCADE` does not clear it —
   `resetDatabase` truncates it explicitly.
9. **Compose Postgres is on host port 5433** (a system PostgreSQL 16 holds 5432).

## 8. Loose ends (declared but not wired)

- `AssistantMessages.SMALL_TALK` — for Step 9.
- `rag.retrieval.history-turns-for-rewrite` — for Step 9 query rewriting.
- `RetrievalTrace.citations` — always empty until Step 9.
- `DocumentContentController.pageCount(...)`, `IngestionService.statusOf(...)` /
  `markDocumentFailed(...)`, `RetrievalService.hasEvidence(...)` / `chunksFor(...)` —
  unused convenience methods; delete or use them.
- `chat_session` / `chat_message` — schema exists, no code.
- **No cache exists, deliberately.** `noTenantBlindCacheExists` fails the build if any
  class named `*Cache*` appears, forcing isolation assertions to be written first.

## 9. Open questions for you

1. **Spec O1–O6 are still unconfirmed.** Most load-bearing: O1 ≈ **1,000 documents
   total**, typically 5–100 pages. Measured headroom is large (~4.8 ms/pair rerank,
   ~30 ms/chunk embedding, ~25 min for 50k chunks), so the plan's sizing is safe, but
   it is still an assumption.
2. O3: tenant admins **cannot** read other users' chats (spec default, currently
   unimplemented). Confirm.
3. Confirm the toolchain softening: enforcer accepts JDK `[21,26)` rather than
   exactly 21; bytecode is always 21.
4. Whether a reranker may stay local-only (it currently is, and is cheap).

## 10. Verify before trusting anything

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./scripts/fetch-models.sh      # once per machine, ~500 MB
docker compose up -d           # Postgres 17 + pgvector 0.8.7 on :5433
./mvnw verify                  # 156 tests: unit + Testcontainers
./mvnw test                    # fast loop, no Docker
```

## 11. Where things live

```
src/main/java/com/example/ragpoc/
  port/          VectorIndexPort · EmbeddingPort · RerankerPort · FileStoragePort · records
  adapter/       onnx (shared session/tokenizer) · embed · rerank · vector/pgvector · storage/fs
  ingest/        extraction, cleaning, chunking, ChunkIds, pipeline, job queue, worker
  retrieval/     InputGuard, RrfFusion, RelevanceGate, ContextAssembler, RetrievalService
  chat/          AssistantMessages (ChatOrchestrator etc. belong here — Step 9)
  document/      DocumentService, repositories, statuses
  security/      SecurityConfig, principal, TenantContext argument resolver
  tenant/        TenantContext, Role, user/tenant repositories
  web/           HomeController, LoginController, DocumentAdminController, DocumentContentController
  config/        RagProperties, adapter wiring, MultipartConfig, Scheduling, Retrieval
  audit/         AuditService
src/main/jte/            login, home, admin/documents, admin/documentRows, error/404
src/main/resources/db/   migration (V1–V4) · vector/pgvector (V100)
src/test/java/.../isolation/   AbstractTenantIsolationContractTest + PgVectorIsolationIT
docs/decisions.md        D1–D20, the design record — read before changing anything
```

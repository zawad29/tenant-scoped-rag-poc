# Decisions

Architecture decisions for the multi-tenant RAG PoC, with the evidence that
drove them. Anything here that later turns out to be wrong should be corrected
in place, with the date and the reason.

---

## D1 — LLM: DeepSeek through its Anthropic-compatible endpoint

**Date:** Step 0 · **Status:** accepted

**Decision.** Talk to DeepSeek at `https://api.deepseek.com/anthropic` using
Spring AI's Anthropic adapter, with `model=deepseek-chat`.

**Evidence** (probed directly with curl before writing code):

| Probe | Result |
| --- | --- |
| `POST /v1/messages`, `x-api-key` + `anthropic-version: 2023-06-01` | HTTP 200 |
| Streaming (`"stream": true`) | Valid SSE, `message_start` / `content_block_delta` / `message_stop` |
| `deepseek-chat` | Single `text` block, 2 output tokens |
| `deepseek-flash` | `thinking` + `text` blocks, 13 output tokens for the same reply |
| `thinking: {"type":"disabled"}` | Accepted, collapses to a single `text` block |
| `https://api.deepseek.com/v1/chat/completions` (Bearer) | HTTP 200 — the fallback path works |

**Why `deepseek-chat` and not `deepseek-flash`.** `deepseek-flash` is a
thinking model: every answer pays for reasoning tokens (roughly 6x on the probe)
and returns `thinking` content blocks that the grounded-generation and citation
paths would have to filter out. `deepseek-chat` returns exactly one text block.
`/models` only advertises `deepseek-flash` and `deepseek-v4-pro`, so
`deepseek-chat` is an alias resolved by the compatibility layer — verified
working, but it is an alias, so the model name is a single config property and
the fallback is `deepseek-flash` with thinking disabled.

**Fallback if the Anthropic path breaks.** Spring AI's OpenAI adapter against
`https://api.deepseek.com` reached the same model in Step 0. Because
`ChatModelPort` exists, this is a property change plus one adapter, not a
rewrite.

**Not done:** no API key in any tracked file. `spring.ai.anthropic.api-key`
reads `DEEPSEEK_API_KEY`, falling back to the `ANTHROPIC_AUTH_TOKEN` the local
environment already exports. Note that the same variable is used by the coding
agent on this machine, so it is read, never overwritten.

---

## D2 — Embeddings: our own ONNX session, not Spring AI's transformers module

**Date:** Step 0 · **Status:** accepted

**Decision.** Implement `EmbeddingPort` with a small in-process ONNX adapter
(`OnnxEmbeddingAdapter` + `OnnxTextModel`) using `onnxruntime` and
`ai.djl.huggingface:tokenizers`. Do not use
`spring-ai-starter-model-transformers`.

**Evidence.**

1. bge models use CLS pooling. `BAAI/bge-base-en-v1.5/1_Pooling/config.json`:
   ```json
   { "pooling_mode_cls_token": true, "pooling_mode_mean_tokens": false }
   ```
2. Spring AI 2.0.1 hard codes mean pooling. Decompiling
   `spring-ai-transformers-2.0.1-sources.jar` shows
   `TransformersEmbeddingModel.java:369`:
   ```java
   NDArray embedding = meanPooling(ndTokenEmbeddings, ndAttentionMask);
   ```
3. The two are not interchangeable. `OnnxEmbeddingAdapterTest.poolingChoiceIsMaterial`
   asserts CLS and MEAN vectors differ (cosine < 0.999) and that they rank the
   same query differently.

**Consequence.** Using the stock starter with bge would not have crashed. It
would have produced plausible 768-dimension vectors and quietly degraded
retrieval — the worst possible failure mode for a system whose entire value is
answer quality. This is the single most important finding of Step 0.

**Bonus.** Avoiding the module also avoids two compile-scope transitive
dependencies, `ai.djl.pytorch:pytorch-engine` and `ai.djl:model-zoo`, which
would have pulled native PyTorch into a build that never uses it.

**Model.** `bge-base-en-v1.5`, 768 dimensions, `model.onnx` (416 MB, fp32).
A quantised `model_quantized.onnx` (110 MB) and `bge-small-en-v1.5` (384 dims)
are drop-in alternatives to be compared in the evaluation step, not now.

**Verified in Step 0:** 768 dims · unit norm after normalisation · related
sentence ranks above unrelated with a >0.05 cosine gap · batched output matches
one-at-a-time output · deterministic · **30.3 ms per chunk** on this host
(32 chunks in 971 ms), so ~50k chunks is ~25 minutes of async ingestion.

---

## D3 — Reranker: local ONNX cross-encoder, sharing the embedding runtime

**Date:** Step 0 · **Status:** accepted

**Decision.** `OnnxCrossEncoderRerankerAdapter` over
`ms-marco-MiniLM-L-6-v2`, reusing `OnnxTextModel`.

**Model choice.** `ms-marco-MiniLM-L-6-v2` is ~22M parameters and **87 MB fp32**,
against `bge-reranker-base` at **1.1 GB fp32 / 279 MB int8** with a 17 MB
tokenizer. At this stage latency and footprint matter more than the last point
of relevance, and bge-reranker-base remains a config change away.

**Verified in Step 0:** relevant passages outrank irrelevant ones · on-topic vs
off-topic separated by a >1.0 logit margin (a threshold can sit between them) ·
input order preserved · deterministic · **4.8 ms per pair**, i.e. 30 candidates
in 144 ms.

**Consequence for configuration.** Reranking is two orders of magnitude cheaper
than assumed when the plan was written, so the candidate pool, not the reranker,
is the thing to widen if recall is short. Threshold calibration still uses these
raw logits; `rag.retrieval.min-relevance` is a starting value only.

---

## D4 — Model assets are fetched once, then the runtime is offline

**Date:** Step 0 · **Status:** accepted

`scripts/fetch-models.sh` downloads the ONNX graphs and tokenizer files into
`models/` (gitignored), verifying size and resuming partial transfers. Both
native libraries ship inside their jars:

- `ai.djl.huggingface:tokenizers:0.32.0` bundles
  `native/lib/linux-x86_64/cpu/libtokenizers.so` (extracted to a local cache at
  first use),
- `com.microsoft.onnxruntime:onnxruntime:1.31.0` bundles the ONNX Runtime native
  library.

The application therefore makes no outbound call to embed or rerank, and there
is no first-request model download. Tests that need the models are skipped with
an explicit assumption ("run scripts/fetch-models.sh") rather than failing, so
`mvn test` works on a fresh clone.

---

## D5 — Spring Boot 4.1.1 with Spring AI 2.0.1, Java 21 bytecode

**Date:** Step 1 · **Status:** accepted

The specification asks for Spring Boot 3.x. Boot 3.5.x is past open-source
end-of-life, and Spring AI's current line (2.0.1) is built against Boot 4.1.1 —
confirmed from `spring-ai-starter-model-transformers:2.0.1`, which declares
`spring-boot-starter:4.1.1`. Starting a new project on the EOL line would be a
false economy, so Boot 4.1.1 was chosen with the API differences verified rather
than assumed.

**Renames found and applied:**

| Boot 3 name | Boot 4 name | Note |
| --- | --- | --- |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | Old one still resolves; its own POM says "deprecated in favor of spring-boot-starter-webmvc" |
| `spring-boot-starter-test` for MVC slices | `spring-boot-starter-webmvc-test` | Needed for `@WebMvcTest`; general test starter is unchanged |

**A silent failure worth recording.** Boot 4 moved autoconfiguration into
per-technology modules. Depending on `spring-boot-starter-jdbc` plus a bare
`org.flywaydb:flyway-core` compiles and starts the application perfectly — and
runs **no migrations at all**, with no warning, because `FlywayAutoConfiguration`
now lives in the separate `spring-boot-flyway` module. The fix is
`spring-boot-starter-flyway`, which brings `spring-boot-jdbc`, HikariCP,
`spring-boot-flyway` and `flyway-core`. Verified end to end: before the change
the database had no tables and the log never mentioned Flyway; after it, Flyway
reports `PostgreSQL 17.11` and creates `flyway_schema_history`.

That is the general shape of the Boot 3 to 4 risk: a missing module degrades
quietly instead of failing loudly, so every capability needs an end-to-end check
rather than a compile check.

**Java.** `maven.compiler.release=21` so bytecode is always Java 21 as the
specification requires. The enforcer accepts `[21,26)` rather than exactly 21,
because this machine's default JDK is 25 and both work; CI pins 21. This is a
deliberate softening of the plan, which said `[21,22)`.

**Verified:** `./mvnw compile` and the Step 0 test suite both pass on JDK 21.

**Measured on this host (2026-10):** boot ~2.6 s on Tomcat/8080, embedding
30–32 ms per chunk, reranking 4.5–4.8 ms per pair.

---

## D9 — Compose Postgres on host port 5433, not 5432

**Date:** Step 1 · **Status:** accepted

**Evidence:** `ss -ltnp` shows a native PostgreSQL 16.15 listening on
`127.0.0.1:5432` (Ubuntu package, service active), so the Compose container
could not bind 5432 and failed to start.

The Compose container wins rather than the system server, because it guarantees
pgvector **0.8.7**, and pgvector 0.8.0 is the floor for iterative HNSW index
scans — which the selective tenant pre-filter depends on to avoid returning
short result sets. The system PostgreSQL 16 has no pgvector at all
(`/usr/share/postgresql/16/extension/vector*` does not exist).

Host port 5433 is the default in both `docker-compose.yml` and
`application.yml`. Verified: extensions `vector 0.8.7` and `pg_trgm 1.6` present.
Testcontainers-based integration tests are unaffected, since they allocate a
random host port.

---

## D10 — Vector schema via Flyway with a dimension placeholder, plus a runtime assertion

**Date:** Step 3 · **Status:** accepted

The specification suggests the adapter creates the vector schema from the
configured dimension. A Flyway migration with a `${vectorDimension}` placeholder
is more reviewable, and the guarantee is kept by a startup assertion instead:
the adapter reads the declared column dimension from the catalogue and fails
fast when it disagrees with `rag.embedding.dimension`, naming the re-index job in
the error.

Silently mixing the vector geometry of two embedding models is the failure this
prevents. A mismatch must stop the application, not degrade it.

Vector migrations live in a separate location (`classpath:db/vector/pgvector`)
using a `V1xx` range, so they can never collide with relational migrations in the
`V1..V9` range. That location is only mounted when the pgvector adapter is
active; the Qdrant profile omits it.

---

## D11 — Testcontainers 2.0.5 (not 1.21.x), plus a missing commons-lang3

**Date:** Step 2 · **Status:** accepted

**Decision.** Use `org.testcontainers:*:2.0.5`, whose artifacts are renamed with a
`testcontainers-` prefix (`testcontainers-postgresql`,
`testcontainers-junit-jupiter`, `testcontainers-qdrant`).

**Evidence.** Testcontainers 1.21.3 could not start any container on this host:

```
BadRequestException (Status 400: "client version 1.32 is too old.
Minimum supported API version is 1.40, please upgrade your client to a newer version")
```

Docker here is 29.4.1, whose daemon requires API >= 1.40, while 1.21.3 bundles
docker-java 3.4.2, which negotiates 1.32. Version 2.0.5 bundles docker-java
3.7.1, which negotiates correctly. The alternative was to export
`DOCKER_API_VERSION` in every developer shell and CI job, which hides a stale
dependency behind an environment variable.

**A second trap while upgrading.** Testcontainers 2.x no longer brings
`commons-lang3` transitively, but still calls `BooleanUtils` while initialising
its Docker client strategies. The resulting failure is a
`ServiceConfigurationError` naming a Docker provider class, which reads like a
Docker misconfiguration rather than a missing jar. Adding
`org.apache.commons:commons-lang3` as a test dependency fixes it.

---

## D12 — Boot 4 moved MVC test slicing to `webmvc.test.autoconfigure`

**Date:** Step 2 · **Status:** accepted

`org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc`
no longer exists. In Boot 4 it is
`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, in the
separate `spring-boot-webmvc-test` module (`@WebMvcTest` moved with it). Same
family of change as D5's Flyway finding: capabilities now live in their own
module, and the failure is a compile error or a silently absent feature.

---

## D13 — Failsafe for integration tests, Surefire for unit tests

**Date:** Step 2 · **Status:** accepted

`*Test` runs under Surefire in the `test` phase; `*IT` runs under Failsafe in
`integration-test`. `./mvnw verify` is therefore the CI gate and runs both. This
keeps `./mvnw test` fast enough to be useful while working — the container-starting
suite is skipped — without letting the isolation suite fall out of the build.

---

## D14 — pgvector adapter: hand-written SQL, tenant predicate built in the adapter

**Date:** Step 5 · **Status:** accepted

**Decision.** `PgVectorIndexAdapter` issues its own SQL through `JdbcClient` and
`NamedParameterJdbcTemplate`. Spring AI's `VectorStore` is not used, and the
architecture test fails the build if anything imports it.

**Why.** Three requirements cannot be expressed together through that
abstraction:

1. the tenant predicate and the active-version predicate belong in the same
   `WHERE` clause as the ranking, so foreign rows never participate in the search
   rather than being filtered out afterwards;
2. `hnsw.iterative_scan` is required for recall under a selective filter;
3. the keyword half of hybrid retrieval needs `tsvector` ranking.

**The load-bearing detail.** The tenant predicate is constructed inside the
adapter from the `TenantContext` argument. Callers supply a tenant and a query;
they never build a filter. `SearchFilter` can only *narrow* the search within a
tenant — its document ids are ANDed with the tenant predicate, so a filter
naming another tenant's document returns nothing rather than that tenant's data.

**Active version without touching chunk rows.** The join is
`document.current_version = chunk.version AND document.status = 'ACTIVE'`. A
replacement therefore writes the new version's chunks first and then moves
`current_version`, which makes the switch atomic: a half-indexed document is
never visible, and no chunk rows are rewritten during a replacement.

**`SET LOCAL`, never `SET`.** `hnsw.iterative_scan = strict_order` and
`hnsw.ef_search` are applied inside a transaction, so they cannot leak to the
next request that borrows the same pooled connection.

**Evidence (Step 5).** 14 integration tests against real PostgreSQL 17 with
pgvector 0.8.7, including:

- `recallHoldsUnderASelectiveFilter`: 10 chunks for tenant A alongside 400 for
  tenant B, asserting a `k=10` search returns exactly 10. Without iterative
  scans this returns short, which is the failure mode the specification warns
  about. Passing here is what justifies the pgvector >= 0.8 requirement;
- `upsertAttributesChunksToTheContextTenant`: the tenant recorded on the row
  comes from the context, and `ChunkRecord` has no tenant field to contradict it;
- `deleteIsScopedToTheCallingTenant`: tenant B deleting tenant A's document id
  affects zero rows;
- `onlyActiveVersionIsSearchable` and `inactiveDocumentIsNotSearchable`;
- `mismatchedDimensionIsRefusedAtStartup`: a 384-dimension configuration against
  the 768-dimension column aborts startup with a message naming the re-index job.

**Deliberately avoided:** the pgvector JDBC type mapping. Vectors are written as
pgvector's text form and cast in SQL (`CAST(:embedding AS vector)`), because the
column is never read back on this path. One fewer dependency, and the cast makes
the type explicit at the point of use.

---

## D15 — A document stays ACTIVE while a replacement is being ingested

**Date:** Step 6 · **Status:** accepted

`document.status` is `PROCESSING` only for a *first* ingestion. The update is
guarded by `current_version = 0`:

```sql
UPDATE document SET status = 'PROCESSING' WHERE ... AND current_version = 0
```

The guard is load-bearing. The vector adapter requires
`document.status = 'ACTIVE'`, so setting PROCESSING during a replacement removes
the document from search for the whole duration of that replacement — the
updating of a document would cause an outage for that very document, and
"users never lose answers during an update" is an explicit requirement. A
document that already has a version therefore stays ACTIVE and keeps answering
from it, and progress is reported from the ingestion job's state instead.
`markFailed` carries the same guard, so a failed replacement does not take the
existing answers away either.

Covered by `IngestionPipelineIT.activeDocumentStaysAnswerableDuringReplacement`,
which stops deliberately in the in-between state.

---

## D16 — CSRF for multipart uploads needs MultipartFilter before the security chain

**Date:** Step 6 · **Status:** accepted

**The bug.** Every document upload from a browser returned 403.

`CsrfFilter` reads the token with `request.getParameter(...)`. For a
`multipart/form-data` POST that returns nothing until the body has been parsed
into parameters, and Spring Security's filter runs long before the
DispatcherServlet parses anything. The hidden `_csrf` field in the form was
therefore invisible to the check.

**The fix.** `MultipartConfig` registers `MultipartFilter` one step ahead of the
security chain (`SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1`), and
`spring.servlet.multipart.resolve-lazily=true` tells Spring MVC to reuse the
parsed request instead of re-reading a consumed stream. The form stays ordinary
HTML with a hidden field; no JavaScript, and no token in the query string (which
would put it in access logs).

**Why this was nearly missed, and the two false positives it exposed.** The
obvious test used the framework's `csrf()` request post-processor, which injects
a valid token directly — the test passed while the browser path was broken. Two
further mistakes compounded it:

1. `signIn` asserted the admin page returned 200. Redirects are followed, so an
   *unauthenticated* request also ends at the login page with a 200. It asserted
   on content after the fix;
2. `TestRestTemplate`'s `ENABLE_COOKIES` is silently ignored without an Apache
   HttpClient on the classpath, so every request was anonymous — which the
   meaningless 200 assertion hid.

**Consequence for the test suite.** `AdminUploadOverHttpIT` runs against a real
embedded Tomcat, scrapes the CSRF token out of the rendered HTML exactly as a
browser does, and includes a *negative* test asserting that the same multipart
POST **without** the token is refused with 403. MockMvc cannot represent this
path at all, because it builds its own filter chain without `MultipartFilter`, so
the multipart tests live in the real-server class and `DocumentAdminIT` keeps
only what MockMvc is good at (routing and authorization mapping).

---

## D17 — Further Boot 4 test-module moves found by building

**Date:** Step 6 · **Status:** accepted

| Boot 3 location | Boot 4 location | Consequence if missed |
| --- | --- | --- |
| `org.springframework.boot.autoconfigure.security.SecurityProperties` | `org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties` | `DEFAULT_FILTER_ORDER` not found |
| `org.springframework.boot.test.web.client.TestRestTemplate` | `org.springframework.boot.resttestclient.TestRestTemplate` (+ `spring-boot-restclient`, `@AutoConfigureTestRestTemplate`) | no test client bean |
| `setRootUri(...)` on `TestRestTemplate` | removed; `setUriTemplateHandler(new DefaultUriBuilderFactory(base))` | "URI with undefined scheme" |

None of these are documented in the migration guide in a way that a compile
against Boot 4 surfaces helpfully; each was found by running the tests. This is
consistent with D5 and D12: in Boot 4, a missing module or moved class degrades
into a confusing runtime symptom rather than a clear build error.

---

## D18 — The search choke point is narrower than the port

**Date:** Step 6 · **Status:** accepted

The architecture rule originally banned any dependency on `VectorIndexPort`
outside the retrieval, adapter, port and config packages. The ingestion pipeline
legitimately needs to `upsert`, `deleteByDocumentVersion` and `countByDocument`,
so the rule fired — and it was the rule that was wrong, not the code.

Writing to the index is an ingestion concern. What must not exist is a second
way to *read* knowledge for answering a question, because that is where tenant
verification and the relevance gate live. The rule is therefore expressed over
the search methods specifically (`denseSearch`, `keywordSearch`): only
`RetrievalService` may call them. A rule that also banned `upsert` would be
satisfied by indirection rather than by safety.

---

## D19 — Retrieval: the gate refuses, it does not ask the model to refuse

**Date:** Step 7 · **Status:** accepted

When the evidence is too weak, `RetrievalService` returns the refusal constant
and **no model call happens at all**. Refusing is therefore a property of the
system rather than of a prompt that might be argued with, and the default
refusal wording (specification O6) is a constant that cannot drift between runs.

**The gate compares reranker logits, not cosine similarity.** A question with no
answer in the corpus still has a nearest neighbour; "nearest" says nothing about
whether it is close. Cosine scores are also not comparable across embedding
models or even across queries within one model, so a threshold on them would not
transfer. The cross-encoder judges the pair, so its score can be thresholded.
`RelevanceGate.asRelevance` maps a score to (0, 1) for display only; thresholding
happens on the raw value, because the sigmoid is monotonic and applying it first
would only hide what is being compared.

**Two thresholds, and the reason for both.** `min-relevance` is the primary
gate on the best score: below it, refuse. `min-chunk-relevance` is a floor that
drops individually weak passages from an otherwise answerable question, so weak
context cannot dilute the prompt. A configuration where the floor exceeds the
primary threshold is rejected at startup, because the floor would then silently
become the real gate.

**Degraded mode without a reranker.** RRF scores are rank-based and have no
fixed scale, so they cannot be compared against a threshold calibrated on
logits. Rather than invent a conversion, the absolute gate is disabled and only
the existence of candidates is required — with the consequence named in the
trace, since answers in that mode are more likely to be grounded in weakly
relevant passages.

**A failed search is never answered from the half that succeeded.** If either
retrieval fails, the request refuses: a partial view of the evidence is exactly
the situation in which a model would guess.

---

## D20 — The isolation suite is a contract, not a test class

**Date:** Step 8 · **Status:** accepted

The specification's ten mandatory cases (15.1) live in
`AbstractTenantIsolationContractTest`. `PgVectorIsolationIT` extends it and does
nothing but choose the storage backend. A second adapter therefore cannot pass a
weaker suite than the first: when the Qdrant adapter arrives it extends the same
contract and changes one property.

Each test asserts on **canary strings** — invented names unique to one tenant —
so a failure says plainly that one organisation's text appeared in another's
results, rather than asserting on a count or on the absence of an exception.

**What is proven (14 tests, all passing):**

| Claim | Test |
| --- | --- |
| Another tenant's topic is refused, with no trace of its content | `askingAboutAnotherTenantsTopicIsRefused` |
| Eight phrasings, including B's text verbatim, never return a B chunk | `retrievalNeverReturnsAnotherTenantsChunks` |
| The same holds in both directions | `theBoundaryHoldsInBothDirections` |
| Search, count, document-filtered search and both delete forms are confined to the caller | `indexOperationsAreConfinedToTheCallingTenant` |
| Another tenant's document id fails for require, delete, replace, find and list | `crossTenantDocumentIdIsNotFound` |
| Another tenant's **storage key** cannot be read or deleted — the key alone is not authority | `crossTenantStorageKeyIsRefused` |
| Injection attempts are refused *before* retrieval (asserted as zero candidates produced) and audited | `promptInjectionAttemptIsRefused` |
| Instructions embedded in a document do not widen access | `instructionsInDocumentTextDoNotWidenAccess` |
| Deleted content stops being retrievable; the other tenant is unaffected | `deletedDocumentIsNoLongerRetrievable` |
| After a replacement only the new version answers | `replacementAnswersOnlyFromTheNewVersion` |
| 20 interleaved parallel retrievals for two tenants never mix | `parallelRetrievalDoesNotMixTenants` |
| Parallel ingestion keeps content and counts apart | `parallelIngestionKeepsContentApart` |
| No cache exists whose key could omit the tenant | `noTenantBlindCacheExists` |
| A 500-chunk tenant B does not starve tenant A's search | `recallSurvivesASelectiveTenantFilter` |

**The cache test is real, not a placeholder.** It imports the project's own
classes with ArchUnit and fails if any class name contains "cache". A test that
asserts `true` would be worse than no test, so adding a cache breaks the build
until tenant-isolation assertions for it are written (specification 15.1.9).

**Who the tenant comes from, restated because every test above depends on it.**
The job row carries `tenant_id`, `TenantContext` is built from it, and every port
method takes it as an argument. There is no `ThreadLocal` in the request path and
no tenant-free overload of any search method, so the parallel tests are passing
because the design has nowhere for a tenant to leak from — not because the tests
happened not to interleave badly.

---

## D6 — JTE starter: `-4`, not `-4-core`

**Date:** Step 1 · **Status:** accepted

`jte-spring-boot-starter-4-core` is framework agnostic: its only compile
dependencies are `spring-boot-starter` and `gg.jte:jte`, with no servlet or
Spring MVC integration. `jte-spring-boot-starter-4` adds the MVC view resolver
on top of `-4-core`. We render HTML through Spring MVC, so `-4` is the correct
artifact. Both are 3.2.4 and built against Boot 4.0.2, which is compatible with
4.1.1.

`gg.jte:jte` bundles the template compiler, so `gg.jte.development-mode=true`
works locally. The `jte-maven-plugin` (precompilation for production) is
deliberately not wired up yet; it will be added together with the first real
templates so the plugin and the templates are verified as one unit.

---

## D7 — Chunk size is capped by the embedding model's context window

**Date:** Step 0 · **Status:** accepted

The specification proposes 500–800 tokens per chunk. bge-base-en-v1.5 has
`max_seq_length: 512`, and the chunk that gets embedded is not the raw chunk:
it is `doc_title + section_title + chunk text` (specification §8.7). A 650-token
chunk plus its titles would be silently truncated by the tokenizer, losing the
tail of every chunk.

Defaults are therefore `target-tokens: 400`, `max-tokens: 480`, leaving room for
the contextual prefix inside 512. Token counts are measured with the embedding
model's own tokenizer, so the two numbers are in the same unit. This is a
starting point for the evaluation step, not a conclusion.

---

## D8 — No ThreadLocal tenant context in the request path

**Date:** Step 0 · **Status:** accepted

`TenantContext` is an immutable value passed explicitly into every port method,
and `VectorIndexPort` declares no method that omits it. The specification's
test 15.1.7 (no tenant bleed across threads, explicit propagation into async
work) is otherwise a test that can only fail intermittently; with explicit
parameters it becomes a compile-time property.

Background ingestion carries `tenant_id` on the `ingestion_job` row and claims
work with `SELECT … FOR UPDATE SKIP LOCKED`, rather than relying on `@Async`
context inheritance, which is exactly the mechanism that leaks.

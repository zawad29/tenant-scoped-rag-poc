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

---

# R11–R16 — Spring AI integration and provider seams

These supersede parts of R4 (hand-written Qdrant store), R6 (dense-only
pipeline plumbing) and R7 (local embedding). The isolation guarantees are
unchanged; only the plumbing moved to the framework.

## R11 — Adopt Spring AI for the vector store, document reading and ETL

`spring-ai-qdrant-store` replaces the 281-line hand-written `QdrantVectorStore`;
`spring-ai-tika-document-reader` replaces `PdfPageExtractor`;
`spring-ai-transformers` provides the embedding model; `spring-ai-rag` supplies
the `DocumentPostProcessor` interface. This removes the hand-rolled gRPC/protobuf
code and the PDF magic-byte/PDFBox plumbing.

The earlier "trim Spring AI" restructure is reversed on purpose: the framework
now owns protocol and parsing, while the domain policy (cid scoping, chunking,
the relevance gate, citations) stays ours.

## R12 — Embedding is all-MiniLM-L6-v2 through `TransformersEmbeddingModel`

`TransformersEmbeddingModel` mean-pools with the attention mask and exposes no
pooling setting, so it must be paired with a model trained for mean pooling.
`all-MiniLM-L6-v2` (384 dimensions, 256-token window) is that model; the chunk
sizes were reduced accordingly. This reverses D2, which used bge with CLS
pooling; bge under `TransformersEmbeddingModel` would be silently mean-pooled and
degraded.

Consequence: the DJL PyTorch engine (and its native) is now a dependency, because
DJL performs the mean pooling. The native is pinned in the pom so it is resolved
by Maven rather than downloaded on first use.

## R13 — Embedding and reranking are provider seams

`EmbeddingModel` (Spring AI's interface) and our own `Reranker` interface are the
only types the pipeline depends on. `ContextualEmbeddingModel` decorates any
`EmbeddingModel` with the `doc_title. section_title. text` prefix, so a hosted
embedding service slots in without losing that behaviour. `RerankingDocumentPostProcessor`
wraps any `Reranker`. `DenseOnlyReranker` is a real object for the disabled case,
so nothing branches on `null`.

Reranker thresholds live under the provider block because they are only
meaningful in that provider's score scale (`ScoringScale.LOGIT` for the local
cross-encoder, `NORMALIZED` for a hosted API).

## R14 — Reranking stays a local ONNX cross-encoder

Spring AI 2.0.1 has `DocumentPostProcessor` but no released reranking model (the
`RerankModel` work is still open). The cross-encoder remains our own code behind
the `Reranker` port. Its internal tensor arithmetic was decomposed into
`PaddedTokenBatch`, `OnnxTensors` and `RerankScores`, which are unit-tested
without the ONNX models. Raw logits are returned unchanged, because the gate
compares them.

## R15 — One live version, no `active` flag

Since data is reset between schema changes and a brief overlap is acceptable, the
per-point `active` flag is gone. A replacement adds the new version's points and
then deletes the superseded version's points by filter. At steady state only one
version is searchable, and the query filter is just the cid.

## R16 — No page numbers anywhere

Tika extracts a single text stream with no page boundaries and no page count.
The first pass kept a page number that was always `1`; that is now removed rather
than carried as a constant. `RawPage` and `CleanedPage` are gone — the seam is
`String extract(Path)` → `List<Block> clean(String)` → `List<Chunk> chunk(title,
blocks)` — and `Chunk`, `SearchHit`, `RetrievedChunk` and the retrieval trace
carry no page range. The rendered-page endpoint
(`GET /documents/{id}/pages/{n}`) went with them: nothing can produce a page to
render. `Document.pageCount` stays as a nullable column and is left unset, so the
admin list shows a dash instead of inventing a count.

The cross-page running header/footer detector was already inactive (it needed at
least three pages, and there is one), and it is deleted with the page concept
rather than kept dormant.

If page citations are ever needed, swap `TikaDocumentReader` for Spring AI's
`PagePdfDocumentReader` (PDF only) and reintroduce the page fields from that
source of truth — not from a placeholder.

---

# R17–R19 — Chat generation, streaming and the panel

These add the generation step the pipeline previously stopped short of. The
isolation guarantees are unchanged: retrieval still decides what the model may
see, and the relevance gate still decides whether it is called at all.

## R17 — Grounded generation through Spring AI's `ChatClient`

`ChatService` calls `RetrievalService` first and only builds a prompt when the
outcome is answerable. The model is reached through Spring AI's provider-neutral
`ChatClient`; the code names no provider. The provider is chosen by
`spring.ai.model.chat` plus the starter on the classpath, and `AppConfiguration`
is the only place that builds the client.

DeepSeek is the first provider (`spring-ai-starter-model-deepseek`, model
`deepseek-chat`). It is a configuration default, not a structural choice: the
prompt avoids provider-specific features, so another starter plus that property
is the whole swap.

The prompt (`ChatPrompts`) carries the spec's generation rules: answer only from
the supplied SOURCES, cite `[S1]`, state what is not covered, treat source text
as data rather than instructions, never reveal the system prompt or other
organisations. The system and user messages are passed as `Message` objects, not
as rendered templates, so a document containing `{}` cannot be misread as a
template placeholder.

A refusal is still not a model output: when the gate refuses, fixed wording from
`AssistantMessages` is returned and no model call happens.

## R18 — The chat surface is SSE over POST, with a no-JS fallback

`POST /chat/stream` answers with `text/event-stream`, emitting `panel`, `delta`
and `error` events; `POST /chat` is the plain-form fallback that retrieves,
answers with a blocking `ChatClient.call()`, and renders the same page. Both call
the same `ChatService` and render the same `chat/panel.jte`.

Two smaller decisions fall out of that:

- **`fetch` rather than `EventSource`.** `EventSource` only issues GETs, and the
  question plus the CSRF token belong in a request body. The browser parses the
  SSE frames from `fetch`'s stream (`static/js/chat.js`, ~40 functional lines);
  with JavaScript off, the form submits normally.
- **Every event's data is JSON, including the HTML ones.** A raw newline in an
  SSE `data:` field ends it early. The events carry `{"html": …}` or
  `{"text": …}`, and JSON escapes newlines for us.
- **The panel is JTE-rendered on both paths.** The markup has one definition and
  the script only swaps `innerHTML`, which keeps escaping server-side.

## R19 — No chat memory yet, and assembly is timed

Each question is answered from a fresh retrieval. There is no session or message
table and no history in the prompt, so a follow-up question gets no context from
the previous turn. This is deliberate for the PoC stage and is the next step
(spec §13), not an oversight.

`RetrievalTrace` gained `assembleMillis` so the panel can show retrieval's three
stages separately; generation timing lives in `ChatTimings`
(`firstTokenMillis`, `generationMillis`, `totalMillis`). A slow answer can then
be attributed to a stage rather than guessed at.

---

# R20 — The UI is Tailwind + htmx behind one server-rendered shell

The pages were nine hand-written templates with a duplicated `<header>` and a
hand-written stylesheet each. They now share `layout/app.jte`: a sidebar
(Home / Chat / Documents / Users / Organisation), a back button, the signed-in
user and the CSRF token. Each page supplies only its body through a JTE content
block, and `web/ShellModel` fills the shell attributes in one place so a new page
cannot forget the CSRF token or show another cid's name.

- **Tailwind through the Play CDN.** No build step and no generated stylesheet in
  the repository; `app.css` and `admin.css` are deleted. The cost is that the
  browser needs internet for styling and the CDN only emits classes it has seen,
  which is why the dynamic classes `chat.js` applies are listed on a hidden node
  in the layout. Compiling Tailwind instead is a `<link>` change plus a build
  step, not a redesign.
- **htmx for navigation and partial updates.** Sidebar links are `hx-boost`ed, so
  navigation swaps the body instead of reloading; the documents table refreshes
  through `hx-get` + `hx-select="#documents-table"`. Forms stay ordinary POSTs:
  they already redirect to a fresh page, and boosting a multipart upload or the
  logout form buys nothing but new failure modes.
- **The streamed answer keeps its small `fetch` parser.** htmx's SSE extension
  only issues GETs, and the question plus the CSRF token belong in a body, so
  `static/js/chat.js` still reads the response stream. Because boosted navigation
  replaces the body, its submit handler is delegated from `document`.
- **The retrieval panel is a nested `<details>` list** rather than a table: a
  selected-context group and a candidates group, each row expanding to its text
  or its drop reason. No JavaScript is involved. Generation timings therefore
  arrive as a separate `timings` fragment rather than by re-rendering the panel,
  which also preserves whichever rows the user had expanded.

### R20 follow-up — independently scrollable panel, candidate text in the trace

The two chat columns scroll separately: on `xl` the grid takes
`calc(100vh - 7rem)` and each column is a `min-h-0` flex child with its own
`overflow-y-auto` region, so reading candidates never moves the conversation and
vice versa. Below `xl` the columns stack and the page scrolls normally.

The retrieval panel now shows the content of every considered candidate, not only
the selected ones, each collapsed inside its own `<details>`. That required
`RetrievalTrace.CandidateTrace` to carry `text`, because the dropped candidates'
text existed only inside the vector-store response and nowhere in the outcome.
It is by far the largest field in the trace; if the trace is ever persisted per
message (spec §13), this is the field to trim or move out of the audit record.

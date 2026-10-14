# Handoff

**Repo:** `tenant-scoped-rag-poc` (branch `main`).
**Stack:** Boot 4.1.1 · Spring AI 2.0.1 · Spring Data JPA · PostgreSQL 17 ·
Qdrant · Apache Tika · local ONNX · Java 21.

## State

`./mvnw verify` is green: 76 unit tests + 23 integration tests (Testcontainers
PostgreSQL + Qdrant). ONNX-dependent tests are skipped when `models/` is absent.

Chat generation is built (R17–R19). `ChatService` retrieves first, and only when
the relevance gate allows it streams a grounded answer from DeepSeek through
Spring AI's provider-neutral `ChatClient`. `/chat` streams over SSE and shows the
retrieval panel with per-stage timings. There is still no chat memory.
`docs/pipeline.md` describes the result and `docs/decisions.md` records R1–R20.

## What changed

- **UI redesign (R20).** Every authenticated page now renders inside one shell,
  `src/main/jte/layout/app.jte`: a sidebar (Home / Chat / Documents / Users /
  Organisation), a back button, and the signed-in user. Styling is Tailwind
  utility classes loaded from the Play CDN; `app.css` and `admin.css` are gone.
  htmx boosts sidebar navigation and drives a partial refresh of the documents
  table (`hx-get` + `hx-select`). The retrieval panel is a nested collapsible
  list of `<details>` elements (selected context, then every candidate), each
  expanding to its content, and on `xl` the two chat columns scroll
  independently. The shell attributes are added in one place, `web/ShellModel`.
- **Chat generation.** `chat/` now holds `ChatService` (retrieve → generate),
  `ChatPrompts` (the grounded prompt), `ChatEvent`/`ChatTimings`, and the SSE
  controller. Retrieval still decides whether the model is called; a refusal is
  fixed wording and costs no tokens. `POST /chat/stream` is the interactive path,
  `POST /chat` the no-JavaScript fallback, both rendering `chat/panel.jte`.
- **Generation timings are a separate SSE frame** (`timings`, from
  `chat/timings.jte`), so the panel's collapsed/expanded state survives the end
  of a stream instead of being rebuilt.
- **DeepSeek via `spring-ai-starter-model-deepseek`**, selected by
  `spring.ai.model.chat=deepseek`. The code names no provider; a different
  starter plus that property is the whole swap.
- **The retrieval panel reports timings.** `RetrievalTrace.assembleMillis` was
  added, and generation timings live in `ChatTimings`.

- **Spring AI owns the plumbing.** `spring-ai-qdrant-store` replaced the
  hand-written Qdrant store; `spring-ai-tika-document-reader` replaced the PDFBox
  extractor; `spring-ai-transformers` provides the embedding model;
  `spring-ai-rag` supplies `DocumentPostProcessor`.
- **Embedding** is `TransformersEmbeddingModel` with **all-MiniLM-L6-v2**
  (384 dims, 256-token window), wrapped in `ContextualEmbeddingModel` which adds
  the `doc_title. section_title. text` prefix. This reverses the old bge+CLS
  choice (mean pooling is required by the Spring AI model); chunk sizes were
  reduced accordingly.
- **Reranking** stays a local ONNX cross-encoder behind our `Reranker` port
  (`OnnxReranker` / `DenseOnlyReranker`); Spring AI has no released reranker. The
  tensor math moved to `PaddedTokenBatch`, `OnnxTensors` and `RerankScores`, and
  the math helpers to `retrieval/math/VectorMath`.
- **Provider seams.** `EmbeddingModel` (Spring AI) and `Reranker` (ours) are the
  only types the pipeline depends on; `AppConfiguration` is the only place naming
  the local implementations. Thresholds live under the active reranker's block
  because they are scale-specific.
- **No `active` flag.** A replacement adds the new version's points, then deletes
  the superseded version by filter. Brief overlap accepted; data is reset anyway.
- **No page numbers.** Tika gives one text stream with no page boundaries and no
  page count, so the whole page concept is gone (R16): `RawPage` and `CleanedPage`
  were deleted, the seam is `String extract(Path)` → `List<Block> clean(String)` →
  `List<Chunk> chunk(title, blocks)` (R16), `Chunk`/`SearchHit`/`RetrievedChunk`
  and the trace carry the document and section only, and the rendered-page
  endpoint is gone. `Document.pageCount` stays nullable and unset, so the admin
  list shows a dash. Tika's `ExtractedTextFormatter` is a bean built from
  `rag.ingestion.formatter` rather than rebuilt per extract.

## Not built

- Chat memory: no session or `chat_message` rows, and no history in the prompt.
- Evaluation harness / threshold calibration.
- Row-level security.
- Seed data (the `V2__demo_data.sql` migration is the only seed).

## Useful commands

```bash
docker compose up -d            # Postgres (5433) + Qdrant (6333/6334)
./scripts/fetch-models.sh       # all-MiniLM-L6-v2 + ms-marco-MiniLM-L6-v2
./mvnw verify                   # the gate (set JAVA_HOME to JDK 21)
./mvnw spring-boot:run
```

`RAG_EMBEDDING_PROVIDER` / `RAG_RERANK_PROVIDER` select the backends. The ML
beans are `@Lazy`, so the app starts without the models; a missing model surfaces
on the first ingest or question.

Chat needs a DeepSeek key: put `DEEPSEEK_API_KEY=sk-...` in `.env` (Spring
imports `.env` itself, so `source`ing it is not required for this one). Leave the
line commented out when there is no key — see the trap below.

## Traps already solved

- **The Tailwind Play CDN needs internet in the browser**, and only emits classes
  it has seen. `chat.js` creates elements after load and `layout/app.jte` carries
  a hidden node listing those classes, so the CDN has them. Without the CDN the
  app still works, just unstyled.
- **htmx boosted navigation replaces the body**, so `chat.js` binds its submit
  handler on `document` (delegated) rather than on the form, and re-queries the
  conversation, panel and form on each submit. A listener bound at load would be
  discarded with the old body.
- **JTE layouts use a content block.** The shell takes `gg.jte.Content content`
  and each page passes a backtick-delimited content block as its `content`
  argument. Cross-template calls resolve dots to slashes (`@template.chat.panel`
  → `chat/panel.jte`).
- **An empty `DEEPSEEK_API_KEY` stops startup.** `application.yml` imports `.env`
  (`spring.config.import`) and defaults the key to `not-configured`, but an empty
  *present* value overrides the default, so `DeepSeekChatModel` fails with
  "DeepSeek API key must be set". Keep the line commented out when there is no
  key; do not export it empty.
- **Boot 4 auto-configures Jackson 3, not Jackson 2.** The `ObjectMapper` bean is
  `tools.jackson.databind.ObjectMapper`. Jackson 2 (`com.fasterxml...`) is on the
  classpath transitively and has no bean, so injecting it fails with a confusing
  `NoSuchBeanDefinitionException`.
- **JTE 3.2.4 has no `render(name, params)` that returns a `String`.** Rendering
  a partial programmatically needs a `gg.jte.output.StringOutput` sink.
- **`CandidateTrace.text` is deliberately fat.** The panel shows the content of
  considered candidates, so the trace carries every candidate's text. Trim or
  drop it before persisting the trace per message (spec §13).
- **Testcontainers Ryuk** cannot connect in this Docker setup; run integration
  tests with `TESTCONTAINERS_RYUK_DISABLED=true`.
- **A running app does not pick up Java changes, but JTE hot-recompiles templates.**
  In dev mode JTE recompiles `.jte` from `src/main/jte` on the first request after
  an edit, while Java classes are only loaded at JVM start. Change a template and
  a record/class together and the new template calls a method the loaded class
  does not have: `NoSuchMethodError` at render time (seen with
  `CandidateTrace.text()`). It is a stale JVM, not a build failure — restart.
- **Stale `target/` resources.** An old build leaves migration files behind
  (`V1__tenancy_and_security.sql`, `V2__documents.sql`, …) that Flyway reports as
  duplicate versions. Run `./mvnw clean` after schema changes.
- **Qdrant client version.** Do not pin `io.qdrant:client`; the version Spring AI
  pulls (1.18.x) is the one whose jar contains the gRPC types the store needs.
- **DJL PyTorch.** `TransformersEmbeddingModel` uses DJL for mean pooling, which
  needs a native engine. `pytorch-native-cpu:linux-x86_64` is pinned in the pom
  so Maven resolves it instead of a runtime download.
- **Dependency versions.** The `ai.djl.huggingface:tokenizers` pin (0.32.0) is
  what carries the bundled tokenizer native; the embedding model uses it via
  Spring AI.
- **Model export.** `scripts/fetch-models.sh` shells out to HuggingFace Optimum.
  Optimum 2.x moved ONNX export into a separate `optimum-onnx` package, so
  installing only `optimum` makes `optimum-cli export onnx` an unrecognized
  command; the script installs both. The embedding export is forced to
  `feature-extraction` (its output is `token_embeddings`, a token-level tensor
  Spring AI mean-pools) and the reranker to `text-classification` (auto-detection
  picks the unsupported `text-ranking`).

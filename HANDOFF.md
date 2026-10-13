# Handoff

**Repo:** `tenant-scoped-rag-poc` (branch `main`).
**Stack:** Boot 4.1.1 · Spring AI 2.0.1 · Spring Data JPA · PostgreSQL 17 ·
Qdrant · Apache Tika · local ONNX · Java 21.

## State

`./mvnw verify` is green: 69 unit tests + 20 integration tests (Testcontainers
PostgreSQL + Qdrant). ONNX-dependent tests are skipped when `models/` is absent.

The pipeline was refactored onto Spring AI and the reranker decomposed into
provider-neutral pieces, then the page concept was removed from ingestion and
retrieval. `docs/pipeline.md` describes the result and `docs/decisions.md` records
R1–R16 (R11–R16 are these two refactors).

## What changed

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

- Chat generation. `/chat` is a retrieval console; the reply area is disabled.
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

## Traps already solved

- **Testcontainers Ryuk** cannot connect in this Docker setup; run integration
  tests with `TESTCONTAINERS_RYUK_DISABLED=true`.
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

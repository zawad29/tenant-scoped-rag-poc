# The Ingestion, Retrieval and Reranking Pipeline

A plain-language but precise tour of what happens to an uploaded file, and what
happens when someone asks a question about it.

The framework owns the plumbing; this project owns the policy.

- **Spring AI owns:** the Qdrant vector store, the Apache Tika document reader,
  the ETL interfaces (`DocumentReader` / `DocumentTransformer` / `DocumentWriter`),
  the embedding model (`TransformersEmbeddingModel`), `Document`, and the
  `DocumentPostProcessor` interface.
- **This project owns:** cid isolation, text cleaning, chunking, the cross-encoder
  reranker, the relevance gate, context assembly, citations and the trace.

Everything is scoped to a **cid** (the company). That scoping is a parameter on
every call, never a global, and it is enforced twice
(see [Isolation](#isolation-the-cid-boundary)).

---

## 1. The big picture

```
                    UPLOAD (synchronous, on the request thread)

 file ─▶ store on disk ─▶ TikaDocumentReader ─▶ TextCleaner ─▶ DocumentChunker
      ─▶ List<Document> ─▶ Spring AI QdrantVectorStore.add   (embeds via
                            ContextualEmbeddingModel) ─▶ Postgres activate
      ─▶ delete superseded version's points

                    QUESTION (synchronous, on the request thread)

 question ─▶ input guard ─▶ CidScopedVectorSearch ─▶ verify returned cids
         ─▶ Reranker (cross-encoder) ─▶ relevance gate ─▶ rank + dedupe + token
            budget ─▶ context with S1, S2… labels
```

Two design choices shape everything else:

1. **Ingestion is synchronous.** `DocumentService.upload()` runs the whole
   pipeline before returning. A document is `ACTIVE` or `FAILED` when the upload
   responds — there is no queue or poller (R10).
2. **Retrieval never trusts the model to decide relevance.** The relevance gate
   is local code comparing numbers. When evidence is too weak, retrieval returns
   a fixed refusal and **no model call happens at all**.

> Chat generation is not built. `/chat` is a *retrieval console* that renders the
> candidates, ranks and scores. The pipeline stops at assembled context.

---

## 2. Provider seams (why the framework can be swapped later)

The pipeline depends on two provider-neutral types, never on the concrete local
implementations:

```
EmbeddingModel (Spring AI's interface)          Reranker (ours)
      ▲                                                ▲
      │                                                │
ContextualEmbeddingModel                      OnnxReranker   DenseOnlyReranker
      │                                          (local ONNX)   (provider=none)
TransformersEmbeddingModel   (today)           [later: CohereReranker, …]
[later: OpenAiEmbeddingModel, …]
```

- `ContextualEmbeddingModel` decorates **any** `EmbeddingModel` and adds the
  `doc_title. section_title. text` prefix used for embedding only. Swapping to a
  hosted embedding service is a bean change plus a re-index (the dimension
  changes), with the prefix behaviour preserved.
- `Reranker` returns one score per passage plus its `ScoringScale`
  (`LOGIT` locally, `NORMALIZED` from a hosted API). The post-processor and gate
  are scale-aware; thresholds live under the provider block because they are only
  meaningful in that scale.
- `AppConfiguration` is the only place that names the local implementations.

---

## 3. Ingestion pipeline

Entry point: `IngestionService.run()`.

### 3.1 Store the file

`FileStorageService.store()` writes to
`data/files/<cidId>/<documentId>/original-v<n>.pdf` via a temp file + atomic
move, and returns the SHA-256. The cid is the first path segment on purpose:
ownership is visible on disk and `pathOf()` re-verifies it. Re-uploading
byte-identical content is a no-op.

### 3.2 Extract text (`TikaTextExtractor` → Spring AI `TikaDocumentReader`)

Apache Tika auto-detects the format, so PDF, DOCX, PPTX and HTML all take the
same path. The whole file becomes one text stream.

Rejection criteria (mapped to `IngestionValidationException.Reason`):

| Check | Criterion | Reason |
|---|---|---|
| Size | ≤ `rag.ingestion.max-file-bytes` (50 MB) | `TOO_LARGE` |
| Encrypted | Tika reports an encrypted document | `ENCRYPTED` |
| Parse | Tika throws | `MALFORMED` |
| Text layer | at least one non-blank character | `NO_EXTRACTABLE_TEXT` |

Tika gives **no page boundaries and no page count**, so the stream is never
split and nothing downstream is measured in pages. The `NOT_A_PDF` and
`TOO_MANY_PAGES` rejection reasons are gone with them: Tika accepts any format it
can detect, and there is no page count to check.

The `ExtractedTextFormatter` is a **bean**, built once from
`rag.ingestion.formatter` instead of being rebuilt on every extract. It is
immutable, so one instance is shared by all ingests; its line deletions default
to zero so it does not fight the cleaner.

### 3.3 Clean (`TextCleaner`)

Extraction gives layout, not prose, so the cleaner drops standalone page numbers,
repairs hyphenation across line breaks, and groups lines into paragraph blocks
with a conservative heading heuristic. Input is the single extracted stream
(`List<Block> clean(String text)`), where `Block(text, heading)` is what lets the
chunker set a section title and prefer a break at a heading.

There is no page concept left to carry, so `RawPage` and `CleanedPage` are gone.
The cross-page running header/footer detector needed at least three pages and was
already inactive under Tika; it has been removed rather than kept dormant —
swapping in Spring AI's `PagePdfDocumentReader` would bring it back with real page
inputs.

### 3.4 Chunk (`DocumentChunker`)

Output: `Chunk(index, text, sectionTitle)` with the clean display text. There is no
page range: a citation identifies the document and the section it sits under.

| Knob | Value | Meaning |
|---|---|---|
| `target-tokens` | 160 | grow a chunk until it reaches this |
| `max-tokens` | 200 | hard cap |
| `min-tokens` | 32 | top up a short chunk unless the document ends |
| `overlap-percent` | 12 | carry back the tail into the next chunk |

Sizing is driven by the `all-MiniLM-L6-v2` **256-token** window: the text
actually embedded is `doc_title + section_title + chunk`, so the chunk must
leave room for the prefix. Sentences are atomic (a chunk never ends
mid-sentence unless one sentence is itself over budget, in which case it is
split at a word boundary). Token counts come from the embedding model's own
tokenizer (`HfTokenCounter`, truncation off).

### 3.5 Write via Spring AI (embed + upsert)

Each chunk becomes a Spring AI `Document`:

- `id` = the deterministic UUID v5 from `ChunkIds.forChunk(cid, document,
  version, index)`, so retries upsert over themselves.
- `text` = the clean chunk text (never the embedding prefix).
- `metadata` = `cid_id`, `document_id`, `version`, `chunk_index`,
  `section_title`, `doc_title`.

`DocumentIndex.add` calls `VectorStore.add`. The store embeds each document
through `ContextualEmbeddingModel`, which builds `doc_title. section_title.
text` for the model while storing the clean text. Qdrant stores metadata flat;
the cid is part of the point id, so two companies uploading the same document
never collide.

There is no `active` flag. A replacement adds the new version's points and then
`DocumentIndex.deleteByDocumentVersion` removes the superseded version's points
by filter. A brief both-active window is accepted; at steady state only one
version exists.

### 3.6 Activate and tidy up

One short DB transaction records the model/dimension/chunk count on the version,
flips `document.current_version` and status to `ACTIVE`, then superseded files
and version rows are removed. On any failure, the new points, file and version
row are best-effort removed, a first-time document is marked `FAILED`, and an
`INGESTION_FAILED` audit event is written. A failed *replacement* leaves the
previous version answerable.

---

## 4. Retrieval pipeline

Entry point: `RetrievalService.retrieve(cid, question)`. Order: **guard → dense
search → rerank → gate → assemble**.

### 4.1 Input guard (`InputGuard`)

Runs before anything is embedded, so a rejected request costs nothing.

| Check | Criterion |
|---|---|
| Empty | blank → `REJECTED_INPUT` |
| Length | > 2000 chars → `REJECTED_INPUT` |
| Rate | > 30 requests/minute/user → `REJECTED_INPUT` |
| Injection | regex match → `REJECTED_INPUT` **and** a `SECURITY_ACCESS_DENIED` audit event |

Injection patterns refuse outright (a question asking the assistant to ignore its
instructions has no legitimate answer). The rate limit is an in-memory per-user
sliding window.

### 4.2 Dense search (`CidScopedVectorSearch`)

The one place a cid-scoped search is built:

```
SearchRequest(query, topK = rag.retrieval.dense-candidates (30),
              filterExpression = "cid_id == '<caller's cid>'")
   -> Spring AI VectorStore.similaritySearch   (embeds the query, then searches)
   -> verify cid_id on every returned Document
```

The cid pre-filter is constructed from the `CidContext` argument, never from a
caller-supplied expression — Spring AI's `VectorStore` accepts a caller-supplied
`SearchRequest`, so the guarantee lives here instead. Every returned document's
recorded `cid_id` is then compared against the caller's; a mismatch writes a
`SECURITY_CID_MISMATCH` event and throws `CidMismatchException`, aborting the
request. The store already filters; this is the independent second opinion.

### 4.3 Rerank (`Reranker`)

Dense search ranks by cosine similarity of two *independently* embedded vectors:
fast and recall-oriented, but a poor judge of "does this passage answer the
question". The cross-encoder fixes the precision end.

```
             Bi-encoder (search)                 Cross-encoder (rerank)
 query ─▶ [enc] ─▶ vector ┐
                           ├─ cosine          [enc](query + passage) ─▶ 1 logit
 passage ─▶ [enc] ─▶ vector ┘                 one forward pass per pair
 (fast, approximate)                          (joint attention, precise)
```

- `OnnxReranker` wraps a local ONNX `ms-marco-MiniLM-L-6-v2`. The model's raw
  logits are returned **unchanged** (`ScoringScale.LOGIT`) — the gate compares
  these exact numbers.
- The tensor arithmetic lives in `PaddedTokenBatch` (right-padding, masks, type
  ids), `OnnxTensors` (inputs/outputs) and `RerankScores` (single-label
  extraction); `OnnxCrossEncoder` is lifecycle + tokenisation; `OnnxReranker` is
  business logic (batching, order).
- `DenseOnlyReranker` (`rag.rerank.provider=none`) returns zeros and reports
  `enabled() = false`, so the pipeline has no `null` branch. `rag.rerank.fail-open`
  governs whether a hosted provider failure degrades to this instead of refusing.

### 4.4 Relevance gate (`RelevanceGate`) — the answer/no-answer criterion

This is the main defence against confident nonsense. It compares the reranker's
scores, not cosine similarity.

| Knob | Value | Role |
|---|---|---|
| `min-relevance` | 0.35 | primary gate: the best score must reach this |
| `min-chunk-relevance` | 0.15 | per-chunk floor: drop weak chunks |

1. `best = max(scores)`; `kept = count(score >= minChunkRelevance)`.
2. `best < minRelevance` → **refuse**.
3. `kept == 0` → **refuse**.
4. Otherwise → **answerable**.

`minChunkRelevance` may not exceed `minRelevance` (constructor-enforced). In
degraded mode (no reranker) the absolute gate is off and any non-empty candidate
set is answerable, ordered by dense score.

Display relevance is `sigmoid(raw)` for `LOGIT` and clamped identity for
`NORMALIZED`, via `RelevanceGate.relevanceFor(scale, score)`.

### 4.5 Assemble context (`ContextAssembler`)

- **Deduplicate** on lower-cased, whitespace-collapsed text.
- **Token budget** (`rag.retrieval.context-token-budget`, 4000), counted with the
  embedding tokenizer. A chunk that would exceed it is skipped, not truncated.
- Selected chunks get stable labels **`S1`, `S2`, …** — the IDs the model must
  later cite.

### 4.6 Outcome

`RetrievalOutcome` is either **answerable** (chunks + trace) or **refused**
(empty chunks, a fixed message, and a trace). Refusal messages are constants in
`AssistantMessages`, because on refusal no model is called.

### 4.7 The trace (`RetrievalTrace`)

Per-stage timings, counts, thresholds, the best score, one `CandidateTrace` per
candidate (dense/rerank scores and ranks, `selected`, `droppedReason`), and the
model IDs. Embedding now happens inside the vector store's search, so
`searchMillis` covers embed+search and `embedMillis` is 0. This is what the
`/chat` console renders and what the evaluation step will use to calibrate the
thresholds.

---

## 5. Criteria at a glance

### Documents (ingestion)

| Question | Answer |
|---|---|
| Acceptable input | any file Tika can parse, ≤ 50 MB, not encrypted, has text |
| Embedded text | `doc_title. section_title. chunk_text` |
| Stored/quoted text | clean `chunk_text` only |
| Chunk size | grow to 160 tokens; hard cap 200; top up to 32; ~12% overlap |
| Chunk id | UUID v5 of `cid:document:version:index` |
| Citation | document title + section title; no page range, Tika has none |
| Searchable | one live version (superseded points deleted after promotion) |

### Questions (retrieval)

| Question | Answer |
|---|---|
| Allowed input | ≤ 2000 chars, ≤ 30/min/user, no injection pattern |
| Candidate pool | top 30 by cosine, `cid_id` pre-filtered and re-verified |
| Ranking | cross-encoder logit, batched 16 |
| Answerable when | `best ≥ 0.35` **and** ≥1 chunk `≥ 0.15` |
| Kept for context | rerank score `≥ 0.15`, top 6, deduplicated, ≤ 4000 tokens |
| Citation labels | `S1`, `S2`, … in context order |
| Otherwise | fixed refusal, no model call |

> Thresholds are starting points, not calibrated values. The evaluation step will
> sweep them against a golden set — and they are per-provider.

---

## 6. Isolation: the cid boundary

Enforced at four independent points:

1. **Every port takes a `CidContext`**; the index record deliberately carries the
   cid as metadata set from that argument.
2. **`CidScopedVectorSearch` builds the cid filter** from the argument, never from
   a caller-supplied expression.
3. **The backstop:** every returned document's `cid_id` is re-checked against the
   caller and a mismatch aborts fatally.
4. **File storage** keys by cid as the first path segment and re-verifies on read.

---

## 7. Configuration reference

All knobs live in `RagProperties` / `application.yml` under `rag.*`:

```yaml
rag:
  embedding:  { provider: transformers, model-id: all-MiniLM-L6-v2, dimension: 384,
                transformers: { model-path: …, tokenizer-path: …,
                                output-name: token_embeddings,
                                max-sequence-length: 256 } }
  rerank:     { provider: onnx, top-n: 6, fail-open: false,
                onnx: { model-path: …, tokenizer-path: …,
                        max-sequence-length: 512, batch-size: 16,
                        min-relevance: 0.35, min-chunk-relevance: 0.15 } }
  chunking:   { target-tokens: 160, max-tokens: 200, min-tokens: 32,
                overlap-percent: 12 }
  retrieval:  { dense-candidates: 30, context-token-budget: 4000 }
  guard:      { max-question-chars: 2000, requests-per-minute-per-user: 30 }
  ingestion:  { max-file-bytes: 52428800,
                formatter: { left-alignment: false, delete-top-lines: 0,
                             delete-bottom-lines: 0 } }
  qdrant:     { host: localhost, port: 6334, collection: chunks, use-tls: false }
  storage:    { local-root: data/files }
```

Models are fetched by `scripts/fetch-models.sh` into gitignored `models/`; the
heavy beans are `@Lazy`, so the app starts without them and a missing model is
reported on first use.

---

## 8. Library evaluation (recorded in `docs/decisions.md`)

- **Spring AI Qdrant store / Tika reader / transformers / RAG interfaces** —
  adopted (R11, R12).
- **DJL ONNX Runtime engine** — not adopted; its NDArray op coverage is limited,
  so pooling would still be hand-rolled, and it hides the raw logits the gate
  needs.
- **LangChain4j** (`langchain4j-onnx-scoring`, `langchain4j-embeddings`) — not
  adopted; no explicit batch control, its own ONNX/DJL versions conflict with the
  pinned ones, and its score normalisation would change the gate's thresholds.
- **Spring AI `RerankModel`** — not released in 2.0.1 (open issue #6524); the
  local cross-encoder stays ours behind the `Reranker` port.

---

## 9. Logging the flow

Every stage logs a stable `step=` marker, so a request can be followed end to end
with `grep 'step='`. INFO is the coarse pipeline; DEBUG adds per-chunk and
per-candidate detail (including the question text).

Ingestion:

```
ingest start cid=… document=… version=… title="…" storageKey=…
ingest step=tika-read file=… bytes=…
ingest step=tika-extracted file=… chars=…
ingest step=extract … chars=…
ingest step=clean … blocks=…
ingest step=chunk … chunks=…
index step=add documents=…
ingest step=index … documents=… embeddingModel=… dimension=…
ingest step=activate … status=ACTIVE
ingest step=cleanup … removing superseded version=…
ingest done … chunks=… status=ACTIVE
```

Retrieval:

```
retrieve start cid=… user=…
retrieve step=guard cid=… allowed=true chars=…            (or allowed=false reason=…)
retrieve step=search-start … topK=… queryChars=…
retrieve step=search-done … candidates=…
retrieve step=search … candidates=… searchMillis=…
retrieve step=rerank … candidates=… rerankMillis=… reranker=… enabled=…
retrieve step=gate … answer=true bestScore=… kept=… dropped=… thresholds=…
retrieve step=rank … ranked=… topN=…
retrieve step=assemble-start …
retrieve step=assemble select source=S1 …
retrieve step=assemble-done selected=… dropped=…
retrieve done … decision=answer selected=…                (or step=refuse reason=…)
```

Refusals carry a reason: `input-guard`, `no-candidates`, `relevance-gate` or
`no-context-after-assembly`. Security events (prompt injection, cid mismatch) are
logged as warnings and also written to `audit_event`.

---

*Rationale: `docs/decisions.md` (R1–R16). Behaviour: the `ingestion/**` and
`retrieval/**` classes referenced above.*

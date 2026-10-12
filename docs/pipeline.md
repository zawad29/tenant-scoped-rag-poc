# The Ingestion, Retrieval and Reranking Pipeline

A plain-language but precise tour of what happens to a PDF after someone uploads
it, and what happens when someone asks a question about it.

The code lives in two modules:

- `ingestion/**` — PDF → clean text → chunks → embeddings → Qdrant.
- `retrieval/**` — question → candidates → reranked evidence → context.

Everything is scoped to a **cid** (the company/tenant). That scoping is a
parameter on every call, never a global, and it is enforced twice (see
[Isolation](#isolation-the-cid-boundary)).

---

## 1. The big picture

```
                    UPLOAD (synchronous, on the request thread)

 PDF file ─▶ store on disk ─▶ extract per page ─▶ clean lines/blocks
        ─▶ chunk (sentence-aware) ─▶ add context prefix ─▶ embed (bge)
        ─▶ Qdrant upsert (inactive) ─▶ promote ─▶ DB: activate ─▶ delete old version

                    QUESTION (synchronous, on the request thread)

 question ─▶ input guard ─▶ embed ─▶ Qdrant dense search (cid + active filter)
         ─▶ verify every returned cid ─▶ rerank (cross-encoder) ─▶ relevance gate
         ─▶ rank + dedupe + token budget ─▶ context with S1, S2… labels
```

Two design choices shape everything else:

1. **Ingestion is synchronous.** `DocumentService.upload()` runs the whole
   pipeline before returning. A document is `ACTIVE` or `FAILED` when the upload
   responds — there is no queue, poller or status page to wait on
   (`docs/decisions.md` R10).
2. **Retrieval never trusts the model to decide relevance.** The relevance gate
   is local code comparing numbers. When evidence is too weak, retrieval returns
   a fixed refusal and **no model call happens at all**.

> Current state: chat generation is not built. `/chat` is a *retrieval console*
> that renders the candidates, ranks and scores. The pipeline below stops at
> assembled context.

---

## 2. Ingestion pipeline

Entry point: `IngestionService.run()` (`ingestion/service/IngestionService.java`).

### 2.1 Store the file

`FileStorageService.store()` writes to `data/files/<cidId>/<documentId>/original-v<n>.pdf`
via a temp file + atomic move, and returns the SHA-256. The cid is the first path
segment on purpose: ownership is visible on disk and `pathOf()` re-verifies it.

- Re-uploading **byte-identical** content is a no-op (`replace()` compares the
  SHA-256) — no new version, no re-embedding.

### 2.2 Extract text (`PdfPageExtractor`)

PDFBox, page by page, `setSortByPosition(true)`. Page numbers are kept because
citations need them.

Rejection criteria (each maps to a user-visible `IngestionValidationException.Reason`):

| Check | Criterion | Reason |
|---|---|---|
| Size | ≤ `rag.ingestion.max-file-bytes` (50 MB) | `TOO_LARGE` |
| Magic bytes | first 5 bytes are `%PDF-` (extension/MIME are not trusted) | `NOT_A_PDF` |
| Page count | ≤ `rag.ingestion.max-pages` (500) | `TOO_MANY_PAGES` |
| Encrypted | cannot be opened without a password | `ENCRYPTED` |
| Parse | PDFBox throws `IOException` | `MALFORMED` |
| Text layer | at least one page has non-blank text | `NO_EXTRACTABLE_TEXT` |

`NO_EXTRACTABLE_TEXT` is deliberate: a scanned PDF has no text layer, OCR is out
of scope, and indexing nothing would silently produce a document that answers no
questions. Per-page text is capped at 1,000,000 characters.

### 2.3 Clean (`TextCleaner`)

PDF extraction gives layout, not prose: hard line breaks mid-sentence, running
headers/footers on every page, hyphenation across lines. The cleaner runs in this
order — **boilerplate detection first**, because reflowing would destroy the line
structure that detection needs.

**Boilerplate (header/footer) removal.** Only the first and last 3 lines of each
page are candidates. A line must:

- appear on ≥ 80% of pages (`BOILERPLATE_PAGE_SHARE`), **and**
- be ≤ 150 characters (`MAX_BOILERPLATE_LINE_LENGTH`), **and**
- have ≥ 2 characters.

Detection is skipped entirely for documents with fewer than 3 pages. Comparison
is shape-based, not value-based: lower-cased with all digit runs masked to `#`,
so "Page 4 of 20" and "Page 7 of 20" match.

The 80% threshold is intentionally high. At 50%, a section heading such as
"ANNUAL LEAVE" that legitimately recurs was being deleted from the document.

**Page numbers.** A standalone number, with optional `Page`/`p.` prefix and
`of N` suffix, is dropped even if it is not repeated.

**Blocks.** Surviving lines are reflowed into paragraph-level blocks:

- Hyphenation is repaired when the previous line ends in `-` (`employ-\nment` →
  `employment`).
- A new block starts on a heading, a list item, or after a line ending in
  `.`/`!`/`?`/`:`.
- **Heading heuristic**: ≤ 80 chars, ≤ 12 words, not ending in sentence/clause
  punctuation or a list marker, and either numbered (`3.1 Eligibility`), or
  title-shaped (≥ 60% of words capitalised) with ≤ 8 words, or all-caps.
  A missed heading costs a slightly worse section title; a false positive cuts
  a sentence in half and corrupts the citation, so the test is conservative.

Unicode is normalised to NFC; exotic spaces, zero-width characters and bullets
are normalised or removed.

### 2.4 Chunk (`DocumentChunker`)

Output: `Chunk(index, text, pageStart, pageEnd, sectionTitle)` — the **clean
display text**, not the text that gets embedded.

Pipeline: cleaned blocks → sentences (`SentenceSplitter`) → pieces. Each piece
carries its page number and the most recent heading as its `section`.

Sizing (`rag.chunking`):

| Knob | Value | Meaning |
|---|---|---|
| `target-tokens` | 400 | grow a chunk until it reaches this |
| `max-tokens` | 480 | hard cap; a piece that would exceed it starts a new chunk |
| `min-tokens` | 48 | top up a short chunk to this, unless the document ends |
| `overlap-percent` | 12 | carry back the tail of a chunk into the next one |

`max-tokens` is 480, not 512, because the text actually embedded is the prefix +
chunk (see 2.5) and bge's window is 512.

Rules:

- The first piece is always taken, so a single oversized sentence cannot stall
  the loop.
- A sentence longer than `max-tokens` is split at **word boundaries** using a
  binary search for the largest fitting prefix (only such sentences pay for it;
  the common case is one token count).
- Overlap is a **token budget** of `max(1, maxTokens × 12%)` ≈ 57 tokens: the
  trailing pieces of a chunk are replayed at the start of the next one, so a
  sentence straddling a boundary is intact in at least one chunk.
- Sentences are the atomic unit, so a chunk never ends mid-sentence (unless one
  sentence is itself over budget). `SentenceSplitter` is conservative about
  abbreviations, initials and dotted acronyms — it would rather keep two
  sentences together than cut "see Fig." from "3".

Token counts come from the **embedding model's own tokenizer** (`HfTokenCounter`,
truncation and padding off), so "tokens" means what bge means.

### 2.5 Contextualise, then embed

Each chunk is embedded as:

```
<doc title>. <section title>. <chunk text>
```

(`IngestionService.contextualise()`). This prefix improves the embedding because
a chunk like "eighteen days per calendar year" is meaningless without knowing it
is about leave. Crucially, **the prefix is embedded but never stored or quoted** —
the payload keeps the clean `chunk.text`.

Embedding (`EmbeddingService` + `OnnxTextModel`), config `rag.embedding`:

| Setting | Value |
|---|---|
| Model | `bge-base-en-v1.5` (local ONNX, in-process) |
| Pooling | **CLS** — bge was trained with CLS; mean-pooling degrades it silently |
| Normalise | L2 normalise to unit length (cosine distance) |
| Dimension | 768 (validated: a mismatch throws) |
| Max sequence | 512 tokens |
| Batch | 16 |

No API key and no outbound traffic: both native runtimes ship in their jars and
the model files are read from disk.

### 2.6 Write to Qdrant, then promote

Chunk IDs are **deterministic UUID v5** over `cidId:documentId:version:chunkIndex`
(`ChunkIds`). The cid is part of the name so two companies uploading the same
document get different points; retries upsert over themselves instead of
duplicating.

Qdrant has no transactions spanning it and PostgreSQL, so the switch is ordered
to **prefer a brief both-active window over an availability gap** (R5):

1. Upsert every point of the new version with `active = false`.
2. `setActive(..., true)` for that version — the new chunks become searchable.
3. One short DB transaction: record model/dimension/chunk count on the version,
   flip `document.current_version` and status to `ACTIVE`.
4. Delete superseded versions' points and files.

So a replacement document is never unanswerable, but for a moment both versions
can match a query.

Payload per point: `cid_id`, `document_id`, `version`, `chunk_index`,
`page_start`, `page_end`, `section_title`, `doc_title`, `text`, `embedding_model`,
`active`. Payload indexes exist on `cid_id`, `document_id`, `version`, `active`.

### 2.7 Failure handling

If anything throws, `cleanUpAfterFailure()` best-effort removes the new version's
points, the stored file and the version row; a first-time (never-active) document
is marked `FAILED`, and an `INGESTION_FAILED` audit event is written. A failed
*replacement* leaves the previous good version active and answerable.

---

## 3. Retrieval pipeline

Entry point: `RetrievalService.retrieve(cid, question)`. The order is fixed and
documented in the class comment: **guard → embed → dense search → cid check →
rerank → gate → assemble**.

### 3.1 Input guard (`InputGuard`)

Runs before anything is embedded, so a rejected request costs nothing.

| Check | Criterion |
|---|---|
| Empty | blank question → `REJECTED_INPUT` |
| Length | > `rag.guard.max-question-chars` (2000) → `REJECTED_INPUT` |
| Rate | > `rag.guard.requests-per-minute-per-user` (30) per rolling minute per user → `REJECTED_INPUT` |
| Injection | regex match → `REJECTED_INPUT` **and** a `SECURITY_ACCESS_DENIED` audit event |

Control and format characters are stripped first. Injection patterns refuse
outright rather than flag-and-continue: a question asking the assistant to ignore
its instructions has no legitimate answer. Patterns cover "ignore previous
instructions", "disregard previous", "reveal/print your system prompt", asking
about *other* organisations, "you are now …", and "jailbreak"/"developer mode".

The rate limit is an in-memory per-user sliding window, so it is per instance.

### 3.2 Embed the question

Same `EmbeddingService`, same model. The question is embedded **as-is** — no
document/section prefix, because it is a query, not a passage.

### 3.3 Dense search

`QdrantVectorStore.denseSearch(cid, vector, limit=rag.retrieval.dense-candidates (30), documentIds=null)`.

The filter is built **inside the store from the `CidContext` argument** and is a
Qdrant *pre-filter*, so other companies' points never enter the ranking:

```
must: cid_id == <caller's cid>   AND   active == true
```

Cosine distance, `limit = 30`, full payload returned. `SearchHit.cidId` comes
from the **point's payload**, not the request — see next step.

### 3.4 Verify the cid — the second opinion

The store already filters, but `verifyCid()` independently compares every
returned `hit.cidId()` against the caller's cid. Any mismatch:

- writes a `SECURITY_CID_MISMATCH` audit event, then
- throws `CidMismatchException`, deliberately fatal, aborting the request.

This is the backstop for a filter-construction bug. One filter bug should not be
the only thing between two companies' data.

### 3.5 Rerank (`RerankerService`) — the cross-encoder

Dense search returns up to 30 candidates ranked by cosine similarity of two
*independently* embedded vectors. That is fast and recall-oriented, but cosine
similarity is not a reliable judgement of "does this passage answer this
question". The reranker fixes the precision end of that.

```
                Bi-encoder (search)                 Cross-encoder (rerank)
 query ─▶ [enc] ─▶ vector ┐
                           ├─ cosine               [enc](query + passage) ─▶ 1 logit
 passage ─▶ [enc] ─▶ vector ┘                      one forward pass per pair
 (embedded separately, fast, approximate)          (joint attention, slow, precise)
```

- Model: `ms-marco-MiniLM-L-6-v2`, local ONNX, in-process.
- `OnnxTextModel.forwardPairs()` tokenises `(query, passage)` **as one packed
  sequence** with token-type ids, so the model attends across the two — that is
  why it beats embedding similarity.
- The model's output is a **single raw logit per pair** (label dimension must be
  1, or it throws). Logits are returned **unchanged** — no softmax, no
  normalisation. The gate compares these exact numbers.
- Scored in batches of 16; the session and tokenizer are not thread-safe, so
  batches are synchronised.

`Candidate` is just `(SearchHit, rerankScore)`.

If reranking is disabled (`rag.rerank.enabled=false`, model bean absent), the
reranker is `null`, every candidate gets score `0.0`, and the pipeline runs in
**degraded mode** (see 3.6).

### 3.6 Relevance gate (`RelevanceGate`) — the answer/no-answer criterion

This is the main defence against confident nonsense. It compares **reranker
logits**, not cosine scores: cosine is not comparable across models or queries,
and "nearest" says nothing about whether a passage is close.

Two thresholds (`rag.retrieval`):

| Knob | Value | Role |
|---|---|---|
| `min-relevance` | 0.35 | the **primary gate**: the best single score must reach this |
| `min-chunk-relevance` | 0.15 | per-chunk floor: drop weak chunks from an answerable question |

Decision procedure on the raw scores:

1. `best = max(scores)`; `kept = count(score >= minChunkRelevance)`.
2. If `best < minRelevance` → **refuse**
   (`"best reranker score X is below min-relevance Y"`).
3. Else if `kept == 0` → **refuse**
   (`"no individual chunk reached min-chunk-relevance"`).
4. Else → **answerable**.

`minChunkRelevance` may not exceed `minRelevance` (constructor-enforced): the
per-chunk floor exists to trim context, not to be the primary gate.

**In degraded mode** there is no absolute floor at all: any non-empty candidate
set is answerable, ordered by dense score. The trace records
`"no reranker configured: dense ordering only, absolute relevance gate disabled"`.

For display only, `asRelevance(raw) = sigmoid(raw) = 1 / (1 + e^-raw)`. Stored in
`RetrievedChunk.relevance`. The gate itself never uses it.

### 3.7 Rank, then assemble context

`rankForContext()`:

- **With reranker**: drop chunks below `minChunkRelevance`, sort by rerank score
  descending, take `rag.retrieval.rerank-top-n` (6).
- **Degraded**: sort by dense score descending, take 6 (no floor exists).

`ContextAssembler.assemble()`:

- **Deduplicate** on lower-cased, whitespace-collapsed text. A near-identical
  passage is dropped with reason `"duplicate of an already selected chunk"`.
- **Token budget**: `rag.retrieval.context-token-budget` (6000), counted with the
  embedding tokenizer. A chunk that would exceed it is *skipped*, not truncated,
  and later smaller chunks may still fit.
- Selected chunks get stable labels **`S1`, `S2`, …** in order. These are the IDs
  the model is later required to cite, so a citation can be checked against the
  sources actually supplied.

If assembly ends up empty, retrieval refuses (`"no candidate survived context
assembly"`).

### 3.8 Outcome

`RetrievalOutcome` is either:

- **answerable** — `chunks` (with `S` ids, page ranges, scores) and a trace, or
- **refused** — empty chunks, a refusal message, and a trace.

Refusal messages are **fixed constants** (`AssistantMessages`), not model output,
because on refusal no model is called: the message cannot be argued with and
cannot drift. The general refusal is:

> "I couldn't find this in your organisation's documents, so I can't answer it.
> Try rephrasing, or ask about a topic covered by your uploaded documents."

Refusal reasons, in order of occurrence: input guard, retrieval/store failure
(`GENERIC_ERROR`), no candidates, gate below threshold, nothing survived assembly.

### 3.9 The trace (`RetrievalTrace`)

Every retrieval records what it did, so a wrong-looking answer can be explained
rather than guessed at:

- per-stage timings (`embedMillis`, `searchMillis`, `rerankMillis`),
- counts (`denseCandidates`, `rerankedCandidates`, `selectedChunks`),
- the thresholds used and the `bestRerankScore`,
- one `CandidateTrace` per candidate: chunk/document/title/pages, **dense score
  and dense rank**, **rerank score and rerank rank**, `selected`, and a
  `droppedReason`,
- the model IDs (`embeddingModel`, `rerankerModel`).

This is what the `/chat` console renders, and it is the raw material the
evaluation step will use to calibrate the thresholds.

---

## 4. Criteria at a glance

### Documents (ingestion)

| Question | Answer |
|---|---|
| Acceptable input | PDF with `%PDF-` magic bytes, ≤ 50 MB, ≤ 500 pages, has a text layer, not encrypted, parses |
| Embedding text | `doc_title. section_title. chunk_text` |
| Stored/quoted text | clean `chunk_text` only (prefix is never stored) |
| Chunk size | grow to 400 tokens; hard cap 480; top up to 48; ~57-token overlap |
| Chunk boundary | sentence-aware; word-boundary only if one sentence exceeds 480 |
| Chunk id | UUID v5 of `cid:document:version:index` (idempotent retries) |
| Searchable | only the active version (`active=true`) |

### Questions (retrieval)

| Question | Answer |
|---|---|
| Allowed input | ≤ 2000 chars, ≤ 30/min/user, no injection pattern |
| Candidate pool | top 30 by cosine, `cid_id` + `active` pre-filtered |
| Ranking | cross-encoder logit, batched 16 |
| Answerable when | `best ≥ 0.35` **and** at least one chunk `≥ 0.15` |
| Kept for context | rerank score `≥ 0.15`, top 6, deduplicated, ≤ 6000 tokens |
| Citation labels | `S1`, `S2`, … in context order |
| Otherwise | refuse with a fixed message, no model call |

> These numbers are **starting points, not calibrated values** (the config file
> says so). The evaluation/calibration step will sweep them against a golden set.

---

## 5. Isolation: the cid boundary

Enforced at four independent points, so no single bug leaks data:

1. **Every port takes a `CidContext`** — there is no cid-free overload, and
   `ChunkRecord` deliberately does **not** carry a cid; it comes from the context
   argument through a separate channel.
2. **The Qdrant filter is built inside `QdrantVectorStore`** from that argument,
   never accepted from a caller, and applied as a pre-filter.
3. **`RetrievalService.verifyCid()`** re-checks the cid on every returned hit
   against the caller, from the payload, and aborts fatally on mismatch.
4. **File storage** keys by cid as the first path segment and re-verifies on read.

Deletion is verified too: `DocumentService.delete()` counts remaining points and
throws if any survive, rather than assuming the delete worked.

---

## 6. Configuration reference

All knobs are in `RagProperties` / `src/main/resources/application.yml` under `rag.*`:

```yaml
rag:
  embedding:  { model-id: bge-base-en-v1.5, pooling: CLS, normalize: true,
                dimension: 768, max-sequence-length: 512, batch-size: 16 }
  rerank:     { enabled: true, model-id: ms-marco-MiniLM-L-6-v2,
                max-sequence-length: 512, batch-size: 16 }
  chunking:   { target-tokens: 400, max-tokens: 480, min-tokens: 48,
                overlap-percent: 12 }
  retrieval:  { dense-candidates: 30, rerank-top-n: 6, min-relevance: 0.35,
                min-chunk-relevance: 0.15, context-token-budget: 6000 }
  guard:      { max-question-chars: 2000, requests-per-minute-per-user: 30 }
  ingestion:  { max-file-bytes: 52428800, max-pages: 500 }
  qdrant:     { host: localhost, port: 6334, collection: chunks, use-tls: false }
  storage:    { local-root: data/files }
```

Models are fetched by `scripts/fetch-models.sh` into gitignored `models/`, and
the ML beans are `@Lazy` so the app can start without them (a missing model is
reported on first use, not at boot).

---

## 7. What is intentionally missing

- **Chat generation** — `/chat` is a retrieval console; the reply area is a
  disabled placeholder.
- **Threshold calibration** — the gate values are uncalibrated defaults.
- **Row-level security** in PostgreSQL, **Qdrant as second adapter**, seed data.
- **Queue/retry/durability** for ingestion — accepted for a PoC (R10).

---

*Source of truth for design rationale: `docs/decisions.md` (R1–R10). Source of
truth for behaviour: the `ingestion/**` and `retrieval/**` classes referenced
above.*

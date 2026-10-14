# CID-Scoped RAG PoC

A RAG chatbot where each company (a **cid**) chats only with its own uploaded
PDFs, gets cited answers, and gets a refusal when the documents don't support an
answer.

## Stack

| | |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 4.1.1 (Spring MVC, Spring Security, Spring Data JPA) + Spring AI 2.0.1 |
| Structured data | PostgreSQL 17 (Flyway migrations) |
| Vectors | Qdrant via Spring AI's `QdrantVectorStore` |
| Embeddings / reranker | Spring AI `TransformersEmbeddingModel` (`all-MiniLM-L6-v2`) + local ONNX cross-encoder (`ms-marco-MiniLM-L-6-v2`) |
| Chat model | Spring AI `ChatClient` over DeepSeek (`deepseek-chat`), streamed to the browser as server-sent events |
| Document parsing | Apache Tika via Spring AI's `TikaDocumentReader` |
| Views | JTE server-side templates |
| UI | Tailwind CSS (Play CDN) + htmx: a shared sidebar layout, boosted navigation, and native `<details>` for the collapsible retrieval list |
| Boilerplate | Lombok (`@Slf4j` for logging) |

## Module layout

Packages are organised by module, each with `entity` / `repository` / `service` /
`controller` where the layer applies.

```
com.example.ragpoc
├── cid/          Cid entity, repository, service, controller; CidContext
├── user/         User entity + Role, repository, service, controller
├── document/     Document + DocumentVersion entities, repositories, services,
│                 admin/content controllers
├── ingestion/    Synchronous ingestion: Tika reader, TextCleaner, DocumentChunker
├── retrieval/    Provider seams and pipeline:
│                 ├── embedding/  ContextualEmbeddingModel decorator
│                 ├── rerank/     Reranker port, OnnxReranker, DenseOnlyReranker
│                 ├── search/     CidScopedVectorSearch, DocumentIndex
│                 ├── onnx/       Cross-encoder + tensor helpers
│                 ├── math/       Pure vector helpers
│                 └── service/    RetrievalService, guard, gate, context assembler
├── chat/         Grounded generation: ChatService, prompts, ChatEvent/
│                 ChatTimings, SSE controller
├── audit/        AuditEvent entity, repository, service
├── storage/      Local file storage, keyed by cid
├── security/     Login, principal, cid argument resolver
├── config/       Properties and bean wiring
└── web/          Shared view formatting, ShellModel (layout attributes),
                  exception handling
```

Templates live in `src/main/jte`: `layout/app.jte` is the sidebar shell every
authenticated page calls, and `chat/panel.jte` + `chat/timings.jte` are
fragments (rendered to strings for the SSE stream and included by the pages).

## Setup

```bash
# 1. Configuration. Optional: the defaults match docker-compose.yml.
cp .env.example .env

# 2. Local ONNX models (gitignored). Exported with HuggingFace Optimum into a
#    local venv (.venv-models); needed for ingestion/retrieval.
./scripts/fetch-models.sh

# 3. PostgreSQL + Qdrant. Pass the env file explicitly so it does not depend on
#    Compose's implicit .env lookup.
docker compose --env-file .env up -d

# 4. Chat provider. Put your DeepSeek key in .env (DEEPSEEK_API_KEY=sk-...),
#    or leave it commented out: the app starts and retrieval works either way,
#    and only the generated answer reports the missing key.
#
# 5. Run (Spring Boot does not read .env, so export it first).
set -a; source .env; set +a
./mvnw spring-boot:run        # :8080
```

`JAVA_HOME` must point at JDK 21 (JDK 21–25 accepted by the enforcer).

### Environment and Docker notes

- `.env` is read by Compose for `${...}` interpolation (ports, credentials). The
  services also declare `env_file: .env`, so the same values are injected into
  the containers. To be explicit regardless of the working directory, use
  `docker compose --env-file .env ...`.
- The host Postgres port defaults to **5433**, but on a machine where that is
  taken, set `POSTGRES_PORT` in `.env` (this repo currently uses **5434**).
  Likewise `QDRANT_GRPC_PORT` (default 6334).
- **Use one Docker daemon.** If both Docker Desktop (`desktop-linux`) and a system
  daemon (`default`) are present, do not run this stack under both: they cannot
  publish the same host ports, and the second `up` fails with
  `address already in use`. Plain `docker compose` uses the context shown by
  `docker context show`; keep all `up`/`down` commands on that context (avoid
  `sudo docker compose` or `--context default` unless you switch consistently).

## Demo accounts

The `V2__demo_data.sql` migration seeds two companies, each with an admin and a
regular user. All four use the password **`demo1234`**.

| Company | Email | Role |
| --- | --- | --- |
| BRAC Bank | `admin@bracbank.test` | `CID_ADMIN` |
| BRAC Bank | `user@bracbank.test` | `USER` |
| City Bank | `admin@citybank.test` | `CID_ADMIN` |
| City Bank | `user@citybank.test` | `USER` |

Sign in at `/login`; a `CID_ADMIN` sees the Documents and Users pages, a `USER`
sees Chat and Profile. Remove the migration (or move it to a dev-only Flyway
location) before using this in production.

## Build and test

```bash
./mvnw test      # unit tests only, no containers, no models
./mvnw verify    # everything: unit + Testcontainers (Postgres + Qdrant)
```

Tests that need the local ONNX models are skipped with an explicit assumption if
`scripts/fetch-models.sh` has not been run, so a fresh clone still builds.

## How isolation is enforced

1. **The cid comes from the principal**, never from a request body, query string,
   header or model output. It is built once per request from the authenticated
   user's own database row.
2. **`CidScopedVectorSearch` builds the cid filter itself** from the `CidContext`
   argument. Callers cannot omit it, and other companies' points are filtered out
   before ranking by the vector store.
3. **Returned chunks are verified** against the caller's cid; a mismatch aborts
   the request and writes a `SECURITY_CID_MISMATCH` audit event. The query
   already filters, so this is the independent backstop for a filter bug.
4. **URL identifiers are resolved with `WHERE id = ? AND cid_id = ?`**, and a miss
   returns 404 rather than 403, so another company's ids cannot be probed.

Ingestion runs synchronously on the upload request, so the cid is passed
 explicitly to `IngestionService`; there is no ambient thread state to inherit.

## Pages

| Route | Purpose |
| --- | --- |
| `/` | Home, links to the other pages |
| `/chat` | Chat: streamed answers grounded in the cid's documents, with a panel showing the retrieved candidates, selected context and per-stage timings |
| `/admin/documents` | Upload / replace / delete documents and list ingested ones (cid admins) |
| `/admin/users` | List and create users (cid admins) |
| `/cid/profile` | Edit the organisation's name, address and email |
| `/documents/{id}` | Download the PDF |

## Known gaps

- **No seeded data.** Create a cid and user directly (or via the `/admin/users`
  screen once one admin exists) to log in.
- **Thresholds are uncalibrated.** `rag.rerank.onnx.min-relevance` and
  `min-chunk-relevance` are starting values, and they are per provider.
- **Dense-only retrieval.** Keyword/hybrid search and RRF are not implemented;
  retrieval is guard → dense search → rerank → gate → context.
- **No page-number citations.** Tika returns one text stream, so citations carry
  the document and section, not a page range.
- **No chat memory yet.** Each question is retrieved and answered fresh; a
  follow-up has no context from the previous turn, and no session or message
  rows are written. Spec §13 is the next step.
- **Chat needs a DeepSeek key.** `DEEPSEEK_API_KEY` must be set for answers; the
  rest of the app runs without it.
- **Tailwind comes from the Play CDN**, so the browser needs internet for
  styling. The server is unaffected. Compiling Tailwind instead is a `<link>`
  change plus a build step.
- **No row-level security** yet; isolation is enforced in the application layer.

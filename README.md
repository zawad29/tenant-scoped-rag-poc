# CID-Scoped RAG PoC

A RAG chatbot where each company (a **cid**) chats only with its own uploaded
PDFs, gets cited answers, and gets a refusal when the documents don't support an
answer.

## Stack

| | |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 4.1.1 (Spring MVC, Spring Security, Spring Data JPA) |
| Structured data | PostgreSQL 17 (Flyway migrations) |
| Vectors | Qdrant |
| Embeddings / reranker | Local ONNX (`bge-base-en-v1.5`, `ms-marco-MiniLM-L-6-v2`) |
| Views | JTE server-side templates |
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
├── ingestion/    Synchronous ingestion pipeline: PDF/text/chunk services
├── retrieval/    Embedding/reranker services, QdrantVectorStore, RetrievalService,
│                 guard, gate, context assembler
├── chat/         Retrieval console controller (no generation yet)
├── audit/        AuditEvent entity, repository, service
├── storage/      Local file storage, keyed by cid
├── security/     Login, principal, cid argument resolver
├── config/       Properties and bean wiring
└── web/          Shared view formatting and exception handling
```

## Setup

```bash
# 1. Configuration. Optional: the defaults match docker-compose.yml.
cp .env.example .env

# 2. Local ONNX models (~500 MB, gitignored). Needed for ingestion/retrieval.
./scripts/fetch-models.sh

# 3. PostgreSQL + Qdrant. Pass the env file explicitly so it does not depend on
#    Compose's implicit .env lookup.
docker compose --env-file .env up -d

# 4. Run (Spring Boot does not read .env, so export it first).
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
2. **`QdrantVectorStore` builds the cid filter itself** from the `CidContext`
   argument. Callers cannot omit it, and other companies' points are filtered out
   before ranking.
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
| `/chat` | Retrieval console: ask a question and see the candidates, ranks and scores. Reply generation is not implemented; the assistant area is a disabled placeholder. |
| `/admin/documents` | Upload / replace / delete documents and list ingested ones (cid admins) |
| `/admin/users` | List and create users (cid admins) |
| `/cid/profile` | Edit the organisation's name, address and email |
| `/documents/{id}`, `/documents/{id}/pages/{n}` | Download the PDF, render a page as PNG |

## Known gaps

- **No seeded data.** Create a cid and user directly (or via the `/admin/users`
  screen once one admin exists) to log in.
- **Thresholds are uncalibrated.** `rag.retrieval.min-relevance` and
  `min-chunk-relevance` are starting values.
- **Dense-only retrieval.** Keyword/hybrid search and RRF were removed during the
  Qdrant migration; retrieval is embed → dense search → rerank → gate.
- **Chat replies are not implemented.** `/chat` is a retrieval console that shows
  what retrieval found; the assistant reply area is a disabled placeholder.
- **No row-level security** yet; isolation is enforced in the application layer.

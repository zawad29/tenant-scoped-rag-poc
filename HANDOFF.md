# Handoff

**Repo:** `tenant-scoped-rag-poc` (branch `restructure/cid-jpa-qdrant`).
**Stack:** Boot 4.1.1 · Spring Data JPA · PostgreSQL 17 · Qdrant · local ONNX ·
Java 21.

## State

The project was restructured from a hand-written `JdbcClient`/pgvector design
into a module-per-domain, Spring Data JPA + Qdrant design, and the company
concept was renamed from "tenant" to **cid**.

- `./mvnw verify` is green: 65 unit tests + 17 integration tests
  (Testcontainers PostgreSQL + Qdrant).
- ONNX-dependent tests are skipped when `models/` is absent.

## What changed

- **Modules:** `cid`, `user`, `document`, `ingestion`, `retrieval`, `audit`,
  `storage`, `security`.
- **Persistence:** Spring Data JPA repositories replace every `JdbcClient` /
  `NamedParameterJdbcTemplate` usage; there is no hand-written SQL left.
- **Ingestion:** synchronous on the upload request. The database queue, poller,
  worker threads and `ingestion_job` table were removed.
- **Vectors:** pgvector and the `chunk` table are gone; chunks (text + payload +
  vector) live in Qdrant. Dense-only retrieval.
- **Naming:** `tenant` → `cid`, `tenant_id` → `cid_id`, role `TENANT_ADMIN` →
  `CID_ADMIN`; `Cid` has `name`, `address`, `email`.
- **Trimmed:** ports/adapters, RRF, keyword search, ArchUnit rules, Spring AI.

## Not built

- Chat generation. `/chat` is a retrieval console: it shows candidates, ranks
  and scores, with the reply area disabled.
- Evaluation harness / threshold calibration.
- Row-level security.
- Seed data.

## Useful commands

```bash
docker compose up -d            # Postgres (5433) + Qdrant (6333/6334)
./scripts/fetch-models.sh       # ONNX models (needed for ingestion/retrieval)
./mvnw verify                   # the gate
./mvnw spring-boot:run
```

Developed DB schema changed completely: a stale local database must be recreated
with `docker compose down -v` before first run.

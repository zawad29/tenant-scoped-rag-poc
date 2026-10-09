-- Runs once, on first initialisation of the Postgres data volume.
--
-- The vector extension is what makes the pgvector adapter possible; pg_trgm
-- backs the fuzzy side of keyword search. Flyway migrations also issue
-- CREATE EXTENSION IF NOT EXISTS as a safety net, but doing it here keeps the
-- app's DB role non-superuser (a requirement for the row-level-security phase).
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

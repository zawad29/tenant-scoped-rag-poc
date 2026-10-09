-- Tenants and users.
--
-- tenant_id is NOT NULL on app_user: the specification's model has every user
-- belonging to exactly one tenant, and there is deliberately no tenant-free
-- account that could act across tenants.

CREATE TABLE tenant (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL,
    status     text        NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_status_known CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT tenant_name_not_blank CHECK (length(btrim(name)) > 0)
);

-- Tenant names need not be unique (two organisations may share a display
-- name), but they must be findable case-insensitively on the admin screens.
CREATE INDEX tenant_name_lower_idx ON tenant (lower(name));

CREATE TABLE app_user (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    email         text        NOT NULL,
    password_hash text        NOT NULL,
    role          text        NOT NULL,
    enabled       boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_user_role_known CHECK (role IN ('TENANT_ADMIN', 'USER')),
    CONSTRAINT app_user_email_not_blank CHECK (length(btrim(email)) > 0)
);

-- Login is by email alone, without choosing a tenant first, so email is unique
-- across the whole system. Case-insensitivity matters: addresses are
-- case-preserving but not case-sensitive in practice, and a duplicate that
-- differed only in case would create two accounts for one person.
CREATE UNIQUE INDEX app_user_email_unique_idx ON app_user (lower(email));
CREATE INDEX app_user_tenant_idx ON app_user (tenant_id);

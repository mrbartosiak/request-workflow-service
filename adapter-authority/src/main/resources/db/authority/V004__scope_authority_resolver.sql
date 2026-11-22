CREATE TABLE request_authority.scope_resolver_operator (
    operator_id uuid PRIMARY KEY,
    credential_digest bytea NOT NULL UNIQUE CHECK (octet_length(credential_digest) = 32)
);

CREATE TABLE request_authority.scope_resolver_grant (
    operator_id uuid NOT NULL REFERENCES request_authority.scope_resolver_operator (operator_id),
    organization text COLLATE "C" NOT NULL REFERENCES request_authority.scope_genesis (organization),
    operation text NOT NULL CHECK (operation IN ('CONFIRM', 'ABORT')),
    PRIMARY KEY (operator_id, organization, operation)
);

CREATE TABLE request_authority.scope_resolver_audit (
    audit_id uuid PRIMARY KEY,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    operator_id uuid REFERENCES request_authority.scope_resolver_operator (operator_id),
    organization text COLLATE "C" NOT NULL,
    attempt_id uuid NOT NULL,
    operation text NOT NULL CHECK (operation IN ('CONFIRM', 'ABORT')),
    authorized boolean NOT NULL CHECK (NOT authorized OR operator_id IS NOT NULL),
    terminal_state text CHECK (terminal_state IN ('COMMITTED', 'ABORTED')),
    outcome text CHECK (outcome IN ('PROJECTED', 'DEFERRED')),
    CHECK (authorized OR (terminal_state IS NULL AND outcome IS NULL)),
    CHECK (outcome IS DISTINCT FROM 'PROJECTED' OR terminal_state IS NOT NULL)
);

CREATE TABLE request_authority.scope_journal_confirmation (
    attempt_id uuid PRIMARY KEY REFERENCES request_authority.scope_journal_attempt (attempt_id),
    evidence_digest text COLLATE "C" NOT NULL CHECK (evidence_digest ~ '^[0-9a-f]{64}$')
);

CREATE VIEW request_authority.scope_resolver_delta_check AS
    SELECT attempt_id, delta_digest, sha256(exact_delta) = delta_digest AS exact
    FROM request_authority.scope_journal_delta;

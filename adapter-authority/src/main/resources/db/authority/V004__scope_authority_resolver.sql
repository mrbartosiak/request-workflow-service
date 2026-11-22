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
    CHECK (terminal_state IS NULL OR
           (operation = 'CONFIRM' AND terminal_state = 'COMMITTED') OR
           (operation = 'ABORT' AND terminal_state = 'ABORTED')),
    CHECK (outcome IS DISTINCT FROM 'PROJECTED' OR terminal_state IS NOT NULL)
);

CREATE TABLE request_authority.scope_journal_terminal (
    attempt_id uuid PRIMARY KEY REFERENCES request_authority.scope_journal_attempt (attempt_id),
    audit_id uuid UNIQUE REFERENCES request_authority.scope_resolver_audit (audit_id)
);

CREATE TABLE request_authority.scope_journal_confirmation (
    attempt_id uuid PRIMARY KEY REFERENCES request_authority.scope_journal_attempt (attempt_id),
    evidence_digest text COLLATE "C" NOT NULL CHECK (evidence_digest ~ '^[0-9a-f]{64}$')
);

CREATE VIEW request_authority.scope_resolver_delta_check AS
    SELECT attempt_id, delta_digest, sha256(exact_delta) = delta_digest AS exact
    FROM request_authority.scope_journal_delta;

-- V003 remains the legacy B09 deployment. Once this guard is installed, its old
-- state-column grants can no longer bypass the audited V004 terminal path.
ALTER TABLE request_authority.scope_journal_attempt ALTER COLUMN state SET DEFAULT 'REGISTERED';

CREATE FUNCTION request_authority.guard_scope_attempt_transition() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $guard$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state = 'REGISTERED' AND NEW.sequence IS NULL THEN
            RETURN NEW;
        END IF;
    ELSIF ROW(NEW.attempt_id, NEW.organization, NEW.generation, NEW.workflow_transaction,
              NEW.before_version, NEW.after_version, NEW.delta_digest)
          IS NOT DISTINCT FROM
          ROW(OLD.attempt_id, OLD.organization, OLD.generation, OLD.workflow_transaction,
              OLD.before_version, OLD.after_version, OLD.delta_digest) THEN
        IF OLD.state = 'REGISTERED' AND NEW.state IN ('REGISTERED', 'PENDING') AND NEW.sequence IS NOT NULL THEN
            NEW.state := 'PENDING';
            RETURN NEW;
        END IF;

        IF NEW.sequence IS NOT DISTINCT FROM OLD.sequence
            AND ((NEW.state = 'ABORTED' AND OLD.state IN ('REGISTERED', 'PENDING'))
                 OR (NEW.state = 'COMMITTED' AND OLD.state = 'PENDING'))
            AND current_user = (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = TG_RELID) THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'Scope attempt transition is not permitted';
END;
$guard$;

REVOKE ALL ON FUNCTION request_authority.guard_scope_attempt_transition() FROM PUBLIC;

CREATE TRIGGER scope_attempt_transition
    BEFORE INSERT OR UPDATE ON request_authority.scope_journal_attempt
    FOR EACH ROW EXECUTE FUNCTION request_authority.guard_scope_attempt_transition();

-- The migration's table lock seals the predecessor boundary. Only already closed
-- B09 aborts qualify for the legacy prefix; unresolved rows require an audit.
INSERT INTO request_authority.scope_journal_terminal (attempt_id)
    SELECT attempt_id FROM request_authority.scope_journal_attempt WHERE state = 'ABORTED';

CREATE VIEW request_authority.scope_journal_terminal_check AS
    SELECT t.attempt_id
    FROM request_authority.scope_journal_terminal t
    JOIN request_authority.scope_journal_attempt j USING (attempt_id)
    LEFT JOIN request_authority.scope_resolver_audit a ON a.audit_id = t.audit_id
    WHERE (t.audit_id IS NULL AND j.state = 'ABORTED')
       OR (a.authorized AND a.attempt_id = j.attempt_id AND a.organization = j.organization
           AND a.terminal_state = j.state);

CREATE FUNCTION request_authority.apply_scope_terminal(audit uuid, expected_state text) RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $terminal$
    WITH authorized AS MATERIALIZED (
        SELECT a.audit_id, a.organization, a.attempt_id,
               CASE a.operation WHEN 'CONFIRM' THEN 'COMMITTED' ELSE 'ABORTED' END AS decision
        FROM request_authority.scope_resolver_audit a
        JOIN request_authority.scope_resolver_grant g USING (operator_id, organization, operation)
        WHERE a.audit_id = audit AND a.authorized AND a.terminal_state IS NULL AND a.outcome IS NULL
        FOR UPDATE OF a
    ), applied AS (
        UPDATE request_authority.scope_journal_attempt j SET state = a.decision
        FROM authorized a
        WHERE j.organization = a.organization AND j.attempt_id = a.attempt_id AND j.state = expected_state
          AND ((a.decision = 'COMMITTED' AND j.state = 'PENDING')
               OR (a.decision = 'ABORTED' AND j.state IN ('REGISTERED', 'PENDING')))
        RETURNING a.audit_id, j.attempt_id, j.state
    ), recorded AS (
        UPDATE request_authority.scope_resolver_audit a SET terminal_state = applied.state
        FROM applied
        WHERE a.audit_id = applied.audit_id
        RETURNING a.attempt_id, a.audit_id
    )
    INSERT INTO request_authority.scope_journal_terminal (attempt_id, audit_id)
    SELECT attempt_id, audit_id FROM recorded
    RETURNING true;
$terminal$;

REVOKE ALL ON FUNCTION request_authority.apply_scope_terminal(uuid, text) FROM PUBLIC;

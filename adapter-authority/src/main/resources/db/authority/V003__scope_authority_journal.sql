CREATE TABLE request_authority.scope_journal_head (
    organization text COLLATE "C" PRIMARY KEY REFERENCES request_authority.scope_genesis (organization),
    last_sequence bigint NOT NULL CHECK (last_sequence >= 0)
);

CREATE TABLE request_authority.scope_journal_attempt (
    attempt_id uuid PRIMARY KEY,
    organization text COLLATE "C" NOT NULL REFERENCES request_authority.scope_journal_head (organization),
    generation bigint NOT NULL REFERENCES request_authority.workflow_certificate (generation),
    workflow_transaction xid8 NOT NULL,
    before_version bigint NOT NULL CHECK (before_version > 0),
    after_version bigint NOT NULL CHECK (after_version - before_version = 1),
    delta_digest bytea NOT NULL CHECK (octet_length(delta_digest) = 32),
    sequence bigint CHECK (sequence > 0),
    state text NOT NULL CHECK (state IN ('REGISTERED', 'PENDING', 'ABORTED', 'COMMITTED')),
    payload_attempt_id uuid GENERATED ALWAYS AS (CASE WHEN sequence IS NOT NULL THEN attempt_id END) STORED,
    UNIQUE (organization, sequence),
    UNIQUE (attempt_id, delta_digest),
    CHECK (state = 'ABORTED' OR (sequence IS NULL) = (state = 'REGISTERED'))
);

CREATE UNIQUE INDEX scope_journal_one_unresolved
    ON request_authority.scope_journal_attempt (organization)
    WHERE state IN ('REGISTERED', 'PENDING');

CREATE TABLE request_authority.scope_journal_delta (
    attempt_id uuid PRIMARY KEY,
    delta_digest bytea NOT NULL CHECK (octet_length(delta_digest) = 32),
    exact_delta bytea NOT NULL CHECK (octet_length(exact_delta) > 0 AND sha256(exact_delta) = delta_digest),
    FOREIGN KEY (attempt_id, delta_digest) REFERENCES request_authority.scope_journal_attempt (attempt_id, delta_digest)
);

ALTER TABLE request_authority.scope_journal_attempt
    ADD CONSTRAINT scope_journal_required_delta FOREIGN KEY (payload_attempt_id)
    REFERENCES request_authority.scope_journal_delta (attempt_id) DEFERRABLE INITIALLY DEFERRED;

INSERT INTO request_authority.scope_journal_head (organization, last_sequence)
    SELECT organization, 0 FROM request_authority.scope_genesis;

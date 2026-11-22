CREATE TABLE requests.scope_authority_witness (
    organization text COLLATE "C" NOT NULL REFERENCES requests.policy_guard (organization),
    sequence bigint NOT NULL CHECK (sequence > 0),
    attempt_id uuid NOT NULL UNIQUE,
    writer_id uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    before_version bigint NOT NULL CHECK (before_version > 0),
    after_version bigint NOT NULL CHECK (after_version - before_version = 1),
    delta_digest text COLLATE "C" NOT NULL CHECK (delta_digest ~ '^[0-9a-f]{64}$'),
    workflow_transaction xid8 NOT NULL,
    PRIMARY KEY (organization, sequence)
);

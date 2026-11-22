CREATE TABLE requests.scope_authority_projection (
    organization text COLLATE "C" PRIMARY KEY REFERENCES requests.policy_guard (organization),
    writer_id uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    sequence bigint NOT NULL CHECK (sequence >= 0),
    policy_version bigint NOT NULL CHECK (policy_version > 0),
    prefix_digest text COLLATE "C" NOT NULL CHECK (prefix_digest ~ '^[0-9a-f]{64}$')
);

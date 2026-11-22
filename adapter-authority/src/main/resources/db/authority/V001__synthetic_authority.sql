CREATE TABLE request_authority.writer_epoch (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    writer_id uuid NOT NULL,
    generation bigint NOT NULL UNIQUE CHECK (generation > 0)
);

CREATE TABLE request_authority.scope_genesis (
    organization text COLLATE "C" PRIMARY KEY CHECK (organization <> ''),
    policy_version bigint NOT NULL CHECK (policy_version > 0),
    policy_digest text COLLATE "C" NOT NULL CHECK (policy_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE request_authority.creation_counter (
    generation bigint PRIMARY KEY REFERENCES request_authority.writer_epoch (generation),
    last_ordinal bigint NOT NULL CHECK (last_ordinal >= 0)
);

CREATE TABLE request_authority.creation_reservation (
    attempt_id uuid PRIMARY KEY,
    organization text COLLATE "C" NOT NULL REFERENCES request_authority.scope_genesis (organization),
    request_ref text COLLATE "C" NOT NULL UNIQUE CHECK (request_ref ~ '^[A-Za-z0-9_-]{32}$'),
    created_epoch bigint NOT NULL REFERENCES request_authority.writer_epoch (generation),
    created_ordinal bigint NOT NULL CHECK (created_ordinal > 0),
    UNIQUE (created_epoch, created_ordinal)
);

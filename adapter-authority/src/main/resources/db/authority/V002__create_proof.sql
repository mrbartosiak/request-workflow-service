CREATE TABLE request_authority.workflow_certificate (
    generation bigint PRIMARY KEY REFERENCES request_authority.writer_epoch (generation),
    server_address text COLLATE "C" NOT NULL CHECK (server_address <> ''),
    server_port integer NOT NULL CHECK (server_port BETWEEN 1 AND 65535),
    database_name text COLLATE "C" NOT NULL CHECK (database_name <> ''),
    server_started_at timestamptz NOT NULL
);

CREATE TABLE request_authority.release_prepared (
    correlation_id uuid PRIMARY KEY,
    organization text COLLATE "C" NOT NULL REFERENCES request_authority.scope_genesis (organization),
    key_digest bytea NOT NULL CHECK (octet_length(key_digest) = 32),
    result_ref text COLLATE "C" NOT NULL REFERENCES request_authority.creation_reservation (request_ref),
    result_revision bigint NOT NULL CHECK (result_revision = 1),
    generation bigint NOT NULL REFERENCES request_authority.workflow_certificate (generation)
);

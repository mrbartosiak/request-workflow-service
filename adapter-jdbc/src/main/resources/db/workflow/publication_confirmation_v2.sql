CREATE TABLE requests.publication_confirmation (
    attempt_id uuid PRIMARY KEY REFERENCES requests.publication_witness (attempt_id),
    writer_id uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    organization text COLLATE "C" NOT NULL,
    request_type text NOT NULL CHECK (request_type IN ('HR_TICKET', 'BUG_REPORT', 'INTERNAL_REQUEST')),
    ref text COLLATE "C" NOT NULL UNIQUE,
    namespace text COLLATE "C" NOT NULL CHECK (namespace = 'initial-requests'),
    publication_id bigint NOT NULL CHECK (publication_id > 0),
    result_revision bigint NOT NULL CHECK (result_revision > 1),
    workflow_transaction xid8 NOT NULL,
    receipt_identity text COLLATE "C" NOT NULL CHECK (receipt_identity ~ '^[0-9a-f]{64}$'),
    projected_by name NOT NULL DEFAULT session_user,
    projected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (namespace, publication_id),
    FOREIGN KEY (organization, ref) REFERENCES requests.request (organization, ref)
);

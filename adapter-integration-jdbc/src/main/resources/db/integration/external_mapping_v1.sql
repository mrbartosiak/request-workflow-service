-- The Create fact and finalized original receipt are committed together and remain immutable.
CREATE VIEW requests.external_mapping_create_evidence AS
    SELECT r.organization, r.request_type, r.ref, r.created_epoch, r.created_ordinal
    FROM requests.request r
    JOIN requests.transition_fact f ON f.organization = r.organization AND f.ref = r.ref
    JOIN requests.operation_receipt o ON o.organization = r.organization AND o.result_ref = r.ref
    WHERE f.sequence = 1 AND f.revision = 1 AND f.action = 'CREATE' AND f.from_state IS NULL
      AND f.to_state = 'CREATED' AND o.finalized AND o.result_action = 'CREATE'
      AND o.result_revision = 1 AND o.result_state = 'CREATED' AND o.original_status = 201
      AND o.key_generation = r.created_epoch AND f.xmin = o.xmin;

CREATE TABLE requests.external_mapping (
    binding_id uuid PRIMARY KEY,
    source_system text COLLATE "C" NOT NULL CHECK (source_system <> ''),
    source_tenant text COLLATE "C" NOT NULL CHECK (source_tenant <> ''),
    identifier_type text COLLATE "C" NOT NULL CHECK (identifier_type <> ''),
    external_id bytea NOT NULL,
    external_id_digest bytea GENERATED ALWAYS AS (sha256(external_id)) STORED,
    organization text COLLATE "C" NOT NULL,
    request_type text COLLATE "C" NOT NULL CHECK (request_type IN ('HR_TICKET', 'BUG_REPORT', 'INTERNAL_REQUEST')),
    ref text COLLATE "C" NOT NULL,
    created_epoch bigint NOT NULL CHECK (created_epoch > 0),
    created_ordinal bigint NOT NULL CHECK (created_ordinal > 0),
    publication_namespace text COLLATE "C",
    publication_id bigint,
    writer_id uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    original_effect boolean NOT NULL,
    workflow_transaction xid8 NOT NULL DEFAULT pg_current_xact_id(),
    FOREIGN KEY (organization, ref) REFERENCES requests.request (organization, ref),
    CHECK ((publication_namespace IS NULL AND publication_id IS NULL)
        OR (publication_namespace IS NOT NULL AND publication_namespace = 'initial-requests'
            AND publication_id IS NOT NULL AND publication_id > 0))
);

CREATE INDEX external_mapping_digest ON requests.external_mapping (external_id_digest);

CREATE VIEW requests.external_mapping_commit_evidence AS
    SELECT binding_id, source_system, source_tenant, identifier_type, external_id, external_id_digest,
           organization, request_type, ref, created_epoch, created_ordinal, publication_namespace,
           publication_id, writer_id, generation
    FROM requests.external_mapping m
    WHERE m.original_effect
      AND m.xmin::text::numeric = mod(m.workflow_transaction::text::numeric, 4294967296)
      AND pg_xact_status(m.workflow_transaction) = 'committed';

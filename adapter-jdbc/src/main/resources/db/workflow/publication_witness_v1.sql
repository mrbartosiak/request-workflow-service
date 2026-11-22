CREATE TABLE requests.publication_witness (
    attempt_id uuid PRIMARY KEY,
    writer_id uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    organization text COLLATE "C" NOT NULL,
    request_type text NOT NULL CHECK (request_type IN ('HR_TICKET', 'BUG_REPORT', 'INTERNAL_REQUEST')),
    ref text COLLATE "C" NOT NULL UNIQUE,
    namespace text COLLATE "C" NOT NULL CHECK (namespace = 'initial-requests'),
    publication_id bigint NOT NULL CHECK (publication_id > 0),
    receipt_issuer text COLLATE "C" NOT NULL,
    receipt_principal text COLLATE "C" NOT NULL,
    receipt_token_id text COLLATE "C" NOT NULL,
    result_revision bigint NOT NULL CHECK (result_revision > 1),
    workflow_transaction xid8 NOT NULL,
    UNIQUE (namespace, publication_id),
    UNIQUE (generation, workflow_transaction),
    FOREIGN KEY (organization, ref) REFERENCES requests.request (organization, ref),
    FOREIGN KEY (receipt_issuer, organization, receipt_principal, receipt_token_id)
        REFERENCES requests.operation_receipt (issuer, organization, principal, token_id)
);

-- The original-workflow observer can inspect proof without reading request names or bodies.
CREATE VIEW requests.publication_target_evidence AS
    SELECT organization, request_type, ref, state, revision FROM requests.request;

CREATE VIEW requests.publication_commit_evidence AS
    SELECT w.attempt_id, w.writer_id, w.generation, w.organization, w.request_type, w.ref,
           w.namespace, w.publication_id, w.result_revision, w.workflow_transaction,
           w.receipt_issuer, w.receipt_principal, w.receipt_token_id
    FROM requests.publication_witness w
    JOIN requests.request r USING (organization, ref, request_type)
    JOIN requests.operation_receipt o
      ON o.organization = w.organization AND o.issuer = w.receipt_issuer
      AND o.principal = w.receipt_principal AND o.token_id = w.receipt_token_id
    JOIN requests.transition_fact f
      ON f.organization = r.organization AND f.ref = r.ref AND f.sequence = r.last_sequence
    WHERE w.xmin::text::numeric = mod(w.workflow_transaction::text::numeric, 4294967296)
      AND r.xmin = w.xmin AND o.xmin = w.xmin AND f.xmin = w.xmin
      AND r.state = 'PUBLISHED' AND r.revision = w.result_revision
      AND r.publication_namespace = w.namespace AND r.publication_id = w.publication_id
      AND o.result_action = 'PUBLISH' AND o.result_ref = w.ref AND o.result_state = 'PUBLISHED'
      AND o.result_revision = w.result_revision AND o.key_generation = w.generation
      AND o.publication_namespace = w.namespace AND o.publication_id = w.publication_id
      AND o.original_status = 200 AND f.action = 'PUBLISH' AND f.revision = w.result_revision
      AND f.to_state = 'PUBLISHED';

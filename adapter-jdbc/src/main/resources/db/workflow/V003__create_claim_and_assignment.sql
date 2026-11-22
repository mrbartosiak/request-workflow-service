ALTER TABLE requests.operation_receipt
    ALTER COLUMN result_action DROP NOT NULL,
    ALTER COLUMN result_ref DROP NOT NULL,
    ALTER COLUMN result_revision DROP NOT NULL,
    ALTER COLUMN result_state DROP NOT NULL,
    ALTER COLUMN original_status DROP NOT NULL,
    ADD COLUMN finalized boolean GENERATED ALWAYS AS (result_ref IS NOT NULL) STORED,
    ADD COLUMN must_finalize boolean NOT NULL DEFAULT true CHECK (must_finalize),
    ADD CONSTRAINT receipt_claim_shape CHECK (
        (finalized AND result_action IS NOT NULL AND result_revision IS NOT NULL
            AND result_state IS NOT NULL AND original_status IS NOT NULL)
        OR (NOT finalized AND result_action IS NULL AND result_revision IS NULL
            AND result_state IS NULL AND original_status IS NULL
            AND publication_namespace IS NULL AND publication_id IS NULL)
    ),
    ADD CONSTRAINT receipt_finalization_target UNIQUE (issuer, organization, principal, token_id, finalized);

-- A tentative row can win the primary key inside the transaction. The deferred reference
-- requires a finalized row with that precise key at commit. A finalized row references itself.
ALTER TABLE requests.operation_receipt
    ADD CONSTRAINT receipt_must_finalize
    FOREIGN KEY (issuer, organization, principal, token_id, must_finalize)
    REFERENCES requests.operation_receipt (issuer, organization, principal, token_id, finalized)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE requests.request_assignment (
    organization text COLLATE "C" NOT NULL,
    ref text COLLATE "C" NOT NULL,
    assignee_person_id text COLLATE "C" NOT NULL CHECK (assignee_person_id <> ''),
    assignment_version bigint NOT NULL CHECK (assignment_version > 0),
    PRIMARY KEY (organization, ref),
    FOREIGN KEY (organization, ref) REFERENCES requests.request (organization, ref)
);

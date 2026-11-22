CREATE TABLE requests.request (
    ref text COLLATE "C" PRIMARY KEY,
    organization text COLLATE "C" NOT NULL REFERENCES requests.policy_guard (organization),
    request_type text NOT NULL,
    originating_person_id text NOT NULL,
    name text NOT NULL,
    created_epoch bigint NOT NULL,
    created_ordinal bigint NOT NULL,
    state text NOT NULL,
    revision bigint NOT NULL,
    last_sequence bigint NOT NULL,
    publication_namespace text COLLATE "C",
    publication_id bigint,
    CONSTRAINT request_scope_unique UNIQUE (organization, ref),
    CONSTRAINT request_creation_order_unique UNIQUE (created_epoch, created_ordinal),
    CONSTRAINT request_publication_unique UNIQUE (publication_namespace, publication_id),
    CONSTRAINT request_identity_present CHECK (ref <> '' AND originating_person_id <> '' AND name <> ''),
    CONSTRAINT request_type CHECK (request_type IN ('HR_TICKET', 'BUG_REPORT', 'INTERNAL_REQUEST')),
    CONSTRAINT request_state CHECK (state IN ('CREATED', 'VERIFIED', 'ACCEPTED', 'DELETED', 'REJECTED', 'PUBLISHED')),
    CONSTRAINT request_counters CHECK (last_sequence > 0 AND revision >= last_sequence),
    CONSTRAINT request_creation_order_positive CHECK (created_epoch > 0 AND created_ordinal > 0),
    CONSTRAINT request_publication_pair CHECK (
        (state = 'PUBLISHED' AND publication_namespace IS NOT NULL
            AND publication_namespace = 'initial-requests' AND publication_id IS NOT NULL AND publication_id > 0)
        OR (state <> 'PUBLISHED' AND publication_namespace IS NULL AND publication_id IS NULL)
    )
);

CREATE TABLE requests.request_body (
    ref text COLLATE "C" PRIMARY KEY,
    organization text COLLATE "C" NOT NULL,
    body text,
    retention_status text NOT NULL DEFAULT 'RETAINED',
    CONSTRAINT body_request_scope FOREIGN KEY (organization, ref) REFERENCES requests.request (organization, ref),
    CONSTRAINT body_payload_retention CHECK (
        (retention_status = 'RETAINED' AND body IS NOT NULL AND body <> '')
        OR (retention_status = 'PURGED' AND body IS NULL)
    )
);

CREATE TABLE requests.transition_fact (
    ref text COLLATE "C" NOT NULL,
    organization text COLLATE "C" NOT NULL,
    sequence bigint NOT NULL,
    revision bigint NOT NULL,
    action text NOT NULL,
    from_state text,
    to_state text NOT NULL,
    recorded_epoch_second bigint NOT NULL,
    recorded_nano integer NOT NULL,
    actor_ref text NOT NULL,
    previous_sequence bigint GENERATED ALWAYS AS (CASE WHEN sequence > 1 THEN sequence - 1 END) STORED,
    CONSTRAINT fact_key PRIMARY KEY (ref, sequence),
    CONSTRAINT fact_request_scope FOREIGN KEY (organization, ref) REFERENCES requests.request (organization, ref),
    CONSTRAINT fact_revision_unique UNIQUE (ref, revision),
    CONSTRAINT fact_scoped_state_unique UNIQUE (organization, ref, sequence, to_state),
    CONSTRAINT fact_previous_state FOREIGN KEY (organization, ref, previous_sequence, from_state)
        REFERENCES requests.transition_fact (organization, ref, sequence, to_state),
    CONSTRAINT fact_counters CHECK (sequence > 0 AND revision >= sequence),
    CONSTRAINT fact_actor_present CHECK (actor_ref <> ''),
    CONSTRAINT fact_recorded_instant CHECK (
        recorded_epoch_second BETWEEN -31557014167219200 AND 31556889864403199
        AND recorded_nano BETWEEN 0 AND 999999999
    ),
    CONSTRAINT fact_transition CHECK (
        (sequence = 1 AND revision = 1 AND action = 'CREATE'
            AND from_state IS NULL AND to_state = 'CREATED')
        OR (sequence > 1 AND from_state IS NOT NULL AND (
            (action = 'DELETE' AND from_state = 'CREATED' AND to_state = 'DELETED')
            OR (action = 'VERIFY' AND from_state = 'CREATED' AND to_state = 'VERIFIED')
            OR (action = 'ACCEPT' AND from_state = 'VERIFIED' AND to_state = 'ACCEPTED')
            OR (action = 'REJECT' AND from_state IN ('VERIFIED', 'ACCEPTED') AND to_state = 'REJECTED')
            OR (action = 'PUBLISH' AND from_state = 'ACCEPTED' AND to_state = 'PUBLISHED')
        ))
    )
);

ALTER TABLE requests.request ADD CONSTRAINT request_history_tail
    FOREIGN KEY (organization, ref, last_sequence, state)
    REFERENCES requests.transition_fact (organization, ref, sequence, to_state)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE requests.operation_receipt (
    issuer text COLLATE "C" NOT NULL,
    organization text COLLATE "C" NOT NULL,
    principal text COLLATE "C" NOT NULL,
    token_id text COLLATE "C" NOT NULL,
    intent_digest_version integer NOT NULL,
    intent_digest bytea NOT NULL,
    key_generation bigint NOT NULL,
    expires_epoch_second bigint NOT NULL,
    expires_nano integer NOT NULL,
    result_action text NOT NULL,
    result_ref text COLLATE "C" NOT NULL,
    result_revision bigint NOT NULL,
    result_state text NOT NULL,
    publication_namespace text COLLATE "C",
    publication_id bigint,
    original_status smallint NOT NULL,
    CONSTRAINT operation_receipt_key PRIMARY KEY (issuer, organization, principal, token_id),
    CONSTRAINT receipt_result_scope FOREIGN KEY (organization, result_ref) REFERENCES requests.request (organization, ref),
    CONSTRAINT receipt_key_present CHECK (issuer <> '' AND principal <> '' AND token_id <> ''),
    CONSTRAINT receipt_digest CHECK (intent_digest_version > 0 AND octet_length(intent_digest) > 0),
    CONSTRAINT receipt_counters CHECK (key_generation > 0 AND result_revision > 0),
    CONSTRAINT receipt_expiry CHECK (
        expires_epoch_second BETWEEN -31557014167219200 AND 31556889864403199
        AND expires_nano BETWEEN 0 AND 999999999
    ),
    CONSTRAINT receipt_result CHECK (
        (result_action = 'CREATE' AND original_status = 201 AND result_state = 'CREATED' AND result_revision = 1)
        OR (original_status = 200 AND (
            (result_action = 'EDIT' AND result_state IN ('CREATED', 'VERIFIED'))
            OR (result_action = 'DELETE' AND result_state = 'DELETED')
            OR (result_action = 'VERIFY' AND result_state = 'VERIFIED')
            OR (result_action = 'ACCEPT' AND result_state = 'ACCEPTED')
            OR (result_action = 'REJECT' AND result_state = 'REJECTED')
            OR (result_action = 'PUBLISH' AND result_state = 'PUBLISHED')
        ))
    ),
    CONSTRAINT receipt_publication_pair CHECK (
        (result_action = 'PUBLISH' AND publication_namespace IS NOT NULL
            AND publication_namespace = 'initial-requests' AND publication_id IS NOT NULL AND publication_id > 0)
        OR (result_action <> 'PUBLISH' AND publication_namespace IS NULL AND publication_id IS NULL)
    )
);

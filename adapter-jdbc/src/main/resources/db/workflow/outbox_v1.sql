CREATE TABLE requests.outbox (
    destination text COLLATE "C" NOT NULL CHECK (destination ~ '^[a-z][a-z0-9-]{0,63}$'),
    tenant text COLLATE "C" NOT NULL,
    effect_id text COLLATE "C" NOT NULL CHECK (effect_id ~ '^[0-9a-f]{64}$'),
    trigger_slot text COLLATE "C" NOT NULL CHECK (trigger_slot = 'publish.reference'),
    request_ref text COLLATE "C" NOT NULL,
    request_revision bigint NOT NULL CHECK (request_revision > 1),
    operation text COLLATE "C" NOT NULL CHECK (operation = 'PUBLISH'),
    schema_version integer NOT NULL,
    projection_ref text COLLATE "C" NOT NULL,
    scheduled_epoch_second bigint NOT NULL CHECK (
        scheduled_epoch_second BETWEEN -31557014167219200 AND 31556889864403199
    ),
    scheduled_nano integer NOT NULL CHECK (scheduled_nano BETWEEN 0 AND 999999999),
    status text NOT NULL DEFAULT 'READY' CHECK (status = 'READY'),
    PRIMARY KEY (destination, tenant, effect_id),
    UNIQUE (destination, tenant, request_ref, request_revision, trigger_slot),
    FOREIGN KEY (tenant, request_ref) REFERENCES requests.request (organization, ref),
    FOREIGN KEY (request_ref, request_revision) REFERENCES requests.transition_fact (ref, revision),
    CONSTRAINT outbox_projection_contract CHECK (
        (schema_version = 1 AND projection_ref = 'publish-request')
        OR (schema_version = 2 AND projection_ref = 'published-request-reference')
    )
);

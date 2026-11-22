CREATE SCHEMA synthetic_delivery;

CREATE TABLE synthetic_delivery.endpoint (
    singleton boolean PRIMARY KEY CHECK (singleton),
    identity uuid NOT NULL UNIQUE
);

INSERT INTO synthetic_delivery.endpoint VALUES (true, gen_random_uuid());

CREATE TABLE synthetic_delivery.delivery_key (
    destination text COLLATE "C" NOT NULL,
    tenant text COLLATE "C" NOT NULL,
    effect_id text COLLATE "C" NOT NULL,
    request_ref text COLLATE "C" NOT NULL,
    request_revision bigint NOT NULL,
    contract text NOT NULL CHECK (contract IN ('SINGLE_EFFECT', 'FENCEABLE', 'UNSAFE')),
    fenced_through bigint NOT NULL DEFAULT 0 CHECK (fenced_through >= 0),
    PRIMARY KEY (destination, tenant, effect_id),
    UNIQUE (destination, tenant, effect_id, contract)
);

CREATE TABLE synthetic_delivery.delivery_attempt (
    destination text COLLATE "C" NOT NULL,
    tenant text COLLATE "C" NOT NULL,
    effect_id text COLLATE "C" NOT NULL,
    attempt_no bigint NOT NULL CHECK (attempt_no > 0),
    contract text NOT NULL,
    state text NOT NULL CHECK (state IN ('PENDING', 'APPLIED', 'DEDUPED', 'FENCED', 'TRANSIENT', 'PERMANENT')),
    effect_ref uuid UNIQUE,
    PRIMARY KEY (destination, tenant, effect_id, attempt_no),
    UNIQUE (destination, tenant, effect_id, attempt_no, effect_ref),
    FOREIGN KEY (destination, tenant, effect_id, contract)
        REFERENCES synthetic_delivery.delivery_key (destination, tenant, effect_id, contract),
    CHECK ((state = 'APPLIED') = (effect_ref IS NOT NULL))
);

CREATE UNIQUE INDEX synthetic_single_effect ON synthetic_delivery.delivery_attempt (destination, tenant, effect_id)
    WHERE contract = 'SINGLE_EFFECT' AND effect_ref IS NOT NULL;

CREATE TABLE synthetic_delivery.delivery_observation (
    proof_id uuid PRIMARY KEY,
    destination text COLLATE "C" NOT NULL,
    tenant text COLLATE "C" NOT NULL,
    effect_id text COLLATE "C" NOT NULL,
    attempt_no bigint NOT NULL CHECK (attempt_no > 0),
    observation text NOT NULL,
    exclusion text NOT NULL,
    effect_ref uuid,
    applied_attempt bigint,
    fenced_through bigint NOT NULL,
    terminal_attempts bigint[] NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    FOREIGN KEY (destination, tenant, effect_id)
        REFERENCES synthetic_delivery.delivery_key (destination, tenant, effect_id),
    FOREIGN KEY (destination, tenant, effect_id, applied_attempt, effect_ref)
        REFERENCES synthetic_delivery.delivery_attempt (destination, tenant, effect_id, attempt_no, effect_ref),
    CHECK ((effect_ref IS NULL) = (applied_attempt IS NULL)),
    CHECK (cardinality(terminal_attempts) = 0 OR observation IN ('TRANSIENT', 'PERMANENT'))
);

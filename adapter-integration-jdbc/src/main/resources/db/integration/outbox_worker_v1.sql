ALTER TABLE requests.outbox
    ADD COLUMN last_attempt_no bigint CHECK (last_attempt_no > 0),
    ADD COLUMN deferred_until timestamptz,
    ADD COLUMN cancelled boolean NOT NULL DEFAULT false,
    DROP CONSTRAINT outbox_status_check,
    ADD CONSTRAINT outbox_status_check CHECK
        (status IN ('READY', 'CLAIMED', 'ARMED', 'UNKNOWN', 'CONFIRMED', 'SUPPRESSED')),
    ADD CONSTRAINT outbox_attempt_required CHECK
        (status = 'READY' OR last_attempt_no IS NOT NULL),
    ADD CONSTRAINT outbox_defer_ready CHECK
        (deferred_until IS NULL OR status = 'READY');

CREATE TABLE requests.outbox_attempt (
    destination text COLLATE "C" NOT NULL,
    tenant text COLLATE "C" NOT NULL,
    effect_id text COLLATE "C" NOT NULL,
    attempt_no bigint NOT NULL CHECK (attempt_no > 0),
    claim_token uuid NOT NULL UNIQUE,
    writer_id uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    claimed_at timestamptz NOT NULL,
    lease_expires_at timestamptz NOT NULL CHECK (lease_expires_at > claimed_at),
    state text NOT NULL CHECK (state IN
        ('CLAIMED', 'ARMED', 'UNKNOWN', 'CONFIRMED', 'SUPPRESSED', 'DEFERRED', 'EXPIRED_UNSENT')),
    policy_version bigint CHECK (policy_version > 0),
    publication_attempt uuid,
    armed_at timestamptz,
    finished_at timestamptz,
    confirmation_ref uuid UNIQUE,
    PRIMARY KEY (destination, tenant, effect_id, attempt_no),
    FOREIGN KEY (destination, tenant, effect_id) REFERENCES requests.outbox (destination, tenant, effect_id),
    CHECK ((state IN ('ARMED', 'UNKNOWN', 'CONFIRMED')
            AND armed_at IS NOT NULL AND policy_version IS NOT NULL AND publication_attempt IS NOT NULL)
        OR (state NOT IN ('ARMED', 'UNKNOWN', 'CONFIRMED')
            AND armed_at IS NULL AND policy_version IS NULL AND publication_attempt IS NULL)),
    CHECK ((finished_at IS NULL) = (state IN ('CLAIMED', 'ARMED'))),
    CHECK ((confirmation_ref IS NOT NULL) = (state = 'CONFIRMED'))
);

ALTER TABLE requests.outbox
    ADD CONSTRAINT outbox_last_attempt
    FOREIGN KEY (destination, tenant, effect_id, last_attempt_no)
    REFERENCES requests.outbox_attempt (destination, tenant, effect_id, attempt_no);

CREATE INDEX outbox_ready_schedule ON requests.outbox
    (scheduled_epoch_second, scheduled_nano, destination, tenant, effect_id) WHERE status = 'READY';

CREATE INDEX outbox_attempt_lease ON requests.outbox_attempt (lease_expires_at)
    WHERE state IN ('CLAIMED', 'ARMED');

CREATE FUNCTION requests.lock_dispatch_guard(text) RETURNS TABLE (policy_version bigint)
    LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog
    AS 'SELECT g.policy_version FROM requests.policy_guard g WHERE g.organization = $1 FOR SHARE';

REVOKE ALL ON FUNCTION requests.lock_dispatch_guard(text) FROM PUBLIC;

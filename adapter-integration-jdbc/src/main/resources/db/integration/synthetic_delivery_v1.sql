ALTER TABLE requests.outbox
    DROP CONSTRAINT outbox_status_check,
    ADD CONSTRAINT outbox_status_check CHECK (status IN
        ('READY', 'CLAIMED', 'ARMED', 'UNKNOWN', 'CONFIRMED', 'SUPPRESSED',
         'ACCEPTED_PENDING', 'RETRYABLE', 'FAILED_PERMANENT', 'QUARANTINED')),
    DROP CONSTRAINT outbox_defer_ready,
    ADD CONSTRAINT outbox_defer_ready CHECK (deferred_until IS NULL OR status IN ('READY', 'RETRYABLE')),
    ADD COLUMN retry_uncertain boolean NOT NULL DEFAULT false,
    ADD COLUMN delivery_endpoint uuid;

ALTER TABLE requests.outbox_attempt
    DROP CONSTRAINT outbox_attempt_state_check,
    ADD CONSTRAINT outbox_attempt_state_check CHECK (state IN
        ('CLAIMED', 'ARMED', 'UNKNOWN', 'CONFIRMED', 'SUPPRESSED', 'DEFERRED', 'EXPIRED_UNSENT',
         'ACCEPTED_PENDING', 'RETRYABLE', 'FAILED_PERMANENT', 'QUARANTINED')),
    ADD CONSTRAINT outbox_attempt_arm_check CHECK (
        (state IN ('ARMED', 'UNKNOWN', 'CONFIRMED', 'ACCEPTED_PENDING', 'RETRYABLE', 'FAILED_PERMANENT', 'QUARANTINED')
            OR (state = 'SUPPRESSED' AND armed_at IS NOT NULL))
        = (armed_at IS NOT NULL)
        AND (armed_at IS NOT NULL) = (policy_version IS NOT NULL)
        AND (armed_at IS NOT NULL) = (publication_attempt IS NOT NULL)),
    ADD COLUMN delivery_observation uuid,
    ADD COLUMN remote_effect uuid,
    ADD COLUMN remote_attempt_no bigint,
    ADD COLUMN recovery_token uuid,
    ADD CONSTRAINT outbox_attempt_remote_application CHECK (
        (remote_effect IS NULL) = (remote_attempt_no IS NULL)
        AND (remote_effect IS NULL OR (state = 'CONFIRMED' AND delivery_observation = confirmation_ref
            AND remote_attempt_no BETWEEN 1 AND attempt_no)));

CREATE INDEX outbox_retry_schedule ON requests.outbox
    (scheduled_epoch_second, scheduled_nano, destination, tenant, effect_id) WHERE status = 'RETRYABLE';

CREATE TABLE requests.outbox_delivery_recovery (
    recovery_id uuid PRIMARY KEY,
    destination text COLLATE "C" NOT NULL,
    tenant text COLLATE "C" NOT NULL,
    effect_id text COLLATE "C" NOT NULL,
    attempt_no bigint NOT NULL,
    operator_issuer text NOT NULL,
    operator_principal text NOT NULL,
    writer_id uuid NOT NULL,
    generation bigint NOT NULL,
    policy_version bigint NOT NULL,
    publication_attempt uuid NOT NULL,
    action text NOT NULL CHECK (action IN ('RECONCILE', 'RETRY', 'FENCE_AND_RETRY', 'SUPPRESS', 'FENCE_AND_SUPPRESS')),
    started_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    disposition text,
    observation_ref uuid,
    finished_at timestamptz,
    FOREIGN KEY (destination, tenant, effect_id, attempt_no)
        REFERENCES requests.outbox_attempt (destination, tenant, effect_id, attempt_no),
    CHECK ((finished_at IS NULL) = (disposition IS NULL))
);

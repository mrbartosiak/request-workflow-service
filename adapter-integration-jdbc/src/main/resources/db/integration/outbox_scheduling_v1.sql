CREATE TABLE requests.outbox_dispatch_lane (
    destination text COLLATE "C" NOT NULL,
    tenant text COLLATE "C" NOT NULL,
    lock_key integer GENERATED ALWAYS AS IDENTITY UNIQUE,
    resume_at timestamptz,
    last_claimed_at timestamptz,
    PRIMARY KEY (destination, tenant)
);

ALTER TABLE requests.outbox_attempt
    ADD COLUMN scheduling_slot smallint CHECK (scheduling_slot BETWEEN 1 AND 2),
    ADD COLUMN scheduling_invocation uuid,
    ADD COLUMN invocation_quiesced_at timestamptz,
    ADD CONSTRAINT outbox_scheduled_invocation_check CHECK (
        (scheduling_invocation IS NOT NULL) = (scheduling_slot IS NOT NULL AND armed_at IS NOT NULL)
        AND (invocation_quiesced_at IS NULL OR scheduling_invocation IS NOT NULL));

CREATE UNIQUE INDEX outbox_scheduled_occupied_slot ON requests.outbox_attempt (destination, tenant, scheduling_slot)
    WHERE scheduling_invocation IS NOT NULL AND invocation_quiesced_at IS NULL;

CREATE INDEX outbox_scheduled_scope ON requests.outbox
    (destination, tenant, scheduled_epoch_second, scheduled_nano, effect_id)
    WHERE status IN ('READY', 'RETRYABLE');

CREATE INDEX outbox_scheduled_active ON requests.outbox_attempt
    (destination, tenant, scheduling_slot, lease_expires_at)
    WHERE state IN ('CLAIMED', 'ARMED');

CREATE INDEX outbox_scheduled_arms ON requests.outbox_attempt
    (destination, tenant, effect_id, armed_at, attempt_no) WHERE armed_at IS NOT NULL;

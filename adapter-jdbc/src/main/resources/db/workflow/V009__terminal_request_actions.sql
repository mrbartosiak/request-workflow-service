ALTER TABLE requests.scope_grant_override
    DROP CONSTRAINT override_grant_shape,
    ADD CONSTRAINT override_grant_shape CHECK (
        (permission = 'KEY_ISSUE' AND request_type = '')
        OR (permission IN (
                'CREATE', 'CREATE_RESULT', 'ROW_READ', 'FIELD_READ_BODY', 'FIELD_READ_NAME',
                'CONTEXT_EDIT', 'CONTEXT_VERIFY', 'CONTEXT_ACCEPT',
                'CONTEXT_REJECT', 'CONTEXT_DELETE', 'CONTEXT_PUBLISH',
                'EXECUTE_EDIT', 'EXECUTE_VERIFY', 'EXECUTE_ACCEPT',
                'EXECUTE_REJECT', 'EXECUTE_DELETE', 'EXECUTE_PUBLISH',
                'RESULT_READ_EDIT', 'RESULT_READ_VERIFY', 'RESULT_READ_ACCEPT',
                'RESULT_READ_DELETE', 'RESULT_READ_REJECT', 'DELETED_METADATA'
            ) AND request_type IN ('HR_TICKET', 'BUG_REPORT', 'INTERNAL_REQUEST'))
    );

CREATE TABLE requests.transition_reason (
    organization text COLLATE "C" NOT NULL,
    ref text COLLATE "C" NOT NULL,
    sequence bigint NOT NULL,
    terminal_state text NOT NULL CHECK (terminal_state IN ('DELETED', 'REJECTED')),
    reason text,
    retention_status text NOT NULL DEFAULT 'RETAINED',
    CONSTRAINT reason_fact_key PRIMARY KEY (ref, sequence),
    CONSTRAINT reason_terminal_fact FOREIGN KEY (organization, ref, sequence, terminal_state)
        REFERENCES requests.transition_fact (organization, ref, sequence, to_state),
    CONSTRAINT reason_payload_retention CHECK (
        (retention_status = 'RETAINED' AND reason IS NOT NULL AND octet_length(reason) BETWEEN 1 AND 2048)
        OR (retention_status = 'PURGED' AND reason IS NULL)
    )
);

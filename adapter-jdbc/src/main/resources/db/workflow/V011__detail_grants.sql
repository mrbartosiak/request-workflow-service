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
                'RESULT_READ_DELETE', 'RESULT_READ_REJECT', 'DELETED_METADATA',
                'RESULT_READ_PUBLISH', 'FIELD_READ_PUBLICATION_ID',
                'FIELD_READ_TYPE', 'FIELD_READ_STATE', 'FIELD_READ_REVISION'
            ) AND request_type IN ('HR_TICKET', 'BUG_REPORT', 'INTERNAL_REQUEST'))
    );

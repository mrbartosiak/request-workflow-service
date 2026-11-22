CREATE TABLE requests.scope_grant_override (
    organization text COLLATE "C" NOT NULL REFERENCES requests.policy_guard (organization),
    issuer text COLLATE "C" NOT NULL CHECK (issuer <> ''),
    principal text COLLATE "C" NOT NULL CHECK (principal <> ''),
    permission text COLLATE "C" NOT NULL,
    request_type text COLLATE "C" NOT NULL,
    allowed boolean NOT NULL,
    applied_version bigint NOT NULL CHECK (applied_version > 1),
    PRIMARY KEY (organization, issuer, principal, permission, request_type),
    CONSTRAINT override_grant_shape CHECK (
        (permission = 'KEY_ISSUE' AND request_type = '')
        OR (permission IN ('CREATE', 'CREATE_RESULT')
            AND request_type IN ('HR_TICKET', 'BUG_REPORT', 'INTERNAL_REQUEST'))
    )
);

CREATE TABLE requests.request_visibility_fact (
    organization text COLLATE "C" NOT NULL,
    ref text COLLATE "C" NOT NULL,
    fact_version bigint NOT NULL CHECK (fact_version > 0),
    assignee_only boolean NOT NULL,
    field_mask integer NOT NULL CHECK (field_mask BETWEEN 0 AND 63),
    applied_version bigint NOT NULL CHECK (applied_version > fact_version),
    PRIMARY KEY (organization, ref),
    FOREIGN KEY (organization, ref) REFERENCES requests.request (organization, ref)
);

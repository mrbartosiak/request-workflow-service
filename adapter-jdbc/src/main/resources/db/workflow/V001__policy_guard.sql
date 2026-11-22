CREATE TABLE requests.policy_guard (
    organization text COLLATE "C" PRIMARY KEY,
    policy_version bigint NOT NULL,
    CONSTRAINT guard_organization_present CHECK (organization <> ''),
    CONSTRAINT guard_version_positive CHECK (policy_version > 0)
);

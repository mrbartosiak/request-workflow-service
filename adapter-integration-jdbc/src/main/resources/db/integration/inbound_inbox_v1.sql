CREATE TABLE requests.inbound_inbox (
    source_system text COLLATE "C" NOT NULL CHECK (source_system ~ '^[a-z][a-z0-9-]{0,63}$'),
    source_tenant text COLLATE "C" NOT NULL CHECK (source_tenant ~ '^[a-z][a-z0-9-]{0,63}$'),
    identity_domain text COLLATE "C" NOT NULL CHECK (identity_domain ~ '^[a-z][a-z0-9-]{0,63}$'),
    source_identity bytea NOT NULL CHECK (octet_length(source_identity) BETWEEN 1 AND 256),
    declared_type text COLLATE "C" NOT NULL CHECK (declared_type IN ('STATUS_CHANGED', 'DETAILS_CHANGED')),
    organization text COLLATE "C" NOT NULL,
    request_type text COLLATE "C" NOT NULL CHECK (request_type IN ('HR_TICKET', 'BUG_REPORT', 'INTERNAL_REQUEST')),
    ref text COLLATE "C" NOT NULL,
    source_version bigint NOT NULL CHECK (source_version > 0),
    target_revision bigint NOT NULL CHECK (target_revision > 0),
    semantic_digest text COLLATE "C" NOT NULL CHECK (semantic_digest ~ '^[0-9a-f]{64}$'),
    workflow_transaction xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY (source_system, source_tenant, identity_domain, source_identity),
    FOREIGN KEY (organization, ref) REFERENCES requests.request (organization, ref)
);

CREATE UNIQUE INDEX inbound_target_source_version ON requests.inbound_inbox
    (source_system, source_tenant, identity_domain, organization, request_type, ref, source_version DESC);

CREATE TABLE requests.inbound_processing (
    source_system text COLLATE "C" NOT NULL,
    source_tenant text COLLATE "C" NOT NULL,
    identity_domain text COLLATE "C" NOT NULL,
    source_identity bytea NOT NULL,
    identifier_type text COLLATE "C" NOT NULL CHECK (identifier_type ~ '^[a-z][a-z0-9-]{0,63}$'),
    external_id bytea NOT NULL CHECK (octet_length(external_id) BETWEEN 1 AND 256),
    envelope_version integer NOT NULL DEFAULT 1 CHECK (envelope_version = 1),
    workflow_transaction xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY (source_system, source_tenant, identity_domain, source_identity),
    FOREIGN KEY (source_system, source_tenant, identity_domain, source_identity)
        REFERENCES requests.inbound_inbox (source_system, source_tenant, identity_domain, source_identity)
);

ALTER TABLE requests.inbound_inbox ADD CONSTRAINT inbound_inbox_requires_processing
    FOREIGN KEY (source_system, source_tenant, identity_domain, source_identity)
    REFERENCES requests.inbound_processing (source_system, source_tenant, identity_domain, source_identity)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE requests.inbound_quarantine (
    source_system text COLLATE "C" NOT NULL,
    source_tenant text COLLATE "C" NOT NULL,
    identity_domain text COLLATE "C" NOT NULL,
    source_identity bytea NOT NULL,
    declared_type text COLLATE "C" NOT NULL CHECK (declared_type IN ('STATUS_CHANGED', 'DETAILS_CHANGED')),
    organization text COLLATE "C" NOT NULL,
    request_type text COLLATE "C" NOT NULL CHECK (request_type IN ('HR_TICKET', 'BUG_REPORT', 'INTERNAL_REQUEST')),
    ref text COLLATE "C" NOT NULL,
    source_version bigint NOT NULL CHECK (source_version > 0),
    target_revision bigint NOT NULL CHECK (target_revision > 0),
    semantic_digest text COLLATE "C" NOT NULL CHECK (semantic_digest ~ '^[0-9a-f]{64}$'),
    workflow_transaction xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY (source_system, source_tenant, identity_domain, source_identity, declared_type, organization,
                 request_type, ref, source_version, target_revision, semantic_digest),
    FOREIGN KEY (source_system, source_tenant, identity_domain, source_identity)
        REFERENCES requests.inbound_inbox (source_system, source_tenant, identity_domain, source_identity),
    FOREIGN KEY (organization, ref) REFERENCES requests.request (organization, ref)
);

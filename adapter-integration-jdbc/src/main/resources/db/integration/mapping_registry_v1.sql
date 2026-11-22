CREATE TABLE request_authority.external_mapping_binding (
    binding_id uuid PRIMARY KEY,
    source_system text COLLATE "C" NOT NULL CHECK (source_system <> ''),
    source_tenant text COLLATE "C" NOT NULL CHECK (source_tenant <> ''),
    identifier_type text COLLATE "C" NOT NULL CHECK (identifier_type <> ''),
    external_id bytea NOT NULL,
    external_id_digest bytea GENERATED ALWAYS AS (sha256(external_id)) STORED,
    organization text COLLATE "C" NOT NULL REFERENCES request_authority.scope_genesis (organization),
    request_type text COLLATE "C" NOT NULL CHECK (request_type IN ('HR_TICKET', 'BUG_REPORT', 'INTERNAL_REQUEST')),
    ref text COLLATE "C" NOT NULL REFERENCES request_authority.creation_reservation (request_ref),
    created_epoch bigint NOT NULL CHECK (created_epoch > 0),
    created_ordinal bigint NOT NULL CHECK (created_ordinal > 0),
    publication_namespace text COLLATE "C",
    publication_id bigint,
    writer_id uuid NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    server_address text COLLATE "C" NOT NULL,
    server_port integer NOT NULL CHECK (server_port BETWEEN 1 AND 65535),
    database_name text COLLATE "C" NOT NULL,
    server_started_at timestamptz NOT NULL,
    CHECK ((publication_namespace IS NULL AND publication_id IS NULL)
        OR (publication_namespace IS NOT NULL AND publication_namespace = 'initial-requests'
            AND publication_id IS NOT NULL AND publication_id > 0))
);

CREATE INDEX external_mapping_binding_digest ON request_authority.external_mapping_binding (external_id_digest);

CREATE TABLE request_authority.external_mapping_quarantine (
    binding_id uuid PRIMARY KEY REFERENCES request_authority.external_mapping_binding (binding_id),
    detected_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE FUNCTION request_authority.reserve_external_mapping(
    p_binding uuid, p_source text, p_tenant text, p_type text, p_external bytea,
    p_org text, p_request_type text, p_ref text, p_epoch bigint, p_ordinal bigint,
    p_namespace text, p_publication bigint, p_writer uuid, p_generation bigint)
RETURNS TABLE(outcome text, id uuid, original_writer uuid, original_generation bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    digest bytea := sha256(p_external);
    selected request_authority.external_mapping_binding%ROWTYPE;
    lineage request_authority.workflow_certificate%ROWTYPE;
BEGIN
    SELECT c.* INTO lineage FROM request_authority.workflow_certificate c
      JOIN request_authority.writer_epoch w USING (generation)
      WHERE w.writer_id = p_writer AND w.generation = p_generation;
    IF NOT FOUND OR NOT EXISTS (
        SELECT 1 FROM request_authority.creation_reservation r
        WHERE r.request_ref = p_ref COLLATE "C" AND r.organization = p_org COLLATE "C"
          AND r.created_epoch = p_epoch AND r.created_ordinal = p_ordinal
    ) THEN
        RETURN;
    END IF;
    IF p_publication IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM request_authority.publication_attempt p
        WHERE p.state = 'COMMITTED' AND p.ref = p_ref COLLATE "C" AND p.organization = p_org COLLATE "C"
          AND p.request_type = p_request_type COLLATE "C" AND p.namespace = p_namespace COLLATE "C"
          AND p.publication_id = p_publication
    ) THEN
        RETURN;
    END IF;

    -- A digest bucket serializes contenders. Identity always uses exact scope and bytes.
    PERFORM pg_advisory_xact_lock(1919250806, hashtext(encode(digest, 'hex')));
    SELECT * INTO selected FROM request_authority.external_mapping_binding b
      WHERE b.external_id_digest = digest AND b.external_id = p_external
        AND b.source_system = p_source COLLATE "C" AND b.source_tenant = p_tenant COLLATE "C"
        AND b.identifier_type = p_type COLLATE "C";
    IF NOT FOUND THEN
        INSERT INTO request_authority.external_mapping_binding (
            binding_id, source_system, source_tenant, identifier_type, external_id, organization,
            request_type, ref, created_epoch, created_ordinal, publication_namespace, publication_id,
            writer_id, generation, server_address, server_port, database_name, server_started_at
        ) VALUES (
            p_binding, p_source, p_tenant, p_type, p_external, p_org, p_request_type, p_ref, p_epoch, p_ordinal,
            p_namespace, p_publication, p_writer, p_generation, lineage.server_address, lineage.server_port,
            lineage.database_name, lineage.server_started_at
        );
        RETURN QUERY SELECT 'RESERVED'::text, p_binding, p_writer, p_generation;
        RETURN;
    END IF;
    IF ROW(selected.organization, selected.request_type, selected.ref, selected.created_epoch,
           selected.created_ordinal, selected.publication_namespace, selected.publication_id)
        IS DISTINCT FROM ROW(p_org COLLATE "C", p_request_type COLLATE "C", p_ref COLLATE "C",
                             p_epoch, p_ordinal, p_namespace COLLATE "C", p_publication) THEN
        INSERT INTO request_authority.external_mapping_quarantine (binding_id) VALUES (selected.binding_id)
          ON CONFLICT (binding_id) DO NOTHING;
    END IF;
    IF EXISTS (SELECT 1 FROM request_authority.external_mapping_quarantine q
               WHERE q.binding_id = selected.binding_id) THEN
        RETURN QUERY SELECT 'QUARANTINED'::text, NULL::uuid, NULL::uuid, NULL::bigint;
    ELSE
        RETURN QUERY SELECT 'EQUIVALENT'::text, selected.binding_id, selected.writer_id, selected.generation;
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION request_authority.reserve_external_mapping(
    uuid, text, text, text, bytea, text, text, text, bigint, bigint, text, bigint, uuid, bigint) FROM PUBLIC;

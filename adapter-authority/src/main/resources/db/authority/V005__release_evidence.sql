CREATE TABLE request_authority.release_evidence_principal (
    login_role name PRIMARY KEY,
    input text COLLATE "C" NOT NULL CHECK (input IN ('PREPARATION', 'HANDOFF', 'CLIENT', 'READ'))
);

CREATE TABLE request_authority.release_evidence_scope (
    login_role name NOT NULL REFERENCES request_authority.release_evidence_principal (login_role),
    organization text COLLATE "C" NOT NULL REFERENCES request_authority.scope_genesis (organization),
    PRIMARY KEY (login_role, organization)
);

CREATE TABLE request_authority.release_result_prepared (
    correlation_id uuid PRIMARY KEY,
    organization text COLLATE "C" NOT NULL REFERENCES request_authority.scope_genesis (organization),
    result_digest bytea NOT NULL CHECK (octet_length(result_digest) = 32),
    result_ref text COLLATE "C" NOT NULL CHECK (result_ref <> ''),
    result_revision bigint NOT NULL CHECK (result_revision > 0),
    result_generation bigint NOT NULL CHECK (result_generation > 0),
    release_generation bigint NOT NULL CHECK (release_generation >= result_generation)
);

CREATE TABLE request_authority.release_handoff_proved (
    correlation_id uuid PRIMARY KEY REFERENCES request_authority.release_result_prepared (correlation_id)
);

CREATE TABLE request_authority.release_client_acknowledged (
    correlation_id uuid PRIMARY KEY REFERENCES request_authority.release_result_prepared (correlation_id)
);

CREATE FUNCTION request_authority.release_evidence_permitted(p_scope text, p_input text)
RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM request_authority.release_evidence_principal p
        JOIN request_authority.release_evidence_scope s USING (login_role)
        WHERE p.login_role = session_user AND p.input = p_input AND s.organization = p_scope
    )
$function$;

CREATE FUNCTION request_authority.prepare_release(
    p_id uuid, p_scope text, p_digest bytea, p_ref text, p_revision bigint,
    p_result_generation bigint, p_release_generation bigint, p_writer uuid, p_version bigint, p_policy_digest text
)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF NOT request_authority.release_evidence_permitted(p_scope, 'PREPARATION') OR NOT EXISTS (
        SELECT 1 FROM request_authority.scope_genesis g, request_authority.writer_epoch w
        JOIN request_authority.workflow_certificate c USING (generation)
        WHERE g.organization = p_scope AND g.policy_version = p_version AND g.policy_digest = p_policy_digest
          AND w.writer_id = p_writer AND w.generation = p_release_generation
    ) THEN
        RETURN false;
    END IF;

    INSERT INTO request_authority.release_result_prepared
        (correlation_id, organization, result_digest, result_ref, result_revision, result_generation, release_generation)
    SELECT p_id, p_scope, p_digest, p_ref, p_revision, p_result_generation, p_release_generation
    WHERE NOT EXISTS (SELECT 1 FROM request_authority.release_prepared WHERE correlation_id = p_id)
    ON CONFLICT (correlation_id) DO NOTHING;

    RETURN EXISTS (
        SELECT 1 FROM request_authority.release_result_prepared r
        WHERE r.correlation_id = p_id AND r.organization = p_scope AND r.result_digest = p_digest
          AND r.result_ref = p_ref AND r.result_revision = p_revision AND r.result_generation = p_result_generation
          AND r.release_generation = p_release_generation
    );
END
$function$;

CREATE FUNCTION request_authority.record_release_evidence(p_id uuid, p_scope text, p_digest bytea, p_input text)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF p_input IS NULL OR p_input NOT IN ('HANDOFF', 'CLIENT')
       OR NOT request_authority.release_evidence_permitted(p_scope, p_input) OR NOT EXISTS (
        SELECT 1 FROM request_authority.release_result_prepared r
        WHERE r.correlation_id = p_id AND r.organization = p_scope AND r.result_digest = p_digest
    ) THEN
        RETURN false;
    END IF;

    IF p_input = 'HANDOFF' THEN
        INSERT INTO request_authority.release_handoff_proved VALUES (p_id) ON CONFLICT DO NOTHING;
    ELSE
        INSERT INTO request_authority.release_client_acknowledged VALUES (p_id) ON CONFLICT DO NOTHING;
    END IF;
    RETURN true;
END
$function$;

CREATE FUNCTION request_authority.read_release_evidence(p_id uuid, p_scope text)
RETURNS TABLE (handoff_proved boolean, client_acknowledged boolean)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    IF NOT request_authority.release_evidence_permitted(p_scope, 'READ') THEN
        RAISE EXCEPTION 'Release evidence is unavailable.' USING ERRCODE = '42501';
    END IF;

    RETURN QUERY
        SELECT h.correlation_id IS NOT NULL, a.correlation_id IS NOT NULL
        FROM request_authority.release_result_prepared r
        LEFT JOIN request_authority.release_handoff_proved h USING (correlation_id)
        LEFT JOIN request_authority.release_client_acknowledged a USING (correlation_id)
        WHERE r.correlation_id = p_id AND r.organization = p_scope
        UNION ALL
        SELECT false, false FROM request_authority.release_prepared r
        WHERE r.correlation_id = p_id AND r.organization = p_scope;
END
$function$;

REVOKE ALL ON FUNCTION request_authority.release_evidence_permitted(text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION request_authority.prepare_release(uuid, text, bytea, text, bigint, bigint, bigint, uuid, bigint, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION request_authority.record_release_evidence(uuid, text, bytea, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION request_authority.read_release_evidence(uuid, text) FROM PUBLIC;

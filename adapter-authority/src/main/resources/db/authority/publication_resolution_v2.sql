CREATE TABLE request_authority.publication_resolver_grant (
    resolver_role name NOT NULL,
    organization text COLLATE "C" NOT NULL REFERENCES request_authority.scope_genesis (organization),
    may_confirm boolean NOT NULL,
    may_abort boolean NOT NULL,
    PRIMARY KEY (resolver_role, organization)
);

CREATE TABLE request_authority.publication_resolution_audit (
    audit_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id uuid NOT NULL REFERENCES request_authority.publication_attempt (attempt_id),
    resolver_role name NOT NULL,
    terminal_state text NOT NULL CHECK (terminal_state IN ('COMMITTED', 'ABORTED')),
    resolved_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION request_authority.may_resolve_publication(p_attempt uuid, p_terminal text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
    SELECT EXISTS (
        SELECT 1 FROM request_authority.publication_attempt a
        JOIN request_authority.publication_resolver_grant g USING (organization)
        WHERE a.attempt_id = p_attempt AND g.resolver_role = session_user
          AND ((p_terminal = 'COMMITTED' AND g.may_confirm) OR (p_terminal = 'ABORTED' AND g.may_abort))
    )
$$;

CREATE FUNCTION request_authority.finish_audited_publication(p_attempt uuid, p_terminal text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    IF NOT request_authority.may_resolve_publication(p_attempt, p_terminal) THEN
        RETURN false;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM request_authority.publication_attempt WHERE attempt_id = p_attempt AND state = p_terminal
    ) AND NOT request_authority.finish_publication(p_attempt, p_terminal) THEN
        RETURN false;
    END IF;
    INSERT INTO request_authority.publication_resolution_audit (attempt_id, resolver_role, terminal_state)
        VALUES (p_attempt, session_user, p_terminal);
    RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION request_authority.may_resolve_publication(uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION request_authority.finish_audited_publication(uuid, text) FROM PUBLIC;

CREATE TABLE request_authority.publication_namespace (
    namespace text COLLATE "C" PRIMARY KEY CHECK (namespace = 'initial-requests'),
    last_id bigint NOT NULL CHECK (last_id >= 0)
);

INSERT INTO request_authority.publication_namespace (namespace, last_id) VALUES ('initial-requests', 0);

CREATE TABLE request_authority.publication_attempt (
    attempt_id uuid PRIMARY KEY,
    namespace text COLLATE "C" NOT NULL REFERENCES request_authority.publication_namespace (namespace),
    generation bigint NOT NULL REFERENCES request_authority.workflow_certificate (generation),
    organization text COLLATE "C" NOT NULL REFERENCES request_authority.scope_genesis (organization),
    request_type text NOT NULL CHECK (request_type IN ('HR_TICKET', 'BUG_REPORT', 'INTERNAL_REQUEST')),
    ref text COLLATE "C" NOT NULL CHECK (ref <> ''),
    backend_pid integer NOT NULL CHECK (backend_pid > 0),
    workflow_transaction xid8 NOT NULL,
    receipt_identity text COLLATE "C" NOT NULL CHECK (receipt_identity ~ '^[0-9a-f]{64}$'),
    result_revision bigint NOT NULL CHECK (result_revision > 1),
    publication_id bigint CHECK (publication_id > 0),
    state text NOT NULL DEFAULT 'REGISTERED',
    UNIQUE (namespace, publication_id),
    UNIQUE (generation, workflow_transaction),
    CHECK ((state = 'REGISTERED' AND publication_id IS NULL)
        OR (state IN ('PENDING', 'COMMITTED') AND publication_id IS NOT NULL)
        OR state = 'ABORTED')
);

CREATE UNIQUE INDEX publication_active_ref ON request_authority.publication_attempt (ref)
    WHERE state IN ('PENDING', 'COMMITTED');

CREATE FUNCTION request_authority.allocate_publication(p_attempt uuid)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    next_id bigint;
    selected_namespace text;
BEGIN
    SELECT namespace INTO selected_namespace
      FROM request_authority.publication_attempt
      WHERE attempt_id = p_attempt AND state = 'REGISTERED' FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    UPDATE request_authority.publication_namespace SET last_id = last_id + 1
      WHERE namespace = selected_namespace AND last_id < 9223372036854775807
      RETURNING last_id INTO next_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    UPDATE request_authority.publication_attempt SET publication_id = next_id, state = 'PENDING'
      WHERE attempt_id = p_attempt;
    RETURN next_id;
END;
$$;

CREATE FUNCTION request_authority.finish_publication(p_attempt uuid, p_terminal text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    UPDATE request_authority.publication_attempt SET state = p_terminal
      WHERE attempt_id = p_attempt AND (
          (p_terminal = 'COMMITTED' AND state = 'PENDING') OR
          (p_terminal = 'ABORTED' AND state IN ('REGISTERED', 'PENDING'))
      );
    RETURN FOUND;
END;
$$;

REVOKE ALL ON FUNCTION request_authority.allocate_publication(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION request_authority.finish_publication(uuid, text) FROM PUBLIC;

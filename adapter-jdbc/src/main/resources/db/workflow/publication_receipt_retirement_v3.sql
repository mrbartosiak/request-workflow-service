CREATE TABLE requests.publication_receipt_retirement (
    attempt_id uuid PRIMARY KEY REFERENCES requests.publication_witness (attempt_id),
    workflow_transaction xid8 NOT NULL,
    receipt_xmin xid NOT NULL
);

CREATE INDEX publication_witness_receipt_key ON requests.publication_witness
    (receipt_issuer, organization, receipt_principal, receipt_token_id);

CREATE VIEW requests.publication_live_receipt_evidence AS
    SELECT w.attempt_id, w.workflow_transaction, o.xmin AS receipt_xmin
    FROM requests.publication_witness w
    JOIN requests.operation_receipt o
      ON o.organization = w.organization AND o.issuer = w.receipt_issuer
      AND o.principal = w.receipt_principal AND o.token_id = w.receipt_token_id
    WHERE o.xmin = w.xmin AND o.finalized
      AND o.result_action = 'PUBLISH' AND o.result_ref = w.ref AND o.result_state = 'PUBLISHED'
      AND o.result_revision = w.result_revision AND o.key_generation = w.generation
      AND o.publication_namespace = w.namespace AND o.publication_id = w.publication_id
      AND o.original_status = 200;

CREATE OR REPLACE VIEW requests.publication_commit_evidence AS
    SELECT w.attempt_id, w.writer_id, w.generation, w.organization, w.request_type, w.ref,
           w.namespace, w.publication_id, w.result_revision, w.workflow_transaction,
           w.receipt_issuer, w.receipt_principal, w.receipt_token_id
    FROM requests.publication_witness w
    JOIN requests.request r USING (organization, ref, request_type)
    JOIN requests.transition_fact f
      ON f.organization = r.organization AND f.ref = r.ref AND f.sequence = r.last_sequence
    WHERE w.xmin::text::numeric = mod(w.workflow_transaction::text::numeric, 4294967296)
      AND r.xmin = w.xmin AND f.xmin = w.xmin
      AND r.state = 'PUBLISHED' AND r.revision = w.result_revision
      AND r.publication_namespace = w.namespace AND r.publication_id = w.publication_id
      AND f.action = 'PUBLISH' AND f.revision = w.result_revision AND f.to_state = 'PUBLISHED'
      AND (
          EXISTS (
              SELECT 1 FROM requests.publication_live_receipt_evidence live
              WHERE live.attempt_id = w.attempt_id
          )
          OR (
              EXISTS (
                  SELECT 1 FROM requests.publication_receipt_retirement retired
                  WHERE retired.attempt_id = w.attempt_id AND retired.workflow_transaction = w.workflow_transaction
                    AND retired.receipt_xmin = w.xmin
              )
              AND NOT EXISTS (
                  SELECT 1 FROM requests.operation_receipt o
                  WHERE o.issuer = w.receipt_issuer AND o.organization = w.organization
                    AND o.principal = w.receipt_principal AND o.token_id = w.receipt_token_id
              )
          )
      );

CREATE FUNCTION requests.require_publication_witness_receipt()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    IF NEW.workflow_transaction <> pg_current_xact_id() OR NOT EXISTS (
        SELECT 1 FROM requests.publication_live_receipt_evidence live
        WHERE live.attempt_id = NEW.attempt_id AND live.workflow_transaction = NEW.workflow_transaction
          AND live.receipt_xmin::text::numeric = mod(NEW.workflow_transaction::text::numeric, 4294967296)
    ) THEN
        RAISE EXCEPTION 'A publication witness requires its exact command receipt' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION requests.preserve_publication_receipt_evidence()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    witness_count bigint;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (OLD.issuer, OLD.organization, OLD.principal, OLD.token_id) IS NOT DISTINCT FROM
           (NEW.issuer, NEW.organization, NEW.principal, NEW.token_id) THEN
            RETURN NEW;
        END IF;
    END IF;

    SELECT count(*) INTO witness_count
    FROM requests.publication_witness w
    WHERE w.receipt_issuer = OLD.issuer AND w.organization = OLD.organization
      AND w.receipt_principal = OLD.principal AND w.receipt_token_id = OLD.token_id;

    IF TG_OP = 'UPDATE' THEN
        IF witness_count <> 0 THEN
            RAISE EXCEPTION 'A bound publication receipt key cannot change' USING ERRCODE = '23503';
        END IF;
        RETURN NEW;
    END IF;

    IF witness_count = 0 AND OLD.result_action IS DISTINCT FROM 'PUBLISH' THEN
        RETURN OLD;
    END IF;
    IF witness_count <> 1 THEN
        RAISE EXCEPTION 'Publication receipt retirement requires one original witness' USING ERRCODE = '23514';
    END IF;

    INSERT INTO requests.publication_receipt_retirement (attempt_id, workflow_transaction, receipt_xmin)
    SELECT exact.attempt_id, exact.workflow_transaction, live.receipt_xmin
    FROM requests.publication_commit_evidence exact
    JOIN requests.publication_live_receipt_evidence live USING (attempt_id)
    WHERE exact.receipt_issuer = OLD.issuer AND exact.organization = OLD.organization
      AND exact.receipt_principal = OLD.principal AND exact.receipt_token_id = OLD.token_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Publication receipt retirement requires exact original commit evidence' USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER publication_witness_requires_receipt
    AFTER INSERT ON requests.publication_witness
    FOR EACH ROW EXECUTE FUNCTION requests.require_publication_witness_receipt();

CREATE TRIGGER publication_receipt_retirement
    BEFORE DELETE OR UPDATE OF issuer, organization, principal, token_id ON requests.operation_receipt
    FOR EACH ROW EXECUTE FUNCTION requests.preserve_publication_receipt_evidence();

REVOKE ALL ON FUNCTION requests.require_publication_witness_receipt() FROM PUBLIC;
REVOKE ALL ON FUNCTION requests.preserve_publication_receipt_evidence() FROM PUBLIC;

-- Remove only the old scoped live FK once both replacement guards are installed.
DO $$
DECLARE
    live_receipt_key name;
BEGIN
    SELECT c.conname INTO STRICT live_receipt_key
    FROM pg_catalog.pg_constraint c
    WHERE c.contype = 'f' AND c.conrelid = 'requests.publication_witness'::regclass
      AND c.confrelid = 'requests.operation_receipt'::regclass
      AND ARRAY(
          SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY AS key(attnum, position)
          JOIN pg_catalog.pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = key.attnum
          ORDER BY key.position
      ) = ARRAY['receipt_issuer', 'organization', 'receipt_principal', 'receipt_token_id']::name[]
      AND ARRAY(
          SELECT a.attname FROM unnest(c.confkey) WITH ORDINALITY AS key(attnum, position)
          JOIN pg_catalog.pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = key.attnum
          ORDER BY key.position
      ) = ARRAY['issuer', 'organization', 'principal', 'token_id']::name[];
    EXECUTE format('ALTER TABLE requests.publication_witness DROP CONSTRAINT %I', live_receipt_key);
END;
$$;

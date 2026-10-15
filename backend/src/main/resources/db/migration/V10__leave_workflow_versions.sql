-- T05.2：只增加設定與nullable提交快照，不預先發布／改派，不回填虛構歷史。
CREATE TABLE leave_workflow_version (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 version_no integer NOT NULL UNIQUE CHECK(version_no>0),
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED')),
 row_version bigint NOT NULL DEFAULT 0 CHECK(row_version>=0),
 name varchar(100) NOT NULL CHECK(length(btrim(name))>0 AND name=btrim(name)),
 description varchar(1000) NOT NULL,
 source varchar(20) NOT NULL CHECK(source IN ('MANAGER','DESIGNATED')),
 designated_approver_id bigint REFERENCES employee(id) ON DELETE RESTRICT,
 base_published_id bigint REFERENCES leave_workflow_version(id) ON DELETE RESTRICT,
 created_by bigint NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL,
 published_by bigint REFERENCES employee(id) ON DELETE RESTRICT,
 published_at timestamptz,
 publish_reason varchar(500),
 CHECK((source='MANAGER' AND designated_approver_id IS NULL) OR (source='DESIGNATED' AND designated_approver_id IS NOT NULL)),
 CHECK((status='DRAFT' AND published_by IS NULL AND published_at IS NULL AND publish_reason IS NULL)
    OR (status='PUBLISHED' AND published_by IS NOT NULL AND published_at IS NOT NULL AND length(btrim(publish_reason))>0))
);
CREATE UNIQUE INDEX uq_leave_workflow_draft ON leave_workflow_version(status) WHERE status='DRAFT';
CREATE TABLE leave_workflow_state (
 id integer PRIMARY KEY CHECK(id=1),
 current_version_id bigint REFERENCES leave_workflow_version(id) ON DELETE RESTRICT
);
INSERT INTO leave_workflow_state(id) VALUES(1);
CREATE TABLE leave_workflow_history (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 request_id uuid NOT NULL UNIQUE,
 version_id bigint NOT NULL REFERENCES leave_workflow_version(id) ON DELETE RESTRICT,
 action varchar(30) NOT NULL CHECK(action IN ('DRAFT_CREATED','DRAFT_SAVED','PUBLISHED')),
 fingerprint text NOT NULL,
 before_content text,
 after_content text NOT NULL,
 before_published_id bigint REFERENCES leave_workflow_version(id) ON DELETE RESTRICT,
 after_published_id bigint REFERENCES leave_workflow_version(id) ON DELETE RESTRICT,
 actor_id bigint NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
 actor_snapshot text NOT NULL,
 reason varchar(500) NOT NULL,
 occurred_at timestamptz NOT NULL
);
CREATE TRIGGER trg_leave_workflow_history_immutable BEFORE UPDATE OR DELETE ON leave_workflow_history
 FOR EACH ROW EXECUTE FUNCTION reject_append_only_change();

CREATE FUNCTION protect_published_leave_workflow() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='PUBLISHED' THEN RAISE EXCEPTION 'published leave workflow is immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_published_leave_workflow BEFORE UPDATE OR DELETE ON leave_workflow_version
 FOR EACH ROW EXECUTE FUNCTION protect_published_leave_workflow();

CREATE FUNCTION validate_current_leave_workflow() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.current_version_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM leave_workflow_version WHERE id=NEW.current_version_id AND status='PUBLISHED')
 THEN RAISE EXCEPTION 'current leave workflow must be published'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_current_leave_workflow BEFORE INSERT OR UPDATE ON leave_workflow_state
 FOR EACH ROW EXECUTE FUNCTION validate_current_leave_workflow();

ALTER TABLE leave_request
 ADD COLUMN workflow_version_id bigint REFERENCES leave_workflow_version(id) ON DELETE RESTRICT,
 ADD COLUMN approval_snapshot text,
 DROP CONSTRAINT ck_leave_request_source,
 DROP CONSTRAINT ck_leave_submitted_approver;
ALTER TABLE leave_request
 ADD CONSTRAINT ck_leave_request_source CHECK(approver_source IN ('DESIGNATED','MANAGER','REASSIGNED','WORKFLOW')),
 ADD CONSTRAINT ck_leave_submitted_approver CHECK(submitted_approver_id<>employee_id AND
  submitted_approver_source IN ('DESIGNATED','MANAGER','REASSIGNED','LEGACY_UNKNOWN','WORKFLOW')),
 ADD CONSTRAINT ck_leave_workflow_snapshot CHECK(workflow_version_id IS NULL OR (approval_snapshot IS NOT NULL
  AND (approval_snapshot::jsonb->>'workflowVersionId')::bigint=workflow_version_id
  AND approval_snapshot::jsonb->>'source'='WORKFLOW'));

CREATE FUNCTION protect_leave_approval_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND ROW(NEW.workflow_version_id,NEW.approval_snapshot) IS DISTINCT FROM ROW(OLD.workflow_version_id,OLD.approval_snapshot)
 THEN RAISE EXCEPTION 'submitted approval snapshot is immutable'; END IF;
 IF TG_OP='INSERT' AND NEW.workflow_version_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM leave_workflow_version WHERE id=NEW.workflow_version_id AND status='PUBLISHED')
 THEN RAISE EXCEPTION 'submitted workflow must be published'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_leave_approval_snapshot BEFORE INSERT OR UPDATE ON leave_request
 FOR EACH ROW EXECUTE FUNCTION protect_leave_approval_snapshot();

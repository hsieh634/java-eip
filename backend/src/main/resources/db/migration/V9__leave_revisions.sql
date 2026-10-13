-- 每筆既有提交成為業務單根版本；舊欄位、狀態及歷程不改。
ALTER TABLE leave_request
 ADD COLUMN document_id bigint,
 ADD COLUMN revision_no integer NOT NULL DEFAULT 1,
 ADD COLUMN operation varchar(20) NOT NULL DEFAULT 'CREATE',
 ADD COLUMN source_request_id bigint,
 ADD COLUMN source_version bigint,
 ADD COLUMN base_effective_request_id bigint,
 ADD COLUMN base_effective_version bigint NOT NULL DEFAULT 0,
 ADD COLUMN fingerprint varchar(64),
 ADD COLUMN submitted_approver_id bigint REFERENCES employee(id) ON DELETE RESTRICT,
 ADD COLUMN submitted_approver_source varchar(20);

UPDATE leave_request r SET document_id=id,
 submitted_approver_id=coalesce((SELECT h.after_approver_id FROM leave_history h
  WHERE h.leave_request_id=r.id AND h.action='SUBMITTED' ORDER BY h.id LIMIT 1),approver_id),
 submitted_approver_source=CASE WHEN approver_source='REASSIGNED' THEN 'LEGACY_UNKNOWN' ELSE approver_source END;

ALTER TABLE leave_request
 ADD CONSTRAINT uq_leave_identity_owner UNIQUE(id,employee_id),
 ADD CONSTRAINT uq_leave_revision_identity UNIQUE(id,document_id,employee_id),
 ADD CONSTRAINT uq_leave_document_revision UNIQUE(document_id,revision_no);

CREATE TABLE leave_document (
 id bigint PRIMARY KEY,
 employee_id bigint NOT NULL REFERENCES employee(id) ON DELETE RESTRICT,
 effective_request_id bigint,
 effective_version bigint NOT NULL DEFAULT 0,
 canceled boolean NOT NULL DEFAULT false,
 UNIQUE(id,employee_id),
 FOREIGN KEY(id,employee_id) REFERENCES leave_request(id,employee_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(effective_request_id,id,employee_id) REFERENCES leave_request(id,document_id,employee_id) DEFERRABLE INITIALLY DEFERRED,
 CHECK(effective_version>=0),
 CHECK((effective_request_id IS NOT NULL AND effective_version>0 AND NOT canceled)
    OR (effective_request_id IS NULL AND canceled AND effective_version>0)
    OR (effective_request_id IS NULL AND NOT canceled AND effective_version=0))
);
INSERT INTO leave_document(id,employee_id,effective_request_id,effective_version)
 SELECT id,employee_id,CASE WHEN status='APPROVED' THEN id ELSE NULL END,
 CASE WHEN status='APPROVED' THEN 1 ELSE 0 END FROM leave_request;

ALTER TABLE leave_request
 ALTER COLUMN document_id SET NOT NULL,
 ALTER COLUMN submitted_approver_id SET NOT NULL,
 ALTER COLUMN submitted_approver_source SET NOT NULL,
 ADD CONSTRAINT fk_leave_document_owner FOREIGN KEY(document_id,employee_id)
  REFERENCES leave_document(id,employee_id) DEFERRABLE INITIALLY DEFERRED,
 ADD CONSTRAINT fk_leave_source_revision FOREIGN KEY(source_request_id,document_id,employee_id)
  REFERENCES leave_request(id,document_id,employee_id) ON DELETE RESTRICT,
 ADD CONSTRAINT fk_leave_base_effective FOREIGN KEY(base_effective_request_id,document_id,employee_id)
  REFERENCES leave_request(id,document_id,employee_id) ON DELETE RESTRICT,
 ADD CONSTRAINT ck_leave_revision CHECK(
  (operation='CREATE' AND document_id=id AND revision_no=1 AND source_request_id IS NULL AND source_version IS NULL
    AND base_effective_request_id IS NULL AND base_effective_version=0 AND fingerprint IS NULL)
  OR (operation IN ('REVISE','CHANGE','CANCEL_ALL','CANCEL_PART') AND document_id<>id AND revision_no>=2
    AND source_request_id IS NOT NULL AND source_request_id<>id AND source_version IS NOT NULL AND source_version>=0
    AND base_effective_version>=0 AND fingerprint IS NOT NULL AND length(fingerprint)=64)),
 ADD CONSTRAINT ck_leave_effective_base CHECK(operation='CREATE'
  OR (operation='REVISE' AND base_effective_request_id IS NULL AND base_effective_version=0)
  OR (operation IN ('CHANGE','CANCEL_ALL','CANCEL_PART') AND base_effective_request_id IS NOT NULL AND base_effective_version>0)),
 ADD CONSTRAINT ck_leave_submitted_approver CHECK(submitted_approver_id<>employee_id AND
  submitted_approver_source IN ('DESIGNATED','MANAGER','REASSIGNED','LEGACY_UNKNOWN'));
CREATE UNIQUE INDEX uq_leave_document_pending ON leave_request(document_id) WHERE status='PENDING';
ALTER TABLE leave_history ADD COLUMN decision_snapshot text;

CREATE FUNCTION prepare_leave_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.operation='CREATE' THEN NEW.document_id:=NEW.id; END IF;
 NEW.submitted_approver_id:=NEW.approver_id;
 NEW.submitted_approver_source:=NEW.approver_source;
 IF NEW.operation<>'CREATE' AND NOT EXISTS(SELECT 1 FROM leave_request r WHERE r.id=NEW.source_request_id
  AND r.document_id=NEW.document_id AND r.employee_id=NEW.employee_id AND r.revision_no<NEW.revision_no) THEN
  RAISE EXCEPTION 'invalid leave revision source';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_prepare_leave_revision BEFORE INSERT ON leave_request FOR EACH ROW EXECUTE FUNCTION prepare_leave_revision();

CREATE FUNCTION create_leave_document() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.operation='CREATE' THEN
  INSERT INTO leave_document(id,employee_id,effective_request_id,effective_version)
   VALUES(NEW.id,NEW.employee_id,CASE WHEN NEW.status='APPROVED' THEN NEW.id ELSE NULL END,
    CASE WHEN NEW.status='APPROVED' THEN 1 ELSE 0 END);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_create_leave_document AFTER INSERT ON leave_request FOR EACH ROW EXECUTE FUNCTION create_leave_document();

CREATE FUNCTION immutable_leave_submission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.employee_id,NEW.request_id,NEW.leave_type,NEW.work_date,NEW.start_time,NEW.end_time,NEW.minutes,NEW.reason,
  NEW.document_id,NEW.revision_no,NEW.operation,NEW.source_request_id,NEW.source_version,
  NEW.base_effective_request_id,NEW.base_effective_version,NEW.fingerprint,NEW.submitted_approver_id,NEW.submitted_approver_source)
 IS DISTINCT FROM ROW(OLD.employee_id,OLD.request_id,OLD.leave_type,OLD.work_date,OLD.start_time,OLD.end_time,OLD.minutes,OLD.reason,
  OLD.document_id,OLD.revision_no,OLD.operation,OLD.source_request_id,OLD.source_version,
  OLD.base_effective_request_id,OLD.base_effective_version,OLD.fingerprint,OLD.submitted_approver_id,OLD.submitted_approver_source) THEN
  RAISE EXCEPTION 'submitted leave content is immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_immutable_leave_submission BEFORE UPDATE ON leave_request FOR EACH ROW EXECUTE FUNCTION immutable_leave_submission();

-- Issue #25: DELETE /api/businesses/{id} is being added. Neither FK below had ON DELETE
-- CASCADE set (V1 just used plain REFERENCES), so deleting a business with any work passes or
-- deadline records would fail outright with a foreign key violation. Deleting a business is
-- meant to remove its work passes and computed deadlines too - handled at the DB level (not by
-- the application issuing separate DELETE statements first) so it stays correct and atomic
-- regardless of how a row ever gets deleted, not just through this one code path.

ALTER TABLE work_pass DROP CONSTRAINT work_pass_business_id_fkey;
ALTER TABLE work_pass ADD CONSTRAINT work_pass_business_id_fkey
    FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE CASCADE;

ALTER TABLE deadline_record DROP CONSTRAINT deadline_record_business_id_fkey;
ALTER TABLE deadline_record ADD CONSTRAINT deadline_record_business_id_fkey
    FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE CASCADE;

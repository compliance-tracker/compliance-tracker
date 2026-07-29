-- Indexes for the app's actual hot-path query patterns (issue #50). Postgres does NOT
-- automatically index a foreign key column on the referencing side (only the referenced side
-- gets one, implicitly, from its own primary key) - every "REFERENCES ..." column added across
-- V1-V9 has been an unindexed sequential scan target this whole time. Not a problem at the
-- current tiny data volume, but each of these backs a query that runs on every real request
-- (or, for deadline_record, every sync/dispatch cycle across every business), so it's worth
-- getting right before real volume ever exists rather than after.

-- BusinessRepository.findByOwnerId / findByIdAndOwnerId - runs on every "list my businesses"
-- and every single-business lookup (deadlines, update, delete), i.e. on almost every
-- authenticated business-related request.
CREATE INDEX idx_business_owner_id ON business (owner_id);

-- WorkPassRepository.findByBusinessId (both the plain and paginated variants) /
-- findByIdAndBusinessId - runs on every work-pass list/create/delete under a business.
CREATE INDEX idx_work_pass_business_id ON work_pass (business_id);

-- DeadlineRecordRepository.existsByBusinessIdAndObligationTypeAndDueDate - the sync job's own
-- dedupe check, run once per computed Deadline for every business on every daily sync
-- (DeadlineSyncService.syncDeadlines), arguably the single hottest query in the app once real
-- data volume exists. The leading two columns (business_id, obligation_type) also cover
-- deleteByBusinessIdAndObligationTypeAndReminderSentFalse's own WHERE clause as a prefix match
-- (issue #30), even though that query's third filter, reminder_sent, isn't part of this index -
-- still far better than the unindexed full scan it would otherwise be.
CREATE INDEX idx_deadline_record_business_obligation_due
    ON deadline_record (business_id, obligation_type, due_date);

-- DeadlineRecordRepository.findByReminderSentFalse - the dispatcher's own "what might need a
-- reminder" query (SqsDispatchService, via DeadlineSyncService.findDueSoonAndUnreminded), run
-- once per dispatch cycle across the ENTIRE table, not scoped to one business. A partial index
-- (only rows where reminder_sent is still false) rather than a plain one on the whole column -
-- once a reminder is actually sent, reminder_sent flips to true and stays true forever, so the
-- true rows are pure dead weight for this specific query and only grow over time; the false
-- rows are the actual small, shrinking subset this query cares about.
CREATE INDEX idx_deadline_record_unreminded ON deadline_record (reminder_sent) WHERE NOT reminder_sent;

-- PasswordResetTokenRepository.deleteByUserId / EmailVerificationTokenRepository.deleteByUserId
-- - low volume in practice (a user has zero or one of each at a time), but still an unindexed
-- FK lookup otherwise. token itself on both tables already has a UNIQUE constraint (V5/V6),
-- which Postgres backs with its own index automatically - no separate index needed there.
CREATE INDEX idx_password_reset_token_user_id ON password_reset_token (user_id);
CREATE INDEX idx_email_verification_token_user_id ON email_verification_token (user_id);

-- Deliberately NOT adding anything for: app_user.email (already UNIQUE, V2, auto-indexed),
-- idempotency_key.idempotency_key/owner_id (already covered by the UNIQUE(idempotency_key,
-- owner_id) constraint, V4, which IdempotencyKeyRepository.findByKeyAndOwnerId's WHERE clause
-- matches exactly).

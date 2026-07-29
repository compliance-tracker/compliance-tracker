package com.chrainx.compliance_tracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Real, unmocked test against the actual Postgres schema Flyway just migrated - a regression
// guard for issue #50's indexes specifically, since nothing about a missing index would ever
// show up as a normal test failure (every query still returns the right rows, just slower) -
// only a direct schema check like this one would ever catch a future migration accidentally
// dropping one of these.
@SpringBootTest
@ActiveProfiles("test")
class DatabaseIndexesIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class, indexName);
        return count != null && count > 0;
    }

    @Test
    void businessOwnerIdIndex_exists() {
        assertTrue(indexExists("idx_business_owner_id"));
    }

    @Test
    void workPassBusinessIdIndex_exists() {
        assertTrue(indexExists("idx_work_pass_business_id"));
    }

    @Test
    void deadlineRecordBusinessObligationDueIndex_exists() {
        assertTrue(indexExists("idx_deadline_record_business_obligation_due"));
    }

    @Test
    void deadlineRecordUnremindedPartialIndex_exists() {
        assertTrue(indexExists("idx_deadline_record_unreminded"));
    }

    @Test
    void passwordResetTokenUserIdIndex_exists() {
        assertTrue(indexExists("idx_password_reset_token_user_id"));
    }

    @Test
    void emailVerificationTokenUserIdIndex_exists() {
        assertTrue(indexExists("idx_email_verification_token_user_id"));
    }
}

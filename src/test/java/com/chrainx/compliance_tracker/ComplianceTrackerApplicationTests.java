package com.chrainx.compliance_tracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// @ActiveProfiles("test") loads application-test.properties on top of application.properties -
// disables real @Scheduled jobs during tests (see SchedulingConfig).
@SpringBootTest
@ActiveProfiles("test")
class ComplianceTrackerApplicationTests {

	@Test
	void contextLoads() {
	}

}

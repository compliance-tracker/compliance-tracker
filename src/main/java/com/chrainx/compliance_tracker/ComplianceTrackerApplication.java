package com.chrainx.compliance_tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Scheduling is enabled via SchedulingConfig (@EnableScheduling there, not here) so it can be
// switched off specifically in tests - see SchedulingConfig for why that matters.
@SpringBootApplication
public class ComplianceTrackerApplication {

	public static void main(String[] args) {
		SpringApplication.run(ComplianceTrackerApplication.class, args);
	}

}

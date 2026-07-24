package com.chrainx.compliance_tracker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling turns on Spring's scheduling machinery app-wide - without it, @Scheduled
// methods (DeadlineSyncService, SqsDispatchService, ReminderWorkerService) are never invoked.
//
// It's gated behind @ConditionalOnProperty (defaulting to enabled) so it can be turned off in
// tests via scheduling.enabled=false. This matters because @SpringBootTest boots the *entire*
// real app - including real @Scheduled jobs. Once ReminderWorkerService existed, its real
// background poller (every 30s) started competing with @SpringBootTest classes' own explicit
// calls to sync/dispatch/poll, silently consuming messages a test had just enqueued before the
// test's own assertions ran - a real, reproducible test failure, not flakiness.
@Configuration
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}

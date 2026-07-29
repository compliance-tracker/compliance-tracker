package com.chrainx.compliance_tracker.logging;

import org.slf4j.MDC;

import java.util.UUID;

// CorrelationIdFilter covers HTTP requests, but the reminder pipeline's real work
// (DeadlineSyncService.syncDeadlines, SqsDispatchService.scheduledDispatch,
// ReminderWorkerService.pollAndProcess) runs on a @Scheduled trigger, not inside any HTTP
// request at all - there's no filter for it to run through. Same underlying need though (issue
// #51): being able to grep one run's log lines out of everything else the app logged that day.
// This gives each scheduled method's own invocation a fresh correlation ID the same way
// CorrelationIdFilter gives each HTTP request one.
public final class CorrelationIdSupport {

    private CorrelationIdSupport() {
    }

    // Restores whatever MDC value (if any) was present before, rather than unconditionally
    // clearing it - defensive correctness for MDC's thread-local, nested-call-safe usage
    // pattern, even though in practice every current caller runs on its own dedicated
    // @Scheduled thread with nothing already set.
    public static void runWithNewCorrelationId(Runnable task) {
        String previous = MDC.get(CorrelationIdFilter.MDC_KEY);
        MDC.put(CorrelationIdFilter.MDC_KEY, UUID.randomUUID().toString());
        try {
            task.run();
        } finally {
            if (previous != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, previous);
            } else {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }
}

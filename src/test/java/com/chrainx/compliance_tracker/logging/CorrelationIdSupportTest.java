package com.chrainx.compliance_tracker.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CorrelationIdSupportTest {

    @AfterEach
    void clearMdc() {
        // Belt-and-suspenders against this test class leaking MDC state into whichever test
        // class JUnit happens to run next in the same JVM/thread.
        MDC.clear();
    }

    @Test
    void setsACorrelationId_forTheDurationOfTheTask() {
        String[] valueDuringTask = new String[1];

        CorrelationIdSupport.runWithNewCorrelationId(() -> valueDuringTask[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertNotNull(valueDuringTask[0]);
    }

    @Test
    void clearsTheCorrelationId_afterTheTaskCompletes_whenNoneWasSetBefore() {
        CorrelationIdSupport.runWithNewCorrelationId(() -> { });

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void restoresThePreviousCorrelationId_ratherThanClearingIt_ifOneWasAlreadySet() {
        // Defensive correctness for nested usage, even though every current caller runs on its
        // own dedicated @Scheduled thread with nothing already set.
        MDC.put(CorrelationIdFilter.MDC_KEY, "outer-id");

        CorrelationIdSupport.runWithNewCorrelationId(() -> { });

        assertEquals("outer-id", MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void generatesADifferentIdOnEachCall() {
        String[] first = new String[1];
        String[] second = new String[1];

        CorrelationIdSupport.runWithNewCorrelationId(() -> first[0] = MDC.get(CorrelationIdFilter.MDC_KEY));
        CorrelationIdSupport.runWithNewCorrelationId(() -> second[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertNotNull(first[0]);
        assertNotNull(second[0]);
        assertNotEquals(first[0], second[0]);
    }

    @Test
    void clearsTheCorrelationId_evenWhenTheTaskThrows() {
        try {
            CorrelationIdSupport.runWithNewCorrelationId(() -> {
                throw new RuntimeException("simulated task failure");
            });
        } catch (RuntimeException expected) {
            // The point of this test - the exception propagating is expected.
        }

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}

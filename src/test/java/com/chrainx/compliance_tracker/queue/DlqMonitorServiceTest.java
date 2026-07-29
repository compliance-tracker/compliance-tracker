package com.chrainx.compliance_tracker.queue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chrainx.compliance_tracker.logging.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DlqMonitorServiceTest {

    private final SqsClient sqsClient = mock(SqsClient.class);
    private final DlqMonitorService service = new DlqMonitorService(sqsClient, "compliance-reminders-dlq");

    // Logback's ListAppender - a standard, non-brittle way to assert "did this class actually
    // log something", attached directly to this class's own logger rather than parsing a real
    // log file. This is the one class in the project whose entire point IS a log line (the
    // "alert" half of issue #18), so unlike everywhere else in this codebase, testing the log
    // output itself is the actual behavior under test, not incidental.
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(DlqMonitorService.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(DlqMonitorService.class)).detachAppender(logAppender);
    }

    private void stubDepth(int depth) {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder()
                        .queueUrl("http://localhost:4566/000000000000/compliance-reminders-dlq").build());
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, String.valueOf(depth)))
                        .build());
    }

    @Test
    void checkDlqDepth_logsAWarning_whenMessagesArePresent() {
        stubDepth(3);

        service.checkDlqDepth();

        assertEquals(1, logAppender.list.size());
        assertEquals(Level.WARN, logAppender.list.get(0).getLevel());
        String formattedMessage = logAppender.list.get(0).getFormattedMessage();
        assertTrue(formattedMessage.contains("3"));
        assertTrue(formattedMessage.contains("compliance-reminders-dlq"));
    }

    @Test
    void checkDlqDepth_logsNothing_whenTheQueueIsEmpty() {
        stubDepth(0);

        service.checkDlqDepth();

        assertEquals(0, logAppender.list.size());
    }

    // Issue #51: same reasoning as the other scheduled jobs - this runs on its own trigger,
    // never inside an HTTP request, so it gets its own correlation ID.
    @Test
    void checkDlqDepth_setsACorrelationId_forTheDurationOfTheCheck_andClearsItAfterwards() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class))).thenAnswer(invocation -> {
            assertNotNull(MDC.get(CorrelationIdFilter.MDC_KEY));
            return GetQueueUrlResponse.builder()
                    .queueUrl("http://localhost:4566/000000000000/compliance-reminders-dlq").build();
        });
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"))
                        .build());

        service.checkDlqDepth();

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}

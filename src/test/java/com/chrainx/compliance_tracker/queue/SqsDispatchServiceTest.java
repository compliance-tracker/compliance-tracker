package com.chrainx.compliance_tracker.queue;
import com.chrainx.compliance_tracker.business.DeadlineRecord;
import com.chrainx.compliance_tracker.business.Business;
import com.chrainx.compliance_tracker.business.DeadlineSyncService;
import com.chrainx.compliance_tracker.logging.CorrelationIdFilter;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqsDispatchServiceTest {

    private final DeadlineSyncService deadlineSyncService = mock(DeadlineSyncService.class);
    private final SqsClient sqsClient = mock(SqsClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SqsDispatchService service =
            new SqsDispatchService(deadlineSyncService, sqsClient, objectMapper, "compliance-reminders");

    @Test
    void dispatchDueSoonDeadlines_sendsOneMessagePerDueSoonRecord() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("http://localhost:4566/000000000000/compliance-reminders").build());

        Business business = new Business();
        business.setId(1L);

        DeadlineRecord record = new DeadlineRecord();
        record.setId(10L);
        record.setBusiness(business);
        record.setDueDate(LocalDate.of(2026, 8, 1));

        when(deadlineSyncService.findDueSoonAndUnreminded(any())).thenReturn(List.of(record));

        int dispatched = service.dispatchDueSoonDeadlines();

        assertEquals(1, dispatched);
        verify(sqsClient, times(1)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void dispatchDueSoonDeadlines_sendsNoMessages_whenNothingDueSoon() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("http://localhost:4566/000000000000/compliance-reminders").build());
        when(deadlineSyncService.findDueSoonAndUnreminded(any())).thenReturn(List.of());

        int dispatched = service.dispatchDueSoonDeadlines();

        assertEquals(0, dispatched);
        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    // Issue #51: scheduledDispatch (not dispatchDueSoonDeadlines directly, which the tests above
    // call) is the one actually wrapped in CorrelationIdSupport, since it's the @Scheduled
    // entry point that runs on its own thread outside any HTTP request.
    @Test
    void scheduledDispatch_setsACorrelationId_forTheDurationOfTheRun_andClearsItAfterwards() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("http://localhost:4566/000000000000/compliance-reminders").build());
        when(deadlineSyncService.findDueSoonAndUnreminded(any())).thenAnswer(invocation -> {
            assertNotNull(MDC.get(CorrelationIdFilter.MDC_KEY));
            return List.of();
        });

        service.scheduledDispatch();

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}

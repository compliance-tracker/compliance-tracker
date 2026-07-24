package com.chrainx.compliance_tracker;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

        when(deadlineSyncService.findDueSoonAndUnreminded(any(), eq(14))).thenReturn(List.of(record));

        int dispatched = service.dispatchDueSoonDeadlines(14);

        assertEquals(1, dispatched);
        verify(sqsClient, times(1)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void dispatchDueSoonDeadlines_sendsNoMessages_whenNothingDueSoon() {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("http://localhost:4566/000000000000/compliance-reminders").build());
        when(deadlineSyncService.findDueSoonAndUnreminded(any(), eq(14))).thenReturn(List.of());

        int dispatched = service.dispatchDueSoonDeadlines(14);

        assertEquals(0, dispatched);
        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
    }
}

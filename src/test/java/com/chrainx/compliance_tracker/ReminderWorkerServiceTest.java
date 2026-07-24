package com.chrainx.compliance_tracker;

import com.chrainx.compliance_tracker.rules.ObligationType;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReminderWorkerServiceTest {

    private final SqsClient sqsClient = mock(SqsClient.class);
    private final DeadlineRecordRepository deadlineRecordRepository = mock(DeadlineRecordRepository.class);
    private final NotificationSender notificationSender = mock(NotificationSender.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ReminderWorkerService worker = new ReminderWorkerService(
            sqsClient, deadlineRecordRepository, notificationSender, objectMapper, "compliance-reminders");

    private Message sqsMessageFor(Long deadlineRecordId) {
        String body = objectMapper.writeValueAsString(
                new ReminderMessage(deadlineRecordId, 1L, ObligationType.ACRA_ANNUAL_RETURN, LocalDate.of(2026, 8, 1)));
        return Message.builder().body(body).receiptHandle("handle-" + deadlineRecordId).build();
    }

    private void stubQueueUrlAndReceive(Message... messages) {
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("http://localhost:4566/000000000000/compliance-reminders").build());
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(messages)).build());
    }

    @Test
    void processesMessage_sendsNotification_marksReminderSent_deletesMessage() {
        Business business = new Business();
        business.setId(1L);
        business.setName("Test Co");

        DeadlineRecord record = new DeadlineRecord();
        record.setId(10L);
        record.setBusiness(business);
        record.setObligationType(ObligationType.ACRA_ANNUAL_RETURN);
        record.setDueDate(LocalDate.of(2026, 8, 1));
        record.setReminderSent(false);

        stubQueueUrlAndReceive(sqsMessageFor(10L));
        when(deadlineRecordRepository.findById(10L)).thenReturn(Optional.of(record));

        worker.pollAndProcess();

        verify(notificationSender, times(1)).send(eq(business), eq(record));
        assertEquals(true, record.isReminderSent());
        verify(deadlineRecordRepository, times(1)).save(record);
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void redeliveredMessage_forAlreadyRemindedRecord_skipsSend_butStillDeletesMessage() {
        Business business = new Business();
        business.setId(1L);

        DeadlineRecord record = new DeadlineRecord();
        record.setId(10L);
        record.setBusiness(business);
        record.setReminderSent(true);

        stubQueueUrlAndReceive(sqsMessageFor(10L));
        when(deadlineRecordRepository.findById(10L)).thenReturn(Optional.of(record));

        worker.pollAndProcess();

        verify(notificationSender, never()).send(any(), any());
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void messageForMissingDeadlineRecord_isDeletedWithoutSending() {
        stubQueueUrlAndReceive(sqsMessageFor(999L));
        when(deadlineRecordRepository.findById(999L)).thenReturn(Optional.empty());

        worker.pollAndProcess();

        verify(notificationSender, never()).send(any(), any());
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }
}

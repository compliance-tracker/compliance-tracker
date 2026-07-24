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

    @Test
    void failedSend_leavesMessageUndeleted_soSqsCanRetryAndEventuallyDeadLetter() {
        Business business = new Business();
        business.setId(1L);

        DeadlineRecord record = new DeadlineRecord();
        record.setId(10L);
        record.setBusiness(business);
        record.setReminderSent(false);

        stubQueueUrlAndReceive(sqsMessageFor(10L));
        when(deadlineRecordRepository.findById(10L)).thenReturn(Optional.of(record));
        doThrow(new RuntimeException("simulated notification failure"))
                .when(notificationSender).send(any(), any());

        worker.pollAndProcess();

        // Not deleted -> stays in the queue, SQS's own visibility timeout + redrive policy
        // (maxReceiveCount, configured on the queue itself) handles retrying and eventually
        // moving it to the dead-letter queue after repeated failures - no app code needed for
        // that part, this just has to not accidentally delete a message that failed.
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
        assertEquals(false, record.isReminderSent());
    }

    @Test
    void oneFailingMessage_doesNotBlockProcessingOfOthersInTheSameBatch() {
        Business business = new Business();
        business.setId(1L);

        DeadlineRecord failingRecord = new DeadlineRecord();
        failingRecord.setId(10L);
        failingRecord.setBusiness(business);
        failingRecord.setReminderSent(false);

        DeadlineRecord okRecord = new DeadlineRecord();
        okRecord.setId(20L);
        okRecord.setBusiness(business);
        okRecord.setReminderSent(false);

        stubQueueUrlAndReceive(sqsMessageFor(10L), sqsMessageFor(20L));
        when(deadlineRecordRepository.findById(10L)).thenReturn(Optional.of(failingRecord));
        when(deadlineRecordRepository.findById(20L)).thenReturn(Optional.of(okRecord));
        doThrow(new RuntimeException("simulated notification failure"))
                .when(notificationSender).send(eq(business), eq(failingRecord));

        worker.pollAndProcess();

        assertEquals(true, okRecord.isReminderSent());
        verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }
}

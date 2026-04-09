package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.XMLMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ErrorQueueRepublishCorrelationKeyTest {

    @Mock
    private ErrorQueueInfrastructure errorQueueInfrastructure;

    @Mock
    private BytesXMLMessage message;

    private ErrorQueueRepublishCorrelationKey key;

    @BeforeEach
    void setUp() {
        key = new ErrorQueueRepublishCorrelationKey(errorQueueInfrastructure, message);
    }

    @Test
    void testHandleSuccessAcksMessage() {
        key.handleSuccess();
        verify(message).ackMessage();
    }

    @Test
    void testGetSourceMessageId() {
        when(message.getMessageId()).thenReturn("msg-123");
        assertThat(key.getSourceMessageId()).isEqualTo("msg-123");
    }

    @Test
    void testGetErrorQueueName() {
        when(errorQueueInfrastructure.getErrorQueueName()).thenReturn("error-queue-1");
        assertThat(key.getErrorQueueName()).isEqualTo("error-queue-1");
    }

    @Test
    void testHandleErrorSendsToErrorQueue() throws Exception {
        when(errorQueueInfrastructure.getMaxDeliveryAttempts()).thenReturn(3L);
        when(errorQueueInfrastructure.getErrorQueueName()).thenReturn("error-q");

        key.handleError();

        verify(errorQueueInfrastructure).send(eq(message), eq(key));
        assertThat(key.getErrorQueueDeliveryAttempt()).isEqualTo(1);
    }

    @Test
    void testHandleErrorRetriesOnFailure() throws Exception {
        when(errorQueueInfrastructure.getMaxDeliveryAttempts()).thenReturn(3L);
        when(errorQueueInfrastructure.getErrorQueueName()).thenReturn("error-q");

        // Fail first, succeed second
        doThrow(new RuntimeException("send failed"))
                .doNothing()
                .when(errorQueueInfrastructure).send(eq(message), eq(key));

        key.handleError();

        verify(errorQueueInfrastructure, times(2)).send(eq(message), eq(key));
        assertThat(key.getErrorQueueDeliveryAttempt()).isEqualTo(2);
    }

    @Test
    void testHandleErrorFallbackAfterMaxAttempts() throws Exception {
        when(errorQueueInfrastructure.getMaxDeliveryAttempts()).thenReturn(1L);
        when(errorQueueInfrastructure.getErrorQueueName()).thenReturn("error-q");

        doThrow(new RuntimeException("send failed"))
                .when(errorQueueInfrastructure).send(eq(message), eq(key));

        key.handleError();

        // After 1 attempt and then exceeding max, fallback should settle as FAILED
        verify(errorQueueInfrastructure, times(1)).send(eq(message), eq(key));
    }

    @Test
    void testHandleErrorFallbackRequeues() throws Exception {
        when(errorQueueInfrastructure.getMaxDeliveryAttempts()).thenReturn(0L);

        key.handleError();

        // 0 max attempts means immediate fallback -> settle FAILED
        verify(message).settle(XMLMessage.Outcome.FAILED);
        verify(errorQueueInfrastructure, never()).send(any(), any());
    }

    @Test
    void testInitialDeliveryAttemptIsZero() {
        assertThat(key.getErrorQueueDeliveryAttempt()).isZero();
    }
}

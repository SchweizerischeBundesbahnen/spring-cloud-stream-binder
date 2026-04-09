package com.solace.spring.cloud.stream.binder.inbound.acknowledge;

import com.solace.spring.cloud.stream.binder.util.ErrorQueueInfrastructure;
import com.solace.spring.cloud.stream.binder.util.ErrorQueueRepublishCorrelationKey;
import com.solace.spring.cloud.stream.binder.util.SolaceAcknowledgmentException;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.XMLMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.acks.AcknowledgmentCallback.Status;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JCSMPAcknowledgementCallbackTest {

    @Mock
    private BytesXMLMessage message;

    @Mock
    private ErrorQueueInfrastructure errorQueueInfrastructure;

    @Test
    void testInitialState() {
        JCSMPAcknowledgementCallback callback = createCallback(Optional.empty());
        assertThat(callback.isAcknowledged()).isFalse();
        assertThat(callback.isAutoAck()).isTrue();
    }

    @Test
    void testAcceptAcknowledgesMessage() {
        JCSMPAcknowledgementCallback callback = createCallback(Optional.empty());
        callback.acknowledge(Status.ACCEPT);

        verify(message).ackMessage();
        assertThat(callback.isAcknowledged()).isTrue();
    }

    @Test
    void testRejectWithErrorQueueRepublishes() {
        ErrorQueueRepublishCorrelationKey correlationKey = mock(ErrorQueueRepublishCorrelationKey.class);
        when(errorQueueInfrastructure.createCorrelationKey(message)).thenReturn(correlationKey);
        when(errorQueueInfrastructure.getErrorQueueName()).thenReturn("error-queue");

        JCSMPAcknowledgementCallback callback = createCallback(Optional.of(errorQueueInfrastructure));
        callback.acknowledge(Status.REJECT);

        verify(correlationKey).handleError();
        assertThat(callback.isAcknowledged()).isTrue();
    }

    @Test
    void testRejectWithoutErrorQueueSettlesRejected() throws JCSMPException {
        JCSMPAcknowledgementCallback callback = createCallback(Optional.empty());
        callback.acknowledge(Status.REJECT);

        verify(message).settle(XMLMessage.Outcome.REJECTED);
        assertThat(callback.isAcknowledged()).isTrue();
    }

    @Test
    void testRequeueSettlesFailed() throws JCSMPException {
        JCSMPAcknowledgementCallback callback = createCallback(Optional.empty());
        callback.acknowledge(Status.REQUEUE);

        verify(message).settle(XMLMessage.Outcome.FAILED);
        assertThat(callback.isAcknowledged()).isTrue();
    }

    @Test
    void testDoubleAcknowledgeIsNoOp() {
        JCSMPAcknowledgementCallback callback = createCallback(Optional.empty());
        callback.acknowledge(Status.ACCEPT);
        callback.acknowledge(Status.ACCEPT);

        verify(message, times(1)).ackMessage();
    }

    @Test
    void testAcknowledgeExceptionWrapsInSolaceException() throws JCSMPException {
        JCSMPException jcsmpException = new JCSMPException("test");
        doThrow(jcsmpException).when(message).settle(XMLMessage.Outcome.FAILED);

        JCSMPAcknowledgementCallback callback = createCallback(Optional.empty());

        assertThatThrownBy(() -> callback.acknowledge(Status.REQUEUE))
                .isInstanceOf(SolaceAcknowledgmentException.class)
                .hasCause(jcsmpException);
    }

    @Test
    void testSolaceAcknowledgmentExceptionIsNotWrapped() throws JCSMPException {
        SolaceAcknowledgmentException original = new SolaceAcknowledgmentException("direct", new RuntimeException());
        doThrow(original).when(message).settle(XMLMessage.Outcome.FAILED);

        JCSMPAcknowledgementCallback callback = createCallback(Optional.empty());

        assertThatThrownBy(() -> callback.acknowledge(Status.REQUEUE))
                .isSameAs(original);
    }

    @Test
    void testNoAutoAck() {
        JCSMPAcknowledgementCallback callback = createCallback(Optional.empty());
        callback.noAutoAck();
        assertThat(callback.isAutoAck()).isFalse();
    }

    @Test
    void testGetMessage() {
        JCSMPAcknowledgementCallback callback = createCallback(Optional.empty());
        assertThat(callback.getMessage()).isSameAs(message);
    }

    @Test
    void testIsErrorQueueEnabled() {
        assertThat(createCallback(Optional.empty()).isErrorQueueEnabled()).isFalse();
        assertThat(createCallback(Optional.of(errorQueueInfrastructure)).isErrorQueueEnabled()).isTrue();
    }

    @Test
    void testRejectErrorQueueRepublishFailure() {
        ErrorQueueRepublishCorrelationKey correlationKey = mock(ErrorQueueRepublishCorrelationKey.class);
        when(errorQueueInfrastructure.createCorrelationKey(message)).thenReturn(correlationKey);
        when(errorQueueInfrastructure.getErrorQueueName()).thenReturn("error-queue");
        doThrow(new RuntimeException("republish failed")).when(correlationKey).handleError();

        JCSMPAcknowledgementCallback callback = createCallback(Optional.of(errorQueueInfrastructure));

        assertThatThrownBy(() -> callback.acknowledge(Status.REJECT))
                .isInstanceOf(SolaceAcknowledgmentException.class)
                .hasMessageContaining("Failed to send XMLMessage");
    }

    private JCSMPAcknowledgementCallback createCallback(Optional<ErrorQueueInfrastructure> eqi) {
        return new JCSMPAcknowledgementCallback(message, eqi);
    }
}

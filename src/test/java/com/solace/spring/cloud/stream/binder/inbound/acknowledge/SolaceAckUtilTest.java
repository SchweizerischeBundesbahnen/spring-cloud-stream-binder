package com.solace.spring.cloud.stream.binder.inbound.acknowledge;

import com.solace.spring.cloud.stream.binder.util.SolaceAcknowledgmentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.acks.AcknowledgmentCallback.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SolaceAckUtilTest {

    @Test
    void testIsErrorQueueEnabledWithJCSMPCallback(@Mock JCSMPAcknowledgementCallback callback) {
        when(callback.isErrorQueueEnabled()).thenReturn(true);
        assertThat(SolaceAckUtil.isErrorQueueEnabled(callback)).isTrue();
    }

    @Test
    void testIsErrorQueueDisabledWithJCSMPCallback(@Mock JCSMPAcknowledgementCallback callback) {
        when(callback.isErrorQueueEnabled()).thenReturn(false);
        assertThat(SolaceAckUtil.isErrorQueueEnabled(callback)).isFalse();
    }

    @Test
    void testIsErrorQueueEnabledWithNonJCSMPCallback(@Mock AcknowledgmentCallback callback) {
        assertThat(SolaceAckUtil.isErrorQueueEnabled(callback)).isFalse();
    }

    @Test
    void testRepublishToErrorQueueSuccess(@Mock JCSMPAcknowledgementCallback callback) {
        when(callback.isAcknowledged()).thenReturn(false);
        when(callback.republishToErrorQueue()).thenReturn(true);

        assertThat(SolaceAckUtil.republishToErrorQueue(callback)).isTrue();
    }

    @Test
    void testRepublishToErrorQueueAlreadyAcknowledged(@Mock JCSMPAcknowledgementCallback callback) {
        when(callback.isAcknowledged()).thenReturn(true);

        assertThat(SolaceAckUtil.republishToErrorQueue(callback)).isFalse();
        verify(callback, never()).republishToErrorQueue();
    }

    @Test
    void testRepublishToErrorQueueNoErrorQueue(@Mock JCSMPAcknowledgementCallback callback) {
        when(callback.isAcknowledged()).thenReturn(false);
        when(callback.republishToErrorQueue()).thenReturn(false);

        assertThat(SolaceAckUtil.republishToErrorQueue(callback)).isFalse();
    }

    @Test
    void testRepublishToErrorQueueNonJCSMPCallback(@Mock AcknowledgmentCallback callback) {
        when(callback.isAcknowledged()).thenReturn(false);

        assertThat(SolaceAckUtil.republishToErrorQueue(callback)).isFalse();
    }
}

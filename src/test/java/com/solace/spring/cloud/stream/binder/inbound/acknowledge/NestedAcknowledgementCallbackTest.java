package com.solace.spring.cloud.stream.binder.inbound.acknowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.acks.AcknowledgmentCallback.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NestedAcknowledgementCallbackTest {

    private NestedAcknowledgementCallback callback;

    @BeforeEach
    void setUp() {
        callback = new NestedAcknowledgementCallback();
    }

    @Test
    void testInitialState() {
        assertThat(callback.isAutoAck()).isTrue();
        assertThat(callback.isAcknowledged()).isTrue(); // empty => all match
        assertThat(callback.size()).isZero();
    }

    @Test
    void testAddCallback(@Mock AcknowledgmentCallback child) {
        callback.addAcknowledgmentCallback(child);
        assertThat(callback.size()).isEqualTo(1);
    }

    @Test
    void testAcknowledgeDelegatesAcceptToAllChildren(
            @Mock AcknowledgmentCallback child1,
            @Mock AcknowledgmentCallback child2) {
        callback.addAcknowledgmentCallback(child1);
        callback.addAcknowledgmentCallback(child2);

        callback.acknowledge(Status.ACCEPT);

        verify(child1).acknowledge(Status.ACCEPT);
        verify(child2).acknowledge(Status.ACCEPT);
    }

    @Test
    void testAcknowledgeDelegatesRejectToAllChildren(
            @Mock AcknowledgmentCallback child1,
            @Mock AcknowledgmentCallback child2) {
        callback.addAcknowledgmentCallback(child1);
        callback.addAcknowledgmentCallback(child2);

        callback.acknowledge(Status.REJECT);

        verify(child1).acknowledge(Status.REJECT);
        verify(child2).acknowledge(Status.REJECT);
    }

    @Test
    void testAcknowledgeDelegatesRequeueToAllChildren(
            @Mock AcknowledgmentCallback child1,
            @Mock AcknowledgmentCallback child2) {
        callback.addAcknowledgmentCallback(child1);
        callback.addAcknowledgmentCallback(child2);

        callback.acknowledge(Status.REQUEUE);

        verify(child1).acknowledge(Status.REQUEUE);
        verify(child2).acknowledge(Status.REQUEUE);
    }

    @Test
    void testIsAcknowledgedAllTrue(
            @Mock AcknowledgmentCallback child1,
            @Mock AcknowledgmentCallback child2) {
        when(child1.isAcknowledged()).thenReturn(true);
        when(child2.isAcknowledged()).thenReturn(true);

        callback.addAcknowledgmentCallback(child1);
        callback.addAcknowledgmentCallback(child2);

        assertThat(callback.isAcknowledged()).isTrue();
    }

    @Test
    void testIsAcknowledgedOneNotAcknowledged(
            @Mock AcknowledgmentCallback child1,
            @Mock AcknowledgmentCallback child2) {
        when(child1.isAcknowledged()).thenReturn(true);
        when(child2.isAcknowledged()).thenReturn(false);

        callback.addAcknowledgmentCallback(child1);
        callback.addAcknowledgmentCallback(child2);

        assertThat(callback.isAcknowledged()).isFalse();
    }

    @Test
    void testNoAutoAckPropagates(
            @Mock AcknowledgmentCallback child1,
            @Mock AcknowledgmentCallback child2) {
        callback.addAcknowledgmentCallback(child1);
        callback.addAcknowledgmentCallback(child2);

        callback.noAutoAck();

        assertThat(callback.isAutoAck()).isFalse();
        verify(child1).noAutoAck();
        verify(child2).noAutoAck();
    }

    @Test
    void testNoAutoAckPropagatesOnNewChildAfterDisable(@Mock AcknowledgmentCallback child) {
        callback.noAutoAck();
        callback.addAcknowledgmentCallback(child);

        verify(child).noAutoAck();
    }

    @Test
    void testAutoAckNotPropagatedOnNewChildWhenEnabled(@Mock AcknowledgmentCallback child) {
        callback.addAcknowledgmentCallback(child);
        verify(child, never()).noAutoAck();
    }
}

package com.solace.spring.cloud.stream.binder.util;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationDataTest {

    @Test
    void testInitialFutureIsIncomplete() {
        CorrelationData data = new CorrelationData();
        assertThat(data.getFuture().isDone()).isFalse();
    }

    @Test
    void testSuccessCompletesFuture() throws Exception {
        CorrelationData data = new CorrelationData();
        data.success();

        assertThat(data.getFuture().isDone()).isTrue();
        assertThat(data.getFuture().get(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void testFailedCompletesFutureExceptionally() {
        CorrelationData data = new CorrelationData();
        MessagingException cause = new MessagingException("test failure");
        data.failed(cause);

        assertThat(data.getFuture().isCompletedExceptionally()).isTrue();
        assertThatThrownBy(() -> data.getFuture().get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCause(cause);
    }

    @Test
    void testSetAndGetMessage() {
        CorrelationData data = new CorrelationData();
        assertThat(data.getMessage()).isNull();

        var message = MessageBuilder.withPayload("test").build();
        data.setMessage(message);

        assertThat(data.getMessage()).isSameAs(message);
    }

    @Test
    void testFutureReturnedIsAlwaysSameInstance() {
        CorrelationData data = new CorrelationData();
        assertThat(data.getFuture()).isSameAs(data.getFuture());
    }
}

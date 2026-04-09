package com.solace.spring.cloud.stream.binder.util;

import org.junit.jupiter.api.Test;
import org.springframework.core.AttributeAccessor;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.support.ErrorMessageUtils;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SolaceMessageHeaderErrorMessageStrategyTest {

    private final SolaceMessageHeaderErrorMessageStrategy strategy = new SolaceMessageHeaderErrorMessageStrategy();

    @Test
    void testBuildErrorMessageWithNullAttributeAccessor() {
        ErrorMessage errorMessage = strategy.buildErrorMessage(new RuntimeException("test"), null);
        assertThat(errorMessage.getPayload()).isInstanceOf(RuntimeException.class);
        assertThat(errorMessage.getHeaders()).doesNotContainKey(IntegrationMessageHeaderAccessor.SOURCE_DATA);
        assertThat(errorMessage.getHeaders()).doesNotContainKey(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK);
    }

    @Test
    void testBuildErrorMessageWithInputMessageContainingHeaders() {
        AcknowledgmentCallback ackCallback = mock(AcknowledgmentCallback.class);
        Object sourceData = new Object();

        var inputMessage = MessageBuilder.withPayload("test")
                .setHeader(IntegrationMessageHeaderAccessor.SOURCE_DATA, sourceData)
                .setHeader(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK, ackCallback)
                .build();

        AttributeAccessor accessor = ErrorMessageUtils.getAttributeAccessor(null, null);
        accessor.setAttribute(ErrorMessageUtils.INPUT_MESSAGE_CONTEXT_KEY, inputMessage);

        ErrorMessage errorMessage = strategy.buildErrorMessage(new RuntimeException("test"), accessor);

        assertThat(errorMessage.getHeaders().get(IntegrationMessageHeaderAccessor.SOURCE_DATA)).isSameAs(sourceData);
        assertThat(errorMessage.getHeaders().get(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK)).isSameAs(ackCallback);
        assertThat(errorMessage.getOriginalMessage()).isSameAs(inputMessage);
    }

    @Test
    void testBuildErrorMessageFallsBackToAttributes() {
        AcknowledgmentCallback ackCallback = mock(AcknowledgmentCallback.class);
        Object sourceData = new Object();

        AttributeAccessor accessor = ErrorMessageUtils.getAttributeAccessor(null, null);
        accessor.setAttribute(SolaceMessageHeaderErrorMessageStrategy.ATTR_SOLACE_RAW_MESSAGE, sourceData);
        accessor.setAttribute(SolaceMessageHeaderErrorMessageStrategy.ATTR_SOLACE_ACKNOWLEDGMENT_CALLBACK, ackCallback);

        ErrorMessage errorMessage = strategy.buildErrorMessage(new RuntimeException("test"), accessor);

        assertThat(errorMessage.getHeaders().get(IntegrationMessageHeaderAccessor.SOURCE_DATA)).isSameAs(sourceData);
        assertThat(errorMessage.getHeaders().get(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK)).isSameAs(ackCallback);
    }

    @Test
    void testBuildErrorMessageInputMessageHeadersTakePrecedence() {
        AcknowledgmentCallback msgAckCallback = mock(AcknowledgmentCallback.class);
        AcknowledgmentCallback attrAckCallback = mock(AcknowledgmentCallback.class);
        Object msgSourceData = new Object();
        Object attrSourceData = new Object();

        var inputMessage = MessageBuilder.withPayload("test")
                .setHeader(IntegrationMessageHeaderAccessor.SOURCE_DATA, msgSourceData)
                .setHeader(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK, msgAckCallback)
                .build();

        AttributeAccessor accessor = ErrorMessageUtils.getAttributeAccessor(null, null);
        accessor.setAttribute(ErrorMessageUtils.INPUT_MESSAGE_CONTEXT_KEY, inputMessage);
        accessor.setAttribute(SolaceMessageHeaderErrorMessageStrategy.ATTR_SOLACE_RAW_MESSAGE, attrSourceData);
        accessor.setAttribute(SolaceMessageHeaderErrorMessageStrategy.ATTR_SOLACE_ACKNOWLEDGMENT_CALLBACK, attrAckCallback);

        ErrorMessage errorMessage = strategy.buildErrorMessage(new RuntimeException("test"), accessor);

        // Message headers should take precedence over attribute accessor
        assertThat(errorMessage.getHeaders().get(IntegrationMessageHeaderAccessor.SOURCE_DATA)).isSameAs(msgSourceData);
        assertThat(errorMessage.getHeaders().get(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK)).isSameAs(msgAckCallback);
    }

    @Test
    void testBuildErrorMessageWithEmptyAccessor() {
        AttributeAccessor accessor = ErrorMessageUtils.getAttributeAccessor(null, null);

        ErrorMessage errorMessage = strategy.buildErrorMessage(new RuntimeException("test"), accessor);

        assertThat(errorMessage.getHeaders()).doesNotContainKey(IntegrationMessageHeaderAccessor.SOURCE_DATA);
        assertThat(errorMessage.getHeaders()).doesNotContainKey(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK);
    }

    @Test
    void testBuildErrorMessageWithNonMessageInputContext() {
        AttributeAccessor accessor = ErrorMessageUtils.getAttributeAccessor(null, null);
        accessor.setAttribute(ErrorMessageUtils.INPUT_MESSAGE_CONTEXT_KEY, "not-a-message");

        ErrorMessage errorMessage = strategy.buildErrorMessage(new RuntimeException("test"), accessor);

        // Non-Message input context should not contribute headers, and original message should be null
        assertThat(errorMessage.getOriginalMessage()).isNull();
    }
}

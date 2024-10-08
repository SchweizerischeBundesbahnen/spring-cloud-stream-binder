package com.solace.spring.cloud.stream.binder.util;

import com.solace.spring.cloud.stream.binder.test.spring.MessageGenerator;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessage;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

public class ErrorChannelSendingCorrelationKeyTest {
    private final ErrorMessageStrategy errorMessageStrategy = new SolaceMessageHeaderErrorMessageStrategy();

    @Test
    void testNoErrorChannel() {
        Message<?> message = MessageBuilder.withPayload("test").build();
        ErrorChannelSendingCorrelationKey key = new ErrorChannelSendingCorrelationKey(message, null,
                errorMessageStrategy);

        String description = "some failure";
        Exception cause = new RuntimeException("test");

        MessagingException exception = key.send(description, cause);
        assertThat(exception).hasMessageStartingWith(description);
        assertThat(exception).hasCause(cause);
        assertThat(exception.getFailedMessage()).isEqualTo(message);
    }

    @Test
    void testErrorChannel(SoftAssertions softly) {
        Message<?> message = MessageBuilder.withPayload("test").build();
        DirectChannel errorChannel = new DirectChannel();
        ErrorChannelSendingCorrelationKey key = new ErrorChannelSendingCorrelationKey(message, errorChannel,
                errorMessageStrategy);

        String description = "some failure";
        Exception cause = new RuntimeException("test");

        errorChannel.subscribe(msg -> {
            softly.assertThat(msg).isInstanceOf(ErrorMessage.class);
            ErrorMessage errorMsg = (ErrorMessage) msg;
            softly.assertThat(errorMsg.getOriginalMessage()).isEqualTo(message);
            softly.assertThat(errorMsg.getPayload()).isInstanceOf(MessagingException.class);
            softly.assertThat(errorMsg.getPayload()).hasMessageStartingWith(description);
            softly.assertThat(errorMsg.getPayload()).hasCause(cause);
            softly.assertThat(((MessagingException) errorMsg.getPayload()).getFailedMessage()).isEqualTo(message);
        });

        MessagingException exception = key.send(description, cause);
        assertThat(exception).hasMessageStartingWith(description);
        assertThat(exception).hasCause(cause);
        assertThat(exception.getFailedMessage()).isEqualTo(message);
    }

    @Test
    void testRawMessageHeader(SoftAssertions softly) {
        Message<?> message = MessageGenerator.generateMessage(i -> "test", i -> Map.of()).build();
        DirectChannel errorChannel = new DirectChannel();
        ErrorChannelSendingCorrelationKey key = new ErrorChannelSendingCorrelationKey(message, errorChannel,
                errorMessageStrategy);
        key.setRawMessages(IntStream.range(0, 1)
                .mapToObj(i -> (XMLMessage) JCSMPFactory.onlyInstance().createMessage(TextMessage.class))
                .toList());

        errorChannel.subscribe(msg -> {
            softly.assertThat(msg.getHeaders()).containsKey(IntegrationMessageHeaderAccessor.SOURCE_DATA);
            softly.assertThat((Object) StaticMessageHeaderAccessor.getSourceData(msg))
                    .isEqualTo(key.getRawMessages().get(0));
        });

        key.send("some failure", new RuntimeException("test"));
    }
}

package com.solace.spring.cloud.stream.binder.springBootTests.healthindicator.duplicatesubscription;

import lombok.Getter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Configuration
public class DuplicateSubscriptionMessageConsumer {
    @Getter
    private final static List<String> receivedMessages = new CopyOnWriteArrayList<>();

    @Bean
    public Consumer<Message<String>> receiveDuplicateSubscription() {
        return (msg -> receivedMessages.add(msg.getPayload()));
    }
}

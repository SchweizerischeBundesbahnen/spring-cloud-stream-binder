package com.solace.spring.cloud.stream.binder.springBootTests.healthindicator.brokenconfig;

import lombok.Getter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Configuration
public class BrokenConfigMessageConsumer {
    @Getter
    private final static List<String> receivedMessages = new CopyOnWriteArrayList<>();

    @Bean
    public Consumer<Message<String>> receiveBrokenConfig() {
        return (msg -> receivedMessages.add(msg.getPayload()));
    }
}

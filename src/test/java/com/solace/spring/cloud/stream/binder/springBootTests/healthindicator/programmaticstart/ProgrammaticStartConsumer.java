package com.solace.spring.cloud.stream.binder.springBootTests.healthindicator.programmaticstart;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Configuration
public class ProgrammaticStartConsumer {
    private final static List<String> receivedMessages = new CopyOnWriteArrayList<>();

    @Bean
    public Consumer<Message<String>> receiveProgrammaticStart() {
        return (msg -> receivedMessages.add(msg.getPayload()));
    }
}

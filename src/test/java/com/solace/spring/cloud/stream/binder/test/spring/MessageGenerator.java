package com.solace.spring.cloud.stream.binder.test.spring;

import org.springframework.integration.support.MessageBuilder;

import java.util.Map;
import java.util.function.Function;

public final class MessageGenerator {
    public static MessageBuilder<?> generateMessage(Function<Integer, ?> payloadGenerator,
                                                    Function<Integer, Map<String, Object>> headersGenerator) {
        return MessageBuilder.withPayload(payloadGenerator.apply(0))
                .copyHeaders(headersGenerator.apply(0));

    }
}

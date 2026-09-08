package com.solace.spring.cloud.stream.binder.springBootTests.customizer;

import com.solace.spring.cloud.stream.binder.outbound.JCSMPOutboundMessageHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.config.ConsumerEndpointCustomizer;
import org.springframework.cloud.stream.config.ProducerMessageHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.endpoint.MessageProducerSupport;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Declares the two customizer beans the way an application would — in its own context, not on the
 * binder. Whether they reach the binder at all is the question {@link CustomizerBeansIT} answers.
 */
@SpringBootApplication
public class CustomizerApp {

    public record CustomizedEndpoint(MessageProducerSupport endpoint, String destination, String group) {}

    public record CustomizedHandler(Object handler, String destination) {}

    public static final AtomicReference<CustomizedEndpoint> CUSTOMIZED_ENDPOINT = new AtomicReference<>();
    public static final AtomicReference<CustomizedHandler> CUSTOMIZED_HANDLER = new AtomicReference<>();

    public static void main(String[] args) {
        SpringApplication.run(CustomizerApp.class, args);
    }

    @Bean
    public Consumer<String> customizedConsumer() {
        return payload -> {};
    }

    @Bean
    public ConsumerEndpointCustomizer<MessageProducerSupport> consumerEndpointCustomizer() {
        return (endpoint, destination, group) ->
                CUSTOMIZED_ENDPOINT.set(new CustomizedEndpoint(endpoint, destination, group));
    }

    @Bean
    public ProducerMessageHandlerCustomizer<JCSMPOutboundMessageHandler> producerMessageHandlerCustomizer() {
        return (handler, destination) ->
                CUSTOMIZED_HANDLER.set(new CustomizedHandler(handler, destination));
    }
}

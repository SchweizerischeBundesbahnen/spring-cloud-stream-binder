package com.solace.spring.cloud.stream.binder;

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.config.autoconfigure.SolaceTracerConfiguration;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.test.junit.extension.SpringCloudStreamExtension;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.spring.cloud.stream.binder.tracing.TracingImpl;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import com.solace.spring.cloud.stream.binder.util.QualityOfService;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solacesystems.jcsmp.DeliveryMode;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.MimeTypeUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for tracing propagation.
 */
@SpringJUnitConfig(classes = {SolaceJavaAutoConfiguration.class,
        OpenTelemetryTracingAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class},
        initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(PubSubPlusExtension.class)
@ExtendWith(SpringCloudStreamExtension.class)
@Slf4j
@AutoConfigureObservability
@Isolated
@DirtiesContext
public class SolaceBinderTracingIT {
    @Autowired
    Tracer tracer;
    @Autowired
    Propagator propagator;

    @CartesianTest(name = "[{index}] deliveryMode={0}, qualityOfService={1}, group={2}")
    @Execution(ExecutionMode.CONCURRENT)
    public void tracing(@CartesianTest.Enum DeliveryMode deliveryMode,
                        @CartesianTest.Enum QualityOfService qualityOfService,
                        @CartesianTest.Values(strings = {"null", "group1"}) String group,
                        SpringCloudStreamContext context,
                        TestInfo testInfo

    ) throws Exception {
        SolaceTracerConfiguration solaceTracerConfiguration = new SolaceTracerConfiguration();
        TracingImpl tracingImpl = solaceTracerConfiguration.tracingImpl(tracer, propagator);
        TracingProxy tracingProxy = solaceTracerConfiguration.tracingProxy(tracingImpl);
        SolaceTestBinder binder = new SolaceTestBinder(context.getBinder(), tracingProxy);

        DirectChannel output = context.createBindableChannel("output", new BindingProperties());
        DirectChannel input = context.createBindableChannel("input", new BindingProperties());

        String topic = "tracing/" + deliveryMode.name() + "/" + qualityOfService.name() + "/" + group;
        String payload = "tracing/" + deliveryMode.name() + "/" + qualityOfService.name() + "/" + group;
        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        producerProperties.getExtension().setDeliveryMode(deliveryMode);
        Binding<MessageChannel> producerBinding = binder.bindProducer(topic, output, producerProperties);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setQualityOfService(qualityOfService);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(topic, "null".equals(group) ? null : group, input, consumerProperties);
        AtomicReference<Message<?>> result = new AtomicReference<>(null);
        AtomicReference<String> spanInitial = new AtomicReference<>(null);
        AtomicReference<String> spanInMessage = new AtomicReference<>(null);
        context.binderBindUnbindLatency();
        input.subscribe(m -> {
            result.set(m);
            Span currentSpan = tracer.currentSpan();
            spanInMessage.set(currentSpan.toString());
        });

        Span sendMessageSpan = tracer.nextSpan().name("sendMessage");
        try (Tracer.SpanInScope ws = tracer.withSpan(sendMessageSpan.start())) {
            sendMessageSpan.tag("tagValue", "tag" + payload);
            sendMessageSpan.event("event" + payload);
            Message<?> message = MessageBuilder.withPayload(payload.getBytes()).setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE).setHeader(BinderHeaders.TARGET_DESTINATION, topic).build();
            output.send(message);
            spanInitial.set(sendMessageSpan.toString());
        } finally {
            // Once done remember to end the span. This will allow collecting
            // the span to send it to a distributed tracing system e.g. Zipkin
            sendMessageSpan.end();
        }

        int wait = 10;
        while (wait > 0 && result.get() == null) {
            wait--;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }
        assertThat(spanInitial.get()).isNotNull();
        assertThat(spanInMessage.get()).isNotNull();
        String initialTraceId = spanInitial.get().split("traceId=")[1].split(", ")[0];
        String messageTraceId = spanInMessage.get().split("traceId=")[1].split(", ")[0];
        assertThat(initialTraceId).isEqualTo(messageTraceId);
        assertThat(result.get()).isNotNull();
        assertThat(result.get().getPayload()).isEqualTo(payload.getBytes());
        producerBinding.unbind();
        consumerBinding.unbind();
    }
}

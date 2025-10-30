package com.solace.spring.cloud.stream.binder.meter;

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.SolaceMessageChannelBinder;
import com.solace.spring.cloud.stream.binder.config.autoconfigure.SolaceMeterConfiguration;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.test.junit.extension.SpringCloudStreamExtension;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.spring.configuration.TestMeterRegistryConfiguration;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.test.integration.junit.jupiter.extension.ExecutorServiceExtension;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.MimeTypeUtils;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static com.solace.spring.cloud.stream.binder.test.util.RetryableAssertions.retryAssert;
import static com.solace.spring.cloud.stream.binder.test.util.SolaceSpringCloudStreamAssertions.isMeterInGivenRange;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for messaging process time tracking.
 */
@SpringJUnitConfig(classes = {
        TestMeterRegistryConfiguration.class,
        SolaceJavaAutoConfiguration.class,
        SolaceMeterConfiguration.class},
        initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(ExecutorServiceExtension.class)
@ExtendWith(PubSubPlusExtension.class)
@ExtendWith(SpringCloudStreamExtension.class)
@Slf4j
public class SolaceMeterAccessorProcessingTimeTestIT {

    @BeforeAll
    static void beforeAll(@Autowired SolaceMessageMeterBinder messageMeterBinder,
                          @Autowired MeterRegistry meterRegistry) {
        messageMeterBinder.bindTo(meterRegistry);
    }

    @BeforeEach
    void setUp(@Autowired SolaceMeterAccessor solaceMeterAccessor,
               SpringCloudStreamContext context) throws NoSuchFieldException, IllegalAccessException {
        SolaceMessageChannelBinder binder = context.getBinder().getBinder();
        Field solaceBinderHealthAccessorField = binder.getClass().getDeclaredField("solaceMeterAccessor");
        solaceBinderHealthAccessorField.setAccessible(true);
        solaceBinderHealthAccessorField.set(binder, Optional.of(solaceMeterAccessor));
    }

    @Test
    public void testApplySimpleMessageWithDefinedProcessingDuration_shouldHaveExpectedProcessingDurationInTrackedResult(
            SpringCloudStreamContext context,
            @Autowired SimpleMeterRegistry meterRegistry,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        String topic = "testProcessingDuration/takes1sToProcess";

        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        BindingProperties producerBindingProperties = new BindingProperties();
        producerBindingProperties.setProducer(producerProperties);
        DirectChannel output = context.createBindableChannel(
                RandomStringUtils.randomAlphanumeric(100), producerBindingProperties);
        Binding<MessageChannel> producerBinding = binder.bindProducer(topic, output, producerProperties);
        context.binderBindUnbindLatency();
        producerProperties.populateBindingName(producerBinding.getBindingName()); //This is needed in order to not get a npe on storing message size / payload metrics. In real-life applications the binder always assigns a binding name.


        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        BindingProperties consumerBindingProperties = new BindingProperties();
        consumerBindingProperties.setConsumer(consumerProperties);
        DirectChannel input = context.createBindableChannel(
                RandomStringUtils.randomAlphanumeric(100), consumerBindingProperties);

        Binding<MessageChannel> consumerBinding = binder.bindConsumer(topic, null, input, consumerProperties);
        consumerProperties.populateBindingName(consumerBinding.getBindingName()); //This is needed in order to not get a npe on storing processing duration. In real-life applications the binder always assigns a binding name.

        byte[] userData = new byte[1024];

        Message<?> message = MessageBuilder.withPayload(userData)
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_OCTET_STREAM)
                .setHeader(BinderHeaders.TARGET_DESTINATION, topic)
                .build();

        context.binderBindUnbindLatency();

        AtomicReference<Message<?>> result = new AtomicReference<>(null);
        input.subscribe((Message<?> incomingMessage) -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for duration, that should not happen ever.", e);
                throw new RuntimeException(e);
            }
            result.set(incomingMessage);
        });
        output.send(message);
        int wait = 100;
        while (wait > 0 && result.get() == null) {
            wait--;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }

        log.info("Validating message processing time meters");
        retryAssert(() -> {
            assertThat(meterRegistry.find(SolaceMessageMeterBinder.METER_NAME_PROCESSING_TIME)
                    .tag(SolaceMessageMeterBinder.TAG_NAME, consumerProperties.getBindingName())
                    .meters())
                    .singleElement()
                    .as("Checking meter %s with name %s",
                            SolaceMessageMeterBinder.METER_NAME_PROCESSING_TIME, consumerProperties.getBindingName())
                    .satisfies(isMeterInGivenRange(consumerProperties.getBindingName(), 1,
                            1000.0, 1200.0, "ms"));
        });

        assertThat(result.get()).isNotNull();
        assertThat(result.get().getPayload()).isEqualTo(userData);

        producerBinding.unbind();
        consumerBinding.unbind();
    }
}

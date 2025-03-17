package com.solace.spring.cloud.stream.binder;

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.test.junit.extension.SpringCloudStreamExtension;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.spring.cloud.stream.binder.util.QualityOfService;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solacesystems.jcsmp.DeliveryMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
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
 * Tests for large messaging.
 */
@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class, initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(PubSubPlusExtension.class)
@ExtendWith(SpringCloudStreamExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
@Isolated
@DirtiesContext
public class SolaceBinderLargeMessagingIT {

    @Test
    public void test2MbNormalMessaging(SpringCloudStreamContext context, TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        DirectChannel output = context.createBindableChannel("output", new BindingProperties());
        DirectChannel input = context.createBindableChannel("input", new BindingProperties());
        String topic = "test2MbNormalMessaging/min2MB";
        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        Binding<MessageChannel> producerBinding = binder.bindProducer(topic, output, producerProperties);
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(topic, null, input, consumerProperties);

        int messageSize = 1024 * 1024 * 2 + 17;
        byte[] userData = new byte[messageSize];

        Message<?> message = MessageBuilder.withPayload(userData)
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_OCTET_STREAM)
                .setHeader(BinderHeaders.TARGET_DESTINATION, topic)
                .build();

        context.binderBindUnbindLatency();

        AtomicReference<Message<?>> result = new AtomicReference<>(null);
        input.subscribe(result::set);
        output.send(message);
        int wait = 100;
        while (wait > 0 && result.get() == null) {
            wait--;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }

        assertThat(result.get()).isNotNull();
        assertThat(result.get().getPayload()).isEqualTo(userData);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] mb={0}, persistent={1}, atMostOnce={2}, groupe={3}")
    @Execution(ExecutionMode.SAME_THREAD)
    public void testLargeMessaging(
            @CartesianTest.Values(ints = {4, 8, 16}) int mb,
            @CartesianTest.Values(booleans = {true, false}) boolean persistent,
            @CartesianTest.Values(booleans = {true, false}) boolean atMostOnce,
            @CartesianTest.Values(booleans = {true, false}) boolean groupe,
            SpringCloudStreamContext context,
            TestInfo testInfo

    ) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        DirectChannel output = context.createBindableChannel("output", new BindingProperties());
        DirectChannel input = context.createBindableChannel("input", new BindingProperties());
        DeliveryMode deliveryMode = persistent ? DeliveryMode.PERSISTENT : DeliveryMode.DIRECT;
        QualityOfService qualityOfService = atMostOnce ? QualityOfService.AT_MOST_ONCE : QualityOfService.AT_LEAST_ONCE;
        String topic = "testLargeMessaging/min" + mb + "MB/" + deliveryMode.name() + "/" + qualityOfService.name();
        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        producerProperties.getExtension().setDeliveryMode(deliveryMode);
        Binding<MessageChannel> producerBinding = binder.bindProducer(topic, output, producerProperties);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setQualityOfService(qualityOfService);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(topic, groupe ? mb + deliveryMode.name() + qualityOfService.name() : null, input, consumerProperties);

        int messageSize = 1024 * 1024 * mb + 17;
        byte[] userData = new byte[messageSize];

        Message<?> message = MessageBuilder.withPayload(userData)
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_OCTET_STREAM)
                .setHeader(BinderHeaders.TARGET_DESTINATION, topic)
                .setHeader(SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT, true)
                .build();

        context.binderBindUnbindLatency();

        AtomicReference<Message<?>> result = new AtomicReference<>(null);
        input.subscribe(result::set);
        output.send(message);
        int wait = 10000;
        while (wait > 0 && result.get() == null) {
            wait--;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }

        assertThat(result.get()).isNotNull();
        assertThat(result.get().getPayload()).isEqualTo(userData);

        producerBinding.unbind();
        consumerBinding.unbind();
    }
}

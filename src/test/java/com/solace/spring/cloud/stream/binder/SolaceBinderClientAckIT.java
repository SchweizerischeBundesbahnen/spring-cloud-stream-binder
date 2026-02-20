package com.solace.spring.cloud.stream.binder;

import community.solace.spring.boot.starter.solaceclientconfig.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.test.junit.extension.SpringCloudStreamExtension;
import com.solace.spring.cloud.stream.binder.test.spring.ConsumerInfrastructureUtil;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.test.integration.junit.jupiter.extension.ExecutorServiceExtension;
import com.solace.test.integration.junit.jupiter.extension.ExecutorServiceExtension.ExecSvc;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueueMsgsResponse;
import com.solace.test.integration.semp.v2.monitor.model.MonitorSempMeta;
import com.solace.test.integration.semp.v2.monitor.model.MonitorSempPaging;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.Queue;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.junitpioneer.jupiter.cartesian.CartesianTest.Values;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.integration.acks.AckUtils;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.MimeTypeUtils;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.solace.spring.cloud.stream.binder.test.util.RetryableAssertions.retryAssert;
import static com.solace.spring.cloud.stream.binder.test.util.SolaceSpringCloudStreamAssertions.errorQueueHasMessages;
import static com.solace.spring.cloud.stream.binder.test.util.SolaceSpringCloudStreamAssertions.hasNestedHeader;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * All tests regarding client acknowledgment
 */
@Slf4j
@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(PubSubPlusExtension.class)
@ExtendWith(SpringCloudStreamExtension.class)
@ExtendWith(ExecutorServiceExtension.class)
public class SolaceBinderClientAckIT<T> {

    @CartesianTest(name = "[{index}] channelType={0}, endpointType={1}")
    public void testAccept(@Values(classes = {DirectChannel.class}) Class<T> channelType,
                           SempV2Api sempV2Api,
                           SpringCloudStreamContext context,
                           TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 1,
                () -> messages.forEach(moduleOutputChannel::send),
                msg -> {
                    AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                    Objects.requireNonNull(ackCallback).noAutoAck();
                    AckUtils.accept(ackCallback);
                });

        validateNumEnqueuedMessages(context, sempV2Api, binder.getConsumerQueueName(consumerBinding), 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}, endpointType={2}")
    public void testReject(@Values(classes = {DirectChannel.class}) Class<T> channelType,
                           SempV2Api sempV2Api,
                           SpringCloudStreamContext context,
                           TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        int numberOfMessages = (1) * 3;
        List<Message<?>> messages = IntStream.range(0, numberOfMessages)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();
        String endpointName = binder.getConsumerQueueName(consumerBinding);

        AtomicBoolean wasRedelivered = new AtomicBoolean(false);
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 3,
                () -> messages.forEach(moduleOutputChannel::send),
                msg -> {
                    if (isRedelivered(msg)) {
                        wasRedelivered.set(true);
                    } else {
                        AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                        Objects.requireNonNull(ackCallback).noAutoAck();
                        AckUtils.reject(ackCallback);
                    }
                });

        //rejected message should not be redelivered
        assertThat(wasRedelivered.get()).isFalse();

        validateNumEnqueuedMessages(context, sempV2Api, endpointName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, endpointName, 0);
        //validateNumAckedMessages(context, sempV2Api, queueName, messages.size());

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}, endpointType={1}")
    public void testRejectWithErrorQueue(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api,
            SpringCloudStreamContext context,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, group0, moduleInputChannel, consumerProperties);
        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();

        String endpointName = binder.getConsumerQueueName(consumerBinding);
        Queue errorQueue = JCSMPFactory.onlyInstance().createQueue(binder.getConsumerErrorQueueName(consumerBinding));

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 1,
                () -> messages.forEach(moduleOutputChannel::send),
                msg -> {
                    AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                    Objects.requireNonNull(ackCallback).noAutoAck();
                    AckUtils.reject(ackCallback);
                });

        assertThat(errorQueue.getName()).satisfies(errorQueueHasMessages(jcsmpSession, messages));

        validateNumEnqueuedMessages(context, sempV2Api, endpointName, 0);
        validateNumAckedMessages(context, sempV2Api, endpointName, messages.size());

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testRequeue(@Values(classes = {DirectChannel.class}) Class<T> channelType,
                            SempV2Api sempV2Api,
                            SpringCloudStreamContext context,
                            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();
        String endpointName = binder.getConsumerQueueName(consumerBinding);

        AtomicBoolean wasRedelivered = new AtomicBoolean(false);
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 2,
                () -> messages.forEach(moduleOutputChannel::send),
                msg -> {
                    if (isRedelivered(msg)) {
                        wasRedelivered.set(true);
                    } else {
                        AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                        Objects.requireNonNull(ackCallback).noAutoAck();
                        AckUtils.requeue(ackCallback);
                    }
                });
        assertThat(wasRedelivered.get()).isTrue();

        validateNumEnqueuedMessages(context, sempV2Api, endpointName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, endpointName, messages.size());
        validateNumAckedMessages(context, sempV2Api, endpointName, messages.size());

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testAsyncAccept(@Values(classes = {DirectChannel.class}) Class<T> channelType,
                                SempV2Api sempV2Api,
                                SpringCloudStreamContext context,
                                @ExecSvc(poolSize = 1, scheduled = true) ScheduledExecutorService executorService,
                                SoftAssertions softly,
                                TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();
        String queueName = binder.getConsumerQueueName(consumerBinding);

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 1,
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    log.info("Received message");
                    AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                    Objects.requireNonNull(ackCallback).noAutoAck();
                    executorService.schedule(() -> {
                        softly.assertThat(queueName)
                                .satisfies(q -> validateNumEnqueuedMessages(context, sempV2Api, q, messages.size()));
                        log.info("Async acknowledging message");
                        AckUtils.accept(ackCallback);
                        callback.run();
                    }, 2, TimeUnit.SECONDS);
                });

        validateNumEnqueuedMessages(context, sempV2Api, queueName, 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testAsyncReject(@Values(classes = {DirectChannel.class}) Class<T> channelType,
                                SempV2Api sempV2Api,
                                SpringCloudStreamContext context,
                                SoftAssertions softly,
                                @ExecSvc(poolSize = 1, scheduled = true) ScheduledExecutorService executorService,
                                TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();
        String queueName = binder.getConsumerQueueName(consumerBinding);

        AtomicBoolean wasRedelivered = new AtomicBoolean(false);
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 1,
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    if (isRedelivered(msg)) {
                        wasRedelivered.set(true);
                        callback.run();
                    } else {
                        AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                        Objects.requireNonNull(ackCallback).noAutoAck();
                        executorService.schedule(() -> {
                            softly.assertThat(queueName)
                                    .satisfies(q -> validateNumEnqueuedMessages(context, sempV2Api, q, messages.size()));
                            AckUtils.reject(ackCallback);
                            callback.run();
                        }, 2, TimeUnit.SECONDS);
                    }
                });

        softly.assertAll();
        assertThat(wasRedelivered.get()).isFalse();
        validateNumEnqueuedMessages(context, sempV2Api, queueName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, queueName, 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testAsyncRejectWithErrorQueue(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api,
            SpringCloudStreamContext context,
            @ExecSvc(poolSize = 1, scheduled = true) ScheduledExecutorService executorService,
            SoftAssertions softly,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0, group0,
                moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();

        String queueName = binder.getConsumerQueueName(consumerBinding);
        Queue errorQueue = JCSMPFactory.onlyInstance().createQueue(binder.getConsumerErrorQueueName(consumerBinding));

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 1,
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                    Objects.requireNonNull(ackCallback).noAutoAck();
                    executorService.schedule(() -> {
                        softly.assertThat(queueName)
                                .satisfies(q -> validateNumEnqueuedMessages(context, sempV2Api, q, messages.size()));
                        AckUtils.reject(ackCallback);
                        callback.run();
                    }, 2, TimeUnit.SECONDS);
                });

        assertThat(errorQueue.getName()).satisfies(errorQueueHasMessages(jcsmpSession, messages));
        validateNumEnqueuedMessages(context, sempV2Api, queueName, 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testAsyncRequeue(@Values(classes = {DirectChannel.class}) Class<T> channelType,
                                 SempV2Api sempV2Api,
                                 SpringCloudStreamContext context,
                                 @ExecSvc(poolSize = 1, scheduled = true) ScheduledExecutorService executorService,
                                 SoftAssertions softly,
                                 TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();
        String queueName = binder.getConsumerQueueName(consumerBinding);

        AtomicBoolean wasRedelivered = new AtomicBoolean(false);
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 2,
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    if (isRedelivered(msg)) {
                        wasRedelivered.set(true);
                        callback.run();
                    } else {
                        AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                        Objects.requireNonNull(ackCallback).noAutoAck();
                        executorService.schedule(() -> {
                            softly.assertThat(queueName)
                                    .satisfies(q -> validateNumEnqueuedMessages(context, sempV2Api, q, messages.size()));
                            AckUtils.requeue(ackCallback);
                            callback.run();
                        }, 2, TimeUnit.SECONDS);
                    }
                });
        assertThat(wasRedelivered.get()).isTrue();

        validateNumEnqueuedMessages(context, sempV2Api, queueName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, queueName, messages.size());

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}, endpointType={2}")
    public void testNoAck(@Values(classes = {DirectChannel.class}) Class<T> channelType,
                          SempV2Api sempV2Api,
                          SpringCloudStreamContext context,
                          TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);


        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();
        String endpointName = binder.getConsumerQueueName(consumerBinding);

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 1,
                () -> messages.forEach(moduleOutputChannel::send),
                msg -> Objects.requireNonNull(StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg)).noAutoAck());

        // Give some time just to make sure
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));

        validateNumEnqueuedMessages(context, sempV2Api, endpointName, messages.size());
        validateNumRedeliveredMessages(context, sempV2Api, endpointName, 0);
        validateNumAckedMessages(context, sempV2Api, endpointName, 0);
        validateNumUnackedMessages(context, sempV2Api, endpointName, messages.size());

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testNoAckAndThrowException(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            SempV2Api sempV2Api,
            SpringCloudStreamContext context,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();
        String queueName = binder.getConsumerQueueName(consumerBinding);

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, consumerProperties.getMaxAttempts() + 2,
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    if (isRedelivered(msg)) {
                        log.info("Received redelivered message");
                        callback.run();
                    } else {
                        log.info("Received message");
                        Objects.requireNonNull(StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg)).noAutoAck();
                        callback.run();
                        throw new RuntimeException("expected exception");
                    }
                });

        validateNumEnqueuedMessages(context, sempV2Api, queueName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, queueName, messages.size());
        validateNumAckedMessages(context, sempV2Api, queueName, messages.size());
        validateNumUnackedMessages(context, sempV2Api, queueName, 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testAcceptAndThrowException(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            SempV2Api sempV2Api,
            SpringCloudStreamContext context,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();
        String queueName = binder.getConsumerQueueName(consumerBinding);

        final CountDownLatch redeliveredLatch = new CountDownLatch(1);
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, consumerProperties.getMaxAttempts(),
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    AcknowledgmentCallback acknowledgmentCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                    Objects.requireNonNull(acknowledgmentCallback).noAutoAck();
                    if (isRedelivered(msg)) {
                        log.info("Received redelivered message");
                        redeliveredLatch.countDown();
                    } else {
                        log.info("Receiving message");
                        AckUtils.accept(acknowledgmentCallback);
                        callback.run();
                        throw new RuntimeException("expected exception");
                    }
                });
        assertThat(redeliveredLatch.await(3, TimeUnit.SECONDS)).isFalse();

        validateNumEnqueuedMessages(context, sempV2Api, queueName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, queueName, 0);
        validateNumAckedMessages(context, sempV2Api, queueName, messages.size());
        validateNumUnackedMessages(context, sempV2Api, queueName, 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testRejectAndThrowException(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            SempV2Api sempV2Api,
            SpringCloudStreamContext context,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();
        String queueName = binder.getConsumerQueueName(consumerBinding);

        AtomicBoolean wasRedelivered = new AtomicBoolean(false);
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, consumerProperties.getMaxAttempts(),
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    if (isRedelivered(msg)) {
                        log.info("Received redelivered message");
                        wasRedelivered.set(true);
                        callback.run();
                    } else {
                        log.info("Receiving message");
                        AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                        Objects.requireNonNull(ackCallback).noAutoAck();
                        AckUtils.reject(ackCallback);
                        callback.run();
                        throw new RuntimeException("expected exception");
                    }
                });

        assertThat(wasRedelivered.get()).isFalse();
        validateNumEnqueuedMessages(context, sempV2Api, queueName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, queueName, 0);
        validateNumAckedMessages(context, sempV2Api, queueName, 0);
        validateNumUnackedMessages(context, sempV2Api, queueName, 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testRequeueAndThrowException(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            SempV2Api sempV2Api,
            SpringCloudStreamContext context,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();
        String queueName = binder.getConsumerQueueName(consumerBinding);

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, consumerProperties.getMaxAttempts() + 2,
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    if (isRedelivered(msg)) {
                        log.info("Received redelivered message");
                        callback.run();
                    } else {
                        log.info("Receiving message");
                        AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                        Objects.requireNonNull(ackCallback).noAutoAck();
                        AckUtils.requeue(ackCallback);
                        callback.run();
                        throw new RuntimeException("expected exception");
                    }
                });

        validateNumEnqueuedMessages(context, sempV2Api, queueName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, queueName, messages.size());
        validateNumAckedMessages(context, sempV2Api, queueName, messages.size());
        validateNumUnackedMessages(context, sempV2Api, queueName, 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}, endpointType={1}")
    public void testNoAckAndThrowExceptionWithErrorQueue(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api,
            SpringCloudStreamContext context,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setQueueMaxMsgRedelivery(5);
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                group0, moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();

        String endpointName = binder.getConsumerQueueName(consumerBinding);
        String errorQueueName = binder.getConsumerErrorQueueName(consumerBinding);

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, consumerProperties.getMaxAttempts() + 1,
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                    Objects.requireNonNull(ackCallback).noAutoAck();
                    callback.run();
                    throw new RuntimeException("expected exception");
                });

        assertThat(errorQueueName).satisfies(errorQueueHasMessages(jcsmpSession, messages));
        validateNumEnqueuedMessages(context, sempV2Api, endpointName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, endpointName, 0);
        validateNumUnackedMessages(context, sempV2Api, endpointName, 0);
        validateNumEnqueuedMessages(context, sempV2Api, errorQueueName, 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testAcceptAndThrowExceptionWithErrorQueue(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            SempV2Api sempV2Api,
            SpringCloudStreamContext context,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0,
                group0, moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();

        String queueName = binder.getConsumerQueueName(consumerBinding);
        String errorQueueName = binder.getConsumerErrorQueueName(consumerBinding);

        final CountDownLatch redeliveredLatch = new CountDownLatch(1);
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, consumerProperties.getMaxAttempts(),
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    AcknowledgmentCallback acknowledgmentCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                    Objects.requireNonNull(acknowledgmentCallback).noAutoAck();
                    if (isRedelivered(msg)) {
                        log.info("Received redelivered message");
                        redeliveredLatch.countDown();
                    } else {
                        log.info("Receiving message");
                        AckUtils.accept(acknowledgmentCallback);
                        callback.run();
                        throw new RuntimeException("expected exception");
                    }
                });

        assertThat(redeliveredLatch.await(3, TimeUnit.SECONDS)).isFalse();

        validateNumEnqueuedMessages(context, sempV2Api, queueName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, queueName, 0);
        validateNumAckedMessages(context, sempV2Api, queueName, messages.size());
        validateNumUnackedMessages(context, sempV2Api, queueName, 0);
        validateNumEnqueuedMessages(context, sempV2Api, errorQueueName, 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testRejectAndThrowExceptionWithErrorQueue(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            SempV2Api sempV2Api,
            SpringCloudStreamContext context,
            JCSMPSession jcsmpSession,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0, group0,
                moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();

        String queueName = binder.getConsumerQueueName(consumerBinding);
        String errorQueueName = binder.getConsumerErrorQueueName(consumerBinding);

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, consumerProperties.getMaxAttempts(),
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                    Objects.requireNonNull(ackCallback).noAutoAck();
                    AckUtils.reject(ackCallback);
                    callback.run();
                    throw new RuntimeException("expected exception");
                });

        assertThat(errorQueueName).satisfies(errorQueueHasMessages(jcsmpSession, messages));
        validateNumEnqueuedMessages(context, sempV2Api, queueName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, queueName, 0);
        validateNumUnackedMessages(context, sempV2Api, queueName, 0);
        validateNumEnqueuedMessages(context, sempV2Api, errorQueueName, 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}")
    public void testRequeueAndThrowExceptionWithErrorQueue(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            SempV2Api sempV2Api,
            SpringCloudStreamContext context,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0, group0,
                moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        context.binderBindUnbindLatency();

        String queueName = binder.getConsumerQueueName(consumerBinding);
        String errorQueueName = binder.getConsumerErrorQueueName(consumerBinding);

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, consumerProperties.getMaxAttempts() + 2,
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    if (isRedelivered(msg)) {
                        log.info("Received redelivered message");
                        callback.run();
                    } else {
                        log.info("Receiving message");
                        AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(msg);
                        Objects.requireNonNull(ackCallback).noAutoAck();
                        AckUtils.requeue(ackCallback);
                        callback.run();
                        throw new RuntimeException("expected exception");
                    }
                });

        validateNumEnqueuedMessages(context, sempV2Api, queueName, 0);
        validateNumRedeliveredMessages(context, sempV2Api, queueName, messages.size());
        validateNumAckedMessages(context, sempV2Api, queueName, messages.size());
        validateNumUnackedMessages(context, sempV2Api, queueName, 0);
        validateNumEnqueuedMessages(context, sempV2Api, errorQueueName, 0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    private boolean isRedelivered(Message<?> message) {
        SoftAssertions isRedelivered = new SoftAssertions();
        isRedelivered.assertThat(message).satisfies(hasNestedHeader(SolaceHeaders.REDELIVERED, Boolean.class,
                v -> assertThat(v).isNotNull().isTrue()));
        return isRedelivered.wasSuccess();
    }

    private void validateNumEnqueuedMessages(SpringCloudStreamContext context,
                                             SempV2Api sempV2Api,
                                             String endpointName,
                                             int expectedCount) throws InterruptedException {
        String vpnName = (String) context.getJcsmpSession().getProperty(JCSMPProperties.VPN_NAME);
        retryAssert(1, TimeUnit.MINUTES, () -> {
            List<Object> messages = new ArrayList<>();
            Optional<String> cursor = Optional.empty();
            do {
                MonitorMsgVpnQueueMsgsResponse responseQ = sempV2Api.monitor()
                        .getMsgVpnQueueMsgs(vpnName, endpointName, Integer.MAX_VALUE, null,
                                null, null);
                cursor = Optional.ofNullable(responseQ.getMeta())
                        .map(MonitorSempMeta::getPaging)
                        .map(MonitorSempPaging::getCursorQuery);
                messages.addAll(responseQ.getData());
                break;
            } while (cursor.isPresent());
            assertThat(messages)
                    .as("Unexpected number of messages on endpoint %s", endpointName)
                    .hasSize(expectedCount);
        });
    }

    private void validateNumAckedMessages(SpringCloudStreamContext context,
                                          SempV2Api sempV2Api,
                                          String endpointName,
                                          int expectedCount) throws InterruptedException {
        retryAssert(
                () -> assertThat(
                        sempV2Api.monitor()
                                .getMsgVpnQueueTxFlows((String) context.getJcsmpSession().getProperty(JCSMPProperties.VPN_NAME),
                                        endpointName, 2, null, null, null)
                                .getData())
                        .as("Unexpected number of flows on queue %s", endpointName)
                        .hasSize(1)
                        .allSatisfy(txFlow -> assertThat(txFlow.getAckedMsgCount())
                                .as("Unexpected number of acked messages on flow %s of queue %s",
                                        txFlow.getFlowId(), endpointName)
                                .isEqualTo(expectedCount)));
    }

    private void validateNumUnackedMessages(SpringCloudStreamContext context,
                                            SempV2Api sempV2Api,
                                            String endpointName,
                                            int expectedCount) throws InterruptedException {
        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnQueueTxFlows((String) context.getJcsmpSession().getProperty(JCSMPProperties.VPN_NAME),
                        endpointName, 2, null, null, null)
                .getData())
                .as("Unexpected number of flows on queue %s", endpointName)
                .hasSize(1)
                .allSatisfy(txFlow -> assertThat(txFlow.getUnackedMsgCount())
                        .as("Unexpected number of unacked messages on flow %s of queue %s",
                                txFlow.getFlowId(), endpointName)
                        .isEqualTo(expectedCount)));

    }

    private void validateNumRedeliveredMessages(SpringCloudStreamContext context,
                                                SempV2Api sempV2Api,
                                                String endpointName,
                                                int expectedCount) throws InterruptedException {
        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnQueue((String) context.getJcsmpSession().getProperty(JCSMPProperties.VPN_NAME),
                        endpointName, null)
                .getData()
                .getRedeliveredMsgCount()
        ).as("Unexpected number of redeliveries on endpoint %s", endpointName)
                .isEqualTo(expectedCount));
    }
}

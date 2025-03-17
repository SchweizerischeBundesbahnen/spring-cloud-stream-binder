package com.solace.spring.cloud.stream.binder;

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceProvisioningUtil;
import com.solace.spring.cloud.stream.binder.test.junit.extension.SpringCloudStreamExtension;
import com.solace.spring.cloud.stream.binder.test.spring.ConsumerInfrastructureUtil;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.spring.cloud.stream.binder.test.util.ThrowingFunction;
import com.solace.spring.cloud.stream.binder.util.DestinationType;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.config.model.ConfigMsgVpnTopicEndpoint;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueue;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueueMsg;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueueTxFlow;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueueTxFlowResponse;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.Queue;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.junitpioneer.jupiter.cartesian.CartesianTest.Values;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.cloud.stream.binder.*;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.provisioning.ProvisioningException;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.MessagingException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.MimeTypeUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.solace.spring.cloud.stream.binder.test.util.RetryableAssertions.retryAssert;
import static com.solace.spring.cloud.stream.binder.test.util.SolaceSpringCloudStreamAssertions.errorQueueHasMessages;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * All tests which modify the default provisioning lifecycle.
 */
@Slf4j
@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(PubSubPlusExtension.class)
@ExtendWith(SpringCloudStreamExtension.class)
@Isolated
@DirtiesContext
public class SolaceBinderProvisioningLifecycleIT {

    @Test
    public void testConsumerProvisionDurableEndpoint(JCSMPSession jcsmpSession,
                                                     SpringCloudStreamContext context,
                                                     TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        assertThat(consumerProperties.getExtension().isProvisionDurableQueue()).isTrue();
        consumerProperties.getExtension().setProvisionDurableQueue(false);

        Endpoint endpoint = null;

        endpoint = JCSMPFactory.onlyInstance().createQueue(SolaceProvisioningUtil
                .getQueueNames(destination0, group0, consumerProperties, false)
                .getConsumerGroupQueueName());


        EndpointProperties endpointProperties = new EndpointProperties();
        endpointProperties.setPermission(EndpointProperties.PERMISSION_MODIFY_TOPIC);

        Binding<MessageChannel> producerBinding = null;
        Binding<MessageChannel> consumerBinding = null;

        try {
            log.info(String.format("Pre-provisioning endpoint %s with Permission %s", endpoint.getName(), endpointProperties.getPermission()));
            jcsmpSession.provision(endpoint, endpointProperties, JCSMPSession.WAIT_FOR_CONFIRM);

            producerBinding = binder.bindProducer(destination0, moduleOutputChannel, context.createProducerProperties(testInfo));
            consumerBinding = binder.bindConsumer(destination0, group0, moduleInputChannel, consumerProperties);

            Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                    .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                    .build();

            context.binderBindUnbindLatency();

            final CountDownLatch latch = new CountDownLatch(1);
            moduleInputChannel.subscribe(message1 -> {
                log.info(String.format("Received message %s", message1));
                latch.countDown();
            });

            moduleOutputChannel.send(message);
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume
        } finally {
            if (producerBinding != null) producerBinding.unbind();
            if (consumerBinding != null) consumerBinding.unbind();
            jcsmpSession.deprovision(endpoint, JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
        }
    }

    @Test
    public void testProducerProvisionDurableQueue(JCSMPSession jcsmpSession, SpringCloudStreamContext context,
                                                  TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        assertThat(producerProperties.getExtension().isProvisionDurableQueue()).isTrue();
        producerProperties.getExtension().setProvisionDurableQueue(false);
        producerProperties.setRequiredGroups(group0);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setProvisionDurableQueue(false);
        consumerProperties.getExtension().setAddDestinationAsSubscriptionToQueue(false);

        Queue queue = JCSMPFactory.onlyInstance().createQueue(SolaceProvisioningUtil
                .getQueueName(destination0, group0, producerProperties));
        EndpointProperties endpointProperties = new EndpointProperties();
        endpointProperties.setPermission(EndpointProperties.PERMISSION_MODIFY_TOPIC);

        Binding<MessageChannel> producerBinding = null;
        Binding<MessageChannel> consumerBinding = null;

        try {
            log.info(String.format("Pre-provisioning queue %s with Permission %s", queue.getName(), endpointProperties.getPermission()));
            jcsmpSession.provision(queue, endpointProperties, JCSMPSession.WAIT_FOR_CONFIRM);

            producerBinding = binder.bindProducer(destination0, moduleOutputChannel, producerProperties);
            consumerBinding = binder.bindConsumer(destination0, group0, moduleInputChannel, consumerProperties);

            Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                    .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                    .build();

            context.binderBindUnbindLatency();

            final CountDownLatch latch = new CountDownLatch(1);
            moduleInputChannel.subscribe(message1 -> {
                log.info(String.format("Received message %s", message1));
                latch.countDown();
            });

            moduleOutputChannel.send(message);
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume
        } finally {
            if (producerBinding != null) producerBinding.unbind();
            if (consumerBinding != null) consumerBinding.unbind();
            jcsmpSession.deprovision(queue, JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
        }
    }

    @Test
    public void testAnonConsumerProvisionEndpoint(SpringCloudStreamContext context,
                                                  TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        assertThat(consumerProperties.getExtension().isProvisionDurableQueue()).isTrue();
        consumerProperties.getExtension().setProvisionDurableQueue(false); // Expect this parameter to do nothing

        Binding<MessageChannel> producerBinding = binder.bindProducer(destination0, moduleOutputChannel, context.createProducerProperties(testInfo));
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(destination0, null, moduleInputChannel, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        context.binderBindUnbindLatency();

        final CountDownLatch latch = new CountDownLatch(1);
        moduleInputChannel.subscribe(message1 -> {
            log.info(String.format("Received message %s", message1));
            latch.countDown();
        });

        moduleOutputChannel.send(message);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testFailProducerProvisioningOnDisablingProvisionDurableQueue(SpringCloudStreamContext context,
                                                                             TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());

        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        assertThat(producerProperties.getExtension().isProvisionDurableQueue()).isTrue();
        producerProperties.getExtension().setProvisionDurableQueue(false);
        producerProperties.setRequiredGroups(group0);

        try {
            Binding<MessageChannel> producerBinding = binder.bindProducer(destination0, moduleOutputChannel, producerProperties);
            producerBinding.unbind();
            fail("Expected producer provisioning to fail due to missing queue");
        } catch (ProvisioningException e) {
            int expectedSubcodeEx = JCSMPErrorResponseSubcodeEx.UNKNOWN_QUEUE_NAME;
            assertThat(e).hasCauseInstanceOf(JCSMPErrorResponseException.class);
            assertThat(((JCSMPErrorResponseException) e.getCause()).getSubcodeEx()).isEqualTo(expectedSubcodeEx);
            log.info(String.format("Successfully threw a %s exception with cause %s, subcode: %s",
                    ProvisioningException.class.getSimpleName(), JCSMPErrorResponseException.class.getSimpleName(),
                    JCSMPErrorResponseSubcodeEx.getSubcodeAsString(expectedSubcodeEx)));
        }
    }

    @Test
    public void testConsumerProvisionErrorQueue(JCSMPSession jcsmpSession, SpringCloudStreamContext context)
            throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        assertThat(consumerProperties.getExtension().isProvisionErrorQueue()).isTrue();
        consumerProperties.getExtension().setProvisionErrorQueue(false);
        consumerProperties.getExtension().setAutoBindErrorQueue(true);

        String errorQueueName = SolaceProvisioningUtil
                .getQueueNames(destination0, group0, consumerProperties, false)
                .getErrorQueueName();
        Queue errorQueue = JCSMPFactory.onlyInstance().createQueue(errorQueueName);

        Binding<MessageChannel> consumerBinding = null;

        try {
            log.info(String.format("Pre-provisioning error queue %s", errorQueue.getName()));
            jcsmpSession.provision(errorQueue, new EndpointProperties(), JCSMPSession.WAIT_FOR_CONFIRM);

            consumerBinding = binder.bindConsumer(destination0, group0, moduleInputChannel, consumerProperties);
            context.binderBindUnbindLatency();
        } finally {
            if (consumerBinding != null) consumerBinding.unbind();
            jcsmpSession.deprovision(errorQueue, JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
        }
    }

    @Test
    public void testConsumerAddDestinationAsSubscriptionsToDurableQueue(JCSMPSession jcsmpSession,
                                                                        SpringCloudStreamContext context,
                                                                        TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        assertThat(consumerProperties.getExtension().isAddDestinationAsSubscriptionToQueue()).isTrue();
        consumerProperties.getExtension().setAddDestinationAsSubscriptionToQueue(false);

        Binding<MessageChannel> producerBinding = binder.bindProducer(destination0, moduleOutputChannel, context.createProducerProperties(testInfo));
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(destination0, group0, moduleInputChannel, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        context.binderBindUnbindLatency();

        final CountDownLatch latch = new CountDownLatch(1);
        moduleInputChannel.subscribe(message1 -> {
            log.info(String.format("Received message %s", message1));
            latch.countDown();
        });

        log.info(String.format("Checking that there is no subscription is on group %s of destination %s", group0, destination0));
        moduleOutputChannel.send(message);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isFalse();

        Queue queue = JCSMPFactory.onlyInstance().createQueue(binder.getConsumerQueueName(consumerBinding));
        Topic topic = JCSMPFactory.onlyInstance().createTopic(destination0);
        log.info(String.format("Subscribing queue %s to topic %s", queue.getName(), topic.getName()));
        jcsmpSession.addSubscription(queue, topic, JCSMPSession.WAIT_FOR_CONFIRM);

        log.info(String.format("Sending message to destination %s", destination0));
        moduleOutputChannel.send(message);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testProducerAddDestinationAsSubscriptionsToDurableQueue(JCSMPSession jcsmpSession,
                                                                        SpringCloudStreamContext context,
                                                                        TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        assertThat(producerProperties.getExtension().isAddDestinationAsSubscriptionToQueue()).isTrue();
        producerProperties.getExtension().setAddDestinationAsSubscriptionToQueue(false);
        producerProperties.setRequiredGroups(group0);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setProvisionDurableQueue(false);
        consumerProperties.getExtension().setAddDestinationAsSubscriptionToQueue(false);

        Binding<MessageChannel> producerBinding = binder.bindProducer(destination0, moduleOutputChannel, producerProperties);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(destination0, group0, moduleInputChannel, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        context.binderBindUnbindLatency();

        final CountDownLatch latch = new CountDownLatch(1);
        moduleInputChannel.subscribe(message1 -> {
            log.info(String.format("Received message %s", message1));
            latch.countDown();
        });

        log.info(String.format("Checking that there is no subscription is on group %s of destination %s", group0, destination0));
        moduleOutputChannel.send(message);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isFalse();

        Queue queue = JCSMPFactory.onlyInstance().createQueue(binder.getConsumerQueueName(consumerBinding));
        Topic topic = JCSMPFactory.onlyInstance().createTopic(destination0);
        log.info(String.format("Subscribing queue %s to topic %s", queue.getName(), topic.getName()));
        jcsmpSession.addSubscription(queue, topic, JCSMPSession.WAIT_FOR_CONFIRM);

        log.info(String.format("Sending message to destination %s", destination0));
        moduleOutputChannel.send(message);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    @Execution(ExecutionMode.SAME_THREAD)
    public void testQueueConsumerConcurrency(SempV2Api sempV2Api,
                                             SpringCloudStreamContext context,
                                             SoftAssertions softly,
                                             TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        int consumerConcurrency = 3;
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.setConcurrency(consumerConcurrency);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                destination0, group0, moduleInputChannel, consumerProperties);

        int numMsgsPerFlow = 10;
        Set<Message<?>> messages = new HashSet<>();
        Map<String, Instant> foundPayloads = new HashMap<>();
        for (int i = 0; i < numMsgsPerFlow * consumerConcurrency; i++) {
            String payload = "foo-" + i;
            foundPayloads.put(payload, null);
            messages.add(MessageBuilder.withPayload(payload.getBytes())
                    .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                    .build());
        }

        context.binderBindUnbindLatency();

        String queue0 = binder.getConsumerQueueName(consumerBinding);

        final long stallIntervalInMillis = 100;
        final CyclicBarrier barrier = new CyclicBarrier(consumerConcurrency);
        final CountDownLatch latch = new CountDownLatch(numMsgsPerFlow * consumerConcurrency);
        moduleInputChannel.subscribe(message1 -> {
            @SuppressWarnings("unchecked")
            List<byte[]> payloads = consumerProperties.isBatchMode() ? (List<byte[]>) message1.getPayload() :
                    Collections.singletonList((byte[]) message1.getPayload());
            for (byte[] payloadBytes : payloads) {
                String payload = new String(payloadBytes);
                synchronized (foundPayloads) {
                    if (!foundPayloads.containsKey(payload)) {
                        softly.fail("Received unexpected message %s", payload);
                        return;
                    }
                }
            }

            Instant timestamp;
            try { // Align the timestamps between the threads with a human-readable stall interval
                barrier.await();
                Thread.sleep(stallIntervalInMillis);
                timestamp = Instant.now();
                log.info("Received message {} (size: {}) (timestamp: {})",
                        StaticMessageHeaderAccessor.getId(message1), payloads.size(), timestamp);
            } catch (InterruptedException e) {
                log.error("Interrupt received", e);
                return;
            } catch (BrokenBarrierException e) {
                log.error("Unexpected barrier error", e);
                return;
            }

            for (byte[] payloadBytes : payloads) {
                String payload = new String(payloadBytes);
                synchronized (foundPayloads) {
                    foundPayloads.put(payload, timestamp);
                }
                latch.countDown();
            }
        });

        messages.forEach(moduleOutputChannel::send);

        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
        assertThat(foundPayloads).allSatisfy((payload, instant) -> {
            assertThat(instant).as("Did not receive message %s", payload).isNotNull();
            assertThat(foundPayloads.values()
                    .stream()
                    // Get "closest" messages with a margin of error of half the stalling interval
                    .filter(instant1 -> Duration.between(instant, instant1).abs()
                            .minusMillis(stallIntervalInMillis / 2).isNegative())
                    .count())
                    .as("Unexpected number of messages in parallel")
                    .isEqualTo((long) consumerConcurrency);
        });

        String msgVpnName = (String) context.getJcsmpSession().getProperty(JCSMPProperties.VPN_NAME);
        List<String> txFlowsIds = sempV2Api.monitor().getMsgVpnQueueTxFlows(
                        msgVpnName,
                        queue0,
                        Integer.MAX_VALUE, null, null, null)
                .getData()
                .stream()
                .map(MonitorMsgVpnQueueTxFlow::getFlowId)
                .map(String::valueOf)
                .collect(Collectors.toList());

        // only one flow, concurrency is handled internal
        assertThat(txFlowsIds).hasSize(1).allSatisfy(flowId -> retryAssert(() ->
                assertThat(sempV2Api.monitor()
                        .getMsgVpnQueueTxFlow(msgVpnName, queue0, flowId, null)
                        .getData()
                        .getAckedMsgCount())
                        .as("Expected all flows to receive exactly %s messages", numMsgsPerFlow)
                        .isEqualTo(numMsgsPerFlow * consumerConcurrency)));

        retryAssert(() -> assertThat(txFlowsIds.stream()
                .map((ThrowingFunction<String, MonitorMsgVpnQueueTxFlowResponse>)
                        flowId -> sempV2Api.monitor().getMsgVpnQueueTxFlow(msgVpnName, queue0, flowId, null))
                .mapToLong(r -> r.getData().getAckedMsgCount())
                .sum())
                .as("Flows did not receive expected number of messages")
                .isEqualTo((long) numMsgsPerFlow * consumerConcurrency));

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testFailConsumerConcurrencyWithExclusiveQueue(SpringCloudStreamContext context) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.setConcurrency(2);
        consumerProperties.getExtension().setQueueAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);

        try {
            Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                    destination0, group0, moduleInputChannel, consumerProperties);
            consumerBinding.unbind();
            fail("Expected consumer provisioning to fail");
        } catch (ProvisioningException e) {
            assertThat(e).hasNoCause();
            assertThat(e.getMessage()).containsIgnoringCase("not supported");
            assertThat(e.getMessage()).containsIgnoringCase("concurrency > 1");
            assertThat(e.getMessage()).containsIgnoringCase("exclusive queue");
        }
    }

    @Test
    public void testFailConsumerConcurrencyWithAnonConsumerGroup(SpringCloudStreamContext context) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        String destination0 = RandomStringUtils.randomAlphanumeric(50);

        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.setConcurrency(2);

        try {
            Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                    destination0, null, moduleInputChannel, consumerProperties);
            consumerBinding.unbind();
            fail("Expected consumer provisioning to fail");
        } catch (ProvisioningException e) {
            assertThat(e).hasNoCause();
            assertThat(e.getMessage()).containsIgnoringCase("not supported");
            assertThat(e.getMessage()).containsIgnoringCase("concurrency > 1");
            assertThat(e.getMessage()).containsIgnoringCase("anonymous consumer groups");
        }
    }

    @Test
    public void testFailConsumerWithConcurrencyLessThan1(SpringCloudStreamContext context) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.setConcurrency(0);

        try {
            Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                    destination0, group0, moduleInputChannel, consumerProperties);
            consumerBinding.unbind();
            fail("Expected consumer provisioning to fail");
        } catch (BinderException e) {
            assertThat(e).hasCauseInstanceOf(MessagingException.class);
            assertThat(e.getCause()).hasCauseInstanceOf(MessagingException.class);
            assertThat(e.getCause().getCause().getMessage()).containsIgnoringCase("concurrency must be greater than 0");
        }
    }

    @Test
    public void testConsumerProvisionWildcardDurableQueue(SpringCloudStreamContext context, TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String topic0Prefix = IntStream.range(0, 2)
                .mapToObj(i -> RandomStringUtils.randomAlphanumeric(10))
                .collect(Collectors.joining(context.getDestinationNameDelimiter()));
        String topic0 = topic0Prefix + IntStream.range(0, 3)
                .mapToObj(i -> RandomStringUtils.randomAlphanumeric(10))
                .collect(Collectors.joining("/"));
        String topic1Prefix = RandomStringUtils.randomAlphanumeric(30);
        String topic1 = topic1Prefix + RandomStringUtils.randomAlphanumeric(20);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setQueueAdditionalSubscriptions(new String[]{topic1Prefix + "*"});

        Message<?> message0 = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();
        Message<?> message1 = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .setHeader(BinderHeaders.TARGET_DESTINATION, topic1)
                .build();

        Binding<MessageChannel> producerBinding = null;
        Binding<MessageChannel> consumerBinding = null;

        try {
            producerBinding = binder.bindProducer(topic0, moduleOutputChannel, context.createProducerProperties(testInfo));
            consumerBinding = binder.bindConsumer(String.format("%s*/>", topic0Prefix),
                    RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

            context.binderBindUnbindLatency();

            final CountDownLatch latch0 = new CountDownLatch(1);
            final CountDownLatch latch1 = new CountDownLatch(1);
            moduleInputChannel.subscribe(msg -> {
                log.info(String.format("Received message %s", msg));
                Destination destination = msg.getHeaders().get(SolaceHeaders.DESTINATION, Destination.class);
                assertThat(destination).isNotNull();
                if (topic0.equals(destination.getName())) {
                    latch0.countDown();
                } else if (topic1.equals(destination.getName())) {
                    latch1.countDown();
                }
            });

            moduleOutputChannel.send(message0);
            moduleOutputChannel.send(message1);
            assertThat(latch0.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(latch1.await(10, TimeUnit.SECONDS)).isTrue();
            TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume
        } finally {
            if (producerBinding != null) producerBinding.unbind();
            if (consumerBinding != null) consumerBinding.unbind();
        }
    }

    @Test
    public void testProducerProvisionWildcardDurableQueue(JCSMPSession jcsmpSession,
                                                          SpringCloudStreamContext context,
                                                          TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());

        String topic0 = String.format("%s*/>", IntStream.range(0, 2)
                .mapToObj(i -> RandomStringUtils.randomAlphanumeric(10))
                .collect(Collectors.joining(context.getDestinationNameDelimiter())));
        String topic1Prefix = RandomStringUtils.randomAlphanumeric(30);
        String topic1 = topic1Prefix + RandomStringUtils.randomAlphanumeric(20);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        producerProperties.setRequiredGroups(group0);
        producerProperties.getExtension().setQueueAdditionalSubscriptions(
                Collections.singletonMap(group0, new String[]{topic1Prefix + "*"}));

        Message<?> message0 = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();
        Message<?> message1 = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .setHeader(BinderHeaders.TARGET_DESTINATION, topic1)
                .build();

        Binding<MessageChannel> producerBinding = null;
        FlowReceiver flowReceiver = null;

        try {
            producerBinding = binder.bindProducer(topic0, moduleOutputChannel, producerProperties);
            context.binderBindUnbindLatency();

            final CountDownLatch latch0 = new CountDownLatch(1);
            final CountDownLatch latch1 = new CountDownLatch(1);

            ConsumerFlowProperties consumerFlowProperties = new ConsumerFlowProperties();
            consumerFlowProperties.setStartState(true);
            consumerFlowProperties.setEndpoint(JCSMPFactory.onlyInstance().createQueue(SolaceProvisioningUtil
                    .getQueueName(topic0, group0, producerProperties)));
            flowReceiver = jcsmpSession.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage bytesXMLMessage) {
                    log.info(String.format("Received message %s", bytesXMLMessage));
                    Destination destination = bytesXMLMessage.getDestination();
                    assertThat(destination).isNotNull();
                    if (topic0.equals(destination.getName())) {
                        latch0.countDown();
                    } else if (topic1.equals(destination.getName())) {
                        latch1.countDown();
                    }
                }

                @Override
                public void onException(JCSMPException e) {
                }
            }, consumerFlowProperties, new EndpointProperties());

            moduleOutputChannel.send(message0);
            moduleOutputChannel.send(message1);

            assertThat(latch0.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(latch1.await(10, TimeUnit.SECONDS)).isTrue();
            TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume
        } finally {
            if (producerBinding != null) producerBinding.unbind();
            if (flowReceiver != null) flowReceiver.close();
        }
    }

    @Test
    public void testAnonConsumerProvisionWildcardDurableQueue(SpringCloudStreamContext context, TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String topic0Prefix = IntStream.range(0, 2)
                .mapToObj(i -> RandomStringUtils.randomAlphanumeric(10))
                .collect(Collectors.joining(context.getDestinationNameDelimiter()));
        String topic0 = topic0Prefix + IntStream.range(0, 3)
                .mapToObj(i -> RandomStringUtils.randomAlphanumeric(10))
                .collect(Collectors.joining("/"));
        String topic1Prefix = RandomStringUtils.randomAlphanumeric(30);
        String topic1 = topic1Prefix + RandomStringUtils.randomAlphanumeric(20);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setQueueAdditionalSubscriptions(new String[]{topic1Prefix + "*"});

        Message<?> message0 = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();
        Message<?> message1 = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .setHeader(BinderHeaders.TARGET_DESTINATION, topic1)
                .build();

        Binding<MessageChannel> producerBinding = null;
        Binding<MessageChannel> consumerBinding = null;

        try {
            producerBinding = binder.bindProducer(topic0, moduleOutputChannel, context.createProducerProperties(testInfo));
            consumerBinding = binder.bindConsumer(String.format("%s*/>", topic0Prefix),
                    null, moduleInputChannel, consumerProperties);

            context.binderBindUnbindLatency();

            final CountDownLatch latch0 = new CountDownLatch(1);
            final CountDownLatch latch1 = new CountDownLatch(1);
            moduleInputChannel.subscribe(msg -> {
                log.info(String.format("Received message %s", msg));
                Destination destination = msg.getHeaders().get(SolaceHeaders.DESTINATION, Destination.class);
                assertThat(destination).isNotNull();
                if (topic0.equals(destination.getName())) {
                    latch0.countDown();
                } else if (topic1.equals(destination.getName())) {
                    latch1.countDown();
                }
            });

            moduleOutputChannel.send(message0);
            moduleOutputChannel.send(message1);
            assertThat(latch0.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(latch1.await(10, TimeUnit.SECONDS)).isTrue();
            TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume
        } finally {
            if (producerBinding != null) producerBinding.unbind();
            if (consumerBinding != null) consumerBinding.unbind();
        }
    }

    @Test
    public void testConsumerProvisionWildcardErrorQueue(JCSMPSession jcsmpSession,
                                                        SempV2Api sempV2Api,
                                                        SpringCloudStreamContext context,
                                                        TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0Prefix = IntStream.range(0, 2)
                .mapToObj(i -> RandomStringUtils.randomAlphanumeric(10))
                .collect(Collectors.joining(context.getDestinationNameDelimiter()));
        String destination0 = destination0Prefix + IntStream.range(0, 3)
                .mapToObj(i -> RandomStringUtils.randomAlphanumeric(10))
                .collect(Collectors.joining("/"));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setAutoBindErrorQueue(true);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        Binding<MessageChannel> producerBinding = null;
        Binding<MessageChannel> consumerBinding = null;

        try {
            producerBinding = binder.bindProducer(destination0, moduleOutputChannel,
                    context.createProducerProperties(testInfo));
            consumerBinding = binder.bindConsumer(String.format("%s*/>", destination0Prefix),
                    RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

            context.binderBindUnbindLatency();

            moduleInputChannel.subscribe(message1 -> {
                throw new RuntimeException("Throwing expected exception!");
            });

            moduleOutputChannel.send(message);

            final ConsumerFlowProperties errorQueueFlowProperties = new ConsumerFlowProperties();
            errorQueueFlowProperties.setStartState(true);
            errorQueueFlowProperties.setEndpoint(JCSMPFactory.onlyInstance().createQueue(
                    binder.getConsumerErrorQueueName(consumerBinding)));
            FlowReceiver flowReceiver = null;
            try {
                flowReceiver = jcsmpSession.createFlow(null, errorQueueFlowProperties);
                assertThat(flowReceiver.receive((int) TimeUnit.SECONDS.toMillis(10))).isNotNull();
            } finally {
                if (flowReceiver != null) {
                    flowReceiver.close();
                }
            }

            // Give some time for the message to actually ack off the original queue
            Thread.sleep(TimeUnit.SECONDS.toMillis(3));

            List<MonitorMsgVpnQueueMsg> enqueuedMessages = sempV2Api.monitor()
                    .getMsgVpnQueueMsgs((String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME),
                            binder.getConsumerQueueName(consumerBinding),
                            2, null, null, null)
                    .getData();
            assertThat(enqueuedMessages).hasSize(0);
        } finally {
            if (producerBinding != null) producerBinding.unbind();
            if (consumerBinding != null) consumerBinding.unbind();
        }
    }

    @Test
    public void testConsumerProvisionIgnoreGroupNameInQueueName(JCSMPSession jcsmpSession,
                                                                SempV2Api sempV2Api,
                                                                SpringCloudStreamContext context,
                                                                TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setQueueNameExpression("'scst/' + (isAnonymous ? 'an/' : 'wk/')  + 'plain/' + destination.trim().replaceAll('[*>]', '_')");
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                destination0, group0, moduleInputChannel, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        context.binderBindUnbindLatency();

        String queueName = binder.getConsumerQueueName(consumerBinding);
        assertThat(queueName).doesNotContain('/' + group0 + '/');

        String errorQueueName = binder.getConsumerErrorQueueName(consumerBinding);
        assertThat(errorQueueName).contains('/' + group0 + '/');

        CountDownLatch latch = new CountDownLatch(consumerProperties.getMaxAttempts());
        moduleInputChannel.subscribe(message1 -> {
            latch.countDown();
            throw new RuntimeException("Throwing expected exception!");
        });

        log.info(String.format("Sending message to destination %s: %s", destination0, message));
        moduleOutputChannel.send(message);

        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();

        final ConsumerFlowProperties errorQueueFlowProperties = new ConsumerFlowProperties();
        errorQueueFlowProperties.setEndpoint(JCSMPFactory.onlyInstance().createQueue(errorQueueName));
        errorQueueFlowProperties.setStartState(true);
        FlowReceiver flowReceiver = null;
        try {
            flowReceiver = jcsmpSession.createFlow(null, errorQueueFlowProperties);
            assertThat(flowReceiver.receive((int) TimeUnit.SECONDS.toMillis(10))).isNotNull();
        } finally {
            if (flowReceiver != null) {
                flowReceiver.close();
            }
        }

        // Give some time for the message to actually ack off the original queue
        Thread.sleep(TimeUnit.SECONDS.toMillis(3));

        List<MonitorMsgVpnQueueMsg> enqueuedMessages = sempV2Api.monitor()
                .getMsgVpnQueueMsgs((String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME), queueName, 2,
                        null, null, null)
                .getData();
        assertThat(enqueuedMessages).hasSize(0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testAnonConsumerProvisionIgnoreGroupNameInQueueName(JCSMPSession jcsmpSession,
                                                                    SempV2Api sempV2Api,
                                                                    SpringCloudStreamContext context,
                                                                    TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setQueueNameExpression("'scst/' + (isAnonymous ? 'an/' : 'wk/')  + 'plain/' + destination.trim().replaceAll('[*>]', '_')");
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                destination0, null, moduleInputChannel, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        context.binderBindUnbindLatency();

        String queueName = binder.getConsumerQueueName(consumerBinding);
        String errorQueueName = binder.getConsumerErrorQueueName(consumerBinding);

        CountDownLatch latch = new CountDownLatch(consumerProperties.getMaxAttempts());
        moduleInputChannel.subscribe(message1 -> {
            latch.countDown();
            throw new RuntimeException("Throwing expected exception!");
        });

        log.info(String.format("Sending message to destination %s: %s", destination0, message));
        moduleOutputChannel.send(message);

        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();

        final ConsumerFlowProperties errorQueueFlowProperties = new ConsumerFlowProperties();
        errorQueueFlowProperties.setEndpoint(JCSMPFactory.onlyInstance().createQueue(errorQueueName));
        errorQueueFlowProperties.setStartState(true);
        FlowReceiver flowReceiver = null;
        try {
            flowReceiver = jcsmpSession.createFlow(null, errorQueueFlowProperties);
            assertThat(flowReceiver.receive((int) TimeUnit.SECONDS.toMillis(10))).isNotNull();
        } finally {
            if (flowReceiver != null) {
                flowReceiver.close();
            }
        }

        // Give some time for the message to actually ack off the original queue
        Thread.sleep(TimeUnit.SECONDS.toMillis(3));

        List<MonitorMsgVpnQueueMsg> enqueuedMessages = sempV2Api.monitor()
                .getMsgVpnQueueMsgs((String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME), queueName, 2, null, null, null)
                .getData();
        assertThat(enqueuedMessages).hasSize(0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testConsumerProvisionIgnoreGroupNameInErrorQueueName(JCSMPSession jcsmpSession,
                                                                     SempV2Api sempV2Api,
                                                                     SpringCloudStreamContext context,
                                                                     TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setErrorQueueNameExpression("'scst/error/' + (isAnonymous ? 'an/' : 'wk/') + 'plain/' + destination.trim().replaceAll('[*>]', '_')");
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                destination0, group0, moduleInputChannel, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        context.binderBindUnbindLatency();

        String queueName = binder.getConsumerQueueName(consumerBinding);
        assertThat(queueName).contains('/' + group0 + '/');

        String errorQueueName = binder.getConsumerErrorQueueName(consumerBinding);
        assertThat(errorQueueName).doesNotContain('/' + group0 + '/');

        CountDownLatch latch = new CountDownLatch(consumerProperties.getMaxAttempts());
        moduleInputChannel.subscribe(message1 -> {
            latch.countDown();
            throw new RuntimeException("Throwing expected exception!");
        });

        log.info(String.format("Sending message to destination %s: %s", destination0, message));
        moduleOutputChannel.send(message);

        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();

        final ConsumerFlowProperties errorQueueFlowProperties = new ConsumerFlowProperties();
        errorQueueFlowProperties.setEndpoint(JCSMPFactory.onlyInstance().createQueue(errorQueueName));
        errorQueueFlowProperties.setStartState(true);
        FlowReceiver flowReceiver = null;
        try {
            flowReceiver = jcsmpSession.createFlow(null, errorQueueFlowProperties);
            assertThat(flowReceiver.receive((int) TimeUnit.SECONDS.toMillis(10))).isNotNull();
        } finally {
            if (flowReceiver != null) {
                flowReceiver.close();
            }
        }

        // Give some time for the message to actually ack off the original queue
        Thread.sleep(TimeUnit.SECONDS.toMillis(3));

        List<MonitorMsgVpnQueueMsg> enqueuedMessages = sempV2Api.monitor()
                .getMsgVpnQueueMsgs((String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME), queueName, 2, null, null, null)
                .getData();
        assertThat(enqueuedMessages).hasSize(0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testAnonConsumerProvisionIgnoreGroupNameInErrorQueueName(JCSMPSession jcsmpSession,
                                                                         SempV2Api sempV2Api,
                                                                         SpringCloudStreamContext context,
                                                                         TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setErrorQueueNameExpression("'scst/error/' + (isAnonymous ? 'an/' : 'wk/') + 'plain/' + destination.trim().replaceAll('[*>]', '_')");
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                destination0, null, moduleInputChannel, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        context.binderBindUnbindLatency();

        String queueName = binder.getConsumerQueueName(consumerBinding);
        String errorQueueName = binder.getConsumerErrorQueueName(consumerBinding);

        CountDownLatch latch = new CountDownLatch(consumerProperties.getMaxAttempts());
        moduleInputChannel.subscribe(message1 -> {
            latch.countDown();
            throw new RuntimeException("Throwing expected exception!");
        });

        log.info(String.format("Sending message to destination %s: %s", destination0, message));
        moduleOutputChannel.send(message);

        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();

        final ConsumerFlowProperties errorQueueFlowProperties = new ConsumerFlowProperties();
        errorQueueFlowProperties.setEndpoint(JCSMPFactory.onlyInstance().createQueue(errorQueueName));
        errorQueueFlowProperties.setStartState(true);
        FlowReceiver flowReceiver = null;
        try {
            flowReceiver = jcsmpSession.createFlow(null, errorQueueFlowProperties);
            assertThat(flowReceiver.receive((int) TimeUnit.SECONDS.toMillis(10))).isNotNull();
        } finally {
            if (flowReceiver != null) {
                flowReceiver.close();
            }
        }

        // Give some time for the message to actually ack off the original queue
        Thread.sleep(TimeUnit.SECONDS.toMillis(3));

        List<MonitorMsgVpnQueueMsg> enqueuedMessages = sempV2Api.monitor()
                .getMsgVpnQueueMsgs((String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME), queueName, 2, null, null, null)
                .getData();
        assertThat(enqueuedMessages).hasSize(0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testConsumerProvisionErrorQueueNameOverride(JCSMPSession jcsmpSession,
                                                            SempV2Api sempV2Api,
                                                            SpringCloudStreamContext context,
                                                            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        final String givenErrorQueueName = RandomStringUtils.randomAlphanumeric(100);
        consumerProperties.getExtension().setErrorQueueNameExpression(String.format("'%s'", givenErrorQueueName));
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                destination0, group0, moduleInputChannel, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        context.binderBindUnbindLatency();

        String queueName = binder.getConsumerQueueName(consumerBinding);
        assertThat(queueName).contains('/' + group0 + '/');
        assertThat(queueName).endsWith('/' + destination0);

        String errorQueueName = binder.getConsumerErrorQueueName(consumerBinding);
        assertThat(errorQueueName).isEqualTo(givenErrorQueueName);

        CountDownLatch latch = new CountDownLatch(consumerProperties.getMaxAttempts());
        moduleInputChannel.subscribe(message1 -> {
            latch.countDown();
            throw new RuntimeException("Throwing expected exception!");
        });

        log.info(String.format("Sending message to destination %s: %s", destination0, message));
        moduleOutputChannel.send(message);

        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();

        final ConsumerFlowProperties errorQueueFlowProperties = new ConsumerFlowProperties();
        errorQueueFlowProperties.setEndpoint(JCSMPFactory.onlyInstance().createQueue(errorQueueName));
        errorQueueFlowProperties.setStartState(true);
        FlowReceiver flowReceiver = null;
        try {
            flowReceiver = jcsmpSession.createFlow(null, errorQueueFlowProperties);
            assertThat(flowReceiver.receive((int) TimeUnit.SECONDS.toMillis(10))).isNotNull();
        } finally {
            if (flowReceiver != null) {
                flowReceiver.close();
            }
        }

        // Give some time for the message to actually ack off the original queue
        Thread.sleep(TimeUnit.SECONDS.toMillis(3));

        List<MonitorMsgVpnQueueMsg> enqueuedMessages = sempV2Api.monitor()
                .getMsgVpnQueueMsgs((String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME), queueName, 2, null, null, null)
                .getData();
        assertThat(enqueuedMessages).hasSize(0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testAnonConsumerProvisionErrorQueueNameOverride(JCSMPSession jcsmpSession,
                                                                SempV2Api sempV2Api,
                                                                SpringCloudStreamContext context,
                                                                TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, context.createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        final String givenErrorQueueName = RandomStringUtils.randomAlphanumeric(100);
        consumerProperties.getExtension().setErrorQueueNameExpression(String.format("'%s'", givenErrorQueueName));
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                destination0, null, moduleInputChannel, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        context.binderBindUnbindLatency();

        String queueName = binder.getConsumerQueueName(consumerBinding);
        assertThat(queueName).endsWith('/' + destination0);

        String errorQueueName = binder.getConsumerErrorQueueName(consumerBinding);
        assertThat(errorQueueName).isEqualTo(givenErrorQueueName);

        CountDownLatch latch = new CountDownLatch(consumerProperties.getMaxAttempts());
        moduleInputChannel.subscribe(message1 -> {
            latch.countDown();
            throw new RuntimeException("Throwing expected exception!");
        });

        log.info(String.format("Sending message to destination %s: %s", destination0, message));
        moduleOutputChannel.send(message);

        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();

        final ConsumerFlowProperties errorQueueFlowProperties = new ConsumerFlowProperties();
        errorQueueFlowProperties.setEndpoint(JCSMPFactory.onlyInstance().createQueue(errorQueueName));
        errorQueueFlowProperties.setStartState(true);
        FlowReceiver flowReceiver = null;
        try {
            flowReceiver = jcsmpSession.createFlow(null, errorQueueFlowProperties);
            assertThat(flowReceiver.receive((int) TimeUnit.SECONDS.toMillis(10))).isNotNull();
        } finally {
            if (flowReceiver != null) {
                flowReceiver.close();
            }
        }

        // Give some time for the message to actually ack off the original queue
        Thread.sleep(TimeUnit.SECONDS.toMillis(3));

        List<MonitorMsgVpnQueueMsg> enqueuedMessages = sempV2Api.monitor()
                .getMsgVpnQueueMsgs((String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME), queueName, 2, null, null, null)
                .getData();
        assertThat(enqueuedMessages).hasSize(0);

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testConsumerProvisionWithQueueNameExpressionResolvingToWhiteSpaces(SpringCloudStreamContext context) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setQueueNameExpression("'   '");

        try {
            binder.bindConsumer(destination0, group0, moduleInputChannel, consumerProperties);
            fail("Expected provisioning to fail due to empty queue name");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(ProvisioningException.class);
            assertThat(e.getMessage()).isEqualTo("Invalid SpEL expression '   ' as it resolves to a String that does not contain actual text.");
        }
    }

    @Test
    public void testConsumerProvisionWithQueueNameTooLong(SpringCloudStreamContext context) throws Exception {
        int MAX_QUEUE_NAME_LENGTH = 200;
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(50);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        String longQueueName = RandomStringUtils.randomAlphanumeric(MAX_QUEUE_NAME_LENGTH + 1);
        consumerProperties.getExtension().setQueueNameExpression("'" + longQueueName + "'");

        try {
            binder.bindConsumer(destination0, group0, moduleInputChannel, consumerProperties);
            fail("Expected provisioning to fail due to queue name exceeding number of allowed characters");
        } catch (Exception e) {
            assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class);
            assertEquals("Queue name \"" + longQueueName + "\" must have a maximum length of 200", e.getCause().getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(classes = {DirectChannel.class})
    public <T> void testConsumerNoAutoStart(
            Class<T> channelType,
            SpringCloudStreamContext context,
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);

        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());
        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.setAutoStartup(false);
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        String queueName = binder.getConsumerQueueName(consumerBinding);

        assertThat(consumerBinding.isRunning()).isFalse();
        assertThat(consumerBinding.isPaused()).isFalse();
        assertThat(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queueName, null)
                .getData()
                .getBindRequestCount())
                .isEqualTo(0);

        consumerBinding.start();

        assertThat(consumerBinding.isRunning()).isTrue();
        assertThat(consumerBinding.isPaused()).isFalse();
        assertThat(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queueName, null)
                .getData())
                .satisfies(queueData -> {
                    assertThat(queueData.getBindRequestCount()).isEqualTo(1);
                    assertThat(queueData.getBindSuccessCount()).isEqualTo(1);
                });

        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] channelType={0}, isDurable={1}")
    public <T> void testConsumerNoAutoStartAndQueueNotExist(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            @Values(booleans = {false, true}) boolean isDurable,
            SpringCloudStreamContext context,
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);

        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());
        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = isDurable ? RandomStringUtils.randomAlphanumeric(10) : null;

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.setAutoStartup(false);
        consumerProperties.getExtension().setProvisionDurableQueue(false);
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, group0, moduleInputChannel, consumerProperties);

        assertThat(consumerBinding.isRunning()).isFalse();
        assertThat(consumerBinding.isPaused()).isFalse();
        assertThat(sempV2Api.monitor()
                .getMsgVpnClient(vpnName, (String) jcsmpSession.getProperty(JCSMPProperties.CLIENT_NAME), null)
                .getData()
                .getBindRequestCount())
                .isEqualTo(0);

        if (isDurable) {
            assertThatThrownBy(consumerBinding::start).getRootCause().hasMessageContaining("Unknown Queue");

            Queue queue = JCSMPFactory.onlyInstance().createQueue(binder.getConsumerQueueName(consumerBinding));
            log.info("Trying to connect consumer to a newly provisioned queue {}", queue.getName());
            Binding<MessageChannel> producerBinding = null;
            try {
                EndpointProperties endpointProperties = new EndpointProperties();
                endpointProperties.setPermission(EndpointProperties.PERMISSION_MODIFY_TOPIC);
                log.info("Provisioning queue {} with Permission {}", queue.getName(),
                        endpointProperties.getPermission());
                jcsmpSession.provision(queue, endpointProperties, JCSMPSession.WAIT_FOR_CONFIRM);

                DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
                producerBinding = binder.bindProducer(destination0, moduleOutputChannel,
                        context.createProducerProperties(testInfo));

                consumerBinding.start();

                assertThat(consumerBinding.isRunning()).isTrue();
                assertThat(consumerBinding.isPaused()).isFalse();
                assertThat(sempV2Api.monitor()
                        .getMsgVpnQueueTxFlows(vpnName, queue.getName(), 2, null, null, null)
                        .getData())
                        .hasSize(1);

                context.binderBindUnbindLatency();

                consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 1,
                        () -> moduleOutputChannel.send(MessageBuilder.withPayload("foo".getBytes())
                                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                                .build()),
                        msg -> log.info(String.format("Received message %s", msg)));
            } finally {
                if (producerBinding != null) producerBinding.unbind();
                consumerBinding.unbind();
                jcsmpSession.deprovision(queue, JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
            }
        }
    }


    @ParameterizedTest(name = "[{index}] channelType={0}, isDurable={1}")
    @ValueSource(classes = {DirectChannel.class})
    public <T> void testConsumerNoAutoStartAndErrorQueueNotExist(
            Class<T> channelType,
            SpringCloudStreamContext context,
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);

        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());
        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.setAutoStartup(false);
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        consumerProperties.getExtension().setProvisionErrorQueue(false);
        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, group0, moduleInputChannel, consumerProperties);

        assertThat(consumerBinding.isRunning()).isFalse();
        assertThat(consumerBinding.isPaused()).isFalse();
        assertThat(sempV2Api.monitor()
                .getMsgVpnClient(vpnName, (String) jcsmpSession.getProperty(JCSMPProperties.CLIENT_NAME), null)
                .getData()
                .getBindRequestCount())
                .isEqualTo(0);

        consumerBinding.start();
        context.binderBindUnbindLatency();

        Queue errorQueue = JCSMPFactory.onlyInstance().createQueue(binder.getConsumerErrorQueueName(consumerBinding));
        Binding<MessageChannel> producerBinding = null;
        try {
            log.info("Provisioning error queue {}", errorQueue.getName());
            jcsmpSession.provision(errorQueue, new EndpointProperties(), JCSMPSession.WAIT_FOR_CONFIRM);

            DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
            producerBinding = binder.bindProducer(destination0, moduleOutputChannel,
                    context.createProducerProperties(testInfo));

            Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                    .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                    .build();

            consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, consumerProperties.getMaxAttempts(),
                    () -> moduleOutputChannel.send(message),
                    (msg, callback) -> {
                        callback.run();
                        throw new RuntimeException("Throwing expected exception!");
                    });

            assertThat(binder.getConsumerErrorQueueName(consumerBinding))
                    .satisfies(errorQueueHasMessages(jcsmpSession, Collections.singletonList(message)));
            retryAssert(() -> assertThat(sempV2Api.monitor()
                    .getMsgVpnQueueMsgs(vpnName, binder.getConsumerQueueName(consumerBinding), 2, null, null, null)
                    .getData())
                    .hasSize(0));
        } finally {
            if (producerBinding != null) producerBinding.unbind();
            consumerBinding.unbind();
            jcsmpSession.deprovision(errorQueue, JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
        }
    }

    @Test
    public void testProducerNoAutoStart(
            SpringCloudStreamContext context,
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());

        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        producerProperties.setAutoStartup(false);
        producerProperties.setRequiredGroups(RandomStringUtils.randomAlphanumeric(10));
        Binding<?> producerBinding = binder.bindProducer(destination0, moduleOutputChannel, producerProperties);

        assertThat(producerBinding.isRunning()).isFalse();
        assertThat(producerBinding.isPaused()).isFalse();
        assertThat(sempV2Api.monitor()
                .getMsgVpnClient(vpnName, (String) jcsmpSession.getProperty(JCSMPProperties.CLIENT_NAME), null)
                .getData()
                .getBindRequestCount())
                .isEqualTo(0);

        producerBinding.start();
        assertThat(producerBinding.isRunning()).isTrue();
        assertThat(producerBinding.isPaused()).isFalse();

        producerBinding.unbind();
    }

    @Test
    public void testProducerNoAutoStartAndQueueNotExist(
            SpringCloudStreamContext context,
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());

        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        producerProperties.setAutoStartup(false);
        producerProperties.setRequiredGroups(RandomStringUtils.randomAlphanumeric(10));
        producerProperties.getExtension().setProvisionDurableQueue(false);
        producerProperties.getExtension().setAddDestinationAsSubscriptionToQueue(false);
        Binding<?> producerBinding = binder.bindProducer(destination0, moduleOutputChannel, producerProperties);

        assertThat(producerBinding.isRunning()).isFalse();
        assertThat(producerBinding.isPaused()).isFalse();
        assertThat(sempV2Api.monitor()
                .getMsgVpnClient(vpnName, (String) jcsmpSession.getProperty(JCSMPProperties.CLIENT_NAME), null)
                .getData()
                .getBindRequestCount())
                .isEqualTo(0);

        producerBinding.start();
        assertThat(producerBinding.isRunning()).isTrue();
        assertThat(producerBinding.isPaused()).isFalse();

        producerBinding.stop();
    }

    @Test
    public void testQueueProvisioningWithProducerDestinationTypeSetToQueue(JCSMPSession jcsmpSession, SpringCloudStreamContext context, SempV2Api sempV2Api,
                                                                           SoftAssertions softly, TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);

        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());

        String destination = RandomStringUtils.randomAlphanumeric(20);

        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        producerProperties.getExtension().setDestinationType(DestinationType.QUEUE);
        assertThat(producerProperties.getExtension().isProvisionDurableQueue()).isTrue();
        assertThat(producerProperties.getExtension().getQueueAccessType()).isEqualTo(EndpointProperties.ACCESSTYPE_NONEXCLUSIVE);

        Queue queue = JCSMPFactory.onlyInstance().createQueue(destination);
        Binding<MessageChannel> producerBinding = null;

        try {
            producerBinding = binder.bindProducer(destination, moduleOutputChannel, producerProperties);

            MonitorMsgVpnQueue vpnQueue = sempV2Api.monitor().getMsgVpnQueue(vpnName, destination, null).getData();
            softly.assertThat(vpnQueue.getQueueName()).isEqualTo(destination);
            softly.assertThat(vpnQueue.getAccessType()).isEqualTo(MonitorMsgVpnQueue.AccessTypeEnum.NON_EXCLUSIVE);
        } finally {
            if (producerBinding != null) producerBinding.unbind();
            jcsmpSession.deprovision(queue, JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
        }
    }


    @Test
    public void testProducerDestinationTypeSetToQueueWithRequiredGroups(SpringCloudStreamContext context, TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();
        DirectChannel moduleOutputChannel = context.createBindableChannel("output", new BindingProperties());
        String destination = RandomStringUtils.randomAlphanumeric(20);

        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        producerProperties.setRequiredGroups("group-A");
        producerProperties.getExtension().setDestinationType(DestinationType.QUEUE);

        Binding<MessageChannel> producerBinding = null;
        try {
            Exception provisioningException = assertThrows(ProvisioningException.class, () -> binder.bindProducer(destination, moduleOutputChannel, producerProperties));
            assertThat(provisioningException)
                    .hasMessageContaining("Producer requiredGroups are not supported when destinationType=QUEUE");
        } finally {
            if (producerBinding != null) producerBinding.unbind();
        }
    }

    void operatorProvisionTopicEndpoint(SempV2Api sempV2Api, String vpnName, String endpointName, String subscription, long maxBindCount) throws
            com.solace.test.integration.semp.v2.config.ApiException {

        final ConfigMsgVpnTopicEndpoint config = new ConfigMsgVpnTopicEndpoint();
        config.setAccessType(ConfigMsgVpnTopicEndpoint.AccessTypeEnum.NON_EXCLUSIVE);
        config.setPermission(ConfigMsgVpnTopicEndpoint.PermissionEnum.DELETE);
        config.setMaxBindCount(maxBindCount);
        config.setTopicEndpointName(endpointName);
        config.setIngressEnabled(true);
        config.setEgressEnabled(true);
        config.setMsgVpnName(vpnName);
        // assume TE is created, when not created test will catch it
        sempV2Api.config().createMsgVpnTopicEndpoint(config, vpnName, null, null);
    }

}
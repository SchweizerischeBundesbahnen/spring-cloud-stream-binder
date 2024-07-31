package com.solace.spring.cloud.stream.binder.inbound.acknowledge;

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.util.*;
import com.solace.test.integration.junit.jupiter.extension.ExecutorServiceExtension;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueueMsg;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueueMsgsResponse;
import com.solace.test.integration.semp.v2.monitor.model.MonitorSempMeta;
import com.solace.test.integration.semp.v2.monitor.model.MonitorSempPaging;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.*;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.junitpioneer.jupiter.cartesian.CartesianTest.Values;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.acks.AcknowledgmentCallback.Status;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.solace.spring.cloud.stream.binder.test.util.RetryableAssertions.retryAssert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.spy;

@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(ExecutorServiceExtension.class)
@ExtendWith(PubSubPlusExtension.class)
@Timeout(value = 2, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
class SolaceAckUtilIT {

    private final AtomicReference<FlowReceiverContainer> flowReceiverContainerReference = new AtomicReference<>();
    private XMLMessageProducer producer;
    private String vpnName;
    private Runnable closeErrorQueueInfrastructureCallback;

    @BeforeEach
    public void setup(JCSMPSession jcsmpSession) throws Exception {
        producer = jcsmpSession.getMessageProducer(
                new JCSMPSessionProducerManager.CloudStreamEventHandler());
        vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
    }

    @AfterEach
    public void cleanup() {
        if (producer != null) {
            producer.close();
        }

        if (closeErrorQueueInfrastructureCallback != null) {
            closeErrorQueueInfrastructureCallback.run();
        }
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testRePubToErrorQueueWhenErrorQueueDisabled(@Values(ints = {1, 255}) int numMessages,
                                                            @Values(booleans = {false, true}) boolean isDurable,
                                                            JCSMPSession jcsmpSession, Queue durableQueue, SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);
        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer,
                numMessages);

        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);

        acknowledgmentCallback.forEach(ac -> {
            assertThat(SolaceAckUtil.isErrorQueueEnabled(ac)).isFalse();
            assertThat(SolaceAckUtil.republishToErrorQueue(ac)).isFalse();
            assertThat(ac.isAcknowledged()).isFalse();
        });

        validateNumEnqueuedMessages(sempV2Api, queue.getName(), numMessages);
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testRePubToErrorQueueWhenErrorQueueEnabled(@Values(ints = {1, 255}) int numMessages,
                                                           @Values(booleans = {false, true}) boolean isDurable,
                                                           JCSMPSession jcsmpSession, Queue durableQueue, SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer,
                numMessages);
        ErrorQueueInfrastructure errorQueueInfrastructure = initErrorQueueInfrastructure(jcsmpSession,
                acknowledgementCallbackFactory);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);

        acknowledgmentCallback.forEach(ac -> {
            assertThat(SolaceAckUtil.isErrorQueueEnabled(ac)).isTrue();
            assertThat(SolaceAckUtil.republishToErrorQueue(ac)).isTrue();
        });

        validateNumEnqueuedMessages(sempV2Api, queue.getName(), 0);
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
        validateNumEnqueuedMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(),
                numMessages);
        validateNumRedeliveredMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(), 0);
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testRePubToErrorQueueAckedMsg(@Values(ints = {1, 255}) int numMessages,
                                              @Values(booleans = {false, true}) boolean isDurable,
                                              JCSMPSession jcsmpSession, Queue durableQueue, SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer,
                numMessages);
        ErrorQueueInfrastructure errorQueueInfrastructure = initErrorQueueInfrastructure(jcsmpSession,
                acknowledgementCallbackFactory);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);

        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(Status.ACCEPT);
            assertThat(SolaceAckUtil.isErrorQueueEnabled(ac)).isTrue();
            assertThat(SolaceAckUtil.republishToErrorQueue(ac)).isFalse();
        });

        validateNumEnqueuedMessages(sempV2Api, queue.getName(), 0);
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
        validateNumEnqueuedMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(), 0);
        validateNumRedeliveredMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(), 0);
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testRePubToErrorQueueRequeueMsg(@Values(ints = {1, 255}) int numMessages,
                                                @Values(booleans = {false, true}) boolean isDurable,
                                                JCSMPSession jcsmpSession, Queue durableQueue, SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer,
                numMessages);
        ErrorQueueInfrastructure errorQueueInfrastructure = initErrorQueueInfrastructure(jcsmpSession,
                acknowledgementCallbackFactory);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);

        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(Status.REQUEUE);
            assertThat(SolaceAckUtil.isErrorQueueEnabled(ac)).isTrue();
            assertThat(SolaceAckUtil.republishToErrorQueue(ac)).isFalse();
        });

        validateNumEnqueuedMessages(sempV2Api, queue.getName(), numMessages);
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), numMessages);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
        validateNumEnqueuedMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(), 0);
        validateNumRedeliveredMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(), 0);
    }

    private FlowReceiverContainer initFlowReceiverContainer(JCSMPSession jcsmpSession, Queue queue)
            throws JCSMPException {
        if (flowReceiverContainerReference.compareAndSet(null, spy(new FlowReceiverContainer(
                jcsmpSession,
                JCSMPFactory.onlyInstance().createQueue(queue.getName()),
                new EndpointProperties(),
                new ConsumerFlowProperties())))) {
            flowReceiverContainerReference.get().bind();
        }
        return flowReceiverContainerReference.get();
    }

    private ErrorQueueInfrastructure initErrorQueueInfrastructure(JCSMPSession jcsmpSession,
                                                                  JCSMPAcknowledgementCallbackFactory ackCallbackFactory) {
        if (closeErrorQueueInfrastructureCallback != null) {
            throw new IllegalStateException("Should only have one error queue infrastructure");
        }

        String producerManagerKey = UUID.randomUUID().toString();
        JCSMPSessionProducerManager jcsmpSessionProducerManager = new JCSMPSessionProducerManager(
                jcsmpSession);
        ErrorQueueInfrastructure errorQueueInfrastructure = new ErrorQueueInfrastructure(
                jcsmpSessionProducerManager,
                producerManagerKey, RandomStringUtils.randomAlphanumeric(20),
                new SolaceConsumerProperties());
        Queue errorQueue = JCSMPFactory.onlyInstance()
                .createQueue(errorQueueInfrastructure.getErrorQueueName());
        ackCallbackFactory.setErrorQueueInfrastructure(errorQueueInfrastructure);
        closeErrorQueueInfrastructureCallback = () -> {
            jcsmpSessionProducerManager.release(producerManagerKey);

            try {
                jcsmpSession.deprovision(errorQueue, JCSMPSession.WAIT_FOR_CONFIRM);
            } catch (JCSMPException e) {
                throw new RuntimeException(e);
            }
        };

        try {
            jcsmpSession.provision(errorQueue, new EndpointProperties(), JCSMPSession.WAIT_FOR_CONFIRM);
        } catch (JCSMPException e) {
            throw new RuntimeException(e);
        }

        return errorQueueInfrastructure;
    }

    private List<MessageContainer> sendAndReceiveMessages(Queue queue,
                                                          FlowReceiverContainer flowReceiverContainer,
                                                          int numMessages)
            throws JCSMPException, UnboundFlowReceiverContainerException {
        assertThat(numMessages).isGreaterThan(0);

        for (int i = 0; i < numMessages; i++) {
            producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class), queue);
        }

        if (numMessages > 1) {
            List<MessageContainer> messageContainers = new ArrayList<>();
            for (int i = 0; i < numMessages; i++) {
                MessageContainer messageContainer = flowReceiverContainer.receive((int)
                        TimeUnit.SECONDS.toMillis(10));
                assertNotNull(messageContainer);
                messageContainers.add(messageContainer);
            }
            return messageContainers;
        } else {
            MessageContainer messageContainer = flowReceiverContainer.receive((int)
                    TimeUnit.SECONDS.toMillis(10));
            assertNotNull(messageContainer);
            return Collections.singletonList(messageContainer);
        }
    }

    private List<AcknowledgmentCallback> createAcknowledgmentCallback(
            JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory,
            List<MessageContainer> messageContainers) {
        assertThat(messageContainers).hasSizeGreaterThan(0);
        return messageContainers.stream().map(acknowledgementCallbackFactory::createCallback).collect(Collectors.toList());
    }

    private void validateNumEnqueuedMessages(SempV2Api sempV2Api, String queueName, int expectedCount)
            throws InterruptedException {
        retryAssert(() -> {
            List<MonitorMsgVpnQueueMsg> messages = new ArrayList<>();
            Optional<String> cursor = Optional.empty();
            do {
                MonitorMsgVpnQueueMsgsResponse response = sempV2Api.monitor()
                        .getMsgVpnQueueMsgs(vpnName, queueName, Integer.MAX_VALUE, cursor.orElse(null),
                                null, null);
                cursor = Optional.ofNullable(response.getMeta())
                        .map(MonitorSempMeta::getPaging)
                        .map(MonitorSempPaging::getCursorQuery);
                messages.addAll(response.getData());
            } while (cursor.isPresent());
            assertThat(messages).hasSize(expectedCount);
        });
    }

    private void validateNumRedeliveredMessages(SempV2Api sempV2Api, String queueName,
                                                int expectedCount)
            throws InterruptedException {
        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queueName, null)
                .getData()
                .getRedeliveredMsgCount())
                .isEqualTo(expectedCount));
    }

    private void validateQueueBindSuccesses(SempV2Api sempV2Api, String queueName, int expectedCount)
            throws InterruptedException {
        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queueName, null)
                .getData()
                .getBindSuccessCount())
                .isEqualTo(expectedCount));
    }
}
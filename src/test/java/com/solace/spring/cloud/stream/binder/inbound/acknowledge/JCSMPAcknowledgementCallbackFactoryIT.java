package com.solace.spring.cloud.stream.binder.inbound.acknowledge;

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.util.*;
import com.solace.test.integration.junit.jupiter.extension.ExecutorServiceExtension;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.config.model.ConfigMsgVpnQueue;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueueMsg;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueueMsgsResponse;
import com.solace.test.integration.semp.v2.monitor.model.MonitorSempMeta;
import com.solace.test.integration.semp.v2.monitor.model.MonitorSempPaging;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.XMLMessage.Outcome;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.function.ThrowingRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.junitpioneer.jupiter.cartesian.CartesianTest.Values;
import org.mockito.Mockito;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.solace.spring.cloud.stream.binder.test.util.RetryableAssertions.retryAssert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Slf4j
@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(ExecutorServiceExtension.class)
@ExtendWith(PubSubPlusExtension.class)
@Timeout(value = 2, unit = TimeUnit.MINUTES)
public class JCSMPAcknowledgementCallbackFactoryIT {
    private final AtomicReference<FlowReceiverContainer> flowReceiverContainerReference = new AtomicReference<>();
    private XMLMessageProducer producer;
    private String vpnName;
    private Runnable closeErrorQueueInfrastructureCallback;

    private static final Log logger = LogFactory.getLog(JCSMPAcknowledgementCallbackFactoryIT.class);

    @BeforeEach
    public void setup(JCSMPSession jcsmpSession) throws Exception {
        producer = jcsmpSession.getMessageProducer(new JCSMPSessionProducerManager.CloudStreamEventHandler());
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
    public void testAccept(@Values(ints = {1, 255}) int numMessages,
                           @Values(booleans = {false, true}) boolean isDurable,
                           JCSMPSession jcsmpSession,
                           Queue durableQueue,
                           SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);
        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);
        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(AcknowledgmentCallback.Status.ACCEPT);
            assertThat(ac.isAcknowledged()).isTrue();
        });
        validateNumEnqueuedMessages(sempV2Api, queue.getName(), 0);
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testReject(@Values(ints = {1, 255}) int numMessages,
                           @Values(booleans = {false, true}) boolean isDurable,
                           JCSMPSession jcsmpSession,
                           Queue durableQueue,
                           SempV2Api sempV2Api)
            throws Exception {

        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);
        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);
        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(AcknowledgmentCallback.Status.REJECT);
            assertThat(ac.isAcknowledged()).isTrue();
        });

        validateNumEnqueuedMessages(sempV2Api, queue.getName(), 0);
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
    }

    @CartesianTest(name = "[{index}] numMessages={0}")
    public void testRejectFail(@Values(ints = {1, 255}) int numMessages,
                               JCSMPSession jcsmpSession,
                               Queue queue,
                               SempV2Api sempV2Api) throws Exception {
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);
        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);

        log.info(String.format("Disabling egress for queue %s", queue.getName()));
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(false), (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME),
                queue.getName(), null, null);
        retryAssert(() -> assertFalse(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue.getName(), null)
                .getData()
                .isEgressEnabled()));

        log.info("Acknowledging messages");
        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(AcknowledgmentCallback.Status.REJECT);
            assertThat(ac.isAcknowledged()).isTrue();
        });


        validateNumEnqueuedMessages(sempV2Api, queue.getName(), numMessages);
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
        assertThat(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue.getName(), null)
                .getData()
                .getBindRequestCount())
                .isGreaterThan(1);

        log.info(String.format("Enabling egress for queue %s", queue.getName()));
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(true), (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME),
                queue.getName(), null, null);
        retryAssert(() -> assertTrue(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue.getName(), null)
                .getData()
                .isEgressEnabled()));

        // Message was redelivered
        log.info("Verifying message was redelivered");
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), numMessages);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 2);
        validateNumEnqueuedMessages(sempV2Api, queue.getName(), numMessages);
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testRejectWithErrorQueue(@Values(ints = {1, 255}) int numMessages,
                                         @Values(booleans = {false, true}) boolean isDurable,
                                         JCSMPSession jcsmpSession,
                                         Queue durableQueue,
                                         SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages);
        ErrorQueueInfrastructure errorQueueInfrastructure = initializeErrorQueueInfrastructure(jcsmpSession,
                acknowledgementCallbackFactory);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);
        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(AcknowledgmentCallback.Status.REJECT);
            assertThat(ac.isAcknowledged()).isTrue();
        });

        validateNumEnqueuedMessages(sempV2Api, queue.getName(), 0);
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
        validateNumEnqueuedMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(), numMessages);
        validateNumRedeliveredMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(), 0);
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testRejectWithErrorQueueFail(@Values(ints = {1, 255}) int numMessages,
                                             @Values(booleans = {false, true}) boolean isDurable,
                                             JCSMPSession jcsmpSession,
                                             Queue durableQueue,
                                             SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages);
        ErrorQueueInfrastructure errorQueueInfrastructure = initializeErrorQueueInfrastructure(jcsmpSession,
                acknowledgementCallbackFactory);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);


        log.info(String.format("Disabling ingress for error queue %s",
                errorQueueInfrastructure.getErrorQueueName()));
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().ingressEnabled(false), (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME),
                errorQueueInfrastructure.getErrorQueueName(), null, null);
        retryAssert(() -> assertFalse(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, errorQueueInfrastructure.getErrorQueueName(), null)
                .getData()
                .isIngressEnabled()));

        log.info("Acknowledging messages");
        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(AcknowledgmentCallback.Status.REJECT);
            assertThat(ac.isAcknowledged()).isTrue();
        });


        validateNumRedeliveredMessages(sempV2Api, queue.getName(), numMessages);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
        validateNumEnqueuedMessages(sempV2Api, queue.getName(), numMessages);

        //Validate Error Queue Stats
        validateNumEnqueuedMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(), 0);
        validateNumRedeliveredMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(), 0);
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testRequeue(@Values(ints = {1, 255}) int numMessages,
                            @Values(booleans = {false, true}) boolean isDurable,
                            JCSMPSession jcsmpSession,
                            Queue durableQueue,
                            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);
        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(AcknowledgmentCallback.Status.REQUEUE);
            assertThat(ac.isAcknowledged()).isTrue();
        });

        validateNumRedeliveredMessages(sempV2Api, queue.getName(), numMessages);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
        validateNumEnqueuedMessages(sempV2Api, queue.getName(), numMessages);
    }

    @CartesianTest(name = "[{index}] numMessages={0}")
    public void testRequeueFail(@Values(ints = {1, 255}) int numMessages,
                                JCSMPSession jcsmpSession,
                                Queue queue,
                                SempV2Api sempV2Api) throws Exception {
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);


        log.info(String.format("Disabling egress for queue %s", queue.getName()));
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(false), (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME),
                queue.getName(), null, null);
        retryAssert(() -> assertFalse(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue.getName(), null)
                .getData()
                .isEgressEnabled()));

        log.info("Acknowledging messages");
        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(AcknowledgmentCallback.Status.REQUEUE);
            assertThat(ac.isAcknowledged()).isTrue();
        });


        validateNumEnqueuedMessages(sempV2Api, queue.getName(), numMessages);
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
        assertThat(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue.getName(), null)
                .getData()
                .getBindRequestCount())
                .isGreaterThan(1);

        log.info(String.format("Enabling egress for queue %s", queue.getName()));
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(true), (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME),
                queue.getName(), null, null);
        retryAssert(() -> assertTrue(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue.getName(), null)
                .getData()
                .isEgressEnabled()));

        // Message was redelivered
        log.info("Verifying message was redelivered");
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), numMessages);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 2);
        validateNumEnqueuedMessages(sempV2Api, queue.getName(), numMessages);
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testReAckAfterAccept(@Values(ints = {1, 255}) int numMessages,
                                     @Values(booleans = {false, true}) boolean isDurable,
                                     JCSMPSession jcsmpSession,
                                     Queue durableQueue,
                                     SempV2Api sempV2Api) throws Throwable {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);


        ThrowingRunnable verifyExpectedState = () -> {
            validateNumEnqueuedMessages(sempV2Api, queue.getName(), 0);
            validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
            validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
        };
        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(AcknowledgmentCallback.Status.ACCEPT);
            assertThat(ac.isAcknowledged()).isTrue();

            for (AcknowledgmentCallback.Status status : AcknowledgmentCallback.Status.values()) {
                ac.acknowledge(status);
                assertThat(ac.isAcknowledged()).isTrue();
            }
        });
        verifyExpectedState.run();
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testReAckAfterReject(@Values(ints = {1, 255}) int numMessages,
                                     @Values(booleans = {false, true}) boolean isDurable,
                                     JCSMPSession jcsmpSession,
                                     Queue durableQueue,
                                     SempV2Api sempV2Api) throws Throwable {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);


        ThrowingRunnable verifyExpectedState = () -> {
            validateNumEnqueuedMessages(sempV2Api, queue.getName(), 0);
            validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
            validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
        };

        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(AcknowledgmentCallback.Status.REJECT);
            assertThat(ac.isAcknowledged()).isTrue();

            for (AcknowledgmentCallback.Status status : AcknowledgmentCallback.Status.values()) {
                ac.acknowledge(status);
                assertThat(ac.isAcknowledged()).isTrue();
            }
        });
        verifyExpectedState.run();
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testReAckAfterRejectWithErrorQueue(@Values(ints = {1, 255}) int numMessages,
                                                   @Values(booleans = {false, true}) boolean isDurable,
                                                   JCSMPSession jcsmpSession,
                                                   Queue durableQueue,
                                                   SempV2Api sempV2Api) throws Throwable {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages);
        ErrorQueueInfrastructure errorQueueInfrastructure = initializeErrorQueueInfrastructure(jcsmpSession,
                acknowledgementCallbackFactory);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);

        ThrowingRunnable verifyExpectedState = () -> {
            validateNumEnqueuedMessages(sempV2Api, queue.getName(), 0);
            validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
            validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
            validateNumEnqueuedMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(), numMessages);
            validateNumRedeliveredMessages(sempV2Api, errorQueueInfrastructure.getErrorQueueName(), 0);
        };

        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(AcknowledgmentCallback.Status.REJECT);
            assertThat(ac.isAcknowledged()).isTrue();
        });
        verifyExpectedState.run();

        acknowledgmentCallback.forEach(ac -> {
            for (AcknowledgmentCallback.Status status : AcknowledgmentCallback.Status.values()) {
                ac.acknowledge(status);
                assertThat(ac.isAcknowledged()).isTrue();
            }
        });
        verifyExpectedState.run();
    }

    @CartesianTest(name = "[{index}] numMessages={0}, isDurable={1}")
    public void testReAckAfterRequeue(@Values(ints = {1, 255}) int numMessages,
                                      @Values(booleans = {false, true}) boolean isDurable,
                                      JCSMPSession jcsmpSession,
                                      Queue durableQueue,
                                      SempV2Api sempV2Api) throws Throwable {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);

        ThrowingRunnable verifyExpectedState = () -> {
            validateNumRedeliveredMessages(sempV2Api, queue.getName(), numMessages);
            validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
            validateNumEnqueuedMessages(sempV2Api, queue.getName(), numMessages);
        };
        acknowledgmentCallback.forEach(ac -> {
            ac.acknowledge(AcknowledgmentCallback.Status.REQUEUE);
            assertThat(ac.isAcknowledged()).isTrue();

        });
        verifyExpectedState.run();

        acknowledgmentCallback.forEach(ac -> {
            for (AcknowledgmentCallback.Status status : AcknowledgmentCallback.Status.values()) {
                ac.acknowledge(status);
                assertThat(ac.isAcknowledged()).isTrue();
            }
        });
        verifyExpectedState.run();
    }

    @CartesianTest(name = "[{index}] status={0}, numMessages={1}, isDurable={2}, createErrorQueue={3}")
    public void testAckWhenFlowUnbound(
            @CartesianTest.Enum(AcknowledgmentCallback.Status.class) AcknowledgmentCallback.Status status,
            @Values(ints = {1, 255}) int numMessages,
            @Values(booleans = {false, true}) boolean isDurable,
            @Values(booleans = {false, true}) boolean createErrorQueue,
            JCSMPSession jcsmpSession,
            Queue durableQueue,
            SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages)
                .stream()
                .map(Mockito::spy)
                .peek(m -> when(m.isStale()).thenReturn(true))
                .collect(Collectors.toList());
        Optional<String> errorQueueName = Optional.of(createErrorQueue)
                .filter(e -> e)
                .map(c -> initializeErrorQueueInfrastructure(jcsmpSession, acknowledgementCallbackFactory))
                .map(ErrorQueueInfrastructure::getErrorQueueName);
        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);

        flowReceiverContainer.unbind();

        acknowledgmentCallback.forEach(ac -> {

            SolaceAcknowledgmentException exception = assertThrows(SolaceAcknowledgmentException.class,
                    () -> ac.acknowledge(status));
            Class<? extends Throwable> expectedRootCause = IllegalStateException.class;

            assertThat(ac.isAcknowledged())
                    .describedAs("Unexpected ack state for %s re-ack", status)
                    .isFalse();

            assertThat(exception)
                    .describedAs("Unexpected root cause for %s re-ack", status)
                    .hasRootCauseInstanceOf(expectedRootCause);
            if (exception instanceof SolaceBatchAcknowledgementException) {
                assertThat((SolaceBatchAcknowledgementException) exception)
                        .describedAs("Unexpected stale batch state for %s re-ack", status)
                        .hasRootCauseInstanceOf(expectedRootCause);
            }
        });

        if (isDurable) {
            validateNumEnqueuedMessages(sempV2Api, queue.getName(), numMessages);
            validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
            validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
        }
        if (errorQueueName.isPresent()) {
            validateNumEnqueuedMessages(sempV2Api, errorQueueName.get(), 0);
            validateNumRedeliveredMessages(sempV2Api, errorQueueName.get(), 0);
        }
    }

    @CartesianTest(name = "[{index}] status={0}, numMessages={1}, isDurable={2}")
    public void testAckThrowsException(
            @CartesianTest.Enum(AcknowledgmentCallback.Status.class) AcknowledgmentCallback.Status status,
            @Values(ints = {1, 255}) int numMessages,
            @Values(booleans = {false, true}) boolean isDurable,
            JCSMPSession jcsmpSession, Queue durableQueue, SempV2Api sempV2Api) throws Exception {
        Queue queue = isDurable ? durableQueue : jcsmpSession.createTemporaryQueue();
        FlowReceiverContainer flowReceiverContainer = initializeFlowReceiverContainer(jcsmpSession, queue);
        JCSMPAcknowledgementCallbackFactory acknowledgementCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);

        JCSMPException expectedException = new JCSMPException("expected exception");
        List<MessageContainer> messageContainers = sendAndReceiveMessages(queue, flowReceiverContainer, numMessages)
                .stream()
                .map(Mockito::spy)
                .peek(m -> {
                    BytesXMLMessage bytesXMLMessage = spy(m.getMessage());
                    when(m.getMessage()).thenReturn(bytesXMLMessage);
                    try {
                        doThrow(expectedException).when(bytesXMLMessage)
                                .settle(any(Outcome.class));
                    } catch (Exception e) {
                        fail("Unwanted exception", e);
                    }
                }).collect(Collectors.toList());

        List<AcknowledgmentCallback> acknowledgmentCallback = createAcknowledgmentCallback(
                acknowledgementCallbackFactory, messageContainers);

        acknowledgmentCallback.forEach(ac -> {


            assertThat(ac.isAcknowledged()).isFalse();
            assertThat(ac.isAutoAck()).isTrue();
            assertThat(SolaceAckUtil.isErrorQueueEnabled(ac)).isFalse();

            SolaceAcknowledgmentException exception = assertThrows(SolaceAcknowledgmentException.class,
                    () -> ac.acknowledge(status));

            assertThat(ac.isAcknowledged())
                    .describedAs("Unexpected ack state for %s re-ack", status)
                    .isFalse();
            assertThat(exception)
                    .describedAs("Unexpected root cause for %s re-ack", status)
                    .hasRootCause(expectedException);

        });
        validateNumEnqueuedMessages(sempV2Api, queue.getName(), numMessages);
        validateNumRedeliveredMessages(sempV2Api, queue.getName(), 0);
        validateQueueBindSuccesses(sempV2Api, queue.getName(), 1);
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

    private FlowReceiverContainer initializeFlowReceiverContainer(
            JCSMPSession jcsmpSession, Queue queue)
            throws JCSMPException {
        AtomicReference<FlowReceiverContainer> ref = flowReceiverContainerReference;

        if (ref.compareAndSet(null, spy(new FlowReceiverContainer(
                jcsmpSession,
                JCSMPFactory.onlyInstance().createQueue(queue.getName()),
                new EndpointProperties(),
                new ConsumerFlowProperties())))) {
            ref.get().bind();
        }
        return ref.get();
    }

    private ErrorQueueInfrastructure initializeErrorQueueInfrastructure(JCSMPSession jcsmpSession,
                                                                        JCSMPAcknowledgementCallbackFactory ackCallbackFactory) {
        if (closeErrorQueueInfrastructureCallback != null) {
            throw new IllegalStateException("Should only have one error queue infrastructure");
        }

        String producerManagerKey = UUID.randomUUID().toString();
        JCSMPSessionProducerManager jcsmpSessionProducerManager = new JCSMPSessionProducerManager(jcsmpSession);
        ErrorQueueInfrastructure errorQueueInfrastructure = new ErrorQueueInfrastructure(jcsmpSessionProducerManager,
                producerManagerKey, RandomStringUtils.randomAlphanumeric(20), new SolaceConsumerProperties());
        Queue errorQueue = JCSMPFactory.onlyInstance().createQueue(errorQueueInfrastructure.getErrorQueueName());
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

    private void validateNumRedeliveredMessages(SempV2Api sempV2Api, String queueName, int expectedCount)
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

    private void validateTransaction(SempV2Api sempV2Api, JCSMPSession jcsmpSession, int numMessages, boolean committed)
            throws InterruptedException {
        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnClientTransactedSessions(vpnName,
                        (String) jcsmpSession.getProperty(JCSMPProperties.CLIENT_NAME), null, null, null, null)
                .getData())
                .singleElement()
                .satisfies(
                        d -> assertThat(d.getSuccessCount()).isEqualTo(1),
                        d -> assertThat(d.getCommitCount()).isEqualTo(committed ? 1 : 0),
                        d -> assertThat(d.getRollbackCount()).isEqualTo(!committed ? 1 : 0),
                        d -> assertThat(d.getFailureCount()).isEqualTo(0),
                        d -> assertThat(d.getConsumedMsgCount()).isEqualTo(committed ? numMessages : 0),
                        d -> assertThat(d.getPendingConsumedMsgCount()).isEqualTo(committed ? 0 : numMessages)
                ));
    }
}

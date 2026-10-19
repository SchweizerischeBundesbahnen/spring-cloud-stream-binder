package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solace.spring.cloud.stream.binder.health.SolaceBinderHealthAccessor;
import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import com.solace.spring.cloud.stream.binder.health.contributors.BindingsHealthContributor;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.indicators.SessionHealthIndicator;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.test.util.SimpleJCSMPEventHandler;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.Endpoint;
import com.solacesystems.jcsmp.EndpointProperties;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageProducer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.provisioning.ProvisioningException;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A queue consumer whose start fails must leave nothing behind. Spring Cloud Stream records such a binding as failed
 * and keeps only a placeholder for it, so neither {@code stop()} nor {@code pause()} reaches what the failed start
 * left running: a flow and its receiver threads would go on taking messages from the queue until the session closes.
 * The broker is real because the claim is about what it delivers.
 */
@ExtendWith(PubSubPlusExtension.class)
class JCSMPInboundQueueMessageProducerIT {
    private static final int RECEIVE_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(5);
    private static final Duration SHUTDOWN_HOOK_LOOK_INTERVAL = Duration.ofMillis(1);
    private static final String GROUP = "failed-start";
    private static final String PAYLOAD = "waits for a consumer that starts";

    private final List<Message<?>> messagesHandedToTheApplication = new CopyOnWriteArrayList<>();
    private final AtomicBoolean shutdownHookArmed = new AtomicBoolean(false);
    private String bindingDestinationName;
    private String queueName;

    @BeforeEach
    void pickADestinationNoOtherTestUses() {
        bindingDestinationName = "failed-start-" + RandomStringUtils.insecure().nextAlphanumeric(10);
    }

    @AfterEach
    void deprovisionTheQueue(JCSMPSession jcsmpSession) throws JCSMPException {
        if (queueName != null) {
            jcsmpSession.deprovision(JCSMPFactory.onlyInstance().createQueue(queueName), JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
        }
    }

    @Test
    void aStart_thatFailsToSubscribeTheQueue_leavesNoReceiverThreadRunning(JCSMPSession jcsmpSession) {
        JCSMPInboundQueueMessageProducer adapter = createAdapter(jcsmpSession, consumerProperties(), failingSubscription(), Optional.empty());

        assertThatThrownBy(adapter::start).isInstanceOf(MessagingException.class);

        assertThat(aliveReceiverThreadNames()).isEmpty();
    }

    @Test
    void aStart_thatFailsToSubscribeTheQueue_leavesTheWaitingMessageUndelivered(JCSMPSession jcsmpSession) throws JCSMPException {
        JCSMPInboundQueueMessageProducer adapter = createAdapter(jcsmpSession, consumerProperties(), failingSubscription(), Optional.empty());
        publishOneMessageToTheQueue(jcsmpSession);

        assertThatThrownBy(adapter::start).isInstanceOf(MessagingException.class);

        assertThatTheMessageWaitsUndelivered(receiveFromTheQueue(jcsmpSession));
    }

    @Test
    void aStart_thatFailsToSubscribeTheQueue_dropsTheBindingHealthIndicator(JCSMPSession jcsmpSession) {
        BindingsHealthContributor bindingsHealth = new BindingsHealthContributor();
        SolaceBinderHealthAccessor healthAccessor = new SolaceBinderHealthAccessor(
                new SolaceBinderHealthContributor(new SessionHealthIndicator(), bindingsHealth, new SolaceHealthIndicator()));
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = consumerProperties();
        JCSMPInboundQueueMessageProducer adapter = createAdapter(jcsmpSession, consumerProperties, failingSubscription(), Optional.of(healthAccessor));

        assertThatThrownBy(adapter::start).isInstanceOf(MessagingException.class);

        assertThat(bindingsHealth.getContributor(consumerProperties.getBindingName())).isNull();
    }

    /**
     * This is how the failure arrives in production: a pod is told to stop while its context is still refreshing, and
     * the shutdown hook of Spring Framework 7 interrupts the starting thread whenever it waits, here for the broker to
     * confirm the queue subscription.
     */
    @Test
    void aStart_interruptedWhileSubscribingTheQueue_leavesTheWaitingMessageUndelivered(JCSMPSession jcsmpSession) throws Exception {
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = consumerProperties();
        JCSMPInboundQueueMessageProducer adapter = createAdapter(jcsmpSession, consumerProperties,
                realSubscriptionThatArmsTheShutdownHook(jcsmpSession, consumerProperties), Optional.empty());
        publishOneMessageToTheQueue(jcsmpSession);

        startWhileTheShutdownHookInterruptsTheSubscription(adapter);

        assertThatTheMessageWaitsUndelivered(receiveFromTheQueue(jcsmpSession));
    }

    @Test
    void aStart_interruptedWhileSubscribingTheQueue_leavesNoReceiverThreadRunning(JCSMPSession jcsmpSession) throws Exception {
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = consumerProperties();
        JCSMPInboundQueueMessageProducer adapter = createAdapter(jcsmpSession, consumerProperties,
                realSubscriptionThatArmsTheShutdownHook(jcsmpSession, consumerProperties), Optional.empty());

        startWhileTheShutdownHookInterruptsTheSubscription(adapter);

        assertThat(aliveReceiverThreadNames()).isEmpty();
    }

    /**
     * The receiver threads start before the flow is created, so a flow the broker refuses must take them down too.
     */
    @Test
    void aStart_thatFailsToCreateTheFlow_leavesNoReceiverThreadRunning(JCSMPSession jcsmpSession) {
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = consumerProperties();
        consumerProperties.getExtension().setProvisionDurableQueue(false);
        JCSMPInboundQueueMessageProducer adapter = createAdapter(jcsmpSession, consumerProperties, endpoint -> {
        }, Optional.empty());

        assertThatThrownBy(adapter::start).isInstanceOf(MessagingException.class);

        assertThat(aliveReceiverThreadNames()).isEmpty();
    }

    private ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties() {
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = new ExtendedConsumerProperties<>(new SolaceConsumerProperties());
        consumerProperties.populateBindingName(bindingDestinationName + "-in-0");
        return consumerProperties;
    }

    private static Consumer<Endpoint> failingSubscription() {
        return endpoint -> {
            throw new ProvisioningException("the broker refused the subscription");
        };
    }

    private Consumer<Endpoint> realSubscriptionThatArmsTheShutdownHook(JCSMPSession jcsmpSession,
                                                                       ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties) {
        SolaceEndpointProvisioner provisioner = new SolaceEndpointProvisioner(jcsmpSession, Optional.empty());
        return endpoint -> {
            shutdownHookArmed.set(true);
            provisioner.addSubscriptionToQueue((Queue) endpoint, bindingDestinationName, consumerProperties.getExtension(), true);
        };
    }

    /**
     * Mirrors {@code AbstractApplicationContext.tryLockForShutdown}, which interrupts the starting thread whenever it is
     * {@code WAITING}. The hook holds back until the subscription is requested, as in the incident where the flow was
     * already bound: an interrupt of an earlier wait fails the start before there is a flow that could deliver. Spring
     * looks every 100 ms; this hook looks every millisecond, so that the interrupt lands in the short wait for the
     * broker on every run. The interrupt the failed start leaves on the test thread is cleared.
     */
    private void startWhileTheShutdownHookInterruptsTheSubscription(JCSMPInboundQueueMessageProducer adapter) throws InterruptedException {
        Thread startingThread = Thread.currentThread();
        AtomicBoolean starting = new AtomicBoolean(true);
        Thread shutdownHook = new Thread(() -> {
            while (starting.get()) {
                if (shutdownHookArmed.get() && startingThread.getState() == Thread.State.WAITING) {
                    startingThread.interrupt();
                }
                LockSupport.parkNanos(SHUTDOWN_HOOK_LOOK_INTERVAL.toNanos());
            }
        });
        shutdownHook.setDaemon(true);
        shutdownHook.start();
        try {
            assertThatThrownBy(adapter::start).isInstanceOf(MessagingException.class);
        } finally {
            starting.set(false);
            Thread.interrupted();
            shutdownHook.join(SHUTDOWN_HOOK_LOOK_INTERVAL.multipliedBy(10).toMillis());
        }
    }

    private JCSMPInboundQueueMessageProducer createAdapter(JCSMPSession jcsmpSession,
                                                           ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties,
                                                           Consumer<Endpoint> postStart,
                                                           Optional<SolaceBinderHealthAccessor> healthAccessor) {
        SolaceConsumerDestination destination = (SolaceConsumerDestination) new SolaceEndpointProvisioner(jcsmpSession, Optional.empty())
                .provisionConsumerDestination(bindingDestinationName, GROUP, consumerProperties);
        queueName = destination.getName();
        JCSMPInboundQueueMessageProducer adapter = new JCSMPInboundQueueMessageProducer(
                destination,
                jcsmpSession,
                consumerProperties,
                new EndpointProperties(),
                postStart,
                new DefaultListableBeanFactory(),
                Optional.empty(),
                Optional.empty(),
                healthAccessor,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        DirectChannel application = new DirectChannel();
        application.subscribe(messagesHandedToTheApplication::add);
        adapter.setOutputChannel(application);
        return adapter;
    }

    private void publishOneMessageToTheQueue(JCSMPSession jcsmpSession) throws JCSMPException {
        XMLMessageProducer producer = jcsmpSession.getMessageProducer(new SimpleJCSMPEventHandler());
        try {
            TextMessage message = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
            message.setText(PAYLOAD);
            message.setDeliveryMode(DeliveryMode.PERSISTENT);
            producer.send(message, JCSMPFactory.onlyInstance().createQueue(queueName));
        } finally {
            producer.close();
        }
    }

    private BytesXMLMessage receiveFromTheQueue(JCSMPSession jcsmpSession) throws JCSMPException {
        ConsumerFlowProperties flowProperties = new ConsumerFlowProperties();
        flowProperties.setEndpoint(JCSMPFactory.onlyInstance().createQueue(queueName));
        FlowReceiver flow = jcsmpSession.createFlow(null, flowProperties, null);
        try {
            flow.start();
            return flow.receive(RECEIVE_TIMEOUT_MS);
        } finally {
            flow.close();
        }
    }

    private void assertThatTheMessageWaitsUndelivered(BytesXMLMessage received) {
        assertThat(received)
                .as("the message published before the failed start, read back from the queue")
                .isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) received).getText()).isEqualTo(PAYLOAD);
        assertThat(received.getRedelivered())
                .as("a redelivered message was handed to the failed consumer before it was closed")
                .isFalse();
        assertThat(messagesHandedToTheApplication).isEmpty();
    }

    private List<String> aliveReceiverThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith(bindingDestinationName + "-"))
                .toList();
    }
}

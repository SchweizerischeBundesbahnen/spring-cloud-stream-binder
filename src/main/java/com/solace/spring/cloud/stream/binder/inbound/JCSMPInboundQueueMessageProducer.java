package com.solace.spring.cloud.stream.binder.inbound;

import com.solace.spring.cloud.stream.binder.health.SolaceBinderHealthAccessor;
import com.solace.spring.cloud.stream.binder.inbound.acknowledge.JCSMPAcknowledgementCallbackFactory;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceProvisioningUtil;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import com.solace.spring.cloud.stream.binder.util.ErrorQueueInfrastructure;
import com.solace.spring.cloud.stream.binder.util.FlowReceiverContainer;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solace.spring.cloud.stream.binder.util.SolaceMessageConversionException;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.XMLMessage.Outcome;
import com.solacesystems.jcsmp.impl.JCSMPBasicSession;
import com.solacesystems.jcsmp.transaction.RollbackException;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.core.AttributeAccessor;
import org.springframework.integration.context.OrderlyShutdownCapable;
import org.springframework.integration.core.Pausable;
import org.springframework.integration.endpoint.MessageProducerSupport;
import org.springframework.lang.Nullable;
import org.springframework.messaging.MessagingException;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.util.Assert;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
public class JCSMPInboundQueueMessageProducer extends MessageProducerSupport implements OrderlyShutdownCapable, Pausable {
    private final String id = UUID.randomUUID().toString();
    private final SolaceConsumerDestination consumerDestination;
    private final JCSMPSession jcsmpSession;
    private final JCSMPSessionEventHandler jcsmpSessionEventHandler;
    private final ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties;
    private final EndpointProperties endpointProperties;
    private final Optional<SolaceMeterAccessor> solaceMeterAccessor;
    private final Optional<TracingProxy> tracingProxy;
    private final Optional<SolaceBinderHealthAccessor> solaceBinderHealthAccessor;
    private final long shutdownInterruptThresholdInMillis = 500; //TODO Make this configurable
    private final List<FlowReceiverContainer> flowReceivers;
    private final Set<AtomicBoolean> consumerStopFlags;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    @Setter
    private Consumer<Endpoint> postStart;
    private ExecutorService executorService;
    @Setter
    private AtomicBoolean remoteStopFlag;
    @Setter
    private RetryTemplate retryTemplate;
    @Setter
    private RecoveryCallback<?> recoveryCallback;
    @Setter
    private ErrorQueueInfrastructure errorQueueInfrastructure;

    private static final ThreadLocal<AttributeAccessor> attributesHolder = new ThreadLocal<>();

    public JCSMPInboundQueueMessageProducer(SolaceConsumerDestination consumerDestination,
                                            JCSMPSession jcsmpSession,
                                            JCSMPSessionEventHandler jcsmpSessionEventHandler,
                                            ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties,
                                            @Nullable EndpointProperties endpointProperties,
                                            Optional<SolaceMeterAccessor> solaceMeterAccessor,
                                            Optional<TracingProxy> tracingProxy,
                                            Optional<SolaceBinderHealthAccessor> solaceBinderHealthAccessor) {
        this.consumerDestination = consumerDestination;
        this.jcsmpSession = jcsmpSession;
        this.jcsmpSessionEventHandler = jcsmpSessionEventHandler;
        this.consumerProperties = consumerProperties;
        this.endpointProperties = endpointProperties;
        this.solaceMeterAccessor = solaceMeterAccessor;
        this.tracingProxy = tracingProxy;
        this.solaceBinderHealthAccessor = solaceBinderHealthAccessor;
        this.flowReceivers = new ArrayList<>(consumerProperties.getConcurrency());
        this.consumerStopFlags = new HashSet<>(consumerProperties.getConcurrency());
    }

    @Override
    protected void doStart() {
        final String endpointName = consumerDestination.getName();
        log.info("Creating {} consumer flows for {} <inbound adapter {}>",
                consumerProperties.getConcurrency(), endpointName, id);

        if (isRunning()) {
            log.warn("Nothing to do. Inbound message channel adapter {} is already running", id);
            return;
        }

        if (consumerProperties.getConcurrency() < 1) {
            String msg = String.format("Concurrency must be greater than 0, was %s <inbound adapter %s>",
                    consumerProperties.getConcurrency(), id);
            log.warn(msg);
            throw new MessagingException(msg);
        }

        if (jcsmpSession instanceof JCSMPBasicSession jcsmpBasicSession
                && !jcsmpBasicSession.isRequiredSettlementCapable(
                Set.of(Outcome.ACCEPTED, Outcome.FAILED, Outcome.REJECTED))) {
            String msg = String.format("The Solace PubSub+ Broker doesn't support message NACK capability, <inbound adapter %s>", id);
            throw new MessagingException(msg);
        }

        if (executorService != null && !executorService.isTerminated()) {
            log.warn("Unexpectedly found running executor service while starting inbound adapter {}, closing it...", id);
            stopAllConsumers();
        }

        Endpoint endpoint = JCSMPFactory.onlyInstance().createQueue(endpointName);
        ConsumerFlowProperties consumerFlowProperties = SolaceProvisioningUtil.getConsumerFlowProperties(
                consumerDestination.getBindingDestinationName(), consumerProperties);

        for (int i = 0, numToCreate = consumerProperties.getConcurrency() - flowReceivers.size(); i < numToCreate; i++) {
            log.info("Creating consumer {} of {} for inbound adapter {}", i + 1, consumerProperties.getConcurrency(), id);
            FlowReceiverContainer flowReceiverContainer = new FlowReceiverContainer(
                    jcsmpSession,
                    endpoint,
                    endpointProperties,
                    consumerFlowProperties);

            if (paused.get()) {
                log.info("Inbound adapter {} is paused, pausing newly created flow receiver container {}", id, flowReceiverContainer.getId());
                flowReceiverContainer.pause();
            }
            flowReceivers.add(flowReceiverContainer);
        }

        if (solaceBinderHealthAccessor.isPresent()) {
            for (int i = 0; i < flowReceivers.size(); i++) {
                solaceBinderHealthAccessor.get().addFlow(consumerProperties.getBindingName(), i, flowReceivers.get(i));
            }
        }

        try {
            for (FlowReceiverContainer flowReceiverContainer : flowReceivers) {
                flowReceiverContainer.bind();
            }
        } catch (JCSMPException e) {
            String msg = String.format("Failed to get message consumer for inbound adapter %s", id);
            log.warn(msg, e);
            flowReceivers.forEach(FlowReceiverContainer::unbind);
            throw new MessagingException(msg, e);
        }

        if (retryTemplate != null) {
            retryTemplate.registerListener(new SolaceRetryListener(endpointName));
        }

        executorService = buildThreadPool(consumerProperties.getConcurrency(), consumerProperties.getBindingName());
        flowReceivers.stream()
                .map(this::buildListener)
                .forEach(listener -> {
                    consumerStopFlags.add(listener.getStopFlag());
                    executorService.submit(listener);
                });
        executorService.shutdown(); // All tasks have been submitted

        runPostStart(endpoint);
        // add subscriptions to queues after reconnect because they might have been lost on the broker depending on the reconnection time
        this.jcsmpSessionEventHandler.addAfterReconnectTask(() -> runPostStart(endpoint));
    }

    private void runPostStart(Endpoint endpoint) {
        if (postStart != null) {
            postStart.accept(endpoint);
        }
    }

    private ExecutorService buildThreadPool(int concurrency, String bindingName) {
        ThreadFactory threadFactory = new CustomizableThreadFactory("solace-scst-consumer-" + bindingName);
        return Executors.newFixedThreadPool(concurrency, threadFactory);
    }

    @Override
    protected void doStop() {
        if (!isRunning()) return;
        stopAllConsumers();
    }

    private void stopAllConsumers() {
        final String queueName = consumerDestination.getName();
        log.info(String.format("Stopping all %s consumer flows to queue %s <inbound adapter ID: %s>",
                consumerProperties.getConcurrency(), queueName, id));
        consumerStopFlags.forEach(flag -> flag.set(true)); // Mark threads for shutdown
        try {
            if (!executorService.awaitTermination(shutdownInterruptThresholdInMillis, TimeUnit.MILLISECONDS)) {
                log.info(String.format("Interrupting all workers for inbound adapter %s", id));
                executorService.shutdownNow();
                if (!executorService.awaitTermination(1, TimeUnit.MINUTES)) {
                    String msg = String.format("executor service shutdown for inbound adapter %s timed out", id);
                    log.warn(msg);
                    throw new MessagingException(msg);
                }
            }

            if (solaceBinderHealthAccessor.isPresent()) {
                for (int i = 0; i < flowReceivers.size(); i++) {
                    solaceBinderHealthAccessor.get().removeFlow(consumerProperties.getBindingName(), i);
                }
            }

            // cleanup
            consumerStopFlags.clear();

        } catch (InterruptedException e) {
            String msg = String.format("executor service shutdown for inbound adapter %s was interrupted", id);
            log.warn(msg);
            throw new MessagingException(msg);
        }
    }

    @Override
    public int beforeShutdown() {
        this.stop();
        return 0;
    }

    @Override
    public int afterShutdown() {
        return 0;
    }


    @Override
    protected AttributeAccessor getErrorMessageAttributes(org.springframework.messaging.Message<?> message) {
        AttributeAccessor attributes = attributesHolder.get();
        return attributes == null ? super.getErrorMessageAttributes(message) : attributes;
    }

    private InboundXMLMessageListener buildListener(FlowReceiverContainer flowReceiverContainer) {
        JCSMPAcknowledgementCallbackFactory ackCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                flowReceiverContainer);
        ackCallbackFactory.setErrorQueueInfrastructure(errorQueueInfrastructure);

        if (consumerProperties.isBatchMode()) {
            log.error("BatchMode is deprecated and should not be used.");
        }

        InboundXMLMessageListener listener;
        if (retryTemplate != null) {
            Assert.state(getErrorChannel() == null,
                    "Cannot have an 'errorChannel' property when a 'RetryTemplate' is provided; " +
                            "use an 'ErrorMessageSendingRecoverer' in the 'recoveryCallback' property to send " +
                            "an error message when retries are exhausted");
            listener = new RetryableInboundXMLMessageListener(
                    flowReceiverContainer,
                    consumerDestination,
                    consumerProperties,
                    this::sendMessage,
                    ackCallbackFactory,
                    retryTemplate,
                    recoveryCallback,
                    solaceMeterAccessor,
                    tracingProxy,
                    remoteStopFlag,
                    attributesHolder
            );
        } else {
            listener = new BasicInboundXMLMessageListener(
                    flowReceiverContainer,
                    consumerDestination,
                    consumerProperties,
                    this::sendMessage,
                    ackCallbackFactory,
                    this::sendErrorMessageIfNecessary,
                    solaceMeterAccessor,
                    tracingProxy,
                    remoteStopFlag,
                    attributesHolder,
                    this.getErrorChannel() != null
            );
        }
        return listener;
    }

    @Override
    public void pause() {
        log.info(String.format("Pausing inbound adapter %s", id));
        flowReceivers.forEach(FlowReceiverContainer::pause);
        paused.set(true);
    }

    @Override
    public void resume() {
        log.info(String.format("Resuming inbound adapter %s", id));
        try {
            for (FlowReceiverContainer flowReceiver : flowReceivers) {
                flowReceiver.resume();
            }
            paused.set(false);
        } catch (Exception e) {
            RuntimeException toThrow = new RuntimeException(
                    String.format("Failed to resume inbound adapter %s", id), e);
            if (paused.get()) {
                log.error(String.format(
                        "Inbound adapter %s failed to be resumed. Resumed flow receiver containers will be re-paused",
                        id), e);
                try {
                    pause();
                } catch (Exception e1) {
                    toThrow.addSuppressed(e1);
                }
            }
            throw toThrow;
        }
    }

    @Override
    public boolean isPaused() {
        if (paused.get()) {
            for (FlowReceiverContainer flowReceiverContainer : flowReceivers) {
                if (!flowReceiverContainer.isPaused()) {
                    log.warn(String.format(
                            "Flow receiver container %s is unexpectedly running for inbound adapter %s",
                            flowReceiverContainer.getId(), id));
                    return false;
                }
            }

            return true;
        } else {
            return false;
        }
    }

    private static final class SolaceRetryListener implements RetryListener {

        private final String queueName;

        private SolaceRetryListener(String queueName) {
            this.queueName = queueName;
        }

        @Override
        public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
            return true;
        }

        @Override
        public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {

        }

        @Override
        public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            log.warn(String.format("Failed to consume a message from destination %s - attempt %s",
                    queueName, context.getRetryCount()));
            for (Throwable nestedThrowable : ExceptionUtils.getThrowableList(throwable)) {
                if (nestedThrowable instanceof SolaceMessageConversionException ||
                        nestedThrowable instanceof RollbackException) {
                    // Do not retry if these exceptions are thrown
                    context.setExhaustedOnly();
                    break;
                }
            }
        }
    }
}

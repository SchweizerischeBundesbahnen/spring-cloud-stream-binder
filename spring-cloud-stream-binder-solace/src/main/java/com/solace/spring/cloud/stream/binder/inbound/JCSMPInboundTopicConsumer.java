package com.solace.spring.cloud.stream.binder.inbound;

import com.solace.spring.cloud.stream.binder.inbound.acknowledge.JCSMPAcknowledgementCallbackFactory;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.util.*;
import com.solacesystems.jcsmp.*;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class JCSMPInboundTopicConsumer extends MessageProducerSupport implements OrderlyShutdownCapable, Pausable {
    private final String id = UUID.randomUUID().toString();
    private final SolaceConsumerDestination consumerDestination;
    private final JCSMPSession jcsmpSession;
    private final ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties;

    @Nullable
    private final SolaceMeterAccessor solaceMeterAccessor;
    private final RetryableTaskService taskService;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private ExecutorService executorService;
    private AtomicBoolean remoteStopFlag;
    private RetryTemplate retryTemplate;
    private RecoveryCallback<?> recoveryCallback;


    private static final Log logger = LogFactory.getLog(JCSMPInboundTopicConsumer.class);
    private static final ThreadLocal<AttributeAccessor> attributesHolder = new ThreadLocal<>();
    private static final Map<String, NonPersistedSubscriptionLoadBalancer> loadBalancers = new ConcurrentHashMap<>();
    private static XMLMessageConsumer msgConsumer = null;

    public JCSMPInboundTopicConsumer(SolaceConsumerDestination consumerDestination,
                                     JCSMPSession jcsmpSession,
                                     RetryableTaskService taskService,
                                     ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties,
                                     @Nullable SolaceMeterAccessor solaceMeterAccessor) {
        this.consumerDestination = consumerDestination;
        this.jcsmpSession = jcsmpSession;
        this.taskService = taskService;
        this.consumerProperties = consumerProperties;
        this.solaceMeterAccessor = solaceMeterAccessor;
    }

    @Override
    protected void doStart() {
        if (isRunning()) {
            logger.warn(String.format("Nothing to do. Inbound message channel adapter %s is already running", id));
            return;
        }

        if (consumerProperties.getConcurrency() < 1) {
            String msg = String.format("Concurrency must be greater than 0, was %s <inbound adapter %s>",
                    consumerProperties.getConcurrency(), id);
            logger.warn(msg);
            throw new MessagingException(msg);
        }

        subscribeAllTopics();


        if (retryTemplate != null) {
            retryTemplate.registerListener(new SolaceRetryListener(consumerDestination.getBindingDestinationName()));
        }

        synchronized (loadBalancers) {
            try {
                if (msgConsumer == null) {
                    msgConsumer = jcsmpSession.getMessageConsumer(new XMLMessageListener() {
                        @Override
                        public void onReceive(final BytesXMLMessage msg) {
                            try {
                                for (NonPersistedSubscriptionLoadBalancer lb : loadBalancers.values()) {
                                    lb.process(msg);
                                }
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        @Override
                        public void onException(final JCSMPException e) {
                            String msg = String.format("Received error while trying to read message from endpoint %s", consumerDestination.getBindingDestinationName());
                            if ((e instanceof JCSMPTransportException || e instanceof ClosedFacilityException)) {
                                logger.debug(msg, e);
                            } else {
                                logger.warn(msg, e);
                            }
                        }
                    });
                    msgConsumer.start();
                }
            } catch (JCSMPException e) {
                String msg = String.format("Failed to get message consumer for inbound adapter %s", id);
                logger.warn(msg, e);
                throw new MessagingException(msg, e);
            }
        }

        executorService = Executors.newFixedThreadPool(consumerProperties.getConcurrency());
        for (int i = 0; i < consumerProperties.getConcurrency(); i++) {
            InboundXMLMessageListener listener = buildListener(new BlockingQueueReceiverContainer(
                    consumerDestination.getBindingDestinationName(),
                    loadBalancers
                            .computeIfAbsent(id, _id -> new NonPersistedSubscriptionLoadBalancer(getAllTopics()))
                            .getBlockingQueue()
            ));

            executorService.submit(listener);
        }
        executorService.shutdown(); // All tasks have been submitted
    }

    private Set<String> getAllTopics() {
        Set<String> topics = new HashSet<>();
        topics.add(consumerDestination.getBindingDestinationName());
        if (!CollectionUtils.isEmpty(consumerDestination.getAdditionalSubscriptions())) {
            topics.addAll(consumerDestination.getAdditionalSubscriptions());
        }
        return topics;

    }

    private void subscribeAllTopics() {
        try {
            for (String topic : getAllTopics()) {
                jcsmpSession.addSubscription(JCSMPFactory.onlyInstance().createTopic(topic));
            }
        } catch (JCSMPException e) {
            String msg = String.format("Failed to get message consumer for topic consumer %s", id);
            logger.warn(msg, e);
            throw new MessagingException(msg, e);
        }
    }

    private void unsubscribeAllTopics() {
        try {
            for (String topic : getAllTopics()) {
                jcsmpSession.removeSubscription(JCSMPFactory.onlyInstance().createTopic(topic));
            }
        } catch (JCSMPException e) {
            String msg = String.format("Failed to get message consumer for topic consumer %s", id);
            logger.warn(msg, e);
            throw new MessagingException(msg, e);
        }
    }

    @Override
    protected void doStop() {
        if (!isRunning()) return;
        unsubscribeAllTopics();
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

    public void setRetryTemplate(RetryTemplate retryTemplate) {
        this.retryTemplate = retryTemplate;
    }

    public void setRecoveryCallback(RecoveryCallback<?> recoveryCallback) {
        this.recoveryCallback = recoveryCallback;
    }

    public void setRemoteStopFlag(AtomicBoolean remoteStopFlag) {
        this.remoteStopFlag = remoteStopFlag;
    }

    @Override
    protected AttributeAccessor getErrorMessageAttributes(org.springframework.messaging.Message<?> message) {
        AttributeAccessor attributes = attributesHolder.get();
        return attributes == null ? super.getErrorMessageAttributes(message) : attributes;
    }

    private InboundXMLMessageListener buildListener(Receiver receiver) {
        JCSMPAcknowledgementCallbackFactory ackCallbackFactory = new JCSMPAcknowledgementCallbackFactory(
                receiver, consumerDestination.isTemporary(), taskService);

        InboundXMLMessageListener listener;
        if (retryTemplate != null) {
            Assert.state(getErrorChannel() == null,
                    "Cannot have an 'errorChannel' property when a 'RetryTemplate' is provided; " +
                            "use an 'ErrorMessageSendingRecoverer' in the 'recoveryCallback' property to send " +
                            "an error message when retries are exhausted");
            listener = new RetryableInboundXMLMessageListener(
                    receiver,
                    consumerDestination,
                    consumerProperties,
                    consumerProperties.isBatchMode() ? new BatchCollector(consumerProperties.getExtension()) : null,
                    this::sendMessage,
                    ackCallbackFactory,
                    retryTemplate,
                    recoveryCallback,
                    solaceMeterAccessor,
                    remoteStopFlag,
                    attributesHolder
            );
        } else {
            listener = new BasicInboundXMLMessageListener(
                    receiver,
                    consumerDestination,
                    consumerProperties,
                    consumerProperties.isBatchMode() ? new BatchCollector(consumerProperties.getExtension()) : null,
                    this::sendMessage,
                    ackCallbackFactory,
                    this::sendErrorMessageIfNecessary,
                    solaceMeterAccessor,
                    remoteStopFlag,
                    attributesHolder,
                    this.getErrorChannel() != null
            );
        }
        return listener;
    }

    @Override
    public void pause() {
        logger.info(String.format("Pausing inbound adapter %s", id));
        msgConsumer.stop();
        paused.set(true);
    }

    @Override
    public void resume() {
        logger.info(String.format("Resuming inbound adapter %s", id));
        try {
            msgConsumer.start();
            paused.set(false);
        } catch (JCSMPException e) {
            throw new RuntimeException(String.format("Failed to resume inbound adapter %s", id), e);
        }
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }

    private static final class SolaceRetryListener implements RetryListener {

        private final String topicName;

        private SolaceRetryListener(String topicName) {
            this.topicName = topicName;
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
            logger.warn(String.format("Failed to consume a message from destination %s - attempt %s",
                    topicName, context.getRetryCount()));
            for (Throwable nestedThrowable : ExceptionUtils.getThrowableList(throwable)) {
                if (nestedThrowable instanceof SolaceMessageConversionException ||
                        nestedThrowable instanceof SolaceStaleMessageException) {
                    // Do not retry if these exceptions are thrown
                    context.setExhaustedOnly();
                    break;
                }
            }
        }
    }
}

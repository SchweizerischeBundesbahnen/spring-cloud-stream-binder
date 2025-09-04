package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solace.spring.cloud.stream.binder.health.SolaceBinderHealthAccessor;
import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import com.solace.spring.cloud.stream.binder.inbound.acknowledge.JCSMPAcknowledgementCallback;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceProvisioningUtil;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import com.solace.spring.cloud.stream.binder.util.ErrorQueueInfrastructure;
import com.solace.spring.cloud.stream.binder.util.LargeMessageSupport;
import com.solace.spring.cloud.stream.binder.util.SolaceAcknowledgmentException;
import com.solace.spring.cloud.stream.binder.util.XMLMessageMapper;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.impl.JCSMPBasicSession;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.RequeueCurrentMessageException;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.integration.acks.AckUtils;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.context.OrderlyShutdownCapable;
import org.springframework.integration.core.Pausable;
import org.springframework.integration.endpoint.MessageProducerSupport;
import org.springframework.integration.support.ErrorMessageUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.support.RetryTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Setter
@RequiredArgsConstructor
public class JCSMPInboundQueueMessageProducer extends MessageProducerSupport implements OrderlyShutdownCapable, Pausable {
    private final SolaceConsumerDestination consumerDestination;
    private final JCSMPSession jcsmpSession;
    private final ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties;
    private final EndpointProperties endpointProperties;
    private final Consumer<Endpoint> postStart;
    private final Optional<SolaceMeterAccessor> solaceMeterAccessor;
    private final Optional<TracingProxy> tracingProxy;
    private final Optional<SolaceBinderHealthAccessor> solaceBinderHealthAccessor;
    private final Optional<RetryTemplate> retryTemplate;
    private final Optional<RecoveryCallback<?>> recoveryCallback;
    private final Optional<ErrorQueueInfrastructure> errorQueueInfrastructure;

    private final ThreadLocal<XMLMessageMapper> xmlMessageMapper = ThreadLocal.withInitial(XMLMessageMapper::new);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final SolaceFlowEventHandler solaceFlowEventHandler = new SolaceFlowEventHandler();
    private final FlowXMLMessageListener flowXMLMessageListener = new FlowXMLMessageListener();
    private final AtomicReference<FlowReceiver> flowReceiver = new AtomicReference<>();
    private final LargeMessageSupport largeMessageSupport = new LargeMessageSupport();


    @SuppressWarnings("OptionalGetWithoutIsPresent")
    void handleMessageWithRetry(Message<?> message, Consumer<Message<?>> sendToConsumerHandler,
                                AcknowledgmentCallback acknowledgmentCallback, BytesXMLMessage bytesXMLMessage)
            throws SolaceAcknowledgmentException {
        retryTemplate.get().execute((context) -> {
            long ts = System.currentTimeMillis();
            sendToConsumerHandler.accept(message);
            log.trace("handleMessageWithRetry step=processBean duration={}ms messageId={}", System.currentTimeMillis() - ts, bytesXMLMessage.getMessageId());
            ts = System.currentTimeMillis();

            AckUtils.autoAck(acknowledgmentCallback);
            log.trace("handleMessageWithRetry step=ack duration={}ms messageId={}", System.currentTimeMillis() - ts, bytesXMLMessage.getMessageId());
            return null;
        }, (context) -> {
            try {
                context.setAttribute(ErrorMessageUtils.INPUT_MESSAGE_CONTEXT_KEY, message);
                Object toReturn = recoveryCallback.get().recover(context);
                AckUtils.autoAck(acknowledgmentCallback);
                return toReturn;
            } catch (Exception ex) {
                handleException(acknowledgmentCallback, bytesXMLMessage, ex);
                return null;
            }
        });
    }

    private static void handleMessageWithoutRetry(Consumer<Message<?>> sendToCustomerConsumer, Message<?> message, BytesXMLMessage bytesXMLMessage, AcknowledgmentCallback acknowledgmentCallback) {
        try {
            long ts = System.currentTimeMillis();

            sendToCustomerConsumer.accept(message);
            log.trace("handleMessageWithoutRetry step=processBean duration={}ms messageId={}", System.currentTimeMillis() - ts, bytesXMLMessage.getMessageId());
            ts = System.currentTimeMillis();

            bytesXMLMessage.ackMessage();
            log.trace("handleMessageWithoutRetry step=ack duration={}ms messageId={}", System.currentTimeMillis() - ts, bytesXMLMessage.getMessageId());
        } catch (Exception ex) {
            handleException(acknowledgmentCallback, bytesXMLMessage, ex);
        }
    }

    private static void handleException(AcknowledgmentCallback acknowledgmentCallback, BytesXMLMessage bytesXMLMessage, Exception ex) {
        if (ExceptionUtils.indexOfType(ex, RequeueCurrentMessageException.class) > -1) {
            //noinspection deprecation
            log.warn("Exception thrown while processing messageId={}. Message will be requeued.",
                    bytesXMLMessage.getMessageId(), ex);
            AckUtils.requeue(acknowledgmentCallback);
        } else {
            log.warn("message processing failed, message rejected", ex);
            AckUtils.reject(acknowledgmentCallback);
        }
    }

    private void sendToConsumer(Message<?> message) {
        AtomicInteger deliveryAttempt = StaticMessageHeaderAccessor.getDeliveryAttempt(message);
        if (deliveryAttempt != null) {
            deliveryAttempt.incrementAndGet();
        }
        sendMessage(message);
    }

    public void onReceiveConcurrent(BytesXMLMessage bytesXMLMessageRaw) {
        long startTs = System.currentTimeMillis();

        AcknowledgmentCallback acknowledgmentCallback = new JCSMPAcknowledgementCallback(bytesXMLMessageRaw, errorQueueInfrastructure);
        LargeMessageSupport.MessageContext messageContext = largeMessageSupport.assemble(bytesXMLMessageRaw, acknowledgmentCallback);
        // we got an incomplete large message and wait for more chunks
        if (messageContext == null) {
            return;
        }
        BytesXMLMessage bytesXMLMessage = messageContext.bytesMessage();

        log.trace("onReceiveConcurrent step=gatherByteXmlMsg duration={}ms messageId={}", System.currentTimeMillis() - startTs, bytesXMLMessageRaw.getMessageId());
        long ts = System.currentTimeMillis();

        try {
            Message<?> message = mapMessageToSpring(bytesXMLMessage, acknowledgmentCallback);
            if (message == null) {
                return;
            }
            log.trace("onReceiveConcurrent step=convertToSpringMsg duration={}ms messageId={}", System.currentTimeMillis() - ts, bytesXMLMessageRaw.getMessageId());
            ts = System.currentTimeMillis();

            Consumer<Message<?>> sendToCustomerConsumer = this::sendToConsumer;
            if (tracingProxy.isPresent() && bytesXMLMessage.getProperties() != null && tracingProxy.get().hasTracingHeader(bytesXMLMessage.getProperties())) {
                sendToCustomerConsumer = tracingProxy.get().wrapInTracingContext(bytesXMLMessage.getProperties(), sendToCustomerConsumer);
            }
            log.trace("onReceiveConcurrent step=tracing duration={}ms messageId={}", System.currentTimeMillis() - ts, bytesXMLMessageRaw.getMessageId());
            ts = System.currentTimeMillis();

            if (retryTemplate.isPresent()) {
                handleMessageWithRetry(message, sendToCustomerConsumer, acknowledgmentCallback, bytesXMLMessage);
            } else {
                handleMessageWithoutRetry(sendToCustomerConsumer, message, bytesXMLMessage, acknowledgmentCallback);
            }
            log.trace("onReceiveConcurrent step=handleWithRetry duration={}ms messageId={}", System.currentTimeMillis() - ts, bytesXMLMessageRaw.getMessageId());
            ts = System.currentTimeMillis();

            solaceMeterAccessor.ifPresent(meterAccessor -> meterAccessor.recordMessage(consumerProperties.getBindingName(), bytesXMLMessage));

            log.trace("onReceiveConcurrent step=microMeter duration={}ms messageId={}", System.currentTimeMillis() - ts, bytesXMLMessageRaw.getMessageId());
            log.trace("onReceiveConcurrent step=total duration={}ms messageId={}", System.currentTimeMillis() - startTs, bytesXMLMessageRaw.getMessageId());
        } catch (Exception ex) {
            log.error("onReceive", ex);
            requeueMessage(bytesXMLMessage);
        }
    }


    private Message<?> mapMessageToSpring(BytesXMLMessage bytesXMLMessage, AcknowledgmentCallback acknowledgmentCallback) {
        try {
            return xmlMessageMapper.get().map(bytesXMLMessage, acknowledgmentCallback, true, consumerProperties.getExtension());
        } catch (RuntimeException e) {
            boolean processedByErrorHandler = this.sendErrorMessageIfNecessary(null, e);
            if (processedByErrorHandler) {
                bytesXMLMessage.ackMessage();
            } else {
                log.warn("Failed to map to a Spring Message and no error channel was configured. Message will be rejected: {}", bytesXMLMessage, e);
                requeueMessage(bytesXMLMessage);
            }
            return null;
        }
    }

    private void requeueMessage(BytesXMLMessage bytesXMLMessage) {
        try {
            bytesXMLMessage.settle(XMLMessage.Outcome.FAILED);
        } catch (JCSMPException ex) {
            log.error("failed to requeue message", ex);
        }
    }

    @Override
    protected void doStart() {
        if (isRunning()) {
            log.warn("Nothing to do. Inbound message channel adapter binding={} is already running", consumerDestination.getName());
            return;
        }
        try {
            startFlowReceiver();
        } catch (Exception e) {
            log.error("Failed to start flow receiver", e);
            throw new MessagingException("Failed to start flow receiver", e);
        }
    }

    private void startFlowReceiver() throws Exception {
        final String endpointName = consumerDestination.getName();
        log.info("Creating {} threads for binding={} <inbound adapter>", consumerProperties.getConcurrency(), endpointName);
        checkPropertiesAndBroker();
        setupFlowEventHandler();
        ConsumerFlowProperties consumerFlowProperties = getConsumerFlowProperties(endpointName);
        long maxProcessingTimeMs = consumerProperties.getExtension().getMaxProcessingTimeMs();
        this.solaceMeterAccessor.ifPresent(ma -> this.flowXMLMessageListener.setSolaceMeterAccessor(ma, consumerProperties.getBindingName()));
        this.flowXMLMessageListener.startReceiverThreads(
                consumerProperties.getConcurrency(),
                consumerDestination.getBindingDestinationName(),
                this::onReceiveConcurrent,
                maxProcessingTimeMs);
        this.flowReceiver.set(jcsmpSession.createFlow(flowXMLMessageListener, consumerFlowProperties, endpointProperties, solaceFlowEventHandler));
        if (!paused.get()) {
            this.flowReceiver.get().start();
        }
        addPostStartToFlowEventHandler();
        postStart.accept(flowReceiver.get().getEndpoint());
    }

    /**
     * Ensures the subscriptions are added after a reconnect
     */
    private void addPostStartToFlowEventHandler() {
        solaceFlowEventHandler.addReconnectRunnable(() -> postStart.accept(flowReceiver.get().getEndpoint()));
    }

    private void checkPropertiesAndBroker() {
        if (consumerProperties.getConcurrency() < 1) {
            String msg = String.format("Concurrency must be greater than 0, was %d <inbound adapter binding=%s>",
                    consumerProperties.getConcurrency(), consumerDestination.getName());
            log.warn(msg);
            throw new MessagingException(msg);
        }
        if (jcsmpSession instanceof JCSMPBasicSession jcsmpBasicSession
                && !jcsmpBasicSession.isRequiredSettlementCapable(
                Set.of(XMLMessage.Outcome.ACCEPTED, XMLMessage.Outcome.FAILED, XMLMessage.Outcome.REJECTED))) {
            String msg = String.format("The Solace PubSub+ Broker doesn't support message NACK capability, <inbound adapter binding=%s>", consumerDestination.getName());
            throw new MessagingException(msg);
        }
    }

    private void setupFlowEventHandler() {
        this.solaceFlowEventHandler.setBindingName(consumerProperties.getBindingName());
        startSolaceHealthIndicator();
    }

    private void startSolaceHealthIndicator() {
        solaceBinderHealthAccessor.ifPresent(solaceBinderHealth -> {
            SolaceHealthIndicator bindingHealthIndicator = solaceBinderHealth.createBindingHealthIndicator(consumerProperties.getBindingName());
            bindingHealthIndicator.healthUp();
            this.solaceFlowEventHandler.setBindingHealthIndicator(bindingHealthIndicator);
        });
    }

    private ConsumerFlowProperties getConsumerFlowProperties(String endpointName) {
        Endpoint endpoint = JCSMPFactory.onlyInstance().createQueue(endpointName);
        log.info("Flow receiver binding={} started in state '{}'", consumerDestination.getName(), paused.get() ? "Paused" : "Running");
        ConsumerFlowProperties consumerFlowProperties = SolaceProvisioningUtil.getConsumerFlowProperties(
                        consumerDestination.getBindingDestinationName(), consumerProperties)
                .setEndpoint(endpoint)
                .setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);
        consumerFlowProperties.setStartState(!paused.get());
        consumerFlowProperties.addRequiredSettlementOutcomes(XMLMessage.Outcome.ACCEPTED, XMLMessage.Outcome.FAILED, XMLMessage.Outcome.REJECTED);
        consumerFlowProperties.setActiveFlowIndication(true); // otherwise no flowEvents will be fired
        return consumerFlowProperties;
    }

    @Override
    protected void doStop() {
        if (!isRunning()) return;
        solaceBinderHealthAccessor.ifPresent(solaceBinderHealth -> solaceBinderHealth.removeBindingHealthIndicator(consumerProperties.getBindingName()));
        FlowReceiver currentFlowReceiver = this.flowReceiver.get();
        if (currentFlowReceiver != null) {
            currentFlowReceiver.stop();
            currentFlowReceiver.close();
            this.flowReceiver.set(null); // Clear the reference to ensure clean restart
        }
        this.flowXMLMessageListener.stopReceiverThreads();
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
    public void pause() {
        log.info("Pausing inbound adapter binding={}", consumerDestination.getName());
        paused.set(true);
        if (this.flowReceiver.get() != null) {
            this.flowReceiver.get().stop();
        }
    }

    @Override
    public void resume() {
        if (!this.isRunning()) {
            throw new IllegalArgumentException("Binding is not started, cannot resume.");
        }
        log.info("Resuming inbound adapter binding={}", consumerDestination.getName());
        paused.set(false);
        try {
            FlowReceiver currentFlowReceiver = this.flowReceiver.get();
            if (currentFlowReceiver != null) {
                currentFlowReceiver.start();
            }
        } catch (JCSMPException e) {
            log.error("Failed to resume/start flow receiver", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }
}

package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solace.spring.cloud.stream.binder.health.SolaceBinderHealthAccessor;
import com.solace.spring.cloud.stream.binder.health.indicators.FlowHealthIndicator;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceProvisioningUtil;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import com.solace.spring.cloud.stream.binder.util.SolaceAcknowledgmentException;
import com.solace.spring.cloud.stream.binder.util.XMLMessageMapper;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.impl.JCSMPBasicSession;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.integration.acks.AckUtils;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.context.OrderlyShutdownCapable;
import org.springframework.integration.core.Pausable;
import org.springframework.integration.endpoint.MessageProducerSupport;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.support.RetryTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
@Setter
@RequiredArgsConstructor
public class JCSMPInboundQueueMessageProducer extends MessageProducerSupport implements OrderlyShutdownCapable, Pausable {
    private final String id = UUID.randomUUID().toString();
    private final SolaceConsumerDestination consumerDestination;
    private final JCSMPSession jcsmpSession;
    private final ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties;
    private final EndpointProperties endpointProperties;
    private final Optional<SolaceMeterAccessor> solaceMeterAccessor;
    private final Optional<TracingProxy> tracingProxy;
    private final Optional<SolaceBinderHealthAccessor> solaceBinderHealthAccessor;
    private final Optional<RetryTemplate> retryTemplate;
    private final Optional<RecoveryCallback<?>> recoveryCallback;
    private final Optional<BiFunction<Message<?>, RuntimeException, Boolean>> errorHandlerFunction;
    private final ThreadLocal<XMLMessageMapper> xmlMessageMapper = ThreadLocal.withInitial(XMLMessageMapper::new);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final SolaceFlowEventHandler solaceFlowEventHandler = new SolaceFlowEventHandler();
    private final FlowXMLMessageListener flowXMLMessageListener = new FlowXMLMessageListener();
    private final AtomicReference<FlowReceiver> flowReceiver = new AtomicReference<>();

    private Consumer<Message<?>> wrapInReplyTemplate(Consumer<Message<?>> sendToCustomerConsumer){
        if (retryTemplate.isEmpty()){
            return sendToCustomerConsumer;
        }
        return message -> {
            return retryTemplate.get().execute((context)-> {
                return sendToCustomerConsumer.accept(message);
            });

        };
    }
    void handleMessage(Supplier<Message<?>> messageSupplier, Consumer<Message<?>> sendToConsumerHandler,
                       AcknowledgmentCallback acknowledgmentCallback)
            throws SolaceAcknowledgmentException {
        Message<?> message = retryTemplate.get().execute((context) -> {
            attributesHolder.set(context);
            return messageSupplier.get();
        }, (context) -> {
            recoveryCallback.get().recover(context);
            AckUtils.autoAck(acknowledgmentCallback);
            return null;
        });

        if (message == null) {
            return;
        }

        retryTemplate.execute((context) -> {
            attributesHolder.set(context);
            sendToConsumerHandler.accept(message);
            AckUtils.autoAck(acknowledgmentCallback);
            return null;
        }, (context) -> {
            Object toReturn = recoveryCallback.recover(context);
            AckUtils.autoAck(acknowledgmentCallback);
            return toReturn;
        });
    }

    public void onReceiveConcurrent(BytesXMLMessage bytesXMLMessage) {
        try {
            Message<?> message = mapMessageToSpring(bytesXMLMessage);
            if (message == null) {
                return;
            }
            Consumer<Message<?>> sendToCustomerConsumer = this::sendMessage;
            if (tracingProxy.isPresent() && bytesXMLMessage.getProperties() != null && tracingProxy.get().hasTracingHeader(bytesXMLMessage.getProperties())) {
                sendToCustomerConsumer = tracingProxy.get().wrapInTracingContext(bytesXMLMessage.getProperties(), sendToCustomerConsumer);
            }

            sendToCustomerConsumer.accept(message);

            solaceMeterAccessor.ifPresent(meterAccessor -> meterAccessor.recordMessage(consumerProperties.getBindingName(), bytesXMLMessage));
            bytesXMLMessage.ackMessage();
        } catch (Exception ex) {
            log.error("onReceive", ex);
            requeueMessage(bytesXMLMessage);
        }
    }

    private Message<?> mapMessageToSpring(BytesXMLMessage bytesXMLMessage) {
        try {
            return xmlMessageMapper.get().map(bytesXMLMessage, null, consumerProperties.getExtension());
        } catch (RuntimeException e) {
            boolean processedByErrorHandler = errorHandlerFunction.isPresent() && errorHandlerFunction.get().apply(null, e);
            if (processedByErrorHandler) {
                bytesXMLMessage.ackMessage();
            } else {
                log.warn("Failed to map %s to a Spring Message and no error channel " +
                        "was configured. Message will be rejected. an XMLMessage", e);
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
            log.warn("Nothing to do. Inbound message channel adapter {} is already running", id);
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
        this.paused.set(!consumerProperties.isAutoStartup());
        final String endpointName = consumerDestination.getName();
        log.info("Creating {} consumer flows for {} <inbound adapter {}>", consumerProperties.getConcurrency(), endpointName, id);
        checkPropertiesAndBroker();
        setupFlowEventHandler();
        ConsumerFlowProperties consumerFlowProperties = getConsumerFlowProperties(endpointName);
        long maxProcessingTimeMs = consumerProperties.getExtension().getMaxProcessingTimeMs();
        this.flowXMLMessageListener.startReceiverThreads(
                consumerProperties.getConcurrency(),
                consumerDestination.getBindingDestinationName(),
                this::onReceiveConcurrent,
                maxProcessingTimeMs);
        this.flowReceiver.set(jcsmpSession.createFlow(flowXMLMessageListener, consumerFlowProperties, endpointProperties, solaceFlowEventHandler));
        if (!paused.get()) {
            this.flowReceiver.get().start();
        }
    }

    private void checkPropertiesAndBroker() {
        if (consumerProperties.getConcurrency() < 1) {
            String msg = String.format("Concurrency must be greater than 0, was %s <inbound adapter %s>",
                    consumerProperties.getConcurrency(), id);
            log.warn(msg);
            throw new MessagingException(msg);
        }
        if (jcsmpSession instanceof JCSMPBasicSession jcsmpBasicSession
                && !jcsmpBasicSession.isRequiredSettlementCapable(
                Set.of(XMLMessage.Outcome.ACCEPTED, XMLMessage.Outcome.FAILED, XMLMessage.Outcome.REJECTED))) {
            String msg = String.format("The Solace PubSub+ Broker doesn't support message NACK capability, <inbound adapter %s>", id);
            throw new MessagingException(msg);
        }
    }

    private void setupFlowEventHandler() {
        this.solaceFlowEventHandler.clearReconnectListeners();
        this.solaceFlowEventHandler.setBindingName(consumerProperties.getBindingName());
        this.solaceFlowEventHandler.setBindingId(id);
        startSolaceHealthIndicator();
    }

    private void startSolaceHealthIndicator() {
        solaceBinderHealthAccessor.ifPresent(solaceBinderHealth -> {
            FlowHealthIndicator flowHealthIndicator = solaceBinderHealth.createFlowHealthIndicator(consumerProperties.getBindingName(), id);
            flowHealthIndicator.up();
            this.solaceFlowEventHandler.setFlowHealthIndicator(flowHealthIndicator);
        });
    }

    private ConsumerFlowProperties getConsumerFlowProperties(String endpointName) {
        Endpoint endpoint = JCSMPFactory.onlyInstance().createQueue(endpointName);
        log.info("Flow receiver {} started in state '{}'", id, paused.get() ? "Paused" : "Running");
        ConsumerFlowProperties consumerFlowProperties = SolaceProvisioningUtil.getConsumerFlowProperties(
                        consumerDestination.getBindingDestinationName(), consumerProperties)
                .setEndpoint(endpoint)
                .setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);
        consumerFlowProperties.setStartState(!paused.get());
        consumerFlowProperties.addRequiredSettlementOutcomes(XMLMessage.Outcome.ACCEPTED, XMLMessage.Outcome.FAILED, XMLMessage.Outcome.REJECTED);
        return consumerFlowProperties;
    }

    @Override
    protected void doStop() {
        if (!isRunning()) return;
        solaceBinderHealthAccessor.ifPresent(solaceBinderHealth -> {
            solaceBinderHealth.removeFlowHealthIndicator(consumerProperties.getBindingName(), id);
        });
        this.flowReceiver.get().stop();
        this.flowReceiver.get().close();
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
        log.info("Pausing inbound adapter {}", id);
        paused.set(true);
        this.flowReceiver.get().stop();
    }

    @Override
    public void resume() {
        log.info("Resuming inbound adapter {}", id);
        paused.set(false);
        try {
            this.flowReceiver.get().start();
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

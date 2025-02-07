package com.solace.spring.cloud.stream.binder.inbound;

import com.solace.spring.cloud.stream.binder.inbound.acknowledge.JCSMPAcknowledgementCallbackFactory;
import com.solace.spring.cloud.stream.binder.inbound.acknowledge.SolaceAckUtil;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import com.solace.spring.cloud.stream.binder.util.*;
import com.solacesystems.jcsmp.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.RequeueCurrentMessageException;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.core.AttributeAccessor;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.integration.acks.AckUtils;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.support.ErrorMessageUtils;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
abstract class InboundXMLMessageListener implements Runnable {
    final FlowReceiverContainer flowReceiverContainer;
    final ConsumerDestination consumerDestination;
    private final ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties;
    final ThreadLocal<AttributeAccessor> attributesHolder;
    private final XMLMessageMapper xmlMessageMapper;
    private final Consumer<Message<?>> messageConsumer;
    private final JCSMPAcknowledgementCallbackFactory ackCallbackFactory;
    private final Optional<SolaceMeterAccessor> solaceMeterAccessor;
    private final Optional<TracingProxy> tracingProxy;
    private final boolean needHolder;
    private final boolean needAttributes;
    @Getter
    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private final Supplier<Boolean> remoteStopFlag;
    private final LargeMessageSupport largeMessageSupport = new LargeMessageSupport();

    InboundXMLMessageListener(FlowReceiverContainer flowReceiverContainer,
                              ConsumerDestination consumerDestination,
                              ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties,
                              Consumer<Message<?>> messageConsumer,
                              JCSMPAcknowledgementCallbackFactory ackCallbackFactory,
                              Optional<SolaceMeterAccessor> solaceMeterAccessor,
                              Optional<TracingProxy> tracingProxy,
                              @Nullable AtomicBoolean remoteStopFlag,
                              ThreadLocal<AttributeAccessor> attributesHolder,
                              boolean needHolder,
                              boolean needAttributes) {
        this.flowReceiverContainer = flowReceiverContainer;
        this.consumerDestination = consumerDestination;
        this.consumerProperties = consumerProperties;
        this.messageConsumer = messageConsumer;
        this.ackCallbackFactory = ackCallbackFactory;
        this.solaceMeterAccessor = solaceMeterAccessor;
        this.tracingProxy = tracingProxy;
        this.remoteStopFlag = () -> remoteStopFlag != null && remoteStopFlag.get();
        this.attributesHolder = attributesHolder;
        this.needHolder = needHolder;
        this.needAttributes = needAttributes;
        this.xmlMessageMapper = flowReceiverContainer.getXMLMessageMapper();
        this.largeMessageSupport.startHousekeeping();
    }

    abstract void handleMessage(Supplier<Message<?>> messageSupplier, Consumer<Message<?>> sendToConsumerHandler, AcknowledgmentCallback acknowledgmentCallback) throws SolaceAcknowledgmentException;

    @Override
    public void run() {
        try {
            while (keepPolling()) {
                try {
                    receive();
                } catch (RuntimeException | UnboundFlowReceiverContainerException e) {
                    log.warn(String.format("Exception received while consuming messages from destination %s", consumerDestination.getName()), e);
                }
            }
        } catch (StaleSessionException e) {
            log.error("Session has lost connection", e);
        } catch (Throwable t) {
            log.error(String.format("Received unexpected error while consuming from destination %s", consumerDestination.getName()), t);
            throw t;
        } finally {
            log.info(String.format("Closing flow receiver to destination %s", consumerDestination.getName()));
            flowReceiverContainer.unbind();
        }
    }

    private boolean keepPolling() {
        return !stopFlag.get() && !remoteStopFlag.get();
    }

    private void receive() throws UnboundFlowReceiverContainerException, StaleSessionException {
        MessageContainer messageContainer;
        try {
            messageContainer = flowReceiverContainer.receive();
        } catch (StaleSessionException e) {
            throw e;
        } catch (JCSMPException e) {
            String msg = String.format("Received error while trying to read message from endpoint %s", flowReceiverContainer.getEndpointName());
            if ((e instanceof JCSMPTransportException || e instanceof ClosedFacilityException) && !keepPolling()) {
                log.debug(msg, e);
            } else {
                log.warn(msg, e);
            }
            return;
        }

        if (solaceMeterAccessor.isPresent() && messageContainer != null) {
            solaceMeterAccessor.get().recordMessage(consumerProperties.getBindingName(), messageContainer.getMessage());
        }

        try {
            if (messageContainer != null) {
                processMessage(messageContainer);
            }
        } finally {
            if (needHolder || needAttributes) {
                attributesHolder.remove();
            }
        }
    }

    private void processMessage(MessageContainer messageContainer) {
        BytesXMLMessage bytesXMLMessage = messageContainer.getMessage();
        AcknowledgmentCallback acknowledgmentCallback = ackCallbackFactory.createCallback(messageContainer);
        LargeMessageSupport.MessageContext messageContext = largeMessageSupport.assemble(bytesXMLMessage, acknowledgmentCallback);
        // we got an incomplete large message and wait for more chunks
        if (messageContext == null) {
            return;
        }
        processMessage(messageContext.bytesMessage(), messageContext.acknowledgmentCallback());
    }

    private void processMessage(BytesXMLMessage bytesXMLMessage, AcknowledgmentCallback acknowledgmentCallback) {
        try {
            Supplier<Message<?>> createMessageSupplier = () -> createOneMessage(bytesXMLMessage, acknowledgmentCallback);
            Consumer<Message<?>> sendToCustomerConsumer = m -> sendOneToConsumer(m, bytesXMLMessage);
            if (tracingProxy.isPresent() && bytesXMLMessage.getProperties() != null && tracingProxy.get().hasTracingHeader(bytesXMLMessage.getProperties())) {
                sendToCustomerConsumer = tracingProxy.get().wrapInTracingContext(bytesXMLMessage.getProperties(), sendToCustomerConsumer);
            }
            handleMessage(createMessageSupplier, sendToCustomerConsumer, acknowledgmentCallback);
        } catch (SolaceAcknowledgmentException e) {
            throw e;
        } catch (Exception e) {
            try {
                if (ExceptionUtils.indexOfType(e, RequeueCurrentMessageException.class) > -1) {
                    log.warn(String.format("Exception thrown while processing XMLMessage %s. Message will be requeued.", bytesXMLMessage.getMessageId()), e);
                    AckUtils.requeue(acknowledgmentCallback);
                } else {
                    log.warn(String.format("Exception thrown while processing XMLMessage %s. Message will be requeued.", bytesXMLMessage.getMessageId()), e);
                    if (!SolaceAckUtil.republishToErrorQueue(acknowledgmentCallback)) {
                        AckUtils.requeue(acknowledgmentCallback);
                    }
                }
            } catch (SolaceAcknowledgmentException e1) {
                e1.addSuppressed(e);
                log.warn(String.format("Exception thrown while re-queuing XMLMessage %s.", bytesXMLMessage.getMessageId()), e1);
                throw e1;
            }
        }
    }

    Message<?> createOneMessage(BytesXMLMessage bytesXMLMessage, AcknowledgmentCallback acknowledgmentCallback) {
        setAttributesIfNecessary(bytesXMLMessage, acknowledgmentCallback);
        return xmlMessageMapper.map(bytesXMLMessage, acknowledgmentCallback, consumerProperties.getExtension());
    }

    void sendOneToConsumer(final Message<?> message, final BytesXMLMessage bytesXMLMessage) throws RuntimeException {
        setAttributesIfNecessary(bytesXMLMessage, message);
        sendToConsumer(message);
    }

    private void sendToConsumer(final Message<?> message) throws RuntimeException {
        AtomicInteger deliveryAttempt = StaticMessageHeaderAccessor.getDeliveryAttempt(message);
        if (deliveryAttempt != null) {
            deliveryAttempt.incrementAndGet();
        }
        messageConsumer.accept(message);
    }

    void setAttributesIfNecessary(XMLMessage xmlMessage, AcknowledgmentCallback acknowledgmentCallback) {
        setAttributesIfNecessary(xmlMessage, null, acknowledgmentCallback);
    }

    void setAttributesIfNecessary(XMLMessage xmlMessage, Message<?> message) {
        setAttributesIfNecessary(xmlMessage, message, null);
    }

    private void setAttributesIfNecessary(Object rawXmlMessage, Message<?> message, AcknowledgmentCallback acknowledgmentCallback) {
        if (needHolder) {
            attributesHolder.set(ErrorMessageUtils.getAttributeAccessor(null, null));
        }

        if (needAttributes) {
            AttributeAccessor attributes = attributesHolder.get();
            if (attributes != null) {
                attributes.setAttribute(ErrorMessageUtils.INPUT_MESSAGE_CONTEXT_KEY, message);
                attributes.setAttribute(SolaceMessageHeaderErrorMessageStrategy.ATTR_SOLACE_RAW_MESSAGE, rawXmlMessage);
                attributes.setAttribute(SolaceMessageHeaderErrorMessageStrategy.ATTR_SOLACE_ACKNOWLEDGMENT_CALLBACK, acknowledgmentCallback);
            }
        }
    }
}

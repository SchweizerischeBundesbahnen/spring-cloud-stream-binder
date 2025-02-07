package com.solace.spring.cloud.stream.binder.outbound;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceProvisioningUtil;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import com.solace.spring.cloud.stream.binder.util.*;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionProducerManager.CloudStreamEventHandler;
import com.solacesystems.jcsmp.*;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.context.Lifecycle;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
public class JCSMPOutboundMessageHandler implements MessageHandler, Lifecycle {
    private final String id = UUID.randomUUID().toString();
    private final DestinationType configDestinationType;
    private final Destination configDestination;
    private final JCSMPSession jcsmpSession;
    private final MessageChannel errorChannel;
    private final JCSMPSessionProducerManager producerManager;
    private final ExtendedProducerProperties<SolaceProducerProperties> properties;
    private final JCSMPStreamingPublishCorrelatingEventHandler producerEventHandler = new CloudStreamEventHandler();
    private final LargeMessageSupport largeMessageSupport = new LargeMessageSupport();
    private final Optional<SolaceMeterAccessor> solaceMeterAccessor;
    private final Optional<TracingProxy> tracing;
    private XMLMessageProducer producer;
    private final XMLMessageMapper xmlMessageMapper = new XMLMessageMapper();
    private boolean isRunning = false;
    @Setter
    private ErrorMessageStrategy errorMessageStrategy;


    public JCSMPOutboundMessageHandler(ProducerDestination destination,
                                       JCSMPSession jcsmpSession,
                                       MessageChannel errorChannel,
                                       JCSMPSessionProducerManager producerManager,
                                       ExtendedProducerProperties<SolaceProducerProperties> properties,
                                       Optional<SolaceMeterAccessor> solaceMeterAccessor,
                                       Optional<TracingProxy> tracing) {
        this.configDestinationType = properties.getExtension().getDestinationType();
        this.configDestination = configDestinationType == DestinationType.TOPIC ?
                JCSMPFactory.onlyInstance().createTopic(destination.getName()) :
                JCSMPFactory.onlyInstance().createQueue(destination.getName());
        this.jcsmpSession = jcsmpSession;
        this.errorChannel = errorChannel;
        this.producerManager = producerManager;
        this.properties = properties;
        this.solaceMeterAccessor = solaceMeterAccessor;
        this.tracing = tracing;
    }

    @SneakyThrows
    @Override
    public void handleMessage(Message<?> message) throws MessagingException {
        ErrorChannelSendingCorrelationKey correlationKey = new ErrorChannelSendingCorrelationKey(message,
                errorChannel, errorMessageStrategy);

        if (!isRunning()) {
            String msg0 = String.format("Cannot send message using handler %s", id);
            String msg1 = String.format("Message handler %s is not running", id);
            throw handleMessagingException(correlationKey, msg0, new ClosedChannelBindingException(msg1));
        }


        try {
            CorrelationData correlationData = message.getHeaders()
                    .get(SolaceBinderHeaders.CONFIRM_CORRELATION, CorrelationData.class);
            if (correlationData != null) {
                if (properties.getExtension().getDeliveryMode() != DeliveryMode.PERSISTENT) {
                    String msg0 = String.format("Cannot send message using handler %s", id);
                    String msg1 = "CONFIRM_CORRELATION is not supported, because the channel is configured as deliveryMode!=PERSISTENT.";
                    throw handleMessagingException(correlationKey, msg0, new IllegalArgumentException(msg1));
                }
                correlationData.setMessage(message);
                correlationKey.setConfirmCorrelation(correlationData);
            }
        } catch (IllegalArgumentException e) {
            throw handleMessagingException(correlationKey,
                    String.format("Unable to parse header %s", SolaceBinderHeaders.CONFIRM_CORRELATION), e);
        }

        List<XMLMessage> smfMessages;
        Destination dynamicDestination;
        XMLMessage smfMessageMapped = xmlMessageMapper.map(
                message,
                properties.getExtension().getHeaderExclusions(),
                properties.getExtension().isNonserializableHeaderConvertToString(),
                properties.getExtension().getDeliveryMode());
        tracing.ifPresent(tracingProxy -> tracingProxy.injectTracingHeader(smfMessageMapped.getProperties()));

        smfMessageMapped.setCorrelationKey(correlationKey);
        dynamicDestination = getDynamicDestination(message.getHeaders(), correlationKey);
        if (message.getHeaders().containsKey(SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT)) {
            smfMessages = largeMessageSupport.split(smfMessageMapped);
        } else {
            smfMessages = List.of(smfMessageMapped);
        }

        correlationKey.setRawMessages(smfMessages);

        try {
            for (int i = 0; i < smfMessages.size(); i++) {
                XMLMessage smfMessage = smfMessages.get(i);
                Destination targetDestination = Objects.requireNonNullElse(dynamicDestination, configDestination);
                log.debug("Publishing message {} of {} to destination [ {}:{} ] <message handler ID: {}>",
                        i + 1, smfMessages.size(), targetDestination instanceof Topic ? "TOPIC" : "QUEUE",
                        targetDestination, id);
                producer.send(smfMessage, targetDestination);
            }
        } catch (JCSMPException e) {
            throw handleMessagingException(correlationKey, "Unable to send message(s) to destination", e);
        } finally {
            if (solaceMeterAccessor.isPresent()) {
                for (XMLMessage smfMessage : smfMessages) {
                    solaceMeterAccessor.get().recordMessage(properties.getBindingName(), smfMessage);
                }
            }
        }
    }

    private Destination getDynamicDestination(Map<String, Object> headers, ErrorChannelSendingCorrelationKey correlationKey) {
        try {
            String dynamicDestName;
            String targetDestinationHeader = StaticMessageHeaderMapAccessor.get(headers,
                    BinderHeaders.TARGET_DESTINATION, String.class);
            if (StringUtils.hasText(targetDestinationHeader)) {
                dynamicDestName = targetDestinationHeader.trim();
            } else {
                return null;
            }

            String targetDestinationTypeHeader = StaticMessageHeaderMapAccessor.get(headers,
                    SolaceBinderHeaders.TARGET_DESTINATION_TYPE, String.class);
            if (StringUtils.hasText(targetDestinationTypeHeader)) {
                targetDestinationTypeHeader = targetDestinationTypeHeader.trim().toUpperCase();
                if (targetDestinationTypeHeader.equals(DestinationType.TOPIC.name())) {
                    return JCSMPFactory.onlyInstance().createTopic(dynamicDestName);
                } else if (targetDestinationTypeHeader.equals(DestinationType.QUEUE.name())) {
                    return JCSMPFactory.onlyInstance().createQueue(dynamicDestName);
                } else {
                    throw new IllegalArgumentException(String.format("Incorrect value specified for header '%s'. Expected [ %s|%s ] but actual value is [ %s ]",
                            SolaceBinderHeaders.TARGET_DESTINATION_TYPE, DestinationType.TOPIC.name(), DestinationType.QUEUE.name(), targetDestinationTypeHeader));
                }
            }

            //No dynamic destinationType present so use configured destinationType
            return configDestinationType == DestinationType.TOPIC ?
                    JCSMPFactory.onlyInstance().createTopic(dynamicDestName) :
                    JCSMPFactory.onlyInstance().createQueue(dynamicDestName);
        } catch (Exception e) {
            throw handleMessagingException(correlationKey, "Unable to parse headers", e);
        }
    }

    @Override
    public void start() {
        log.info("Creating producer to {} {} <message handler ID: {}>", configDestinationType, configDestination.getName(), id);
        if (isRunning()) {
            log.warn("Nothing to do, message handler {} is already running", id);
            return;
        }

        try {
            XMLMessageProducer defaultProducer = producerManager.get(id);
            // flow producers don't support direct messaging
            if (DeliveryMode.DIRECT.equals(properties.getExtension().getDeliveryMode())) {
                producer = defaultProducer;
            } else {
                producer = jcsmpSession.createProducer(SolaceProvisioningUtil.getProducerFlowProperties(jcsmpSession),
                        producerEventHandler);
            }
        } catch (Exception e) {
            String msg = String.format("Unable to get a message producer for session %s", jcsmpSession.getSessionName());
            log.warn(msg, e);
            closeResources();
            throw new RuntimeException(msg, e);
        }

        isRunning = true;
    }

    @Override
    public void stop() {
        if (!isRunning()) return;
        closeResources();
        isRunning = false;
    }

    private void closeResources() {
        log.info("Stopping producer to {} {} <message handler ID: {}>", configDestinationType, configDestination.getName(), id);
        if (producer != null && !DeliveryMode.DIRECT.equals(properties.getExtension().getDeliveryMode())) {
            log.info("Closing producer <message handler ID: {}>", id);
            producer.close();
        }
        producerManager.release(id);
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    private MessagingException handleMessagingException(ErrorChannelSendingCorrelationKey key, String msg, Exception e)
            throws MessagingException {
        log.warn(msg, e);
        return key.send(msg, e);
    }
}

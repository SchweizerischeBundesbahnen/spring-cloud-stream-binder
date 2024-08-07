package com.solace.spring.cloud.stream.binder.outbound;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceProvisioningUtil;
import com.solace.spring.cloud.stream.binder.util.*;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionProducerManager.CloudStreamEventHandler;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.transaction.RollbackException;
import com.solacesystems.jcsmp.transaction.TransactedSession;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.context.Lifecycle;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.lang.Nullable;
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
    @Nullable
    private final SolaceMeterAccessor solaceMeterAccessor;
    private XMLMessageProducer producer;
    @Nullable
    private TransactedSession transactedSession;
    private final XMLMessageMapper xmlMessageMapper = new XMLMessageMapper();
    private boolean isRunning = false;
    @Setter
    private ErrorMessageStrategy errorMessageStrategy;


    public JCSMPOutboundMessageHandler(ProducerDestination destination,
                                       JCSMPSession jcsmpSession,
                                       MessageChannel errorChannel,
                                       JCSMPSessionProducerManager producerManager,
                                       ExtendedProducerProperties<SolaceProducerProperties> properties,
                                       @Nullable SolaceMeterAccessor solaceMeterAccessor) {
        this.configDestinationType = properties.getExtension().getDestinationType();
        this.configDestination = configDestinationType == DestinationType.TOPIC ?
                JCSMPFactory.onlyInstance().createTopic(destination.getName()) :
                JCSMPFactory.onlyInstance().createQueue(destination.getName());
        this.jcsmpSession = jcsmpSession;
        this.errorChannel = errorChannel;
        this.producerManager = producerManager;
        this.properties = properties;
        this.solaceMeterAccessor = solaceMeterAccessor;
    }

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
        List<Destination> dynamicDestinations;
        if (message.getHeaders().containsKey(SolaceBinderHeaders.BATCHED_HEADERS)) {
            log.debug("Detected header {}, handling as batched message (Message<List<?>>) <message handler ID: {}>",
                    SolaceBinderHeaders.BATCHED_HEADERS, id);
            smfMessages = xmlMessageMapper.mapBatchMessage(
                    message,
                    properties.getExtension().getHeaderExclusions(),
                    properties.getExtension().isNonserializableHeaderConvertToString(),
                    properties.getExtension().getDeliveryMode()
            );
            BatchProxyCorrelationKey batchProxyCorrelationKey = transactedSession == null ?
                    new BatchProxyCorrelationKey(correlationKey, smfMessages.size()) : null;
            smfMessages.forEach(smfMessage -> smfMessage.setCorrelationKey(
                    Objects.requireNonNullElse(batchProxyCorrelationKey, correlationKey)));

            if (transactedSession == null) {
                smfMessages.get(smfMessages.size() - 1).setAckImmediately(true);
            }

            // after successfully running xmlMessageMapper.mapBatchMessage(),
            // SolaceBinderHeaders.BATCHED_HEADERS is verified to be well-formed

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> batchedHeaders = (List<Map<String, Object>>) message.getHeaders()
                    .getOrDefault(SolaceBinderHeaders.BATCHED_HEADERS,
                            Collections.nCopies(smfMessages.size(), Collections.emptyMap()));

            dynamicDestinations = batchedHeaders.stream()
                    .map(h -> getDynamicDestination(h, correlationKey))
                    .toList();
        } else {
            XMLMessage smfMessage = xmlMessageMapper.map(
                    message,
                    properties.getExtension().getHeaderExclusions(),
                    properties.getExtension().isNonserializableHeaderConvertToString(),
                    properties.getExtension().getDeliveryMode());

            smfMessage.setCorrelationKey(correlationKey);
            smfMessages = List.of(smfMessage);
            dynamicDestinations = Collections.singletonList(getDynamicDestination(message.getHeaders(), correlationKey));
        }

        correlationKey.setRawMessages(smfMessages);

        try {
            for (int i = 0; i < smfMessages.size(); i++) {
                XMLMessage smfMessage = smfMessages.get(i);
                Destination targetDestination = Objects.requireNonNullElse(dynamicDestinations.get(i), configDestination);
                log.debug("Publishing message {} of {} to destination [ {}:{} ] <message handler ID: {}>",
                        i + 1, smfMessages.size(), targetDestination instanceof Topic ? "TOPIC" : "QUEUE",
                        targetDestination, id);
                producer.send(smfMessage, targetDestination);
            }
            if (transactedSession != null) {
                log.debug("Committing transaction <message handler ID: {}>", id);
                transactedSession.commit();
                producerEventHandler.responseReceivedEx(correlationKey);
            }
        } catch (JCSMPException e) {
            if (transactedSession != null) {
                try {
                    if (!(e instanceof RollbackException)) {
                        log.debug("Rolling back transaction <message handler ID: {}>", id);
                        transactedSession.rollback();
                    }
                } catch (JCSMPException ex) {
                    log.debug("Failed to rollback transaction", ex);
                    e.addSuppressed(ex);
                } finally {
                    producerEventHandler.handleErrorEx(correlationKey, e, System.currentTimeMillis());
                }
            }

            throw handleMessagingException(correlationKey, "Unable to send message(s) to destination", e);
        } finally {
            if (solaceMeterAccessor != null) {
                for (XMLMessage smfMessage : smfMessages) {
                    solaceMeterAccessor.recordMessage(properties.getBindingName(), smfMessage);
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
            } else if (properties.getExtension().isTransacted()) {
                log.info("Creating transacted session  <message handler ID: {}>", id);
                transactedSession = jcsmpSession.createTransactedSession();
                producer = transactedSession.createProducer(SolaceProvisioningUtil.getProducerFlowProperties(jcsmpSession),
                        producerEventHandler);
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
        if (transactedSession != null) {
            log.info("Closing transacted session <message handler ID: {}>", id);
            transactedSession.close();
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

package com.solace.spring.cloud.stream.binder.util;

import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solacesystems.jcsmp.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessagingException;

@Slf4j
public class ErrorQueueInfrastructure {
    private final JCSMPSessionProducerManager producerManager;
    private final String producerKey;
    private final String errorQueueName;
    private final SolaceConsumerProperties consumerProperties;
    private final XMLMessageMapper xmlMessageMapper = new XMLMessageMapper();

    public ErrorQueueInfrastructure(JCSMPSessionProducerManager producerManager, String producerKey,
                                    String errorQueueName, SolaceConsumerProperties consumerProperties) {
        this.producerManager = producerManager;
        this.producerKey = producerKey;
        this.errorQueueName = errorQueueName;
        this.consumerProperties = consumerProperties;
    }

    public void send(MessageContainer messageContainer, ErrorQueueRepublishCorrelationKey key) throws JCSMPException {
        XMLMessage xmlMessage = xmlMessageMapper.mapError(messageContainer.getMessage(), consumerProperties);
        xmlMessage.setCorrelationKey(key);
        Queue queue = JCSMPFactory.onlyInstance().createQueue(errorQueueName);
        XMLMessageProducer producer;
        try {
            producer = producerManager.get(producerKey);
        } catch (Exception e) {
            String msg = String.format("Failed to get producer to send message %s to queue %s",
                    xmlMessage.getMessageId(), errorQueueName);
            log.warn(msg, e);
            throw new MessagingException(msg, e);
        }

        producer.send(xmlMessage, queue);
    }

    public ErrorQueueRepublishCorrelationKey createCorrelationKey(MessageContainer messageContainer,
                                                                  FlowReceiverContainer flowReceiverContainer) {
        return new ErrorQueueRepublishCorrelationKey(this, messageContainer, flowReceiverContainer);
    }

    public String getErrorQueueName() {
        return errorQueueName;
    }

    public long getMaxDeliveryAttempts() {
        return consumerProperties.getErrorQueueMaxDeliveryAttempts();
    }
}

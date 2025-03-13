package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.XMLMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ErrorQueueRepublishCorrelationKey {
    private final ErrorQueueInfrastructure errorQueueInfrastructure;
    private final BytesXMLMessage message;
    private long errorQueueDeliveryAttempt = 0;


    public void handleSuccess() {
        message.ackMessage();
    }

    public void handleError() {
        while (true) {
            if (errorQueueDeliveryAttempt >= errorQueueInfrastructure.getMaxDeliveryAttempts()) {
                fallback();
                break;
            } else {
                errorQueueDeliveryAttempt++;
                log.info(String.format("Republishing XMLMessage %s to error queue %s - attempt %s of %s",
                        message.getMessageId(), errorQueueInfrastructure.getErrorQueueName(),
                        errorQueueDeliveryAttempt, errorQueueInfrastructure.getMaxDeliveryAttempts()));
                try {
                    errorQueueInfrastructure.send(message, this);
                    break;
                } catch (Exception e) {
                    log.warn(String.format("Could not send XMLMessage %s to error queue %s",
                            message.getMessageId(),
                            errorQueueInfrastructure.getErrorQueueName()));
                }
            }
        }
    }

    private void fallback() {
        log.info(String.format(
                "Exceeded max error queue delivery attempts. XMLMessage %s will be re-queued",
                message.getMessageId()));
        requeueMessage(message);
    }

    private void requeueMessage(BytesXMLMessage bytesXMLMessage) {
        try {
            bytesXMLMessage.settle(XMLMessage.Outcome.FAILED);
        } catch (JCSMPException ex) {
            log.error("failed to requeue message", ex);
        }
    }

    public String getSourceMessageId() {
        return message.getMessageId();
    }

    public String getErrorQueueName() {
        return errorQueueInfrastructure.getErrorQueueName();
    }

    long getErrorQueueDeliveryAttempt() {
        return errorQueueDeliveryAttempt;
    }
}

package com.solace.spring.cloud.stream.binder.inbound.acknowledge;

import com.solace.spring.cloud.stream.binder.util.ErrorQueueInfrastructure;
import com.solace.spring.cloud.stream.binder.util.SolaceAcknowledgmentException;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.XMLMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.acks.AcknowledgmentCallback;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class JCSMPAcknowledgementCallback implements AcknowledgmentCallback {
    private final BytesXMLMessage message;
    private final Optional<ErrorQueueInfrastructure> errorQueueInfrastructure;
    private boolean acknowledged = false;
    private boolean autoAckEnabled = true;

    @Override
    public void acknowledge(Status status) {
        if (acknowledged) {
            log.debug("{} {} is already acknowledged", XMLMessage.class.getSimpleName(),
                    message.getMessageId());
            return;
        }
        try {
            switch (status) {
                case ACCEPT:
                    message.ackMessage();
                    break;
                case REJECT:
                    if (republishToErrorQueue()) {
                        break;
                    } else {
                        message.settle(XMLMessage.Outcome.REJECTED);
                    }
                    break;
                case REQUEUE:
                    log.debug("{} {}: Will be re-queued",
                            XMLMessage.class.getSimpleName(), message.getMessageId());
                    message.settle(XMLMessage.Outcome.FAILED);
            }
        } catch (SolaceAcknowledgmentException e) {
            throw e;
        } catch (Exception e) {
            throw new SolaceAcknowledgmentException(String.format("Failed to acknowledge XMLMessage %s",
                    message.getMessageId()), e);
        }
        acknowledged = true;
    }

    /**
     * Send the message to the error queue and acknowledge the message.
     *
     * @return {@code true} if successful, {@code false} if {@code errorQueueInfrastructure} is not
     * defined.
     */
    boolean republishToErrorQueue() {
        if (errorQueueInfrastructure.isEmpty()) {
            return false;
        }

        log.debug("{} {}: Will be republished onto error queue {}",
                XMLMessage.class.getSimpleName(), message.getMessageId(),
                errorQueueInfrastructure.get().getErrorQueueName());

        try {
            errorQueueInfrastructure.get().createCorrelationKey(message).handleError();
        } catch (Exception e) {
            throw new SolaceAcknowledgmentException(
                    String.format("Failed to send XMLMessage %s to error queue",
                            message.getMessageId()), e);
        }
        return true;
    }

    @Override
    public boolean isAcknowledged() {
        return acknowledged;
    }

    @Override
    public void noAutoAck() {
        autoAckEnabled = false;
    }

    @Override
    public boolean isAutoAck() {
        return autoAckEnabled;
    }

    BytesXMLMessage getMessage() {
        return message;
    }

    boolean isErrorQueueEnabled() {
        return errorQueueInfrastructure.isPresent();
    }
}
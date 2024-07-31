package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.XMLMessageProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.MessagingException;

import java.util.Optional;
import java.util.UUID;

@Slf4j
public class JCSMPSessionProducerManager extends SharedResourceManager<XMLMessageProducer> {
    private final JCSMPSession session;
    private final CloudStreamEventHandler publisherEventHandler = new CloudStreamEventHandler();

    public JCSMPSessionProducerManager(JCSMPSession session) {
        super("producer");
        this.session = session;
    }

    @Override
    XMLMessageProducer create() throws JCSMPException {
        return session.getMessageProducer(publisherEventHandler);
    }

    @Override
    void close() {
        sharedResource.close();
    }

    public static class CloudStreamEventHandler implements JCSMPStreamingPublishCorrelatingEventHandler {

        @Override
        public void responseReceivedEx(Object correlationKey) {
            if (correlationKey instanceof ErrorChannelSendingCorrelationKey key) {
                if (log.isTraceEnabled()) {
                    log.trace("Producer received response for message " +
                            StaticMessageHeaderAccessor.getId(key.getInputMessage()));
                }
                if (key.getConfirmCorrelation() != null) {
                    key.getConfirmCorrelation().success();
                }
            } else if (correlationKey instanceof ErrorQueueRepublishCorrelationKey key) {
                try {
                    key.handleSuccess();
                } catch (SolaceAcknowledgmentException e) { // unlikely to happen
                    log.warn(String.format("Message %s successfully sent to error queue %s, " +
                                    "but failed to acknowledge consumer message. Message is likely duplicated and was/will be"
                                    + " redelivered on the original queue.",
                            key.getSourceMessageId(), key.getErrorQueueName()), e);
                    throw e;
                }
            } else if (log.isTraceEnabled()) {
                log.trace("Producer received response for correlation key: " + correlationKey);
            }
        }

        @Override
        public void handleErrorEx(Object correlationKey, JCSMPException cause, long timestamp) {
            if (correlationKey instanceof ErrorChannelSendingCorrelationKey key) {
                UUID springMessageId = Optional.ofNullable(key.getInputMessage())
                        .map(Message::getHeaders)
                        .map(MessageHeaders::getId)
                        .orElse(null);
                String msg = String.format("Producer received error during publishing (Spring message %s) at %s",
                        springMessageId, timestamp);
                log.warn(msg, cause);
                MessagingException messagingException = key.send(msg, cause);

                if (key.getConfirmCorrelation() != null) {
                    key.getConfirmCorrelation().failed(messagingException);
                }
            } else if (correlationKey instanceof ErrorQueueRepublishCorrelationKey key) {
                try {
                    key.handleError();
                } catch (SolaceAcknowledgmentException e) { // unlikely to happen
                    log.warn(String.format("Cannot republish message %s to error queue %s. " +
                                    "It was/will be redelivered on the original queue",
                            key.getSourceMessageId(), key.getErrorQueueName()), e);
                    throw e;
                }
            } else {
                log.warn(String.format("Producer received error for correlation key: %s at %s", correlationKey,
                        timestamp), cause);
            }
        }
    }
}

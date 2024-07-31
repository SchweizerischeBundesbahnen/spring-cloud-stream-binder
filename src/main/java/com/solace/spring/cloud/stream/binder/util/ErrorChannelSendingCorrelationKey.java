package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.XMLMessage;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.AttributeAccessor;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.integration.support.ErrorMessageUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;

import java.util.List;

@Slf4j
public class ErrorChannelSendingCorrelationKey {
    @Getter
    private final Message<?> inputMessage;
    private final MessageChannel errorChannel;
    private final ErrorMessageStrategy errorMessageStrategy;
    @Getter
    @Setter
    private List<XMLMessage> rawMessages;
    @Getter
    @Setter
    private CorrelationData confirmCorrelation;


    public ErrorChannelSendingCorrelationKey(Message<?> inputMessage, MessageChannel errorChannel,
                                             ErrorMessageStrategy errorMessageStrategy) {
        this.inputMessage = inputMessage;
        this.errorChannel = errorChannel;
        this.errorMessageStrategy = errorMessageStrategy;
    }

    /**
     * Send the message to the error channel if defined.
     *
     * @param msg   the failure description
     * @param cause the failure cause
     * @return the exception wrapper containing the failed input message
     */
    public MessagingException send(String msg, Exception cause) {
        MessagingException exception = new MessagingException(inputMessage, msg, cause);
        if (errorChannel != null) {
            AttributeAccessor attributes = ErrorMessageUtils.getAttributeAccessor(inputMessage, null);
            if (rawMessages != null && !rawMessages.isEmpty()) {
                attributes.setAttribute(SolaceMessageHeaderErrorMessageStrategy.ATTR_SOLACE_RAW_MESSAGE,
                        rawMessages.get(0));
            }
            log.debug(String.format("Sending message %s to error channel %s", inputMessage.getHeaders().getId(),
                    errorChannel));
            errorChannel.send(errorMessageStrategy.buildErrorMessage(exception, attributes));
        }
        return exception;
    }
}

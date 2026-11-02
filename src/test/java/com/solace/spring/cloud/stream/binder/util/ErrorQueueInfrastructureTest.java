package com.solace.spring.cloud.stream.binder.util;

import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Pins which message the error queue names when it cannot get a producer: the error message copy has no id
 * before it is sent, so only the id of the failed source message lets an operator find the message that was lost.
 */
@ExtendWith(MockitoExtension.class)
class ErrorQueueInfrastructureTest {
    private static final String PRODUCER_KEY = "producer-key";
    private static final String ERROR_QUEUE_NAME = "error-q";
    private static final String SOURCE_MESSAGE_ID = "ID:fe80::1:55555-0/3/1";

    @Mock
    private JCSMPSessionProducerManager producerManager;

    @Test
    @SuppressWarnings("deprecation")
    void sendingWithoutAProducerNamesTheFailedSourceMessage() throws Exception {
        ErrorQueueInfrastructure errorQueueInfrastructure = new ErrorQueueInfrastructure(
                producerManager, PRODUCER_KEY, ERROR_QUEUE_NAME, new SolaceConsumerProperties());
        BytesXMLMessage failedMessage = spy(JCSMPFactory.onlyInstance().createMessage(BytesXMLMessage.class));
        doReturn(SOURCE_MESSAGE_ID).when(failedMessage).getMessageId();
        when(producerManager.get(PRODUCER_KEY)).thenThrow(new JCSMPException("no producer"));

        assertThatThrownBy(() -> errorQueueInfrastructure.send(failedMessage,
                errorQueueInfrastructure.createCorrelationKey(failedMessage)))
                .isInstanceOf(MessagingException.class)
                .hasMessage("Failed to get producer to send message " + SOURCE_MESSAGE_ID + " to queue " + ERROR_QUEUE_NAME);
    }
}

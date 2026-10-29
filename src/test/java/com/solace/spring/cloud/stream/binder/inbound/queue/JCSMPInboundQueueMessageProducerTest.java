package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.util.ErrorQueueInfrastructure;
import com.solacesystems.jcsmp.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.handler.advice.ErrorMessageSendingRecoverer;
import org.springframework.messaging.MessageHandler;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pins how the inbound queue adapter hands a message to the application and settles it with the broker.
 * <p>The JCSMP flow is not there: each message is a spy, and an ack that throws stands for a flow that closed
 * while the message was processed, as it does when a binding stops. The message was processed by then, so it
 * must neither reach the application again nor be rejected, whether the binder or the application acknowledges
 * it. On a flow that is still open the broker does not redeliver an unsettled message, so a failing ack there
 * is retried.</p>
 */
class JCSMPInboundQueueMessageProducerTest {
    private static final int MAX_ATTEMPTS = 3;

    @Mock
    private SolaceConsumerDestination consumerDestination;
    @Mock
    private JCSMPSession jcsmpSession;
    @Mock
    private ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties;
    @Mock
    private EndpointProperties endpointProperties;
    @Mock
    private ErrorQueueInfrastructure errorQueueInfrastructure;
    @Mock
    private BeanFactory beanFactory;

    private final DirectChannel outputChannel = new DirectChannel();
    private final QueueChannel errorChannel = new QueueChannel();
    private JCSMPInboundQueueMessageProducer producer;
    private JCSMPInboundQueueMessageProducer startedProducer;
    private MessageHandler messageHandler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        messageHandler = mock(MessageHandler.class);
        outputChannel.subscribe(messageHandler);

        when(consumerProperties.getExtension()).thenReturn(new SolaceConsumerProperties());

        producer = createProducer(Optional.empty(), Optional.empty());
    }

    @AfterEach
    void stopTheStartedProducer() {
        if (startedProducer != null) {
            startedProducer.stop();
        }
    }

    @Test
    void aProcessedMessage_whoseAckFails_reachesTheHandlerOnce() {
        createRetryingProducer().onReceiveConcurrent(createMessageOnAClosedFlow());

        verify(messageHandler, times(1)).handleMessage(any());
    }

    @Test
    void aProcessedMessage_whoseAckFails_staysOffTheErrorChannel() {
        createRetryingProducer().onReceiveConcurrent(createMessageOnAClosedFlow());

        assertThat(errorChannel.getQueueSize())
                .as("a message the handler processed must not be recovered when only its ack failed")
                .isZero();
    }

    @Test
    void aFailingMessage_reachesTheHandlerOncePerAttempt() {
        doThrow(new IllegalStateException("handler failed")).when(messageHandler).handleMessage(any());

        createRetryingProducer().onReceiveConcurrent(createMessage());

        verify(messageHandler, times(MAX_ATTEMPTS)).handleMessage(any());
    }

    @Test
    void aFailingMessage_afterItsLastAttempt_reachesTheErrorChannel() {
        doThrow(new IllegalStateException("handler failed")).when(messageHandler).handleMessage(any());

        createRetryingProducer().onReceiveConcurrent(createMessage());

        assertThat(errorChannel.getQueueSize()).isOne();
    }

    @Test
    void aRecoveredMessage_whoseAckFails_isLeftForTheBrokerToRedeliver() throws JCSMPException {
        doThrow(new IllegalStateException("handler failed")).when(messageHandler).handleMessage(any());
        BytesMessage message = createMessageOnAClosedFlow();

        createRetryingProducer().onReceiveConcurrent(message);

        verify(errorQueueInfrastructure, never()).createCorrelationKey(any());
        verify(message, never()).settle(any());
    }

    @Test
    void aManuallyAcknowledgedMessage_whoseAckFailsOnAClosedFlow_reachesTheHandlerOnce() {
        letTheHandlerAcknowledgeManually();

        createRetryingProducer().onReceiveConcurrent(createMessageOnAClosedFlow());

        verify(messageHandler, times(1)).handleMessage(any());
    }

    @Test
    void aManuallyAcknowledgedMessage_whoseAckFailsOnAClosedFlow_staysOffTheErrorChannel() {
        letTheHandlerAcknowledgeManually();

        createRetryingProducer().onReceiveConcurrent(createMessageOnAClosedFlow());

        assertThat(errorChannel.getQueueSize())
                .as("a message whose own ack failed on a closed flow must be left to the broker, not recovered")
                .isZero();
    }

    @Test
    void aManuallyAcknowledgedMessage_whoseAckFailsOnAClosedFlowWithoutRetries_isLeftForTheBrokerToRedeliver()
            throws JCSMPException {
        letTheHandlerAcknowledgeManually();
        BytesMessage message = createMessageOnAClosedFlow();

        producer.onReceiveConcurrent(message);

        verify(errorQueueInfrastructure, never()).createCorrelationKey(any());
        verify(message, never()).settle(any());
    }

    @Test
    void aManuallyAcknowledgedMessage_whoseAckFailsOnAnOpenFlow_reachesTheHandlerOncePerAttempt() throws JCSMPException {
        letTheHandlerAcknowledgeManually();

        startRetryingProducerOnAnOpenFlow().onReceiveConcurrent(createMessageOnAClosedFlow());

        verify(messageHandler, times(MAX_ATTEMPTS)).handleMessage(any());
    }

    @Test
    void aProcessedMessage_whoseAckFailsWithoutRetries_isLeftForTheBrokerToRedeliver() throws JCSMPException {
        BytesMessage message = createMessageOnAClosedFlow();

        producer.onReceiveConcurrent(message);

        verify(errorQueueInfrastructure, never()).createCorrelationKey(any());
        verify(message, never()).settle(any());
    }

    @Test
    void testLargeMessageSupport_allMessagesShouldBeAcknowledgedAtOnce_whenLastChunkWasReceivedAndMessageWasProcessed() throws SDTException {
        long chunkId = 12345L;
        int chunkCount = 3;
        BytesMessage chunk1 = createChunk(chunkId, 0, chunkCount);
        BytesMessage chunk2 = createChunk(chunkId, 1, chunkCount);
        BytesMessage chunk3 = createChunk(chunkId, 2, chunkCount);

        producer.onReceiveConcurrent(chunk1);
        verify(chunk1, times(0)).ackMessage();
        verify(messageHandler, times(0)).handleMessage(any());

        producer.onReceiveConcurrent(chunk2);
        verify(chunk1, times(0)).ackMessage();
        verify(chunk2, times(0)).ackMessage();
        verify(messageHandler, times(0)).handleMessage(any());

        producer.onReceiveConcurrent(chunk3);

        verify(messageHandler, times(1)).handleMessage(any());
        verify(chunk1, times(1)).ackMessage();
        verify(chunk2, times(1)).ackMessage();
        verify(chunk3, times(1)).ackMessage();
    }

    @Test
    void testLargeMessageSupport_allMessagesShouldBeAcknowledgedAtOnce_whenTwoMessageAreProcessedParallelLastChunkWasReceivedAndMessageWasProcessed() throws SDTException {
        long chunkId1 = 12345L;
        long chunkId2 = 67890L;
        int chunkCount = 3;

        BytesMessage chunk1_1 = createChunk(chunkId1, 0, chunkCount);
        BytesMessage chunk1_2 = createChunk(chunkId1, 1, chunkCount);
        BytesMessage chunk1_3 = createChunk(chunkId1, 2, chunkCount);

        BytesMessage chunk2_1 = createChunk(chunkId2, 0, chunkCount);
        BytesMessage chunk2_2 = createChunk(chunkId2, 1, chunkCount);
        BytesMessage chunk2_3 = createChunk(chunkId2, 2, chunkCount);

        producer.onReceiveConcurrent(chunk1_1);
        verify(chunk1_1, times(0)).ackMessage();
        verify(messageHandler, times(0)).handleMessage(any());

        producer.onReceiveConcurrent(chunk2_1);
        verify(chunk2_1, times(0)).ackMessage();
        verify(messageHandler, times(0)).handleMessage(any());

        producer.onReceiveConcurrent(chunk1_2);
        verify(chunk1_1, times(0)).ackMessage();
        verify(chunk1_2, times(0)).ackMessage();
        verify(messageHandler, times(0)).handleMessage(any());

        producer.onReceiveConcurrent(chunk2_2);
        verify(chunk2_1, times(0)).ackMessage();
        verify(chunk2_2, times(0)).ackMessage();
        verify(messageHandler, times(0)).handleMessage(any());

        producer.onReceiveConcurrent(chunk1_3);

        verify(messageHandler, times(1)).handleMessage(any());
        verify(chunk1_1, times(1)).ackMessage();
        verify(chunk1_2, times(1)).ackMessage();
        verify(chunk1_3, times(1)).ackMessage();
        verify(chunk2_1, times(0)).ackMessage();
        verify(chunk2_2, times(0)).ackMessage();
        verify(chunk2_3, times(0)).ackMessage();

        producer.onReceiveConcurrent(chunk2_3);

        verify(messageHandler, times(2)).handleMessage(any());
        verify(chunk2_1, times(1)).ackMessage();
        verify(chunk2_2, times(1)).ackMessage();
        verify(chunk2_3, times(1)).ackMessage();
    }

    private BytesMessage createChunk(long chunkId, int index, int count) throws SDTException {
        BytesMessage message = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        message.setData(new byte[]{1, 2, 3});
        SDTMap properties = JCSMPFactory.onlyInstance().createMap();
        properties.putLong(SolaceBinderHeaders.CHUNK_ID, chunkId);
        properties.putInteger(SolaceBinderHeaders.CHUNK_INDEX, index);
        properties.putInteger(SolaceBinderHeaders.CHUNK_COUNT, count);
        message.setProperties(properties);
        return spy(message);
    }

    private BytesMessage createMessage() {
        BytesMessage message = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        message.setData(new byte[]{1, 2, 3});
        return spy(message);
    }

    private BytesMessage createMessageOnAClosedFlow() {
        BytesMessage message = createMessage();
        doThrow(new IllegalStateException("Attempted an operation on a closed message consumer"))
                .when(message).ackMessage();
        return message;
    }

    private void letTheHandlerAcknowledgeManually() {
        doAnswer(invocation -> {
            AcknowledgmentCallback acknowledgmentCallback =
                    StaticMessageHeaderAccessor.getAcknowledgmentCallback(invocation.getArgument(0));
            acknowledgmentCallback.noAutoAck();
            acknowledgmentCallback.acknowledge(AcknowledgmentCallback.Status.ACCEPT);
            return null;
        }).when(messageHandler).handleMessage(any());
    }

    private JCSMPInboundQueueMessageProducer startRetryingProducerOnAnOpenFlow() throws JCSMPException {
        when(consumerProperties.getConcurrency()).thenReturn(1);
        when(consumerDestination.getName()).thenReturn("queue");
        when(consumerDestination.getBindingDestinationName()).thenReturn("destination");
        when(jcsmpSession.createFlow(any(XMLMessageListener.class), any(ConsumerFlowProperties.class),
                any(EndpointProperties.class), any(FlowEventHandler.class)))
                .thenReturn(mock(FlowReceiver.class));
        startedProducer = createRetryingProducer();
        startedProducer.start();
        return startedProducer;
    }

    private JCSMPInboundQueueMessageProducer createRetryingProducer() {
        RetryTemplate retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(MAX_ATTEMPTS - 1)
                .delay(Duration.ZERO)
                .build());
        return createProducer(Optional.of(retryTemplate), Optional.of(new ErrorMessageSendingRecoverer(errorChannel)));
    }

    private JCSMPInboundQueueMessageProducer createProducer(Optional<RetryTemplate> retryTemplate,
                                                           Optional<ErrorMessageSendingRecoverer> recoveryCallback) {
        JCSMPInboundQueueMessageProducer queueMessageProducer = new JCSMPInboundQueueMessageProducer(
                consumerDestination,
                jcsmpSession,
                consumerProperties,
                endpointProperties,
                e -> {
                },
                beanFactory,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                retryTemplate,
                recoveryCallback,
                Optional.of(errorQueueInfrastructure)
        );
        queueMessageProducer.setOutputChannel(outputChannel);
        return queueMessageProducer;
    }
}

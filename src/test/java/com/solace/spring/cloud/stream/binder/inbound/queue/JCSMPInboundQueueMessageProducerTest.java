package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.util.ErrorQueueInfrastructure;
import com.solacesystems.jcsmp.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.MessageHandler;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JCSMPInboundQueueMessageProducerTest {

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

    private JCSMPInboundQueueMessageProducer producer;
    private MessageHandler messageHandler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        DirectChannel outputChannel = new DirectChannel();
        messageHandler = mock(MessageHandler.class);
        outputChannel.subscribe(messageHandler);

        when(consumerProperties.getExtension()).thenReturn(new SolaceConsumerProperties());

        producer = new JCSMPInboundQueueMessageProducer(
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
                Optional.empty(),
                Optional.empty(),
                Optional.of(errorQueueInfrastructure)
        );
        producer.setOutputChannel(outputChannel);
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

}

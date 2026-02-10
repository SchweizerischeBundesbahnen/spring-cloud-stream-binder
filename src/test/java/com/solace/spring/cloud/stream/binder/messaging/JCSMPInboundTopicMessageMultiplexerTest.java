package com.solace.spring.cloud.stream.binder.messaging;

import com.solace.spring.cloud.stream.binder.inbound.topic.JCSMPInboundTopicMessageMultiplexer;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solacesystems.jcsmp.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;

@Slf4j
public class JCSMPInboundTopicMessageMultiplexerTest {
    @Test
    void testCallFromMultipleThreads_shouldNotThrowConcurrentModification() throws JCSMPException, ExecutionException, InterruptedException {
        var session = Mockito.mock(JCSMPSession.class);

        AtomicReference<XMLMessageListener> capturedListener = new AtomicReference<>();

        Mockito.when(session.getMessageConsumer(Mockito.any(XMLMessageListener.class))).thenAnswer((d) -> {
            XMLMessageListener listener = (XMLMessageListener) d.getArguments()[0];
            capturedListener.set(listener);
            return mock(XMLMessageConsumer.class);
        });

        JCSMPInboundTopicMessageMultiplexer multiplexer = new JCSMPInboundTopicMessageMultiplexer(session, Optional.empty(), Optional.empty());
        SolaceConsumerDestination consumerDestination = Mockito.mock(SolaceConsumerDestination.class);
        Mockito.when(consumerDestination.getName()).thenReturn("consumer");
        ExtendedConsumerProperties<SolaceConsumerProperties> properties = Mockito.mock(ExtendedConsumerProperties.class);

        multiplexer.createTopicMessageProducer(consumerDestination, "myGroup", properties);

        ExecutorService executorService = Executors.newFixedThreadPool(10);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            futures.add(executorService.submit(() -> {
                // Simulate a message being received
                BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
                Mockito.when(mockMessage.getMessageId()).thenReturn("TestMessageId");
                Mockito.when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));
                byte[] data = UUID.randomUUID().toString().getBytes();
                Mockito.when(mockMessage.getBytes()).thenReturn(data);

                capturedListener.get().onReceive(mockMessage);
                return "ok";
            }));
        }
        for (Future<String> future : futures) {
            future.get(); // to ensure all features finished
        }
    }
}

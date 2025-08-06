package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.Topic;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class FlowXMLMessageListenerTest {

    @Test
    void testStartReceiverThreads_StartsSpecifiedNumberOfThreads() throws InterruptedException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        Consumer<BytesXMLMessage> messageConsumer = Mockito.mock(Consumer.class);

        int threadCount = 3;
        String threadNamePrefix = "testStartReceiverThreads_StartsSpecifiedNumberOfThreads";
        long processingTime = 1000;

        // Start the receiver threads
        listener.startReceiverThreads(threadCount, threadNamePrefix, messageConsumer, processingTime);

        // Wait briefly to let threads initialize
        Thread.sleep(1000);

        AtomicInteger runningThreads = new AtomicInteger(0);
        Thread.getAllStackTraces().keySet().forEach(thread -> {
            if (thread.getName().startsWith(threadNamePrefix)) {
                runningThreads.incrementAndGet();
            }
        });

        // Verify that the expected number of threads were created
        assert runningThreads.get() == threadCount + 1; // Include watchdog thread
    }

    @Test
    void testStartReceiverThreads_CallsMessageConsumerWhenMessageIsPolled() throws InterruptedException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        Consumer<BytesXMLMessage> messageConsumer = Mockito.mock(Consumer.class);

        int threadCount = 1;
        String threadNamePrefix = "TestThread";
        long processingTime = 1000;

        // Start the receiver threads
        listener.startReceiverThreads(threadCount, threadNamePrefix, messageConsumer, processingTime);

        // Simulate a message being received
        BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
        Mockito.when(mockMessage.getMessageId()).thenReturn("TestMessageId");
        Mockito.when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));

        listener.onReceive(mockMessage);

        // Wait briefly to allow message to be processed
        Thread.sleep(2000);

        // Verify that the consumer was called with the message
        verify(messageConsumer, timeout(2000)).accept(mockMessage);
    }

    @Test
    void testStartReceiverThreads_useAllThreads() throws InterruptedException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        Consumer<BytesXMLMessage> messageConsumer = Mockito.mock(Consumer.class);
        List<BytesXMLMessage> results = new ArrayList<>();
        doAnswer(invocation -> {
            Thread.sleep(1000);
            synchronized (results) {
                results.add(invocation.getArgument(0));
            }
            return null;
        })
                .when(messageConsumer)
                .accept(any(BytesXMLMessage.class));

        int threadCount = 10;
        String threadNamePrefix = "testStartReceiverThreads_useAllThreads";
        long processingTime = 1000;

        // Start the receiver threads
        listener.startReceiverThreads(threadCount, threadNamePrefix, messageConsumer, processingTime);

        // Wait briefly to let threads initialize
        Thread.sleep(1000);

        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
            Mockito.when(mockMessage.getMessageId()).thenReturn("TestMessageId");
            Mockito.when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));

            listener.onReceive(mockMessage);
        }
        long afterSend = System.nanoTime();
        long sendTimeMs = (afterSend - start) / 1000000L;
        assertThat(sendTimeMs)
                .as("sendTimeMs is not within the expected range")
                .isBetween(0L, 500L);

        // Wait until 20 elements are in the results list
        assertThat(results).satisfies(r ->
                await().atMost(2500, TimeUnit.MILLISECONDS).until(() -> r.size() == 20)
        );
    }

    @Test
    void testStartReceiverThreads_WatchdogLogsWarningForLongProcessing() throws NoSuchFieldException, IllegalAccessException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        // Use reflection to access the private 'activeMessages' field
        Field activeMessagesField = FlowXMLMessageListener.class.getDeclaredField("activeMessages");
        activeMessagesField.setAccessible(true);
        Set<FlowXMLMessageListener.MessageInProgress> activeMessages = (Set<FlowXMLMessageListener.MessageInProgress>) activeMessagesField.get(listener);
        Consumer<BytesXMLMessage> messageConsumer = message -> {
            try {
                // Simulate a long message processing time
                Thread.sleep(6000);
            } catch (InterruptedException ignored) {
            }
        };

        int threadCount = 1;
        String threadNamePrefix = "WatchdogTestThread";
        long processingTime = 500;

        // Start the receiver threads
        listener.startReceiverThreads(threadCount, threadNamePrefix, messageConsumer, processingTime);

        // Simulate a message being received
        BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
        Mockito.when(mockMessage.getMessageId()).thenReturn("TestMessageId");
        Mockito.when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));
        listener.onReceive(mockMessage);


        // Wait for the message to be marked as warned in the activeMessages map
        await().atMost(700, TimeUnit.MILLISECONDS)
                .until(() -> activeMessages.iterator().next().isWarned());
        await().atMost(5500, TimeUnit.MILLISECONDS)
                .until(() -> activeMessages.iterator().next().isErrored());
    }
}
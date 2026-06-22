package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.SDTException;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class FlowXMLMessageListenerTest {

    @Test
    void testStartReceiverThreads_StartsSpecifiedNumberOfThreads() throws InterruptedException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        try {
            Consumer<BytesXMLMessage> messageConsumer = Mockito.mock(Consumer.class);

            int threadCount = 3;
            String threadNamePrefix = "testStartReceiverThreads_StartsSpecifiedNumberOfThreads";

            // Start the receiver threads
            listener.startReceiverThreads(threadCount, threadNamePrefix, messageConsumer, 2000);

            // Wait briefly to let threads initialize
            Thread.sleep(1000);

            AtomicInteger runningThreads = new AtomicInteger(0);
            Thread.getAllStackTraces().keySet().forEach(thread -> {
                if (thread.getName().startsWith(threadNamePrefix)) {
                    runningThreads.incrementAndGet();
                }
            });

            // Verify that the expected number of threads were created (workers + watchdog)
            assertThat(runningThreads.get()).isEqualTo(threadCount + 1);
        } finally {
            listener.stopReceiverThreads();
        }
    }

    @Test
    void testStartReceiverThreads_CallsMessageConsumerWhenMessageIsPolled() throws InterruptedException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        try {
            Consumer<BytesXMLMessage> messageConsumer = Mockito.mock(Consumer.class);

            int threadCount = 1;
            String threadNamePrefix = "TestThread";

            // Start the receiver threads
            listener.startReceiverThreads(threadCount, threadNamePrefix, messageConsumer, 2000);

            // Simulate a message being received
            BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
            Mockito.when(mockMessage.getMessageId()).thenReturn("TestMessageId");
            Mockito.when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));

            listener.onReceive(mockMessage);

            // Wait briefly to allow message to be processed
            Thread.sleep(2000);

            // Verify that the consumer was called with the message
            verify(messageConsumer, timeout(2000)).accept(mockMessage);
        } finally {
            listener.stopReceiverThreads();
        }
    }

    @Test
    void testStartReceiverThreads_useAllThreads() throws InterruptedException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        try {
            Consumer<BytesXMLMessage> messageConsumer = Mockito.mock(Consumer.class);
            List<BytesXMLMessage> results = new CopyOnWriteArrayList<>();
            doAnswer(invocation -> {
                Thread.sleep(1000);
                results.add(invocation.getArgument(0));
                return null;
            })
                    .when(messageConsumer)
                    .accept(any(BytesXMLMessage.class));

            int threadCount = 10;
            String threadNamePrefix = "testStartReceiverThreads_useAllThreads";

            // Start the receiver threads
            listener.startReceiverThreads(threadCount, threadNamePrefix, messageConsumer, 2000);

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
        } finally {
            listener.stopReceiverThreads();
        }
    }

    @Test
    void testStartReceiverThreads_WatchdogLogsDeadlockWarning() throws InterruptedException, NoSuchFieldException, IllegalAccessException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();

        try {
            Consumer<BytesXMLMessage> messageConsumer = message -> {
                try {
                    // Simulate a very long message processing time to trigger deadlock detection
                    Thread.sleep(3500);
                } catch (InterruptedException ignored) {
                }
            };

            int threadCount = 1;
            String threadNamePrefix = "DeadlockTestThread";

            // Use very short timeout (1 second) for testing deadlock detection
            listener.startReceiverThreads(threadCount, threadNamePrefix, messageConsumer, 1000);

            // Simulate a message being received
            Topic topic = JCSMPFactory.onlyInstance().createTopic("test/topic");
            BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
            Mockito.when(mockMessage.getMessageId()).thenReturn("TestMessageId-Deadlock");
            Mockito.when(mockMessage.getDestination()).thenReturn(topic);
            listener.onReceive(mockMessage);

            // Wait for deadlock detection to occur (watchdog runs every 1000ms)
            Field field = FlowXMLMessageListener.class.getDeclaredField("activeMessages");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<FlowXMLMessageListener.MessageInProgress> activeMessages = (Set<FlowXMLMessageListener.MessageInProgress>) field.get(listener);

            await().atMost(4500, TimeUnit.MILLISECONDS).until(() ->
                    activeMessages.stream().anyMatch(FlowXMLMessageListener.MessageInProgress::isWarned)
            );

            boolean warned = activeMessages.stream().anyMatch(FlowXMLMessageListener.MessageInProgress::isWarned);
            assertThat(warned).as("Expected a message to be warned due to deadlock").isTrue();

            // Wait for processing to complete to avoid interfering with next tests
            await().atMost(4000, TimeUnit.MILLISECONDS).until(activeMessages::isEmpty);

        } finally {
            listener.stopReceiverThreads();
        }
    }

    @Test
    void testMessageInProgress_EqualsAndHashCode_UsesOnlyImmutableFields() {
        BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
        when(mockMessage.getMessageId()).thenReturn("TestMessageId");
        when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));

        long receivedNanos = System.nanoTime();

        FlowXMLMessageListener.MessageInProgress mip1 = new FlowXMLMessageListener.MessageInProgress(receivedNanos, mockMessage);
        FlowXMLMessageListener.MessageInProgress mip2 = new FlowXMLMessageListener.MessageInProgress(receivedNanos, mockMessage);

        // Initially equal
        assertThat(mip1).isEqualTo(mip2);
        assertThat(mip1.hashCode()).isEqualTo(mip2.hashCode());

        // Modify mutable fields - should still be equal
        mip1.setStartNanos(1000L);
        mip1.setThreadName("thread-1");
        mip1.setWarned(true);


        mip2.setStartNanos(2000L);
        mip2.setThreadName("thread-2");
        mip2.setWarned(false);


        // Still equal because only receivedNanos and bytesXMLMessage are used
        assertThat(mip1).isEqualTo(mip2);
        assertThat(mip1.hashCode()).isEqualTo(mip2.hashCode());
    }

    @Test
    void testMessageInProgress_HashCodeIsCached() {
        BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
        when(mockMessage.getMessageId()).thenReturn("TestMessageId");
        when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));

        FlowXMLMessageListener.MessageInProgress mip = new FlowXMLMessageListener.MessageInProgress(System.nanoTime(), mockMessage);

        int hash1 = mip.hashCode();
        int hash2 = mip.hashCode();
        int hash3 = mip.hashCode();

        // All calls should return the same cached value
        assertThat(hash1).isEqualTo(hash2).isEqualTo(hash3);
    }

    @Test
    void testMessageInProgress_CanBeAddedAndRemovedFromHashSetAfterMutation() {
        BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
        when(mockMessage.getMessageId()).thenReturn("TestMessageId");
        when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));

        FlowXMLMessageListener.MessageInProgress mip = new FlowXMLMessageListener.MessageInProgress(System.nanoTime(), mockMessage);

        Set<FlowXMLMessageListener.MessageInProgress> set = new HashSet<>();
        set.add(mip);

        assertThat(set).contains(mip);

        // Modify mutable fields
        mip.setStartNanos(1000L);
        mip.setThreadName("thread-1");
        mip.setWarned(true);


        // Should still be in the set and removable
        assertThat(set).contains(mip);
        boolean removed = set.remove(mip);
        assertThat(removed).isTrue();
        assertThat(set).isEmpty();
    }

    @Test
    void testMessageInProgress_DifferentMessagesAreNotEqual() {
        BytesXMLMessage mockMessage1 = mock(BytesXMLMessage.class);
        when(mockMessage1.getMessageId()).thenReturn("TestMessageId1");
        when(mockMessage1.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));

        BytesXMLMessage mockMessage2 = mock(BytesXMLMessage.class);
        when(mockMessage2.getMessageId()).thenReturn("TestMessageId2");
        when(mockMessage2.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));

        long receivedNanos = System.nanoTime();

        FlowXMLMessageListener.MessageInProgress mip1 = new FlowXMLMessageListener.MessageInProgress(receivedNanos, mockMessage1);
        FlowXMLMessageListener.MessageInProgress mip2 = new FlowXMLMessageListener.MessageInProgress(receivedNanos, mockMessage2);

        assertThat(mip1).isNotEqualTo(mip2);
    }

    @Test
    void testMessageInProgress_DifferentReceivedNanosAreNotEqual() {
        BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
        when(mockMessage.getMessageId()).thenReturn("TestMessageId");
        when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));

        FlowXMLMessageListener.MessageInProgress mip1 = new FlowXMLMessageListener.MessageInProgress(1000L, mockMessage);
        FlowXMLMessageListener.MessageInProgress mip2 = new FlowXMLMessageListener.MessageInProgress(2000L, mockMessage);

        assertThat(mip1).isNotEqualTo(mip2);
    }

    @Test
    void testMessageInProgress_ToStringIncludesAllFields() {
        BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
        when(mockMessage.getMessageId()).thenReturn("TestMessageId");
        when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));

        FlowXMLMessageListener.MessageInProgress mip = new FlowXMLMessageListener.MessageInProgress(12345L, mockMessage);
        mip.setStartNanos(67890L);
        mip.setThreadName("test-thread");
        mip.setWarned(true);

        String toString = mip.toString();

        assertThat(toString).contains("12345"); // receivedNanos
        assertThat(toString).contains("67890"); // startNanos
        assertThat(toString).contains("test-thread"); // threadName
        assertThat(toString).contains("warned"); // warned field
    }

    @Test
    void testStartReceiverThreads_100ConsumersConcurrentProcessing() throws InterruptedException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        try {
            int threadCount = 100;
            int messageCount = 1000;
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch allMessagesProcessed = new CountDownLatch(messageCount);
            Set<String> processedThreadNames = java.util.concurrent.ConcurrentHashMap.newKeySet();

            Consumer<BytesXMLMessage> messageConsumer = message -> {
                processedThreadNames.add(Thread.currentThread().getName());
                processedCount.incrementAndGet();
                // Simulate some processing time
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
                allMessagesProcessed.countDown();
            };

            String threadNamePrefix = "test100Consumers";

            // Start 100 receiver threads
            listener.startReceiverThreads(threadCount, threadNamePrefix, messageConsumer, 60000);

            // Wait briefly to let threads initialize
            Thread.sleep(500);

            // Send 1000 messages
            long start = System.currentTimeMillis();
            for (int i = 0; i < messageCount; i++) {
                BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
                when(mockMessage.getMessageId()).thenReturn("TestMessageId-" + i);
                when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic/" + i));
                listener.onReceive(mockMessage);
            }
            long sendTime = System.currentTimeMillis() - start;

            // Sending should be fast (non-blocking)
            assertThat(sendTime).as("Sending messages should be fast").isLessThan(5000);

            // Wait for all messages to be processed
            boolean completed = allMessagesProcessed.await(30, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - start;

            assertThat(completed).as("All messages should be processed within timeout").isTrue();
            assertThat(processedCount.get()).isEqualTo(messageCount);

            // Verify multiple threads were used (at least 50% of threads should have processed something)
            assertThat(processedThreadNames.size())
                    .as("Multiple threads should have been used for processing")
                    .isGreaterThanOrEqualTo(threadCount / 2);

            // With 100 threads and 10ms per message, 1000 messages should complete much faster than sequential
            // Sequential would be 1000 * 10ms = 10 seconds
            // Parallel should be around 1000 / 100 * 10ms = 100ms + overhead
            assertThat(totalTime)
                    .as("Parallel processing should be significantly faster than sequential")
                    .isLessThan(5000);

        } finally {
            listener.stopReceiverThreads();
        }
    }

    @Test
    void testStartReceiverThreads_100ConsumersActiveMessagesSetIntegrity() throws Exception {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        try {
            int threadCount = 100;
            int messageCount = 500;
            CountDownLatch allMessagesProcessed = new CountDownLatch(messageCount);
            AtomicInteger processedCount = new AtomicInteger(0);
            Set<String> processedThreadNames = java.util.concurrent.ConcurrentHashMap.newKeySet();

            // Access activeMessages via reflection to verify integrity
            Field activeMessagesField = FlowXMLMessageListener.class.getDeclaredField("activeMessages");
            activeMessagesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<FlowXMLMessageListener.MessageInProgress> activeMessages =
                    (Set<FlowXMLMessageListener.MessageInProgress>) activeMessagesField.get(listener);

            Consumer<BytesXMLMessage> messageConsumer = message -> {
                processedThreadNames.add(Thread.currentThread().getName());
                // Simulate some processing with varying times to create contention
                try {
                    Thread.sleep((long) (Math.random() * 20));
                } catch (InterruptedException ignored) {
                }
                processedCount.incrementAndGet();
                allMessagesProcessed.countDown();
            };

            String threadNamePrefix = "test100ConsumersIntegrity";

            // Start 100 receiver threads
            listener.startReceiverThreads(threadCount, threadNamePrefix, messageConsumer, 60000);

            // Wait briefly to let threads initialize
            Thread.sleep(500);

            // Send messages
            for (int i = 0; i < messageCount; i++) {
                BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
                when(mockMessage.getMessageId()).thenReturn("IntegrityTest-" + i);
                when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/integrity/" + i));
                listener.onReceive(mockMessage);
            }

            // Wait for all messages to be processed
            boolean completed = allMessagesProcessed.await(30, TimeUnit.SECONDS);

            assertThat(completed).as("All messages should be processed").isTrue();

            // Verify all messages were processed
            assertThat(processedCount.get())
                    .as("All messages should have been processed")
                    .isEqualTo(messageCount);

            // Verify parallelization occurred (at least 50% of threads should have processed something)
            assertThat(processedThreadNames.size())
                    .as("Multiple threads should have been used for processing")
                    .isGreaterThanOrEqualTo(threadCount / 2);

            // After all messages are processed, activeMessages should be empty
            await().atMost(5, TimeUnit.SECONDS).until(activeMessages::isEmpty);

            assertThat(activeMessages)
                    .as("Active messages set should be empty after all processing completes")
                    .isEmpty();

        } finally {
            listener.stopReceiverThreads();
        }
    }

    /**
     * Graceful-shutdown regression test.
     *
     * <p>Models the {@code JCSMPInboundQueueMessageProducer.doStop()} ordering at the listener level.
     * The {@link Consumer} passed to {@code startReceiverThreads} stands in for
     * {@code onReceiveConcurrent}, whose {@code activeMessages} window spans the message ACK. A shared
     * {@link AtomicBoolean} simulates {@code FlowReceiver.close()}: any "ACK" that runs after it is set
     * is the bug we are guarding against (an ACK on a closed flow throws {@code IllegalStateException}).
     *
     * <p>With the fix, {@code drain()} blocks until every queued and in-flight message has been fully
     * processed, so closing the flow afterwards is safe. If {@code drain()} were a no-op, some messages
     * would be "ACKed" after close and the assertions below would fail.
     */
    @Test
    void testDrain_allInFlightMessagesSettledBeforeFlowClose() {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();

        AtomicBoolean flowClosed = new AtomicBoolean(false);
        AtomicInteger acked = new AtomicInteger();
        List<Throwable> ackAfterClose = Collections.synchronizedList(new ArrayList<>());

        Consumer<BytesXMLMessage> consumer = msg -> {
            sleepQuietly(200); // simulate message processing
            if (flowClosed.get()) {
                // simulates settling/ACK on an already-closed flow -> IllegalStateException in real life
                ackAfterClose.add(new IllegalStateException("ACK on closed flow"));
            } else {
                acked.incrementAndGet();
            }
        };

        int n = 12;
        listener.startReceiverThreads(3, "drainTest", consumer, 2000);
        for (int i = 0; i < n; i++) {
            listener.onReceive(mockMessage());
        }

        // Mirror doStop(): FlowReceiver.stop() (no new deliveries) -> drain() -> close() -> stop threads
        listener.drain(10_000);
        flowClosed.set(true); // simulate FlowReceiver.close()
        listener.stopReceiverThreads();

        assertThat(listener.isIdle())
                .as("listener must be idle once drain() returns")
                .isTrue();
        assertThat(ackAfterClose)
                .as("no message may be ACKed after the flow is closed")
                .isEmpty();
        assertThat(acked.get())
                .as("every in-flight message must be settled before the flow is closed")
                .isEqualTo(n);
    }

    // ---------------------------------------------------------------------------------------------
    // Partition-aware dispatch (STTRS-3135 variant 2)
    // ---------------------------------------------------------------------------------------------

    @Test
    void testPartitionAware_sameKeyAlwaysHandledBySameThread_andOrderPreserved() throws InterruptedException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        try {
            int threadCount = 4;
            int keyCount = 8;
            int perKey = 25;
            CountDownLatch latch = new CountDownLatch(keyCount * perKey);
            Map<String, Set<String>> threadsByKey = new ConcurrentHashMap<>();
            Map<String, List<Integer>> seqByKey = new ConcurrentHashMap<>();

            Consumer<BytesXMLMessage> consumer = msg -> {
                String key = readPartitionKey(msg);
                int seq = Integer.parseInt(msg.getMessageId().substring(msg.getMessageId().indexOf('#') + 1));
                threadsByKey.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                        .add(Thread.currentThread().getName());
                seqByKey.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(seq);
                try {
                    Thread.sleep(1); // create contention so a non-affine dispatch would interleave threads
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                latch.countDown();
            };

            listener.startReceiverThreads(threadCount, "partitionAware", consumer, 60000, true);
            Thread.sleep(200);

            // Interleave keys but keep each key's sequence numbers strictly increasing in send order.
            for (int seq = 0; seq < perKey; seq++) {
                for (int k = 0; k < keyCount; k++) {
                    listener.onReceive(mockPartitionedMessage("key-" + k, "key-" + k + "#" + seq));
                }
            }

            assertThat(latch.await(30, TimeUnit.SECONDS)).as("all messages processed").isTrue();

            assertThat(threadsByKey).hasSize(keyCount);
            threadsByKey.forEach((key, threads) -> assertThat(threads)
                    .as("partition %s must be processed by exactly one worker thread", key)
                    .hasSize(1));

            seqByKey.forEach((key, seqs) -> assertThat(seqs)
                    .as("messages of partition %s must be processed in receive order", key)
                    .containsExactlyElementsOf(IntStream.range(0, perKey).boxed().toList()));

            Set<String> allThreads = threadsByKey.values().stream()
                    .flatMap(Set::stream).collect(Collectors.toSet());
            assertThat(allThreads)
                    .as("multiple worker threads should be used across partitions")
                    .hasSizeGreaterThanOrEqualTo(2);
        } finally {
            listener.stopReceiverThreads();
        }
    }

    @Test
    void testPartitionAware_nullPartitionKey_distributedRoundRobinWithoutError() throws InterruptedException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        try {
            int threadCount = 4;
            int messageCount = 200;
            CountDownLatch latch = new CountDownLatch(messageCount);
            Set<String> threads = ConcurrentHashMap.newKeySet();

            Consumer<BytesXMLMessage> consumer = msg -> {
                threads.add(Thread.currentThread().getName());
                latch.countDown();
            };

            listener.startReceiverThreads(threadCount, "pa-nullkey", consumer, 60000, true);
            Thread.sleep(200);

            for (int i = 0; i < messageCount; i++) {
                listener.onReceive(mockPartitionedMessage(null, "m#" + i));
            }

            assertThat(latch.await(30, TimeUnit.SECONDS)).as("all messages processed").isTrue();
            assertThat(threads)
                    .as("messages without a partition key should be spread round-robin across threads")
                    .hasSizeGreaterThanOrEqualTo(2);
        } finally {
            listener.stopReceiverThreads();
        }
    }

    @Test
    void testNonPartitionAware_singleSharedQueue_doesNotPinPartitionToOneThread() throws InterruptedException {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        try {
            int threadCount = 4;
            int messageCount = 200;
            CountDownLatch latch = new CountDownLatch(messageCount);
            Set<String> threads = ConcurrentHashMap.newKeySet();

            Consumer<BytesXMLMessage> consumer = msg -> {
                threads.add(Thread.currentThread().getName());
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                latch.countDown();
            };

            // Default mode (partitionAware = false): all workers share a single queue.
            listener.startReceiverThreads(threadCount, "shared", consumer, 60000);
            Thread.sleep(200);

            for (int i = 0; i < messageCount; i++) {
                listener.onReceive(mockPartitionedMessage("same-key", "same#" + i));
            }

            assertThat(latch.await(30, TimeUnit.SECONDS)).as("all messages processed").isTrue();
            assertThat(threads)
                    .as("shared-queue mode must NOT pin a single partition key to one thread")
                    .hasSizeGreaterThanOrEqualTo(2);
        } finally {
            listener.stopReceiverThreads();
        }
    }

    @Test
    void testPartitionAware_singleThread_usesSingleQueue() throws Exception {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        try {
            @SuppressWarnings("unchecked")
            Consumer<BytesXMLMessage> consumer = mock(Consumer.class);
            listener.startReceiverThreads(1, "pa-single", consumer, 60000, true);
            assertThat(workerQueueCount(listener))
                    .as("with concurrency 1, partition-aware mode must not create per-thread queues")
                    .isEqualTo(1);
        } finally {
            listener.stopReceiverThreads();
        }
    }

    @Test
    void testPartitionAware_multiThread_createsOneQueuePerThread() throws Exception {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        try {
            @SuppressWarnings("unchecked")
            Consumer<BytesXMLMessage> consumer = mock(Consumer.class);
            listener.startReceiverThreads(5, "pa-multi", consumer, 60000, true);
            assertThat(workerQueueCount(listener))
                    .as("partition-aware mode must create one queue per worker thread")
                    .isEqualTo(5);
        } finally {
            listener.stopReceiverThreads();
        }
    }

    @Test
    void testNonPartitionAware_multiThread_usesSingleQueue() throws Exception {
        FlowXMLMessageListener listener = new FlowXMLMessageListener();
        try {
            @SuppressWarnings("unchecked")
            Consumer<BytesXMLMessage> consumer = mock(Consumer.class);
            listener.startReceiverThreads(5, "shared-multi", consumer, 60000); // partitionAware defaults to false
            assertThat(workerQueueCount(listener))
                    .as("default mode must use a single shared queue regardless of thread count")
                    .isEqualTo(1);
        } finally {
            listener.stopReceiverThreads();
        }
    }

    @SuppressWarnings("unchecked")
    private static int workerQueueCount(FlowXMLMessageListener listener) throws Exception {
        Field field = FlowXMLMessageListener.class.getDeclaredField("messageQueues");
        field.setAccessible(true);
        return ((List<?>) field.get(listener)).size();
    }

    private static String readPartitionKey(BytesXMLMessage msg) {
        try {
            SDTMap properties = msg.getProperties();
            return properties == null ? null
                    : properties.getString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY);
        } catch (SDTException e) {
            throw new RuntimeException(e);
        }
    }

    private static BytesXMLMessage mockPartitionedMessage(String partitionKey, String messageId) {
        BytesXMLMessage msg = mock(BytesXMLMessage.class);
        Mockito.when(msg.getMessageId()).thenReturn(messageId);
        Mockito.when(msg.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));
        SDTMap properties = JCSMPFactory.onlyInstance().createMap();
        if (partitionKey != null) {
            try {
                properties.putString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY, partitionKey);
            } catch (SDTException e) {
                throw new RuntimeException(e);
            }
        }
        Mockito.when(msg.getProperties()).thenReturn(properties);
        return msg;
    }

    private static BytesXMLMessage mockMessage() {
        BytesXMLMessage mockMessage = mock(BytesXMLMessage.class);
        Mockito.when(mockMessage.getMessageId()).thenReturn("TestMessageId");
        Mockito.when(mockMessage.getDestination()).thenReturn(JCSMPFactory.onlyInstance().createTopic("test/topic"));
        return mockMessage;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

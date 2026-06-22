package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.SDTException;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.XMLMessage;
import com.solacesystems.jcsmp.XMLMessageListener;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public class FlowXMLMessageListener implements XMLMessageListener {

    /**
     * Local queues on the heap that distribute messages to worker threads.
     * <p>In the default (throughput) mode this list holds a single queue that every worker thread polls.
     * In partition-aware mode (consumer {@code partitionAware = true} with {@code concurrency > 1}) it
     * holds one queue per worker thread, and {@link #onReceive} routes each message to the queue selected
     * by its partition key so that a given partition is always processed by the same thread, in receive
     * order. The list is replaced atomically on each (re)start and never mutated in place.</p>
     * <p>The queues are unbounded, but their effective size is influenced by the client flow transport
     * window, the broker queue's "Maximum Delivered Unacknowledged Messages per Flow" setting, and
     * {@code max-guaranteed-message-size}. Together these settings create backpressure towards the broker
     * and help protect the heap from overflow.</p>
     */
    private volatile List<BlockingQueue<MessageInProgress>> messageQueues = List.of(new LinkedBlockingDeque<>());
    private final AtomicInteger partitionRoundRobin = new AtomicInteger();
    private final Set<MessageInProgress> activeMessages = ConcurrentHashMap.newKeySet();
    private final AtomicReference<SolaceMeterAccessor> solaceMeterAccessor = new AtomicReference<>();
    private final AtomicReference<Supplier<String>> bindingNameSupplier = new AtomicReference<>();
    private final Set<Thread> receiverThreads = new HashSet<>();
    private volatile boolean running = true;


    public void setSolaceMeterAccessor(SolaceMeterAccessor solaceMeterAccessor, Supplier<String> bindingNameSupplier) {
        this.solaceMeterAccessor.set(solaceMeterAccessor);
        this.bindingNameSupplier.set(bindingNameSupplier);
    }

    public void startReceiverThreads(int threadCount, String threadNamePrefix, Consumer<BytesXMLMessage> messageConsumer, long watchdogTimeoutMs) {
        startReceiverThreads(threadCount, threadNamePrefix, messageConsumer, watchdogTimeoutMs, false);
    }

    /**
     * @param partitionAware when {@code true} and {@code threadCount > 1}, one queue is created per worker
     *                       thread and {@link #onReceive} routes each message to a queue by its partition
     *                       key, so a given partition is always handled by the same thread in receive
     *                       order. When {@code false}, a single shared queue feeds all worker threads
     *                       (maximum throughput, no per-partition ordering).
     */
    public void startReceiverThreads(int threadCount, String threadNamePrefix, Consumer<BytesXMLMessage> messageConsumer, long watchdogTimeoutMs, boolean partitionAware) {

        // Check if threads are already running and stop them first (outside synchronized block to avoid deadlock)
        boolean needToStop;
        synchronized (receiverThreads) {
            needToStop = !receiverThreads.isEmpty();
        }

        if (needToStop) {
            log.warn("Receiver threads are already running, stopping them first");
            stopReceiverThreads();
        }

        synchronized (receiverThreads) {
            // Per-thread queues only make sense for ordered partition dispatch with more than one worker.
            boolean perThreadQueues = partitionAware && threadCount > 1;

            // Build fresh, empty worker queues. Replacing the list (rather than clearing in place) discards
            // any stale messages from a previous run and publishes the new queues atomically before the
            // first message can arrive (the FlowReceiver is created only after this method returns).
            int queueCount = perThreadQueues ? threadCount : 1;
            List<BlockingQueue<MessageInProgress>> queues = new ArrayList<>(queueCount);
            for (int i = 0; i < queueCount; i++) {
                queues.add(new LinkedBlockingDeque<>());
            }
            this.messageQueues = List.copyOf(queues);
            this.partitionRoundRobin.set(0);

            // Set running to true before starting threads
            running = true;

            for (int i = 0; i < threadCount; i++) {
                String threadName = threadNamePrefix + "-" + i;
                // In partition-aware mode worker i drains its own queue; otherwise all workers share queue 0.
                BlockingQueue<MessageInProgress> workerQueue = this.messageQueues.get(perThreadQueues ? i : 0);
                Thread thread = new Thread(() -> loop(threadName, messageConsumer, workerQueue));
                thread.setName(threadName);
                receiverThreads.add(thread);
                thread.start();
                log.info("Started receiving thread {}", thread.getName());
            }
            Thread watchdogThread = new Thread(() -> watchdog(watchdogTimeoutMs));
            watchdogThread.setName(threadNamePrefix + "-watchdog");
            receiverThreads.add(watchdogThread);
            watchdogThread.start();
        }
    }

    public void stopReceiverThreads() {
        running = false;

        synchronized (receiverThreads) {
            if (receiverThreads.isEmpty()) {
                return; // No threads to stop
            }

            log.info("Stopping {} receiver threads", receiverThreads.size());

            // Wait for all threads to finish
            for (Thread thread : receiverThreads) {
                try {
                    thread.join(5000); // Wait up to 5 seconds for each thread
                    if (thread.isAlive()) {
                        log.warn("Thread {} did not stop within timeout, interrupting", thread.getName());
                        thread.interrupt();
                    } else {
                        log.info("Thread {} stopped successfully", thread.getName());
                    }
                } catch (InterruptedException e) {
                    log.warn("Interrupted while waiting for thread {} to stop", thread.getName());
                    Thread.currentThread().interrupt();
                }
            }

            // Clear the thread tracking
            receiverThreads.clear();
            log.info("All receiver threads stopped and cleared");
        }
    }

    /**
     * @return {@code true} when nothing is waiting in the internal queue and nothing is currently
     * being processed. Note that {@code activeMessages} membership spans the whole worker
     * {@code loop} iteration, which includes the consumer callback's ACK/settle, so "idle" means all
     * received messages have been fully settled.
     */
    public boolean isIdle() {
        return totalQueued() == 0 && activeMessages.isEmpty();
    }

    /**
     * @return the total number of messages waiting across all worker queues.
     */
    private int totalQueued() {
        int total = 0;
        for (BlockingQueue<MessageInProgress> queue : messageQueues) {
            total += queue.size();
        }
        return total;
    }

    /**
     * @return the oldest message waiting at the head of any worker queue, or {@code null} if all queues
     * are empty. Used to measure queue backpressure (how long the oldest unprocessed message has waited).
     */
    private MessageInProgress oldestQueuedMessage() {
        MessageInProgress oldest = null;
        for (BlockingQueue<MessageInProgress> queue : messageQueues) {
            MessageInProgress head = queue.peek();
            if (head != null && (oldest == null || head.getReceivedNanos() < oldest.getReceivedNanos())) {
                oldest = head;
            }
        }
        return oldest;
    }

    /**
     * Blocks until {@link #isIdle()} returns {@code true} or {@code timeoutMs} elapses.
     *
     * <p>The caller MUST have stopped the {@code FlowReceiver} first (so the broker delivers no new
     * messages) but MUST NOT have closed it yet, so that messages already pulled into the internal
     * queue can still be processed and ACKed/NACKed on the still-open flow. Worker threads keep
     * running for the duration. On timeout the method returns anyway; any unsettled messages will be
     * redelivered by the broker (client-ack = at-least-once).
     */
    @SuppressWarnings("BusyWait")
    public void drain(long timeoutMs) {
        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!isIdle()) {
            if (System.nanoTime() >= deadlineNanos) {
                log.warn("Drain timeout after {}ms: {} queued and {} in-flight message(s) remain; "
                                + "closing flow anyway (unsettled messages will be redelivered)",
                        timeoutMs, totalQueued(), activeMessages.size());
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Drain interrupted; closing flow with messages still in-flight");
                return;
            }
        }
        log.info("Drain complete: all in-flight messages processed and settled");
    }

    @SuppressWarnings("BusyWait")
    private void watchdog(long watchdogTimeoutMs) {
        while (running) {
            try {
                SolaceMeterAccessor meter = solaceMeterAccessor.get();
                Supplier<String> bindingSupplier = bindingNameSupplier.get();
                String binding = bindingSupplier != null ? bindingSupplier.get() : null;
                if (meter != null && binding != null) {
                    meter.recordQueueSize(binding, totalQueued());
                    meter.recordActiveMessages(binding, activeMessages.size());

                    // measure backpressure by looking at the oldest message across all worker queues
                    MessageInProgress oldestMessage = oldestQueuedMessage();
                    long backpressure = oldestMessage != null ? (System.nanoTime() - oldestMessage.getReceivedNanos()) / 1_000_000L : 0;
                    meter.recordQueueBackpressure(binding, backpressure);
                }

                long currentTimeNanos = System.nanoTime();
                for (MessageInProgress messageInProgress : activeMessages) {
                    // startNanos is set before adding to activeMessages (happens-before via ConcurrentHashMap),
                    // but guard defensively against seeing a zero value
                    if (messageInProgress.getStartNanos() == 0) {
                        continue;
                    }
                    long timeInProcessing = (currentTimeNanos - messageInProgress.getStartNanos()) / 1_000_000L;

                    // Deadlock detection: warn once if processing exceeds timeout
                    if (timeInProcessing > watchdogTimeoutMs && !messageInProgress.isWarned()) {
                        log.warn("Message processing exceeded {} ms (potential deadlock): thread={}, messageId={}, destination={}",
                                watchdogTimeoutMs,
                                messageInProgress.getThreadName(),
                                messageInProgress.getBytesXMLMessage().getMessageId(),
                                messageInProgress.getBytesXMLMessage().getDestination().getName());
                        messageInProgress.setWarned(true);
                    }
                }

                // Sleep for a short interval to update metrics frequently, but at most watchdogTimeoutMs.
                // This ensures metrics like queue size and backpressure are updated every 1 second even if the deadlock timeout is large (e.g. 5 minutes).
                // Ensure sleep time is at least 10ms to avoid IllegalArgumentException and excessive CPU usage if watchdogTimeoutMs is small/zero
                long sleepTime = Math.max(10, Math.min(watchdogTimeoutMs, 1000));
                Thread.sleep(sleepTime);
            } catch (Throwable e) { // Catch Throwable because spring sometimes catches Throwable
                log.error(e.getMessage(), e);
            }
        }
    }

    private void loop(String threadName, Consumer<BytesXMLMessage> messageConsumer, BlockingQueue<MessageInProgress> queue) {
        while (running) {
            try {
                MessageInProgress polled = queue.poll(1, TimeUnit.SECONDS);
                if (polled != null) {
                    long now = System.nanoTime();
                    polled.setStartNanos(now);
                    polled.setThreadName(threadName);

                    SolaceMeterAccessor meter = solaceMeterAccessor.get();
                    Supplier<String> bindingSupplier = bindingNameSupplier.get();
                    String binding = bindingSupplier != null ? bindingSupplier.get() : null;
                    if (meter != null && binding != null) {
                        meter.recordMessageQueueWaitTime(binding, (now - polled.getReceivedNanos()) / 1_000_000L);
                    }

                    log.trace("loop add mip={}", polled);
                    activeMessages.add(polled);
                    try {
                        messageConsumer.accept(polled.getBytesXMLMessage());
                    } finally {
                        log.trace("loop remove mip={}", polled);
                        activeMessages.remove(polled);
                        if (meter != null && binding != null) {
                            meter.recordMessageProcessingTimeDuration(binding, (System.nanoTime() - polled.getStartNanos()) / 1_000_000L);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error was not properly handled in JCSMPInboundQueueMessageProducer", e);
            }
        }
    }

    @Override
    public void onReceive(BytesXMLMessage bytesXMLMessage) {
        log.debug("Received BytesXMLMessage:{}", bytesXMLMessage);
        // The worker queues are local queues on the heap.
        // It distributes messages to worker threads and prevents blocking the single Solace dispatcher thread.
        // This queue should be protected by the client flow transport window, the broker queue's delivered-unacked limit,
        // or max-guaranteed-message-size to prevent heap overflow.
        // The onReceive method runs on the Solace dispatcher thread and must not block; otherwise, the entire connection is stalled.
        // Use non-blocking offer since the queue is unbounded and blocking the dispatcher must be avoided.
        BlockingQueue<MessageInProgress> queue = selectQueue(bytesXMLMessage);
        if (!queue.offer(new MessageInProgress(System.nanoTime(), bytesXMLMessage))) {
            // This should never happen with an unbounded queue
            log.error("Failed to enqueue message, message will be rejected: {}", bytesXMLMessage);
            settleMessageAsFailed(bytesXMLMessage);
        }
    }

    /**
     * Selects the worker queue for an incoming message. With a single shared queue (default throughput
     * mode) this is always queue 0. In partition-aware mode the message is routed by the hash of its
     * Solace partition key ({@code JMSXGroupID}) so that all messages of one partition land on the same
     * worker thread and are therefore processed sequentially in receive order. Messages without a
     * partition key carry no ordering constraint and are spread round-robin across the queues.
     *
     * <p>Runs on the single Solace dispatcher thread, so the relative order of {@code offer()} calls into
     * any given queue matches the broker delivery order — that is what guarantees per-partition ordering.
     */
    private BlockingQueue<MessageInProgress> selectQueue(BytesXMLMessage bytesXMLMessage) {
        List<BlockingQueue<MessageInProgress>> queues = this.messageQueues;
        int queueCount = queues.size();
        if (queueCount == 1) {
            return queues.get(0);
        }
        String partitionKey = readPartitionKey(bytesXMLMessage);
        int index = (partitionKey == null || partitionKey.isEmpty())
                ? Math.floorMod(partitionRoundRobin.getAndIncrement(), queueCount)
                : Math.floorMod(partitionKey.hashCode(), queueCount);
        return queues.get(index);
    }

    private static String readPartitionKey(BytesXMLMessage bytesXMLMessage) {
        try {
            SDTMap properties = bytesXMLMessage.getProperties();
            if (properties != null && properties.containsKey(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY)) {
                return properties.getString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY);
            }
        } catch (SDTException e) {
            log.warn("Failed to read partition key for partition-aware dispatch; falling back to round-robin", e);
        }
        return null;
    }

    private void settleMessageAsFailed(BytesXMLMessage bytesXMLMessage) {
        try {
            bytesXMLMessage.settle(XMLMessage.Outcome.FAILED);
        } catch (JCSMPException ex) {
            log.error("Failed to settle message as FAILED: {}", ex.getMessage(), ex);
        }
    }

    @Override
    public void onException(JCSMPException e) {
        log.error("Failed to receive message", e);
    }

    @Getter
    @Setter
    @ToString(exclude = "cachedHashCode")
    @RequiredArgsConstructor
    static class MessageInProgress {
        private final long receivedNanos;
        private final BytesXMLMessage bytesXMLMessage;
        private long startNanos;
        private String threadName;

        // Should not be part of hashcode. Because a changing hash or equal will prevent removing from `activeMessages` set.
        private volatile boolean warned = false;

        // Lazy-initialized cached hashCode (immutable fields only).
        // Intentionally NOT synchronized: worst case is computing hashCode twice,
        // which is cheaper than paying synchronization cost on every access.
        private Integer cachedHashCode;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MessageInProgress that)) {
                return false;
            }

            return receivedNanos == that.receivedNanos &&
                    Objects.equals(bytesXMLMessage, that.bytesXMLMessage);
        }

        @Override
        public int hashCode() {
            if (cachedHashCode == null) {
                cachedHashCode = Objects.hash(receivedNanos, bytesXMLMessage);
            }
            return cachedHashCode;
        }
    }
}

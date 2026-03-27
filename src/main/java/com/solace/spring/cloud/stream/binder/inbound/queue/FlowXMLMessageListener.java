package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.XMLMessage;
import com.solacesystems.jcsmp.XMLMessageListener;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
public class FlowXMLMessageListener implements XMLMessageListener {

    /**
     * Local queue on the heap to distribute messages to worker threads.
     * <p>The queue is unbounded, but its effective size is limited by the `maxUnacknowledgedMessages`
     * (or `max-guaranteed-message-size`) setting on the flow. This creates backpressure towards the broker
     * and protects the heap from overflow.</p>
     */
    private final BlockingQueue<MessageInProgress> messageQueue = new LinkedBlockingDeque<>();
    private final Set<MessageInProgress> activeMessages = ConcurrentHashMap.newKeySet();
    private final AtomicReference<SolaceMeterAccessor> solaceMeterAccessor = new AtomicReference<>();
    private final AtomicReference<String> bindingName = new AtomicReference<>();
    private final Set<Thread> receiverThreads = new HashSet<>();
    private volatile boolean running = true;


    public void setSolaceMeterAccessor(SolaceMeterAccessor solaceMeterAccessor, String bindingName) {
        this.solaceMeterAccessor.set(solaceMeterAccessor);
        this.bindingName.set(bindingName);
    }

    public void startReceiverThreads(int threadCount, String threadNamePrefix, Consumer<BytesXMLMessage> messageConsumer, long watchdogTimeoutMs) {

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
            // Clear the message queue to avoid processing stale messages
            messageQueue.clear();

            // Set running to true before starting threads
            running = true;

            for (int i = 0; i < threadCount; i++) {
                String threadName = threadNamePrefix + "-" + i;
                Thread thread = new Thread(() -> loop(threadName, messageConsumer));
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

    @SuppressWarnings("BusyWait")
    private void watchdog(long watchdogTimeoutMs) {
        while (running) {
            try {
                SolaceMeterAccessor meter = solaceMeterAccessor.get();
                String binding = bindingName.get();
                if (meter != null && binding != null) {
                    meter.recordQueueSize(binding, messageQueue.size());
                    meter.recordActiveMessages(binding, activeMessages.size());

                    // measure backpressure by looking at the oldest message in the queue
                    MessageInProgress oldestMessage = messageQueue.peek();
                    long backpressure = oldestMessage != null ? System.currentTimeMillis() - oldestMessage.getReceivedMillis() : 0;
                    meter.recordQueueBackpressure(binding, backpressure);
                }

                long currentTimeMillis = System.currentTimeMillis();
                for (MessageInProgress messageInProgress : activeMessages) {
                    // startMillis is set before adding to activeMessages (happens-before via ConcurrentHashMap),
                    // but guard defensively against seeing a zero value
                    if (messageInProgress.getStartMillis() == 0) {
                        continue;
                    }
                    long timeInProcessing = currentTimeMillis - messageInProgress.getStartMillis();

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
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    private void loop(String threadName, Consumer<BytesXMLMessage> messageConsumer) {
        while (running) {
            try {
                MessageInProgress polled = messageQueue.poll(1, TimeUnit.SECONDS);
                if (polled != null) {
                    long now = System.currentTimeMillis();
                    polled.setStartMillis(now);
                    polled.setThreadName(threadName);

                    SolaceMeterAccessor meter = solaceMeterAccessor.get();
                    String binding = bindingName.get();
                    if (meter != null && binding != null) {
                        meter.recordMessageQueueWaitTime(binding, now - polled.getReceivedMillis());
                    }

                    log.trace("loop add mip={}", polled);
                    activeMessages.add(polled);
                    try {
                        messageConsumer.accept(polled.getBytesXMLMessage());
                    } finally {
                        log.trace("loop remove mip={}", polled);
                        activeMessages.remove(polled);
                        if (meter != null && binding != null) {
                            meter.recordMessageProcessingTimeDuration(binding, System.currentTimeMillis() - polled.getStartMillis());
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
        try {
            int i = 0;
            // The messageQueue is a local queue on the heap.
            // It distributes messages to worker threads and prevents blocking the single Solace dispatcher thread.
            // This queue should be protected by maxUnacknowledgedMessages (or max-guaranteed-message-size) to prevent heap overflow.
            // The onReceive method runs on the Solace dispatcher thread and must not block; otherwise, the entire connection is stalled.
            while (i++ < 100) {
                // since the messageQueue is unbounded this should never happen and is here for paranoia and because the blocking put had strange behaviours
                if (messageQueue.offer(new MessageInProgress(System.currentTimeMillis(), bytesXMLMessage), 1, TimeUnit.SECONDS)) {
                    return;
                }
            }
            // All offer attempts failed — this should never happen with an unbounded queue
            log.error("Failed to enqueue message after 100 attempts, message will be rejected: {}", bytesXMLMessage);
            settleMessageAsFailed(bytesXMLMessage);
        } catch (InterruptedException e) {
            log.warn("Interrupted while enqueuing message, message will be rejected: {}", bytesXMLMessage);
            settleMessageAsFailed(bytesXMLMessage);
        }
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
        private final long receivedMillis;
        private final BytesXMLMessage bytesXMLMessage;
        private long startMillis;
        private String threadName;

        // Should not be part of hashcode. Because a changing hash or equal will prevent removing from `activeMessages` set.
        private boolean warned = false;

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

            return receivedMillis == that.receivedMillis &&
                    Objects.equals(bytesXMLMessage, that.bytesXMLMessage);
        }

        @Override
        public int hashCode() {
            if (cachedHashCode == null) {
                cachedHashCode = Objects.hash(receivedMillis, bytesXMLMessage);
            }
            return cachedHashCode;
        }
    }
}

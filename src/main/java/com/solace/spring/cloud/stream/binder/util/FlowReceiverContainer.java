package com.solace.spring.cloud.stream.binder.util;

import com.solace.spring.cloud.stream.binder.health.handlers.SolaceFlowHealthEventHandler;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.XMLMessage.Outcome;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>A {@link FlowReceiver} wrapper object which allows for flow rebinds.</p>
 * <p>Messaging operations concurrently invoked through this object during a rebind operation are not affected
 * by the rebind.</p>
 */
@Slf4j
public class FlowReceiverContainer {
    @Getter
    private final UUID id = UUID.randomUUID();
    private final JCSMPSession session;
    private final Endpoint endpoint;
    private final EndpointProperties endpointProperties;
    private final ConsumerFlowProperties consumerFlowProperties;
    private final AtomicReference<FlowReceiverReference> flowReceiverAtomicReference = new AtomicReference<>();
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    //Lock to serialize operations like - bind, unbind, pause, resume
    private final ReentrantLock writeLock = new ReentrantLock();

    private final XMLMessageMapper xmlMessageMapper = new XMLMessageMapper();
    @Setter
    private FlowEventHandler eventHandler;

    public FlowReceiverContainer(JCSMPSession session,
                                 Endpoint endpoint,
                                 EndpointProperties endpointProperties,
                                 ConsumerFlowProperties consumerFlowProperties) {
        this.session = session;
        this.endpoint = endpoint;
        this.endpointProperties = endpointProperties;
        this.consumerFlowProperties = consumerFlowProperties
                .setEndpoint(endpoint)
                .setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);
        this.eventHandler = new SolaceFlowEventHandler(xmlMessageMapper, id.toString());
    }

    /**
     * <p>Create the {@link FlowReceiver} and {@link FlowReceiver#start() starts} it.</p>
     * <p>Does nothing if this container is already bound to a {@link FlowReceiver}.</p>
     *
     * @return If no flow is bound, return the new flow reference ID. Otherwise, return the existing flow reference ID.
     * @throws JCSMPException a JCSMP exception
     */
    public UUID bind() throws JCSMPException {
        // Ideally would just use flowReceiverReference.compareAndSet(null, newFlowReceiver),
        // but we can't initialize a FlowReceiver object without actually provisioning it.
        // Explicitly using a lock here is our only option.
        writeLock.lock();
        try {
            log.info("Binding flow receiver container {}", id);
            FlowReceiverReference existingFlowReceiverReference = flowReceiverAtomicReference.get();
            if (existingFlowReceiverReference != null) {
                UUID existingFlowRefId = existingFlowReceiverReference.getId();
                log.info("Flow receiver container {} is already bound to {}", id, existingFlowRefId);
                return existingFlowRefId;
            } else {
                log.info("Flow receiver container {} started in state '{}'", id, isPaused.get() ? "Paused" : "Running");
                consumerFlowProperties.setStartState(!isPaused.get());
                consumerFlowProperties.addRequiredSettlementOutcomes(Outcome.ACCEPTED, Outcome.FAILED, Outcome.REJECTED);

                FlowReceiver flowReceiver = session.createFlow(null, consumerFlowProperties, endpointProperties, eventHandler);
                if (eventHandler != null && eventHandler instanceof SolaceFlowHealthEventHandler) {
                    ((SolaceFlowHealthEventHandler) eventHandler).setHealthStatusUp();
                }
                FlowReceiverReference newFlowReceiverReference = new FlowReceiverReference(flowReceiver);
                flowReceiverAtomicReference.set(newFlowReceiverReference);
                xmlMessageMapper.resetIgnoredProperties(id.toString());
                return newFlowReceiverReference.getId();
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Closes the bound {@link FlowReceiver}.
     */
    public void unbind() {
        writeLock.lock();
        try {
            FlowReceiverReference flowReceiverReference = flowReceiverAtomicReference.getAndSet(null);
            if (flowReceiverReference != null) {
                log.info("Unbinding flow receiver container {}", id);
                flowReceiverReference.getStaleMessagesFlag().set(true);
                flowReceiverReference.get().close();
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * <p>Receives the next available message, waiting until one is available.</p>
     * <p><b>Note:</b> This method is not thread-safe.</p>
     *
     * @return The next available message or null if is interrupted or no flow is bound.
     * @throws JCSMPException                        a JCSMP exception
     * @throws UnboundFlowReceiverContainerException flow receiver container is not bound
     * @see FlowReceiver#receive()
     */
    public MessageContainer receive() throws JCSMPException, UnboundFlowReceiverContainerException {
        return receive(null);
    }

    /**
     * <p>Receives the next available message. If no message is available, this method blocks until
     * {@code timeoutInMillis} is reached.</p>
     * <p><b>Note:</b> This method is not thread-safe.</p>
     *
     * @param timeoutInMillis The timeout in milliseconds. If {@code null}, wait forever.
     *                        If less than zero and no message is available, return immediately.
     * @return The next available message or null if the timeout expires, is interrupted, or no flow is bound.
     * @throws JCSMPException                        a JCSMP exception
     * @throws UnboundFlowReceiverContainerException flow receiver container is not bound
     * @see FlowReceiver#receive(int)
     */
    public MessageContainer receive(Integer timeoutInMillis) throws JCSMPException, UnboundFlowReceiverContainerException {

        FlowReceiverReference flowReceiverReference = flowReceiverAtomicReference.get();
        if (flowReceiverReference == null) {
            throw new UnboundFlowReceiverContainerException(
                    String.format("Flow receiver container %s is not bound", id));
        }

        // Always true: expiry - System.currentTimeMillis() < timeoutInMillis
        // So just set it to 0 (no-wait) if we underflow

        // The flow's receive shouldn't be locked behind the read lock.
        // This lets it be interrupt-able if the flow were to be shutdown mid-receive.
        BytesXMLMessage xmlMessage;
        if (timeoutInMillis == null) {
            xmlMessage = flowReceiverReference.get().receive();
        } else if (timeoutInMillis == 0) {
            xmlMessage = flowReceiverReference.get().receiveNoWait();
        } else {
            // realTimeout > 0: Wait until timeout
            // realTimeout < 0: Equivalent to receiveNoWait()
            xmlMessage = flowReceiverReference.get().receive(timeoutInMillis);
        }

        if (xmlMessage == null) {
            return null;
        }

        return new MessageContainer(xmlMessage, flowReceiverReference.getId(),
                flowReceiverReference.getStaleMessagesFlag());
    }

    /**
     * <p>Acknowledge the message off the broker and mark the provided message container as acknowledged.</p>
     * <p><b>WARNING:</b> Only messages created by this {@link FlowReceiverContainer} instance's {@link #receive()}
     * may be passed as a parameter to this function.</p>
     *
     * @param messageContainer The message
     */
    public void acknowledge(MessageContainer messageContainer) {
        if (messageContainer == null || messageContainer.isAcknowledged()) {
            return;
        }

        try {
            messageContainer.getMessage().settle(Outcome.ACCEPTED);
        } catch (JCSMPException | IllegalStateException ex) {
            throw new SolaceAcknowledgmentException("Failed to ACK a message", ex);
        }
        messageContainer.setAcknowledged(true);
    }

    /**
     * <p>Represents a negative acknowledgement outcome. Is used to signal that the application
     * failed to process the message and request broker to requeue/redeliver the message and mark the
     * provided message container as acknowledged.</p> <p>Message may be moved to DMQ by broker once
     * max-redelivered configured on endpoint is reached. Message may be delayed if the endpoint has
     * delayed redelivery configured.</p>
     * <p><b>WARNING:</b> Only messages created by this {@link FlowReceiverContainer} instance's
     * {@link #receive()} may be passed as a parameter to this function.</p>
     *
     * @param messageContainer The message
     */
    public void requeue(MessageContainer messageContainer) {
        if (messageContainer == null || messageContainer.isAcknowledged()) {
            return;
        }

        try {
            messageContainer.getMessage().settle(Outcome.FAILED);
        } catch (JCSMPException | IllegalStateException ex) {
            throw new SolaceAcknowledgmentException("Failed to REQUEUE a message", ex);
        }

        messageContainer.setAcknowledged(true);
    }

    /**
     * <p>Represents a negative acknowledgement outcome. Is used to signal that the application has
     * rejected the message such as when application determines the message is invalid and mark the
     * provided message container as acknowledged.</p><p>Message will NOT be redelivered. Message will
     * be moved to DMQ. If DMQ is not configured, message is discarded/deleted.</p>
     * <p><b>WARNING:</b> Only messages created by this {@link FlowReceiverContainer} instance's
     * {@link #receive()} may be passed as a parameter to this function.</p>
     *
     * @param messageContainer The message
     */
    public void reject(MessageContainer messageContainer) {
        if (messageContainer == null || messageContainer.isAcknowledged()) {
            return;
        }
        try {
            messageContainer.getMessage().settle(Outcome.REJECTED);
        } catch (JCSMPException | IllegalStateException ex) {
            throw new SolaceAcknowledgmentException("Failed to REJECT a message", ex);
        }

        messageContainer.setAcknowledged(true);
    }

    public void pause() {
        writeLock.lock();
        try {
            log.info("Pausing flow receiver container {}", id);
            doFlowReceiverReferencePause();
            isPaused.set(true);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * <b>CAUTION:</b> DO NOT USE THIS. This is only exposed for testing. Use {@link #pause()} instead to pause
     * the flow receiver container.
     *
     * @see #pause()
     */
    void doFlowReceiverReferencePause() {
        writeLock.lock();
        try {
            FlowReceiverReference flowReceiverReference = flowReceiverAtomicReference.get();
            if (flowReceiverReference != null) {
                flowReceiverReference.pause();
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void resume() throws JCSMPException {
        writeLock.lock();
        try {
            log.info("Resuming flow receiver container {}", id);
            doFlowReceiverReferenceResume();
            isPaused.set(false);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * <b>CAUTION:</b> DO NOT USE THIS. This is only exposed for testing. Use {@link #resume()} instead to resume
     * the flow receiver container.
     *
     * @see #resume()
     */
    void doFlowReceiverReferenceResume() throws JCSMPException {
        writeLock.lock();
        try {
            FlowReceiverReference flowReceiverReference = flowReceiverAtomicReference.get();
            if (flowReceiverReference != null) {
                flowReceiverReference.resume();
            }
        } finally {
            writeLock.unlock();
        }
    }

    public boolean isPaused() {
        return isPaused.get();
    }

    /**
     * <p>Get the nested {@link FlowReceiver}.</p>
     * <p><b>Caution:</b> Instead of using this, consider instead implementing a new function with the required rebind
     * guards.</p>
     *
     * @return The nested flow receiver.
     */
    @Nullable
    FlowReceiverReference getFlowReceiverReference() {
        return flowReceiverAtomicReference.get();
    }

    public String getEndpointName() {
        return endpoint.getName();
    }

    public XMLMessageMapper getXMLMessageMapper() {
        return xmlMessageMapper;
    }

    static class FlowReceiverReference {
        /**
         * -- GETTER --
         * Get the flow receiver reference ID.
         * This is <b>NOT</b> the actual flow ID, but just a reference ID used by this binder to identify a
         * particular
         * instance.
         * We can't use the actual flow ID for this since that can transparently change by itself due to
         * events such as reconnection.
         *
         * @return the flow receiver reference ID.
         */
        @Getter
        private final UUID id = UUID.randomUUID();
        private final FlowReceiver flowReceiver;
        @Getter
        private final AtomicBoolean staleMessagesFlag = new AtomicBoolean(false);

        public FlowReceiverReference(FlowReceiver flowReceiver) {
            this.flowReceiver = flowReceiver;
        }

        public FlowReceiver get() {
            return flowReceiver;
        }

        private void pause() {
            flowReceiver.stop();
        }

        private void resume() throws JCSMPException {
            flowReceiver.start();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FlowReceiverReference that = (FlowReceiverReference) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }
}

package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPException;

import java.util.UUID;
import java.util.concurrent.Future;

public interface Receiver {
    /**
     * <p>Create the {@link FlowReceiver} and {@link FlowReceiver#start() starts} it.</p>
     * <p>Does nothing if this container is already bound to a {@link FlowReceiver}.</p>
     * @return If no flow is bound, return the new flow reference ID. Otherwise, return the existing flow reference ID.
     * @throws JCSMPException a JCSMP exception
     */
    UUID bind() throws JCSMPException;

    /**
     * Closes the bound {@link FlowReceiver}.
     */
    void unbind();

    /**
     * <p>Rebinds the flow if {@code flowReceiverReferenceId} matches this container's existing flow reference's ID.
     * </p>
     * <p><b>Note:</b> If the flow is bound to a temporary queue, it may lose all of its messages when rebound.</p>
     * <p><b>Note:</b> If an exception is thrown, the flow container may be left in an unbound state.</p>
     * @param flowReceiverReferenceId The flow receiver reference ID to match.
     * @return The new flow reference ID or the existing flow reference ID if it the flow reference IDs do not match.
     * @throws JCSMPException a JCSMP exception
     * @throws InterruptedException was interrupted while waiting for the remaining messages to be acknowledged
     * @throws UnboundFlowReceiverContainerException flow receiver container is not bound
     */
    UUID rebind(UUID flowReceiverReferenceId) throws JCSMPException, InterruptedException,
            UnboundFlowReceiverContainerException;

    /**
     * Same as {@link #rebind(UUID)}, but with the option to return immediately with {@code null} if the lock cannot
     * be acquired.
     * @param flowReceiverReferenceId The flow receiver reference ID to match.
     * @param returnImmediately return {@code null} if {@code true} and lock cannot be acquired.
     * @return The new flow reference ID or the existing flow reference ID if it the flow reference IDs do not match.
     * Or {@code null} if {@code returnImmediately} is {@code true} and the lock cannot be acquired.
     * @throws JCSMPException a JCSMP exception
     * @throws InterruptedException was interrupted while waiting for the remaining messages to be acknowledged
     * @throws UnboundFlowReceiverContainerException flow receiver container is not bound
     * @see #rebind(UUID)
     */
    UUID rebind(UUID flowReceiverReferenceId, boolean returnImmediately) throws JCSMPException,
            InterruptedException, UnboundFlowReceiverContainerException;

    /**
     * <p>Receives the next available message, waiting until one is available.</p>
     * <p><b>Note:</b> This method is not thread-safe.</p>
     * @return The next available message or null if is interrupted or no flow is bound.
     * @throws JCSMPException a JCSMP exception
     * @throws UnboundFlowReceiverContainerException flow receiver container is not bound
     * @see FlowReceiver#receive()
     */
    MessageContainer receive() throws JCSMPException, UnboundFlowReceiverContainerException;

    /**
     * <p>Receives the next available message. If no message is available, this method blocks until
     * {@code timeoutInMillis} is reached.</p>
     * <p><b>Note:</b> This method is not thread-safe.</p>
     * @param timeoutInMillis The timeout in milliseconds. If {@code null}, wait forever.
     *                           If less than zero and no message is available, return immediately.
     * @return The next available message or null if the timeout expires, is interrupted, or no flow is bound.
     * @throws JCSMPException a JCSMP exception
     * @throws UnboundFlowReceiverContainerException flow receiver container is not bound
     * @see FlowReceiver#receive(int)
     */
    MessageContainer receive(Integer timeoutInMillis) throws JCSMPException, UnboundFlowReceiverContainerException;

    /**
     * <p>Acknowledge the message off the broker and mark the provided message container as acknowledged.</p>
     * <p><b>WARNING:</b> Only messages created by this {@link FlowReceiverContainer} instance's {@link #receive()}
     * may be passed as a parameter to this function. Failure to do so will misalign the timing for when rebinds
     * will occur, causing rebinds to unintentionally trigger early/late.</p>
     * @param messageContainer The message
     * @throws SolaceStaleMessageException the message is stale and cannot be acknowledged
     */
    void acknowledge(MessageContainer messageContainer) throws SolaceStaleMessageException;

    /**
     * Same as {@link #acknowledge(MessageContainer)}, but returns a future to asynchronously capture the flow
     * container's rebind result.
     * @param messageContainer The message.
     * @return a future containing the new flow reference ID or the current flow reference ID if message was already
     * acknowledge.
     * @throws SolaceStaleMessageException the message is stale and cannot be acknowledged
     * @throws UnboundFlowReceiverContainerException flow container is not bound
     * @see #acknowledgeRebind(MessageContainer)
     */
    Future<UUID> futureAcknowledgeRebind(MessageContainer messageContainer)
            throws SolaceStaleMessageException, UnboundFlowReceiverContainerException;

    /**
     * <p>Mark the provided message container as acknowledged and initiate a {@link #rebind}.</p>
     * <p><b>WARNING:</b> Only messages created by this {@link FlowReceiverContainer} instance's {@link #receive()}
     * may be passed as a parameter to this function. Failure to do so will misalign the timing for when rebinds
     * will occur, causing rebinds to unintentionally trigger early/late.</p>
     * <p><b>Note:</b> If an exception is thrown, the flow container may be left in an unbound state.
     * Use {@link MessageContainer#isStale()} and {@link #isBound()} to check and {@link #bind()} to recover.</p>
     * @param messageContainer The message.
     * @return The new flow reference ID or the current flow reference ID if message was already acknowledge.
     * @throws JCSMPException a JCSMP exception
     * @throws InterruptedException was interrupted while waiting for the remaining messages to be acknowledged
     * @throws SolaceStaleMessageException the message is stale and cannot be acknowledged
     * @throws UnboundFlowReceiverContainerException flow container is not bound
     */
    UUID acknowledgeRebind(MessageContainer messageContainer)
            throws JCSMPException, InterruptedException, SolaceStaleMessageException, UnboundFlowReceiverContainerException;

    /**
     * Same as {@link #acknowledge(MessageContainer)}, but with the option to return immediately with if the lock
     * cannot be acquired. Even if immediately returned, the message container will still be marked as acknowledged.
     * @param messageContainer The message.
     * @param returnImmediately Return {@code null} if {@code true} and the lock cannot be acquired.
     * @return The new flow reference ID, or the current flow reference ID if message was already acknowledge,
     * or {@code null} if {@code returnImmediately} is {@code true} and the lock cannot be acquired.
     * @throws JCSMPException a JCSMP exception
     * @throws InterruptedException was interrupted while waiting for the remaining messages to be acknowledged
     * @throws SolaceStaleMessageException the message is stale and cannot be acknowledged
     * @throws UnboundFlowReceiverContainerException flow container is not bound
     * @see #acknowledgeRebind(MessageContainer)
     */
    UUID acknowledgeRebind(MessageContainer messageContainer, boolean returnImmediately)
            throws JCSMPException, InterruptedException, SolaceStaleMessageException, UnboundFlowReceiverContainerException;

    String getEndpointName();
    XMLMessageMapper getXMLMessageMapper();

    UUID getId();

    boolean isBound();
}

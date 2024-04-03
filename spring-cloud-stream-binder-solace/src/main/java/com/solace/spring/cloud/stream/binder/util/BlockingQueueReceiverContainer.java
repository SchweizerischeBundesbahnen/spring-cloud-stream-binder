package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class BlockingQueueReceiverContainer implements Receiver {
    private final UUID id = UUID.randomUUID();

    private final String topicName;
    private final BlockingQueue<BytesXMLMessage> messageReceiver;
    private final XMLMessageMapper xmlMessageMapper = new XMLMessageMapper();

    public BlockingQueueReceiverContainer(
            String topicName,
            BlockingQueue<BytesXMLMessage> messageReceiver
    ) {
        this.topicName = topicName;
        this.messageReceiver = messageReceiver;
    }

    @Override
    public UUID bind() throws JCSMPException {
        return id;
    }

    @Override
    public void unbind() {
        // noop
    }

    @Override
    public UUID rebind(UUID flowReceiverReferenceId) throws JCSMPException, InterruptedException, UnboundFlowReceiverContainerException {
        return id;
    }

    @Override
    public UUID rebind(UUID flowReceiverReferenceId, boolean returnImmediately) throws JCSMPException, InterruptedException, UnboundFlowReceiverContainerException {
        return id;
    }

    @Override
    public MessageContainer receive() throws JCSMPException, UnboundFlowReceiverContainerException {
        return receive(null);
    }

    @Override
    public MessageContainer receive(Integer realTimeout) throws JCSMPException, UnboundFlowReceiverContainerException {
        // The flow's receive shouldn't be locked behind the read lock.
        // This lets it be interrupt-able if the flow were to be shutdown mid-receive.
        BytesXMLMessage xmlMessage;
        try {
            if (realTimeout == null || realTimeout == 0) {
                do {
                    xmlMessage = messageReceiver.poll(500, TimeUnit.MILLISECONDS);
                } while (xmlMessage == null);
            } else {
                xmlMessage = messageReceiver.poll(realTimeout, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            throw new CancellationException("Interrupted");
        }

        if (xmlMessage == null) {
            return null;
        }

        return new MessageContainer(xmlMessage, null, new AtomicBoolean(false));
    }

    @Override
    public void acknowledge(MessageContainer messageContainer) throws SolaceStaleMessageException {
        if (messageContainer == null || messageContainer.isAcknowledged()) {
            return;
        }
        if (messageContainer.isStale()) {
            throw new SolaceStaleMessageException(String.format("Message container %s (XMLMessage %s) is stale",
                    messageContainer.getId(), messageContainer.getMessage().getMessageId()));
        }

        messageContainer.getMessage().ackMessage();
        messageContainer.setAcknowledged(true);
    }

    @Override
    public Future<UUID> futureAcknowledgeRebind(MessageContainer messageContainer) throws
            SolaceStaleMessageException {
        acknowledge(messageContainer);

        return CompletableFuture.completedFuture(id);
    }

    @Override
    public UUID acknowledgeRebind(MessageContainer messageContainer) throws SolaceStaleMessageException {
        acknowledge(messageContainer);
        return id;
    }

    @Override
    public UUID acknowledgeRebind(MessageContainer messageContainer, boolean returnImmediately) throws
            SolaceStaleMessageException {
        acknowledge(messageContainer);
        return id;
    }

    @Override
    public String getEndpointName() {
        return topicName;
    }

    @Override
    public XMLMessageMapper getXMLMessageMapper() {
        return xmlMessageMapper;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isBound() {
        return true;
    }
}

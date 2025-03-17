package com.solace.spring.cloud.stream.binder.util;

import com.solace.spring.cloud.stream.binder.inbound.acknowledge.NestedAcknowledgementCallback;
import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.impl.BytesMessageImpl;
import com.solacesystems.jcsmp.impl.JCSMPGenericXMLMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.acks.AcknowledgmentCallback;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
public class LargeMessageSupport {
    public static final long RECEIVE_TIMEOUT = TimeUnit.SECONDS.toMillis(60);
    public static final int CHUNK_SIZE = 1024 * 1024 * 8;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<Long, MessageContextBytes[]> context = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public void startHousekeeping() {
        synchronized (running) {
            if (running.get()) {
                return;
            }
            running.set(true);
            Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::housekeeping, 60, 30, TimeUnit.SECONDS);
        }
    }

    public void housekeeping() {
        try {
            Set<Map.Entry<Long, MessageContextBytes[]>> outdated;
            synchronized (context) {
                var entries = context.entrySet();
                outdated = entries.stream().filter(e -> tooOld(e.getValue())).collect(Collectors.toSet());
            }
            for (var o : outdated) {
                synchronized (context) {
                    context.remove(o.getKey());
                }
                for (int i = 0; i < o.getValue().length; i++) {
                    var msg = o.getValue()[i];
                    if (msg != null) {
                        msg.acknowledgmentCallback().acknowledge(AcknowledgmentCallback.Status.REJECT);
                        log.warn("Check if Queue is partitioned correctly!");
                        log.warn("Incomplete large message dropped/rejected, no message received within 1 Minute. Dropped chunk {} index {} of {}", o.getKey(), i, o.getValue().length);
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Error during housekeeping", ex);
        }
    }

    boolean tooOld(MessageContextBytes[] messages) {
        return Arrays.stream(messages).filter(Objects::nonNull).mapToLong(messageContextBytes -> messageContextBytes.timestamp().toEpochMilli()).max().orElse(0) < Instant.now().toEpochMilli() - RECEIVE_TIMEOUT;
    }

    public MessageContext assemble(BytesXMLMessage smfMessage, AcknowledgmentCallback acknowledgmentCallback) {
        try {
            if (smfMessage.getProperties() == null || !smfMessage.getProperties().containsKey(SolaceBinderHeaders.CHUNK_ID)) {
                //no large message -> send ahead
                return new MessageContext(smfMessage, acknowledgmentCallback, Instant.now());
            }
            Long chunkId = smfMessage.getProperties().getLong(SolaceBinderHeaders.CHUNK_ID);
            if (chunkId == null) {
                throw new RuntimeException("Missing chunkId");
            }
            Integer chunkIndex = smfMessage.getProperties().getInteger(SolaceBinderHeaders.CHUNK_INDEX);
            if (chunkIndex == null) {
                throw new RuntimeException("Missing chunkIndex");
            }
            Integer chunkCount = smfMessage.getProperties().getInteger(SolaceBinderHeaders.CHUNK_COUNT);
            if (chunkCount == null) {
                throw new RuntimeException("Missing chunkCount");
            }
            if (!(smfMessage instanceof BytesMessage)) {
                throw new RuntimeException("LargeMessageSupport is only available for BytesMessages");
            }

            MessageContextBytes[] chunkArray;
            synchronized (context) {
                chunkArray = context.computeIfAbsent(chunkId, k -> new MessageContextBytes[chunkCount]);
            }
            if (chunkArray[chunkIndex] == null) {
                chunkArray[chunkIndex] = new MessageContextBytes((BytesMessage) smfMessage, acknowledgmentCallback, Instant.now());
            } else {
                log.warn("Duplicate chunk id={} index={} of {} received, drop it", chunkId, chunkIndex, chunkCount);
                return null;
            }
            int length = 0;
            for (int i = chunkArray.length - 1; i >= 0; i--) {
                MessageContextBytes messageContextBytes = chunkArray[i];
                if (messageContextBytes == null) {
                    // not all chunks received yet
                    return null;
                }
                length += messageContextBytes.bytesMessage().getData().length;
            }
            byte[] data = new byte[length];
            int offset = 0;
            NestedAcknowledgementCallback nestedAcknowledgementCallback = new NestedAcknowledgementCallback();
            for (MessageContextBytes messageContext : chunkArray) {
                byte[] msgBytes = messageContext.bytesMessage().getData();
                System.arraycopy(msgBytes, 0, data, offset, msgBytes.length);
                offset += msgBytes.length;
                if (messageContext.acknowledgmentCallback() != null) {
                    nestedAcknowledgementCallback.addAcknowledgmentCallback(messageContext.acknowledgmentCallback());
                }
            }
            BytesMessage bytesMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
            bytesMessage.setData(data);
            bytesMessage.setHTTPContentType(smfMessage.getHTTPContentType());
            bytesMessage.setDeliveryMode(smfMessage.getDeliveryMode());
            bytesMessage.setPriority(smfMessage.getPriority());
            bytesMessage.setCorrelationKey(smfMessage.getCorrelationKey());
            if (((BytesMessageImpl) bytesMessage).getWrappedMessage() instanceof JCSMPGenericXMLMessage msg) {
                msg.setDestinationReceived(smfMessage.getDestination());
            }
            SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
            metadata.putAll(smfMessage.getProperties());
            metadata.remove(SolaceBinderHeaders.CHUNK_ID);
            metadata.remove(SolaceBinderHeaders.CHUNK_INDEX);
            metadata.remove(SolaceBinderHeaders.CHUNK_COUNT);
            bytesMessage.setProperties(metadata);
            bytesMessage.setReadOnly();
            synchronized (context) {
                context.remove(chunkId);
            }
            return new MessageContext(bytesMessage, nestedAcknowledgementCallback, Instant.now());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<XMLMessage> split(XMLMessage smfMessage) {
        if (!(smfMessage instanceof BytesMessage)) {
            throw new RuntimeException("LargeMessageSupport is only available for BytesMessage");
        }
        byte[] data = ((BytesMessage) smfMessage).getData();
        if (data.length <= CHUNK_SIZE) {
            return List.of(smfMessage);
        }

        long rest = data.length % CHUNK_SIZE;
        int chunks = data.length / CHUNK_SIZE;
        int chunkCount = chunks + (rest > 0 ? 1 : 0);

        long chunkId = secureRandom.nextLong();
        List<XMLMessage> result = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            int from = i * CHUNK_SIZE;
            int to = (i + 1) * CHUNK_SIZE;
            result.add(createChunkMessage(smfMessage, data, from, to, chunkId, i, chunkCount));
        }
        if (rest > 0) {
            result.add(createChunkMessage(smfMessage, data, chunks * CHUNK_SIZE, data.length, chunkId, chunkCount - 1, chunkCount));
        }
        return result;
    }

    private static BytesMessage createChunkMessage(XMLMessage original, byte[] data, int from, int to, long chunkId, int index, int chunkCount) {
        byte[] dataChunk = Arrays.copyOfRange(data, from, to);
        BytesMessage bytesMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        bytesMessage.setData(dataChunk);
        bytesMessage.setHTTPContentType(original.getHTTPContentType());
        bytesMessage.setDeliveryMode(original.getDeliveryMode());
        bytesMessage.setPriority(original.getPriority());
        bytesMessage.setCorrelationKey(original.getCorrelationKey());
        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
        try {
            metadata.putAll(original.getProperties());
            if (!metadata.containsKey(SolaceBinderHeaders.PARTITION_KEY)) {
                metadata.putString(SolaceBinderHeaders.PARTITION_KEY, chunkId + "");
            }
            metadata.putLong(SolaceBinderHeaders.CHUNK_ID, chunkId);
            metadata.putInteger(SolaceBinderHeaders.CHUNK_INDEX, index);
            metadata.putInteger(SolaceBinderHeaders.CHUNK_COUNT, chunkCount);
        } catch (SDTException e) {
            throw new RuntimeException(e);
        }
        bytesMessage.setProperties(metadata);
        return bytesMessage;
    }

    public record MessageContextBytes(BytesMessage bytesMessage, AcknowledgmentCallback acknowledgmentCallback, Instant timestamp) {
    }

    public record MessageContext(BytesXMLMessage bytesMessage, AcknowledgmentCallback acknowledgmentCallback, Instant timestamp) {
    }
}

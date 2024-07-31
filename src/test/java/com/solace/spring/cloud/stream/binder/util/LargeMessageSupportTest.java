package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.XMLMessage;
import org.junit.jupiter.api.Test;
import org.springframework.integration.acks.AcknowledgmentCallback;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;


class LargeMessageSupportTest {
    private final SecureRandom secureRandom = new SecureRandom();

    @Test
    void split_shouldNotSplitSmallMessage() {
        LargeMessageSupport largeMessageSupport = new LargeMessageSupport();
        BytesMessage originalMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        byte[] data = new byte[LargeMessageSupport.CHUNK_SIZE - 1];
        secureRandom.nextBytes(data);
        originalMessage.setData(data);
        List<XMLMessage> chunks = largeMessageSupport.split(originalMessage);
        assertThat(chunks.size()).isEqualTo(1);
        assertThat(((BytesMessage) chunks.get(0)).getData()).isEqualTo(data);
    }

    @Test
    void split_shouldSplitBigMessage() {
        LargeMessageSupport largeMessageSupport = new LargeMessageSupport();
        BytesMessage originalMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        byte[] userData = new byte[LargeMessageSupport.CHUNK_SIZE + 1];
        secureRandom.nextBytes(userData);
        originalMessage.setData(userData);
        List<XMLMessage> chunks = largeMessageSupport.split(originalMessage);
        assertThat(chunks.size()).isEqualTo(2);
    }

    @Test
    void split_shouldSplit80BigMessage() {
        LargeMessageSupport largeMessageSupport = new LargeMessageSupport();
        BytesMessage originalMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        int messageSize = 1024 * 1024 * 80 + 17;
        byte[] userData = new byte[messageSize];
        secureRandom.nextBytes(userData);
        originalMessage.setData(userData);
        List<XMLMessage> chunks = largeMessageSupport.split(originalMessage);
        assertThat(chunks.size()).isEqualTo(messageSize / LargeMessageSupport.CHUNK_SIZE + 1);
        int len = 0;
        for (XMLMessage xmlMessage : chunks) {
            len += ((BytesMessage) xmlMessage).getData().length;
        }
        assertThat(len).isEqualTo(messageSize);
        Collections.reverse(chunks);
        AtomicBoolean done = new AtomicBoolean(false);
        for (XMLMessage xmlMessage : chunks) {
            LargeMessageSupport.MessageContext messageContext = largeMessageSupport.assemble((BytesXMLMessage) xmlMessage, mock(AcknowledgmentCallback.class));
            if (messageContext != null) {
                assertThat(((BytesMessage) messageContext.bytesMessage()).getData()).isEqualTo(userData);
                done.set(true);
            }
        }
        assertThat(done.get()).isTrue();
    }

    @Test
    void housekeeping_ignore_new_messages() {
        LargeMessageSupport largeMessageSupport = new LargeMessageSupport();
        BytesMessage originalMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        int messageSize = 1024 * 1024 * 80 + 17;
        byte[] userData = new byte[messageSize];
        secureRandom.nextBytes(userData);
        originalMessage.setData(userData);
        List<XMLMessage> chunks = largeMessageSupport.split(originalMessage);
        assertThat(chunks.size()).isEqualTo(messageSize / LargeMessageSupport.CHUNK_SIZE + 1);
        int len = 0;
        for (XMLMessage xmlMessage : chunks) {
            len += ((BytesMessage) xmlMessage).getData().length;
        }
        assertThat(len).isEqualTo(messageSize);
        Collections.reverse(chunks);
        AtomicBoolean done = new AtomicBoolean(false);
        for (XMLMessage xmlMessage : chunks) {
            LargeMessageSupport.MessageContext messageContext = largeMessageSupport.assemble((BytesXMLMessage) xmlMessage, mock(AcknowledgmentCallback.class));
            largeMessageSupport.housekeeping();
            if (messageContext != null) {
                assertThat(((BytesMessage) messageContext.bytesMessage()).getData()).isEqualTo(userData);
                done.set(true);
            }
        }
        assertThat(done.get()).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void housekeeping_remove_old_messages() throws NoSuchFieldException, IllegalAccessException {
        LargeMessageSupport largeMessageSupport = new LargeMessageSupport();
        BytesMessage originalMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        int messageSize = 1024 * 1024 * 80 + 17;
        byte[] userData = new byte[messageSize];
        secureRandom.nextBytes(userData);
        originalMessage.setData(userData);
        List<XMLMessage> chunks = largeMessageSupport.split(originalMessage);
        Field contextField = largeMessageSupport.getClass().getDeclaredField("context");
        contextField.setAccessible(true);
        var context = (Map<Long, LargeMessageSupport.MessageContextBytes[]>) contextField.get(largeMessageSupport);
        for (XMLMessage xmlMessage : chunks.stream().skip(1).toList()) {
            largeMessageSupport.assemble((BytesXMLMessage) xmlMessage, mock(AcknowledgmentCallback.class));
        }
        LargeMessageSupport.MessageContextBytes[] contextArray = context.entrySet().iterator().next().getValue();
        List<AcknowledgmentCallback> callbacks = new ArrayList<>();
        for (int i = 0; i < contextArray.length; i++) {
            if (contextArray[i] != null) {
                callbacks.add(contextArray[i].acknowledgmentCallback());
                contextArray[i] = new LargeMessageSupport.MessageContextBytes(contextArray[i].bytesMessage(), contextArray[i].acknowledgmentCallback(), Instant.now().minusSeconds(61));
            }
        }
        largeMessageSupport.housekeeping();
        assertThat(context.entrySet()).isEmpty();
        for (AcknowledgmentCallback ack : callbacks) {
            verify(ack).acknowledge(eq(AcknowledgmentCallback.Status.REJECT));
        }
    }
}
package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.XMLMessage;

/**
 * Reads the message id that logs and error queue correlation identify a message by. JCSMP deprecated it in favor
 * of the replication group message id, so the deprecation is suppressed here, in one place.
 */
public final class XMLMessageIds {

    private XMLMessageIds() {
    }

    @SuppressWarnings("deprecation")
    public static String messageIdOf(XMLMessage message) {
        return message.getMessageId();
    }
}

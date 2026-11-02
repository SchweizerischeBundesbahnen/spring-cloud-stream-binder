package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.XMLMessage;

/**
 * Reads the message id that logs and error queue correlation identify a message by, the same id the broker shows in
 * its UI. JCSMP deprecated it for any use but acknowledgements and names no replacement, so the deprecation is
 * suppressed here, in one place.
 */
public final class XMLMessageIds {

    private XMLMessageIds() {
    }

    @SuppressWarnings("deprecation")
    public static String messageIdOf(XMLMessage message) {
        return message.getMessageId();
    }
}

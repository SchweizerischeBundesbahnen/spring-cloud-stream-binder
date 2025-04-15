package com.solace.spring.cloud.stream.binder.meter;

import com.solacesystems.jcsmp.XMLMessage;

/**
 * <p>Proxy class for the Solace binder to access meter components.
 * Always use this instead of directly using meter components in Solace binder code.</p>
 * <p>Allows for the Solace binder to still function correctly without micrometer on the classpath.</p>
 */
public class SolaceMeterAccessor {
    private final SolaceMessageMeterBinder solaceMessageMeterBinder;

    public SolaceMeterAccessor(SolaceMessageMeterBinder solaceMessageMeterBinder) {
        this.solaceMessageMeterBinder = solaceMessageMeterBinder;
    }

    public void recordMessage(String bindingName, XMLMessage message) {
        solaceMessageMeterBinder.recordMessage(bindingName, message);
    }

    public void recordQueueSize(String bindingName, int queueSize) {
        solaceMessageMeterBinder.recordQueueSize(bindingName, queueSize);
    }
    public void recordActiveMessages(String bindingName, int activeMessages) {
        solaceMessageMeterBinder.recordActiveMessages(bindingName, activeMessages);
    }
}

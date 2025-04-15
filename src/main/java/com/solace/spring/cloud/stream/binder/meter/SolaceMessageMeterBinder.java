package com.solace.spring.cloud.stream.binder.meter;

import com.solacesystems.jcsmp.XMLMessage;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.micrometer.core.instrument.binder.MeterBinder;

public class SolaceMessageMeterBinder implements MeterBinder {
    MeterRegistry registry;

    public static final String METER_NAME_TOTAL_SIZE = "solace.message.size.total";
    public static final String METER_NAME_PAYLOAD_SIZE = "solace.message.size.payload";
    public static final String METER_NAME_QUEUE_SIZE = "solace.message.queue.size";
    public static final String METER_NAME_ACTIVE_MESSAGES_SIZE = "solace.message.active.size";
    public static final String METER_DESCRIPTION_TOTAL_SIZE = "Total message size";
    public static final String METER_DESCRIPTION_PAYLOAD_SIZE = "Message payload size";
    public static final String METER_DESCRIPTION_QUEUE_SIZE = "Message queue size";
    public static final String METER_DESCRIPTION_ACTIVE_MESSAGES_SIZE = "Messages active in processing";
    public static final String TAG_NAME = "name";

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordMessage(String bindingName, XMLMessage message) {
        long payloadSize = message.getAttachmentContentLength() + message.getContentLength();
        registerSizeMeter(METER_NAME_TOTAL_SIZE, METER_DESCRIPTION_TOTAL_SIZE, bindingName)
                .record(payloadSize + message.getBinaryMetadataContentLength(0));
        registerSizeMeter(METER_NAME_PAYLOAD_SIZE, METER_DESCRIPTION_PAYLOAD_SIZE, bindingName)
                .record(payloadSize);
    }

    public void recordQueueSize(String bindingName, int queueSize) {
        DistributionSummary.builder(METER_NAME_QUEUE_SIZE)
                .description(METER_DESCRIPTION_QUEUE_SIZE)
                .tag(TAG_NAME, bindingName)
                .baseUnit(BaseUnits.MESSAGES)
                .register(registry)
                .record(queueSize);
    }

    public void recordActiveMessages(String bindingName, int activeMessages) {
        DistributionSummary.builder(METER_NAME_ACTIVE_MESSAGES_SIZE)
                .description(METER_DESCRIPTION_ACTIVE_MESSAGES_SIZE)
                .tag(TAG_NAME, bindingName)
                .baseUnit(BaseUnits.MESSAGES)
                .register(registry)
                .record(activeMessages);
    }

    private DistributionSummary registerSizeMeter(String meterName,
                                                  String description,
                                                  String bindingName) {
        return DistributionSummary.builder(meterName)
                .description(description)
                .tag(TAG_NAME, bindingName)
                .baseUnit(BaseUnits.BYTES)
                .register(registry);
    }
}

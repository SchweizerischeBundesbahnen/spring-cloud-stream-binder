package com.solace.spring.cloud.stream.binder.meter;

import com.solacesystems.jcsmp.XMLMessage;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SolaceMessageMeterBinder implements MeterBinder {
    MeterRegistry registry;

    public static final String METER_NAME_TOTAL_SIZE = "solace.message.size.total";
    public static final String METER_NAME_PAYLOAD_SIZE = "solace.message.size.payload";
    public static final String METER_NAME_PROCESSING_TIME = "solace.message.processing.time";
    public static final String METER_NAME_QUEUE_SIZE = "solace.message.queue.size";
    public static final String METER_NAME_ACTIVE_MESSAGES_SIZE = "solace.message.active.size";
    public static final String METER_NAME_QUEUE_BACKPRESSURE = "solace.message.queue.backpressure";
    public static final String METER_DESCRIPTION_TOTAL_SIZE = "Total message size";
    public static final String METER_DESCRIPTION_PAYLOAD_SIZE = "Message payload size";
    public static final String METER_DESCRIPTION_PROCESSING_TIME = "How long each message has been processed, before thread has been handed back";
    public static final String METER_DESCRIPTION_QUEUE_SIZE = "Message queue size";
    public static final String METER_DESCRIPTION_ACTIVE_MESSAGES_SIZE = "Messages active in processing";
    public static final String METER_DESCRIPTION_QUEUE_BACKPRESSURE = "The age of the oldest message that is waiting for being processed in process queue.";
    public static final String TAG_NAME = "name";

    public final Map<String, DistributionSummary> meterCache = new ConcurrentHashMap<>();

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordMessage(String bindingName, XMLMessage message) {
        if (registry == null) {
            return;
        }

        long payloadSize = message.getAttachmentContentLength() + message.getContentLength();
        registerSizeMeter(METER_NAME_TOTAL_SIZE, METER_DESCRIPTION_TOTAL_SIZE, bindingName)
                .record(payloadSize + message.getBinaryMetadataContentLength(0));
        registerSizeMeter(METER_NAME_PAYLOAD_SIZE, METER_DESCRIPTION_PAYLOAD_SIZE, bindingName)
                .record(payloadSize);
    }

    public void recordQueueSize(String bindingName, int queueSize) {
        if (registry == null) {
            return;
        }

        meterCache.computeIfAbsent(
                        METER_NAME_QUEUE_SIZE + bindingName,
                        ignored -> DistributionSummary.builder(METER_NAME_QUEUE_SIZE)
                                .description(METER_DESCRIPTION_QUEUE_SIZE)
                                .tag(TAG_NAME, bindingName)
                                .baseUnit(BaseUnits.MESSAGES)
                                .register(registry)
                )
                .record(queueSize);
    }

    public void recordActiveMessages(String bindingName, int activeMessages) {
        if (registry == null) {
            return;
        }

        meterCache.computeIfAbsent(
                        METER_NAME_ACTIVE_MESSAGES_SIZE + bindingName,
                        ignored -> DistributionSummary.builder(METER_NAME_ACTIVE_MESSAGES_SIZE)
                                .description(METER_DESCRIPTION_ACTIVE_MESSAGES_SIZE)
                                .tag(TAG_NAME, bindingName)
                                .baseUnit(BaseUnits.MESSAGES)
                                .register(registry)
                )
                .record(activeMessages);
    }

    public void recordQueueBackpressure(String bindingName, long oldestMessagesWaitingForMs) {
        if (registry == null) {
            return;
        }

        meterCache.computeIfAbsent(
                        METER_NAME_QUEUE_BACKPRESSURE + bindingName,
                        ignored -> DistributionSummary.builder(METER_NAME_QUEUE_BACKPRESSURE)
                                .description(METER_DESCRIPTION_QUEUE_BACKPRESSURE)
                                .tag(TAG_NAME, bindingName)
                                .baseUnit(BaseUnits.MILLISECONDS)
                                .register(registry)
                )
                .record(oldestMessagesWaitingForMs);
    }

    public void recordMessageProcessingTimeDuration(String bindingName, long processingDurationMs) {
        if (registry == null) {
            return;
        }

        meterCache.computeIfAbsent(
                        METER_NAME_PROCESSING_TIME + bindingName,
                        ignored -> DistributionSummary.builder(METER_NAME_PROCESSING_TIME)
                                .description(METER_DESCRIPTION_PROCESSING_TIME)
                                .tag(TAG_NAME, bindingName)
                                .baseUnit(BaseUnits.MILLISECONDS)
                                .register(registry)
                )
                .record(processingDurationMs);
    }

    private DistributionSummary registerSizeMeter(String meterName,
                                                  String description,
                                                  String bindingName) {
        return meterCache.computeIfAbsent(
                meterName + bindingName,
                m -> DistributionSummary.builder(meterName)
                        .description(description)
                        .tag(TAG_NAME, bindingName)
                        .baseUnit(BaseUnits.BYTES)
                        .register(registry)
        );
    }
}

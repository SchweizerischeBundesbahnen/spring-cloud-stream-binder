package com.solace.spring.cloud.stream.binder.inbound.acknowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.acks.AcknowledgmentCallback;

import java.util.ArrayList;
import java.util.List;

public class NestedAcknowledgementCallback implements AcknowledgmentCallback {
    private final List<AcknowledgmentCallback> acknowledgmentCallbacks = new ArrayList<>();
    private boolean autoAckEnabled= true;

    public void addAcknowledgmentCallback(AcknowledgmentCallback acknowledgmentCallback) {
        this.acknowledgmentCallbacks.add(acknowledgmentCallback);
    }

    @Override
    public void acknowledge(Status status) {
        acknowledgmentCallbacks.forEach(a -> a.acknowledge(status));
    }

    @Override
    public boolean isAcknowledged() {
        return acknowledgmentCallbacks.stream().allMatch(AcknowledgmentCallback::isAcknowledged);
    }

    @Override
    public void noAutoAck() {
        autoAckEnabled = false;
    }

    @Override
    public boolean isAutoAck() {
        return autoAckEnabled;
    }
}
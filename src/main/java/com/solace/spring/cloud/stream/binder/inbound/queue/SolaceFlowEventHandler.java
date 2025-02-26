package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solace.spring.cloud.stream.binder.health.indicators.FlowHealthIndicator;
import com.solacesystems.jcsmp.FlowEventArgs;
import com.solacesystems.jcsmp.FlowEventHandler;
import com.solacesystems.jcsmp.impl.flow.FlowHandle;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Setter
public class SolaceFlowEventHandler implements FlowEventHandler {
    private String bindingName;
    private String bindingId;
    private FlowHealthIndicator flowHealthIndicator;
    private final List<Runnable> reconnectListeners = new ArrayList<>();

    public void addReconnectListener(Runnable reconnectListener) {
        synchronized (reconnectListeners) {
            reconnectListeners.add(reconnectListener);
        }
    }

    public void clearReconnectListeners() {
        synchronized (reconnectListeners) {
            reconnectListeners.clear();
        }
    }

    @Override
    public void handleEvent(Object source, FlowEventArgs flowEventArgs) {
        if (log.isDebugEnabled()) {
            log.debug("({}): Received Solace Flow event [{}].", source, flowEventArgs);
        }
        if (source instanceof FlowHandle flowHandle) {
            log.info("FlowEvent bindingName:{} bindingId:{} flowId:{} event:{}", bindingId, bindingName, flowHandle.getFlowId(), flowEventArgs.getEvent());
        }
        if (flowEventArgs.getEvent() != null) {
            switch (flowEventArgs.getEvent()) {
                case FLOW_DOWN:
                    if (flowHealthIndicator != null) {
                        flowHealthIndicator.down(flowEventArgs);
                    }
                    break;
                case FLOW_RECONNECTING:
                    if (flowHealthIndicator != null) {
                        flowHealthIndicator.reconnecting(flowEventArgs);
                    }
                    break;
                case FLOW_UP:
                case FLOW_RECONNECTED:
                    if (flowHealthIndicator != null) {
                        flowHealthIndicator.up();
                    }
                    synchronized (reconnectListeners) {
                        for (Runnable reconnectListener : reconnectListeners) {
                            reconnectListener.run();
                        }
                    }
                    break;
            }
        }
    }
}
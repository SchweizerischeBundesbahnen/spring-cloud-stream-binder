package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
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
    private SolaceHealthIndicator bindingHealthIndicator;
    private List<Runnable> reconnectRunnables = new ArrayList<>();

    public void addReconnectRunnable(Runnable runnable) {
        reconnectRunnables.add(runnable);
    }

    @Override
    public void handleEvent(Object source, FlowEventArgs flowEventArgs) {
        if (log.isDebugEnabled()) {
            log.debug("({}): Received Solace Flow event [{}].", source, flowEventArgs);
        }
        if (source instanceof FlowHandle flowHandle) {
            // 0x1FFFFF = 2097151 = 000111111111111111111111 remove everything above 21 bit since solace somehow add a bit there
            // Masking higher bits to display flowId similar to the Solace broker UI and log format
            long flowId = flowHandle.getFlowId() & 0x1FFFFF;
            log.info("FlowEvent binding={} flowId={} event={}", bindingName, flowId, flowEventArgs.getEvent());
        }
        if (flowEventArgs.getEvent() != null) {
            switch (flowEventArgs.getEvent()) {
                case FLOW_DOWN:
                    if (bindingHealthIndicator != null) {
                        bindingHealthIndicator.healthDown(flowEventArgs);
                    }
                    break;
                case FLOW_RECONNECTING:
                    if (bindingHealthIndicator != null) {
                        bindingHealthIndicator.healthReconnecting(flowEventArgs);
                    }
                    break;
                case FLOW_UP:
                case FLOW_RECONNECTED:
                    if (bindingHealthIndicator != null) {
                        bindingHealthIndicator.healthUp();
                    }
                    if (!reconnectRunnables.isEmpty()) {
                        for (Runnable runnable : reconnectRunnables) {
                            runnable.run();
                        }
                    }
                    break;
            }
        }
    }
}
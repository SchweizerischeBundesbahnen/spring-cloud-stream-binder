package com.solace.spring.cloud.stream.binder.tracing;

import com.solacesystems.jcsmp.SDTMap;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * <p>Proxy class for the Solace binder to access tracing components.
 * Always use this instead of directly using tracing components in Solace binder code.</p>
 * <p>Allows for the Solace binder to still function correctly without micrometer on the classpath.</p>
 */
@RequiredArgsConstructor
public class TracingProxy {
    public static final String TRACE_PARENT = "traceparent";
    public static final String TRACE_STATE = "tracestate";
    private final TracingImpl tracingImpl;

    public boolean hasTracingHeader(SDTMap headerMap) {
        return headerMap.containsKey(TRACE_PARENT);
    }

    public void injectTracingHeader(SDTMap headerMap) {
        tracingImpl.injectTracingHeader(headerMap);
    }

    public Consumer<Message<?>> wrapInTracingContext(SDTMap headerMap, Consumer<Message<?>> messageConsumer) {
        return this.tracingImpl.wrapInTracingContext(headerMap, messageConsumer);
    }
}

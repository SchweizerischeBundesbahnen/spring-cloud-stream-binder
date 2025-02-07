package com.solace.spring.cloud.stream.binder.tracing;

import com.solacesystems.jcsmp.SDTMap;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

@RequiredArgsConstructor
public class TracingImpl {
    private final Tracer tracer;
    private final Propagator propagator;

    public void injectTracingHeader(SDTMap headerMap) {
        if (tracer != null && headerMap != null) {
            Span span = tracer.currentSpan();
            if (span != null) {
                propagator.inject(span.context(), headerMap, this::putString);
            }
        }
    }


    public Consumer<Message<?>> wrapInTracingContext(SDTMap tracingHeader, Consumer<Message<?>> messageConsumer) {
        return (message) -> {
            Span.Builder extractedSpan = propagator.extract(tracingHeader, this::getString);
            Span span = extractedSpan.start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                messageConsumer.accept(message);
            } finally {
                span.end();
            }
        };
    }

    @SneakyThrows
    private void putString(@Nullable SDTMap map, String key, String value) {
        if (map != null && (TracingProxy.TRACE_PARENT.equals(key) || TracingProxy.TRACE_STATE.equals(key))) {
            map.putString(key, value);
        }
    }

    @SneakyThrows
    private String getString(@Nullable SDTMap map, String key) {
        if (map != null) {
            return map.getString(key);
        }
        return null;
    }
}

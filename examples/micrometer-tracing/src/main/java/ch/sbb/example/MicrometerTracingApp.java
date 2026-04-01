package ch.sbb.example;

import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SpringBootApplication
public class MicrometerTracingApp {
    private static final Logger log = LoggerFactory.getLogger(MicrometerTracingApp.class);
    // Stores the format: "PUBLISHER:TRACE_ID:SPAN_ID" / "CONSUMER:TRACE_ID:SPAN_ID"
    public static final BlockingQueue<String> TRACING_LOGS = new LinkedBlockingQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    
    // Spring Boot automatically configures the Tracer
    private final Tracer tracer;
    
    public MicrometerTracingApp(Tracer tracer) {
        this.tracer = tracer;
    }

    public static void main(String[] args) { SpringApplication.run(MicrometerTracingApp.class, args); }

    @Bean
    public Supplier<Message<String>> tracingPublisher() {
        return () -> {
            int c = count.incrementAndGet();
            if (c > 3) return null;
            
            // Create a trace log right before we return the message
            String currentTraceId = tracer.currentSpan() != null ? tracer.currentSpan().context().traceId() : "none";
            String currentSpanId = tracer.currentSpan() != null ? tracer.currentSpan().context().spanId() : "none";
            log.info("Publishing msg {}. TraceID: {}, SpanID: {}", c, currentTraceId, currentSpanId);
            TRACING_LOGS.offer("PUBLISHER:" + currentTraceId);

            return MessageBuilder.withPayload("msg-" + c).build();
        };
    }

    @Bean
    public Consumer<Message<String>> tracingConsumer() {
        return msg -> {
            String currentTraceId = tracer.currentSpan() != null ? tracer.currentSpan().context().traceId() : "none";
            String currentSpanId = tracer.currentSpan() != null ? tracer.currentSpan().context().spanId() : "none";
            log.info("Consumed {}. TraceID: {}, SpanID: {}", msg.getPayload(), currentTraceId, currentSpanId);
            TRACING_LOGS.offer("CONSUMER:" + currentTraceId);
        };
    }
}

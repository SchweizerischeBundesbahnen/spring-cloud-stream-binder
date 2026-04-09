package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class MicrometerTracingApp {
    private static final Logger log = LoggerFactory.getLogger(MicrometerTracingApp.class);
    public record TraceObservation(String origin, String payload, String traceId, String spanId) {}

    public static final BlockingQueue<TraceObservation> TRACING_LOGS = new LinkedBlockingQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    
    // Spring Boot automatically configures the Tracer
    private final Tracer tracer;
    private final StreamBridge streamBridge;
    
    public MicrometerTracingApp(Tracer tracer, StreamBridge streamBridge) {
        this.tracer = tracer;
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(MicrometerTracingApp.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        if (count.get() < 3) {
            int c = count.incrementAndGet();
            String payload = "msg-" + c;
            
            // Start a new span manually to observe trace propagation
            io.micrometer.tracing.Span newSpan = tracer.nextSpan().name("send-msg").start();
            try (io.micrometer.tracing.Tracer.SpanInScope ws = tracer.withSpan(newSpan)) {
                String currentTraceId = newSpan.context().traceId();
                String currentSpanId = newSpan.context().spanId();
                log.info("Publishing msg {}. TraceID: {}, SpanID: {}", c, currentTraceId, currentSpanId);
                TRACING_LOGS.offer(new TraceObservation("PUBLISHER", payload, currentTraceId, currentSpanId));
                
                streamBridge.send("tracingPublisher-out-0", MessageBuilder.withPayload(payload)
                        .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                        .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                        .build());
            } finally {
                newSpan.end();
            }
        }
    }

    @Bean
    public Consumer<Message<String>> tracingConsumer() {
        return msg -> {
            String currentTraceId = tracer.currentSpan() != null ? tracer.currentSpan().context().traceId() : "none";
            String currentSpanId = tracer.currentSpan() != null ? tracer.currentSpan().context().spanId() : "none";
            log.info("Consumed {}. TraceID: {}, SpanID: {}", msg.getPayload(), currentTraceId, currentSpanId);
            TRACING_LOGS.offer(new TraceObservation("CONSUMER", msg.getPayload(), currentTraceId, currentSpanId));
        };
    }
}

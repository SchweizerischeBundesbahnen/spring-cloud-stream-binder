package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
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
public class SolaceHeadersApp {
    private static final Logger log = LoggerFactory.getLogger(SolaceHeadersApp.class);
    public record ReceivedHeaders(String correlationId, String customOrgId, Long timeToLive, Boolean dmqEligible) {}

    public static final BlockingQueue<ReceivedHeaders> RECEIVED_HEADERS = new LinkedBlockingQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    private final StreamBridge streamBridge;

    public SolaceHeadersApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(SolaceHeadersApp.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        int index = count.getAndIncrement();
        Message<String> message = MessageBuilder.withPayload("custom-msg-" + index)
                // Standard Solace headers
                .setHeader(SolaceHeaders.CORRELATION_ID, "corr-id-" + index)
                .setHeader(SolaceHeaders.APPLICATION_MESSAGE_TYPE, "Example/Type")
                .setHeader(SolaceHeaders.PRIORITY, index % 255)
            .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
            .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                // User-defined properties (Spring automatically maps these)
                .setHeader("custom-org-id", "ORG-" + index)
                .build();
                
        streamBridge.send("headerPublisher-out-0", message);
        log.info("Published with CorrelationID: corr-id-{}", index);
    }

    @Bean
    public Consumer<Message<String>> headerConsumer() {
        return msg -> {
            String correlationId = msg.getHeaders().get(SolaceHeaders.CORRELATION_ID, String.class);
            String customOrg = msg.getHeaders().get("custom-org-id", String.class);
            Long timeToLive = msg.getHeaders().get(SolaceHeaders.TIME_TO_LIVE, Long.class);
            Boolean dmqEligible = msg.getHeaders().get(SolaceHeaders.DMQ_ELIGIBLE, Boolean.class);
            
            log.info("Received {} | CorrelationID={} | TTL={} | dmqEligible={} | custom-org-id={} (should be null due to headerExclusions)",
                    msg.getPayload(), correlationId, timeToLive, dmqEligible, customOrg);

            RECEIVED_HEADERS.offer(new ReceivedHeaders(correlationId, customOrg, timeToLive, dmqEligible));
        };
    }
}

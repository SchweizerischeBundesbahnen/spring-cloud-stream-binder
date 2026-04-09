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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class RedeliveryApp {
    private static final Logger log = LoggerFactory.getLogger(RedeliveryApp.class);
    public static final AtomicInteger TOTAL_ATTEMPTS = new AtomicInteger(0);
    public static final AtomicInteger BROKER_REDELIVERY_COUNT = new AtomicInteger(0);
    public static final AtomicInteger SUCCESS_COUNT = new AtomicInteger(0);
    private final AtomicBoolean published = new AtomicBoolean();
    private final StreamBridge streamBridge;

    public RedeliveryApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(RedeliveryApp.class, args); }

    @Scheduled(initialDelay = 1000, fixedDelay = 60000)
    public void publish() {
        if (published.compareAndSet(false, true)) {
            streamBridge.send("retryProducer-out-0", MessageBuilder.withPayload("retry-me")
                    .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                    .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                    .build());
            log.info("Published message designed for retry and broker redelivery");
        }
    }

    @Bean
    public Consumer<Message<String>> retryConsumer() {
        return msg -> {
            boolean redelivered = Boolean.TRUE.equals(msg.getHeaders().get(SolaceHeaders.REDELIVERED, Boolean.class));
            int count = TOTAL_ATTEMPTS.incrementAndGet();
            if (redelivered) {
                BROKER_REDELIVERY_COUNT.incrementAndGet();
            }

            log.info("Attempt {}: {} (redelivered: {})", count, msg.getPayload(), redelivered);
            if (!redelivered) {
                throw new RuntimeException("Force broker redelivery after exhausting local retries");
            }

            log.info("Succeeded after broker redelivery on attempt {}", count);
            SUCCESS_COUNT.incrementAndGet();
        };
    }
}

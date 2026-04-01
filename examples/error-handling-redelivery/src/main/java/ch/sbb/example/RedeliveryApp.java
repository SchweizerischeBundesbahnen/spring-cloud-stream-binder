package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class RedeliveryApp {
    private static final Logger log = LoggerFactory.getLogger(RedeliveryApp.class);
    public static final AtomicInteger ATTEMPT_COUNT = new AtomicInteger(0);
    public static final AtomicInteger SUCCESS_COUNT = new AtomicInteger(0);
    private final StreamBridge streamBridge;

    public RedeliveryApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(RedeliveryApp.class, args); }

    @Scheduled(fixedRate = 10000)
    public void publish() {
        streamBridge.send("retryProducer-out-0", "retry-me");
        log.info("Published message designed for retry");
    }

    @Bean
    public Consumer<Message<String>> retryConsumer() {
        return msg -> {
            int count = ATTEMPT_COUNT.incrementAndGet();
            log.info("Attempt {}: {} (redelivered: {})", count, msg.getPayload(), msg.getHeaders().get(com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders.REDELIVERED));
            if (count < 3) {
                throw new RuntimeException("Retry attempt " + count);
            }
            log.info("Succeeded on attempt {}", count);
            SUCCESS_COUNT.incrementAndGet();
            ATTEMPT_COUNT.set(0); // reset for the next run
        };
    }
}

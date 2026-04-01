package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class ErrorQueueApp {
    private static final Logger log = LoggerFactory.getLogger(ErrorQueueApp.class);
    public static final AtomicInteger ATTEMPT_COUNT = new AtomicInteger(0);
    private final StreamBridge streamBridge;

    public ErrorQueueApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(ErrorQueueApp.class, args); }

    @Scheduled(fixedRate = 5000)
    public void publishErrorTrigger() {
        streamBridge.send("errorProducer-out-0", "fail-me");
        log.info("Published message that will fail");
    }

    @Bean
    public Consumer<String> failingConsumer() {
        return msg -> {
            int attempt = ATTEMPT_COUNT.incrementAndGet();
            log.info("Attempt {} for: {}", attempt, msg);
            throw new RuntimeException("Intentional failure for error queue demo");
        };
    }
}

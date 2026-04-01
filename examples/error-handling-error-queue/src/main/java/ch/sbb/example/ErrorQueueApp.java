package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SpringBootApplication
public class ErrorQueueApp {
    private static final Logger log = LoggerFactory.getLogger(ErrorQueueApp.class);
    public static final AtomicInteger ATTEMPT_COUNT = new AtomicInteger(0);

    public static void main(String[] args) { SpringApplication.run(ErrorQueueApp.class, args); }

    @Bean public Supplier<String> errorProducer() { return () -> "fail-me"; }

    @Bean
    public Consumer<String> failingConsumer() {
        return msg -> {
            int attempt = ATTEMPT_COUNT.incrementAndGet();
            log.info("Attempt {} for: {}", attempt, msg);
            throw new RuntimeException("Intentional failure for error queue demo");
        };
    }
}

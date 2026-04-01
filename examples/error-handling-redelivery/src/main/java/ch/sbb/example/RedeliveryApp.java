package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import org.springframework.messaging.Message;


import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SpringBootApplication
public class RedeliveryApp {
    private static final Logger log = LoggerFactory.getLogger(RedeliveryApp.class);
    public static final AtomicInteger ATTEMPT_COUNT = new AtomicInteger(0);
    public static final AtomicInteger SUCCESS_COUNT = new AtomicInteger(0);

    public static void main(String[] args) { SpringApplication.run(RedeliveryApp.class, args); }

    @Bean public Supplier<String> retryProducer() { return () -> "retry-me"; }

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
        };
    }
}

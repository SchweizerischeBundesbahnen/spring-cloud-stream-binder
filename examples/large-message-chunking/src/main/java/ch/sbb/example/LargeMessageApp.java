package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SpringBootApplication
public class LargeMessageApp {
    private static final Logger log = LoggerFactory.getLogger(LargeMessageApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    
    public static void main(String[] args) { SpringApplication.run(LargeMessageApp.class, args); }

    @Bean
    public Supplier<String> chunkedPublisher() {
        return () -> {
            // Generate a 1MB payload
            return "A".repeat(1024 * 1024);
        };
    }

    @Bean
    public Consumer<String> chunkedConsumer() {
        return msg -> {
            log.info("Received large message of length: {}", msg.length());
            RECEIVED.offer(msg);
        };
    }
}

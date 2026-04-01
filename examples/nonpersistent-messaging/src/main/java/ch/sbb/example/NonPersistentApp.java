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
public class NonPersistentApp {
    private static final Logger log = LoggerFactory.getLogger(NonPersistentApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();

    public static void main(String[] args) { SpringApplication.run(NonPersistentApp.class, args); }

    @Bean
    public Supplier<String> directPublisher() {
        return () -> "direct-msg-" + System.currentTimeMillis();
    }

    @Bean
    public Consumer<String> directConsumer() {
        return msg -> {
            log.info("Received Direct msg: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

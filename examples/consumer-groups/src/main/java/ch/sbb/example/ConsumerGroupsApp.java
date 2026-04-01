package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SpringBootApplication
public class ConsumerGroupsApp {
    private static final Logger log = LoggerFactory.getLogger(ConsumerGroupsApp.class);
    public static final BlockingQueue<String> DURABLE = new LinkedBlockingQueue<>();
    public static final BlockingQueue<String> ANON = new LinkedBlockingQueue<>();

    public static void main(String[] args) {
        SpringApplication.run(ConsumerGroupsApp.class, args);
    }

    @Bean public Supplier<String> publisher() { return () -> "group-test-" + System.currentTimeMillis(); }
    @Bean public Consumer<String> durableConsumer() { return msg -> { log.info("Durable: {}", msg); DURABLE.offer(msg); }; }
    @Bean public Consumer<String> anonConsumer() { return msg -> { log.info("Anon: {}", msg); ANON.offer(msg); }; }
}

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
public class MultiBinderApp {
    private static final Logger log = LoggerFactory.getLogger(MultiBinderApp.class);
    public static final BlockingQueue<String> RECEIVED_1 = new LinkedBlockingQueue<>();
    public static final BlockingQueue<String> RECEIVED_2 = new LinkedBlockingQueue<>();
    
    public static void main(String[] args) { SpringApplication.run(MultiBinderApp.class, args); }

    private final java.util.concurrent.atomic.AtomicInteger count1 = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger count2 = new java.util.concurrent.atomic.AtomicInteger();

    @Bean
    public Supplier<String> fromBroker1() {
        return () -> "msg-to-broker1-" + count1.incrementAndGet();
    }

    @Bean
    public Supplier<String> fromBroker2() {
        return () -> "msg-to-broker2-" + count2.incrementAndGet();
    }

    @Bean
    public Consumer<String> toBroker1() {
        return msg -> {
            log.info("Received from Broker 1: {}", msg);
            RECEIVED_1.offer(msg);
        };
    }

    @Bean
    public Consumer<String> toBroker2() {
        return msg -> {
            log.info("Received from Broker 2: {}", msg);
            RECEIVED_2.offer(msg);
        };
    }
}

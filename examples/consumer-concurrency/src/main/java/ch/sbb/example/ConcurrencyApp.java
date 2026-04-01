package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;

import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SpringBootApplication
public class ConcurrencyApp {
    private static final Logger log = LoggerFactory.getLogger(ConcurrencyApp.class);
    public static final BlockingQueue<String> THREADS = new LinkedBlockingQueue<>();

    public static void main(String[] args) { SpringApplication.run(ConcurrencyApp.class, args); }

    @Bean
    public Supplier<Message<String>> fastPublisher() {
        int[] count = {0};
        return () -> {
            if (count[0]++ < 20) {
                return MessageBuilder.withPayload("msg-" + count[0]).build();
            }
            return null;
        };
    }

    @Bean
    public Consumer<String> concurrentConsumer() {
        return msg -> {
            THREADS.offer(Thread.currentThread().getName());
            log.info("Thread {} processing {}", Thread.currentThread().getName(), msg);
        };
    }
}

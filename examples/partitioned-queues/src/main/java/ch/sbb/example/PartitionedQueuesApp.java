package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SpringBootApplication
public class PartitionedQueuesApp {
    private static final Logger log = LoggerFactory.getLogger(PartitionedQueuesApp.class);
    public static final java.util.Map<String, String> MSG_TO_THREAD = new java.util.concurrent.ConcurrentHashMap<>();
    
    private final AtomicInteger count = new AtomicInteger();

    public static void main(String[] args) { SpringApplication.run(PartitionedQueuesApp.class, args); }

    @Bean
    public Supplier<Message<String>> partitionedPublisher() {
        return () -> {
            int current = count.incrementAndGet();
            String key = (current % 2 == 0) ? "Key-A" : "Key-B";
            Message<String> msg = MessageBuilder.withPayload("msg-" + current)
                    .setHeader(SolaceBinderHeaders.PARTITION_KEY, key)
                    .build();
            System.out.println("PUBLISHING: " + msg.getPayload() + " with key: " + key);
            return msg;
        };
    }

    @Bean
    public Consumer<Message<String>> partitionedConsumer() {
        return msg -> {
            String payload = msg.getPayload();
            String thread = Thread.currentThread().getName();
            log.info("Received '{}' on thread '{}'", payload, thread);
            MSG_TO_THREAD.put(payload, thread);
        };
    }
}

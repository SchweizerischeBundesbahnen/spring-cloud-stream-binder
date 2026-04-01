package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class PartitionedQueuesApp {
    private static final Logger log = LoggerFactory.getLogger(PartitionedQueuesApp.class);
    // Track which instance processed which message to assert partitioning correctness
    public static final BlockingQueue<String> RECEIVED_INSTANCE_0 = new LinkedBlockingQueue<>();
    public static final BlockingQueue<String> RECEIVED_INSTANCE_1 = new LinkedBlockingQueue<>();
    
    // Some mock data to partition by
    private static final String[] PAYLOADS = {"apple", "banana", "cherry", "date"};
    private final AtomicInteger count = new AtomicInteger();
    private final StreamBridge streamBridge;

    public PartitionedQueuesApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(PartitionedQueuesApp.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        int index = count.getAndIncrement() % PAYLOADS.length;
        String payload = PAYLOADS[index];
        // The binder uses the payload string as the partition key natively
        Message<String> msg = MessageBuilder.withPayload(payload).build();
        streamBridge.send("partitionedPublisher-out-0", msg);
        log.info("Published: {}", payload);
    }

    @Bean
    public Consumer<String> consumerInstance0() {
        return msg -> {
            log.info("Instance 0 processing {}", msg);
            RECEIVED_INSTANCE_0.offer(msg);
        };
    }

    @Bean
    public Consumer<String> consumerInstance1() {
        return msg -> {
            log.info("Instance 1 processing {}", msg);
            RECEIVED_INSTANCE_1.offer(msg);
        };
    }
}

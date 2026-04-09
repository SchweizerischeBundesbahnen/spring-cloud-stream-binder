package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class ConsumerGroupsApp {
    private static final Logger log = LoggerFactory.getLogger(ConsumerGroupsApp.class);
    public static final BlockingQueue<String> DURABLE = new LinkedBlockingQueue<>();
    public static final BlockingQueue<String> ANON = new LinkedBlockingQueue<>();
    private final AtomicInteger count = new AtomicInteger(1);
    private final StreamBridge streamBridge;

    public ConsumerGroupsApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(ConsumerGroupsApp.class, args); }

    @Scheduled(fixedRate = 2000)
    public void publish() {
        String msg = "group-msg-" + count.getAndIncrement();
        streamBridge.send("publisher-out-0", MessageBuilder.withPayload(msg)
                .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                .build());
        log.info("Published: {}", msg);
    }

    @Bean
    public Consumer<String> queuedConsumer() {
        return msg -> {
            log.info("Durable consumer received: {}", msg);
            DURABLE.offer(msg);
        };
    }

    @Bean
    public Consumer<String> anonConsumer() {
        return msg -> {
            log.info("Anonymous consumer received: {}", msg);
            ANON.offer(msg);
        };
    }
}

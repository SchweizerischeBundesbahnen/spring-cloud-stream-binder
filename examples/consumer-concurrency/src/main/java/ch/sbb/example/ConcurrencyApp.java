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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class ConcurrencyApp {
    private static final Logger log = LoggerFactory.getLogger(ConcurrencyApp.class);
    public static final BlockingQueue<String> THREADS = new LinkedBlockingQueue<>();
    private final AtomicBoolean burstPublished = new AtomicBoolean();
    private final StreamBridge streamBridge;

    public ConcurrencyApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(ConcurrencyApp.class, args); }

    @Scheduled(initialDelay = 1000, fixedDelay = 60000)
    public void publishBurst() {
        if (burstPublished.compareAndSet(false, true)) {
            for (int messageIndex = 1; messageIndex <= 20; messageIndex++) {
                String payload = "msg-" + messageIndex;
                streamBridge.send("fastPublisher-out-0", MessageBuilder.withPayload(payload)
                        .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                        .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                        .build());
            }
            log.info("Finished publishing 20 messages in burst");
        }
    }

    @Bean
    public Consumer<String> concurrentConsumer() {
        return msg -> {
            THREADS.offer(Thread.currentThread().getName());
            log.info("Thread {} processing {}", Thread.currentThread().getName(), msg);
        };
    }
}

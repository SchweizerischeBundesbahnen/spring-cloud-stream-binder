package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class QueueProvisioningApp {
    private static final Logger log = LoggerFactory.getLogger(QueueProvisioningApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();

    /**
     * One message per subscription on the single custom-named queue: the binding's own destination,
     * a topic matched by the {@code example/extra/>} wildcard, and the exact {@code example/more} topic.
     */
    static final Map<String, String> PAYLOAD_PER_TOPIC = Map.of(
            "example/primary/topic", "message-from-primary-topic",
            "example/extra/subtopic", "message-from-extra-subtopic",
            "example/more", "message-from-more-topic");

    private final AtomicBoolean published = new AtomicBoolean();
    private final StreamBridge streamBridge;

    public QueueProvisioningApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(QueueProvisioningApp.class, args); }

    @Scheduled(initialDelay = 1000, fixedDelay = 60000)
    public void publishOnePerSubscription() {
        if (!published.compareAndSet(false, true)) {
            return;
        }
        PAYLOAD_PER_TOPIC.forEach((topic, payload) -> {
            // StreamBridge.send(...) can throw a MessagingException (e.g. once the producer's sendRetryTimeoutMs window
            // is exhausted), so always wrap the publish in a try/catch even though the binder retries transient failures.
            try {
                streamBridge.send(topic, MessageBuilder.withPayload(payload)
                        .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                        .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                        .build());
                log.info("Published {} to {}", payload, topic);
            } catch (MessagingException e) {
                log.error("Failed to publish {} to {}", payload, topic, e);
            }
        });
    }

    @Bean
    public Consumer<String> customQueueConsumer() {
        return msg -> {
            log.info("Received from custom generated queue: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class MultiBinderApp {
    private static final Logger log = LoggerFactory.getLogger(MultiBinderApp.class);
    public static final BlockingQueue<String> RECEIVED_1 = new LinkedBlockingQueue<>();
    public static final BlockingQueue<String> RECEIVED_2 = new LinkedBlockingQueue<>();

    private final AtomicInteger count = new AtomicInteger(1);
    private final StreamBridge streamBridge;

    public MultiBinderApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(MultiBinderApp.class, args); }

    @Scheduled(fixedRate = 1000)
    public void publishToBrokers() {
        int c = count.getAndIncrement();
        // StreamBridge.send(...) can throw a MessagingException (e.g. once the producer's sendRetryTimeoutMs window is
        // exhausted), so always wrap the publish in a try/catch even though the binder retries transient failures.
        String msg1 = "msg-to-broker1-" + c;
        try {
            streamBridge.send("fromBroker1-out-0", MessageBuilder.withPayload(msg1)
                .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                .build());
            log.info("Published to Broker 1: {}", msg1);
        } catch (MessagingException e) {
            log.error("Failed to publish to Broker 1: {}", msg1, e);
        }

        String msg2 = "msg-to-broker2-" + c;
        try {
            streamBridge.send("fromBroker2-out-0", MessageBuilder.withPayload(msg2)
                .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                .build());
            log.info("Published to Broker 2: {}", msg2);
        } catch (MessagingException e) {
            log.error("Failed to publish to Broker 2: {}", msg2, e);
        }
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

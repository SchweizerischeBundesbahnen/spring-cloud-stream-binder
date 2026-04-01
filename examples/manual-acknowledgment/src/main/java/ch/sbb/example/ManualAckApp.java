package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.acks.AcknowledgmentCallback.Status;
import org.springframework.messaging.Message;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class ManualAckApp {
    private static final Logger log = LoggerFactory.getLogger(ManualAckApp.class);
    public static final BlockingQueue<String> ACCEPTED = new LinkedBlockingQueue<>();
    public static final AtomicInteger MSG_COUNT = new AtomicInteger(0);
    private final StreamBridge streamBridge;

    public ManualAckApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(ManualAckApp.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        streamBridge.send("producer-out-0", "msg-" + MSG_COUNT.incrementAndGet());
    }

    @Bean
    public Consumer<Message<String>> manualAckConsumer() {
        return msg -> {
            AcknowledgmentCallback ack = msg.getHeaders().get(
                IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK, AcknowledgmentCallback.class);
            String payload = msg.getPayload();
            log.info("Processing: {}", payload);
            if (ack != null) {
                ack.acknowledge(Status.ACCEPT);
                ACCEPTED.offer(payload);
            }
        };
    }
}

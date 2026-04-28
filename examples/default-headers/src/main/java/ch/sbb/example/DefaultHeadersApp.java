package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
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

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class DefaultHeadersApp {
    private static final Logger log = LoggerFactory.getLogger(DefaultHeadersApp.class);
    public record ReceivedHeaders(String customDefaultHeader, String overridingHeader, Long timeToLive, String senderId) {}

    public static final BlockingQueue<ReceivedHeaders> RECEIVED_HEADERS = new LinkedBlockingQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    private final StreamBridge streamBridge;

    public DefaultHeadersApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(DefaultHeadersApp.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        int index = count.getAndIncrement();
        Message<String> message;

        if (index % 2 == 0) {
            // Will fallback to default header
            message = MessageBuilder.withPayload("custom-msg-" + index)
                    .build();
        } else {
            // Will override default header
            message = MessageBuilder.withPayload("custom-msg-" + index)
                    .setHeader("custom-default-header", "overridden-value")
                    .build();
        }

        streamBridge.send("headerPublisher-out-0", message);
        log.info("Published message {}", index);
    }

    @Bean
    public Consumer<Message<String>> headerConsumer() {
        return msg -> {
            String customDefaultHeader = msg.getHeaders().get("custom-default-header", String.class);
            String overridingHeader = customDefaultHeader;
            Long timeToLive = msg.getHeaders().get(SolaceHeaders.TIME_TO_LIVE, Long.class);
            String senderId = msg.getHeaders().get(SolaceHeaders.SENDER_ID, String.class);

            log.info("Received {} | custom-default-header={} | timeToLive={} | senderId={}",
                msg.getPayload(), customDefaultHeader, timeToLive, senderId);

            RECEIVED_HEADERS.offer(new ReceivedHeaders(customDefaultHeader, overridingHeader, timeToLive, senderId));
        };
    }
}

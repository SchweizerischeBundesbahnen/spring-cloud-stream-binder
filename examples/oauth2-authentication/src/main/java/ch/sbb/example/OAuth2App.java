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
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class OAuth2App {
    private static final Logger log = LoggerFactory.getLogger(OAuth2App.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private final StreamBridge streamBridge;

    public OAuth2App(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(OAuth2App.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        // StreamBridge.send(...) can throw a MessagingException (e.g. once the producer's sendRetryTimeoutMs window is
        // exhausted), so always wrap the publish in a try/catch even though the binder retries transient failures.
        try {
            streamBridge.send("oauthPublisher-out-0", MessageBuilder.withPayload("oauth-secured-msg")
                    .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                    .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                    .build());
            log.info("Published authenticated message");
        } catch (MessagingException e) {
            log.error("Failed to publish authenticated message", e);
        }
    }

    @Bean
    public Consumer<String> oauthConsumer() {
        return msg -> {
            log.info("Received authenticated message: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

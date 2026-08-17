package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

@SpringBootApplication
@RestController
public class PauseResumeApp {
    private static final Logger log = LoggerFactory.getLogger(PauseResumeApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private final StreamBridge streamBridge;

    public PauseResumeApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(PauseResumeApp.class, args); }

    @PostMapping("/send")
    public ResponseEntity<String> publish(@RequestBody String payload) {
        // StreamBridge.send(...) can throw a MessagingException (e.g. once the producer's sendRetryTimeoutMs window is
        // exhausted, or while the producer binding is paused). Publishing straight from this request thread, so always
        // catch it and return a proper HTTP error response instead of letting the request fail unhandled.
        try {
            streamBridge.send("example/pausable/topic", MessageBuilder.withPayload(payload)
                    .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                    .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                    .build());
            log.info("Published to pausable topic: {}", payload);
            return ResponseEntity.ok("Sent " + payload);
        } catch (MessagingException e) {
            log.error("Failed to publish to pausable topic: {}", payload, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Failed to send " + payload + ": " + e.getMessage());
        }
    }

    @Bean
    public Consumer<String> pausableConsumer() {
        return msg -> {
            log.info("Received from pausable consumer: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

@SpringBootApplication
@RestController
public class DynamicDestinationsApp {

    private static final Logger log = LoggerFactory.getLogger(DynamicDestinationsApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();

    private final StreamBridge streamBridge;

    public DynamicDestinationsApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) {
        SpringApplication.run(DynamicDestinationsApp.class, args);
    }

    @PostMapping("/send")
    public String publish(@RequestParam String destination, @RequestBody String payload) {
        log.info("Publishing to dynamic destination '{}': {}", destination, payload);
        streamBridge.send(destination, MessageBuilder.withPayload(payload)
                .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                .build());
        return "Sent to " + destination;
    }

    @Bean
    public Consumer<String> wildcardConsumer() {
        return msg -> {
            log.info("Received from wildcard consumer: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

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
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class NonPersistentApp {
    private static final Logger log = LoggerFactory.getLogger(NonPersistentApp.class);
    public record ReceivedDirectMessage(String payload, Boolean discardIndication) {}

    public static final BlockingQueue<ReceivedDirectMessage> RECEIVED = new LinkedBlockingQueue<>();
    private final StreamBridge streamBridge;

    public NonPersistentApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(NonPersistentApp.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        String msg = "direct-msg-" + System.currentTimeMillis();
        streamBridge.send("directPublisher-out-0", MessageBuilder.withPayload(msg)
                .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                .build());
        log.info("Published Direct msg: {}", msg);
    }

    @Bean
    public Consumer<Message<String>> directConsumer() {
        return msg -> {
            Boolean discardIndication = msg.getHeaders().get(SolaceHeaders.DISCARD_INDICATION, Boolean.class);
            log.info("Received Direct msg: {} | discardIndication={}", msg.getPayload(), discardIndication);
            RECEIVED.offer(new ReceivedDirectMessage(msg.getPayload(), discardIndication));
        };
    }
}

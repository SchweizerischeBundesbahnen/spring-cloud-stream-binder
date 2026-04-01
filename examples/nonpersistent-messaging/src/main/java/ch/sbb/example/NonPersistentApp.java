package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class NonPersistentApp {
    private static final Logger log = LoggerFactory.getLogger(NonPersistentApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private final StreamBridge streamBridge;

    public NonPersistentApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(NonPersistentApp.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        String msg = "direct-msg-" + System.currentTimeMillis();
        streamBridge.send("directPublisher-out-0", msg);
        log.info("Published Direct msg: {}", msg);
    }

    @Bean
    public Consumer<String> directConsumer() {
        return msg -> {
            log.info("Received Direct msg: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

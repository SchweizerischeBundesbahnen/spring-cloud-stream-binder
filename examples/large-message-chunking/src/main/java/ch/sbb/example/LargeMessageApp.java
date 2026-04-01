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
public class LargeMessageApp {
    private static final Logger log = LoggerFactory.getLogger(LargeMessageApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private final StreamBridge streamBridge;
    private final String largePayload;
    
    public LargeMessageApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
        this.largePayload = "A".repeat(1024 * 1024); // 1 MB payload
    }

    public static void main(String[] args) { SpringApplication.run(LargeMessageApp.class, args); }

    @Scheduled(fixedRate = 5000)
    public void publishLargeMessage() {
        streamBridge.send("chunkedPublisher-out-0", largePayload);
        log.info("Published large message of {} bytes", largePayload.length());
    }

    @Bean
    public Consumer<String> chunkedConsumer() {
        return msg -> {
            log.info("Received large message of length: {}", msg.length());
            RECEIVED.offer(msg);
        };
    }
}

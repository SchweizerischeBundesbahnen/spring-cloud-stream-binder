package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

@SpringBootApplication
public class QueueProvisioningApp {
    private static final Logger log = LoggerFactory.getLogger(QueueProvisioningApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();

    public static void main(String[] args) { SpringApplication.run(QueueProvisioningApp.class, args); }

    @Bean
    public Consumer<String> customQueueConsumer() {
        return msg -> {
            log.info("Received from custom generated queue: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

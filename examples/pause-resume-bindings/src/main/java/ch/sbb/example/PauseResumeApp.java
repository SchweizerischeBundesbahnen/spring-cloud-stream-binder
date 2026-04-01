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
public class PauseResumeApp {
    private static final Logger log = LoggerFactory.getLogger(PauseResumeApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();

    public static void main(String[] args) { SpringApplication.run(PauseResumeApp.class, args); }

    @Bean
    public Consumer<String> pausableConsumer() {
        return msg -> {
            log.info("Received from pausable consumer: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

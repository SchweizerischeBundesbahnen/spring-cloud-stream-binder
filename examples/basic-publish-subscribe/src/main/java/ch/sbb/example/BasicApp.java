package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SpringBootApplication
public class BasicApp {
    private static final Logger log = LoggerFactory.getLogger(BasicApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();

    public static void main(String[] args) {
        SpringApplication.run(BasicApp.class, args);
    }

    @Bean
    public Supplier<String> source() {
        return () -> "Hello from Solace at " + System.currentTimeMillis();
    }

    @Bean
    public Consumer<String> sink() {
        return msg -> {
            log.info("Received: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

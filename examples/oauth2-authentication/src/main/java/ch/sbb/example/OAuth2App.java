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
public class OAuth2App {
    private static final Logger log = LoggerFactory.getLogger(OAuth2App.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();

    public static void main(String[] args) { SpringApplication.run(OAuth2App.class, args); }

    @Bean
    public Supplier<String> oauthPublisher() {
        return () -> "oauth-secured-msg";
    }

    @Bean
    public Consumer<String> oauthConsumer() {
        return msg -> {
            log.info("Received authenticated message: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

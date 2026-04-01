package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@SpringBootApplication
public class ProcessorApp {
    private static final Logger log = LoggerFactory.getLogger(ProcessorApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();

    public static void main(String[] args) {
        SpringApplication.run(ProcessorApp.class, args);
    }

    @Bean
    public Supplier<String> source() {
        return () -> "hello world";
    }

    @Bean
    public Function<String, String> uppercase() {
        return String::toUpperCase;
    }

    @Bean
    public Consumer<String> sink() {
        return msg -> {
            log.info("Received transformed: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

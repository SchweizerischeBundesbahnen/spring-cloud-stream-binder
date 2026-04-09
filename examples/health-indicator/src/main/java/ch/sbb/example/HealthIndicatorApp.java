package ch.sbb.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.function.Consumer;

@SpringBootApplication
public class HealthIndicatorApp {

    public static void main(String[] args) { SpringApplication.run(HealthIndicatorApp.class, args); }

    @Bean
    public Consumer<String> healthConsumer() {
        return msg -> {};
    }
}

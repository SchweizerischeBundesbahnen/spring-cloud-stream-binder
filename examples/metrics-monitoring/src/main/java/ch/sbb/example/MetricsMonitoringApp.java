package ch.sbb.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SpringBootApplication
public class MetricsMonitoringApp {
    private static final Logger log = LoggerFactory.getLogger(MetricsMonitoringApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private final AtomicInteger count = new AtomicInteger();

    public static void main(String[] args) { SpringApplication.run(MetricsMonitoringApp.class, args); }

    @Bean
    public Supplier<String> metricsPublisher() {
        return () -> {
            int c = count.incrementAndGet();
            if (c > 5) return null;
            return "msg-" + c;
        };
    }

    @Bean
    public Consumer<String> metricsConsumer() {
        return msg -> {
            log.info("Received metric msg: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

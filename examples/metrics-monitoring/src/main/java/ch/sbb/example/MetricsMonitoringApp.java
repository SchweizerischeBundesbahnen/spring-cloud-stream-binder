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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class MetricsMonitoringApp {
    private static final Logger log = LoggerFactory.getLogger(MetricsMonitoringApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    private final StreamBridge streamBridge;

    public MetricsMonitoringApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(MetricsMonitoringApp.class, args); }

    @Scheduled(fixedRate = 1000)
    public void publish() {
        if (count.get() < 5) {
            int c = count.incrementAndGet();
            streamBridge.send("metricsPublisher-out-0", "msg-" + c);
            log.info("Published metric msg: {}", "msg-" + c);
        }
    }

    @Bean
    public Consumer<String> metricsConsumer() {
        return msg -> {
            log.info("Received metric msg: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

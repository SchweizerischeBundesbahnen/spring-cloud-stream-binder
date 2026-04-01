package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SpringBootApplication
public class SolaceHeadersApp {
    private static final Logger log = LoggerFactory.getLogger(SolaceHeadersApp.class);
    public static final BlockingQueue<Message<String>> RECEIVED = new LinkedBlockingQueue<>();
    
    public static void main(String[] args) { SpringApplication.run(SolaceHeadersApp.class, args); }

    @Bean
    public Supplier<Message<String>> headerPublisher() {
        return () -> MessageBuilder.withPayload("Headers demo payload")
                    .setHeader(SolaceHeaders.CORRELATION_ID, "corr-123")
                    .setHeader(SolaceHeaders.PRIORITY, 100)
                    .setHeader(SolaceHeaders.TIME_TO_LIVE, 60000L)
                    .setHeader(SolaceHeaders.APPLICATION_MESSAGE_TYPE, "demo/json")
                    .setHeader(SolaceHeaders.SENDER_TIMESTAMP, System.currentTimeMillis())
                    .setHeader("custom-header", "should-be-excluded")
                    .build();
    }

    @Bean
    public Consumer<Message<String>> headerConsumer() {
        return msg -> {
            log.info("Received with headers: {}", msg.getHeaders());
            RECEIVED.offer(msg);
        };
    }
}

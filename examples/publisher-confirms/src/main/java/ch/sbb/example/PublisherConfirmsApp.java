package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import com.solace.spring.cloud.stream.binder.util.CorrelationData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@SpringBootApplication
public class PublisherConfirmsApp {
    private static final Logger log = LoggerFactory.getLogger(PublisherConfirmsApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private final PublishService publishService;

    public PublisherConfirmsApp(PublishService publishService) {
        this.publishService = publishService;
    }

    public static void main(String[] args) { SpringApplication.run(PublisherConfirmsApp.class, args); }

    @EventListener(ApplicationReadyEvent.class)
    public void publishStartupSample() {
        try {
            boolean confirmed = publishService.publishAndWait("startup-confirm-msg");
            log.info("Published startup-confirm-msg and broker confirmation result: {}", confirmed);
        } catch (Exception e) {
            log.error("Failed to publish startup-confirm-msg", e);
        }
    }

    @Bean
    public Consumer<String> confirmConsumer() {
        return msg -> {
            log.info("Received: {}", msg);
            RECEIVED.offer(msg);
        };
    }
}

@Service
class PublishService {
    private final StreamBridge streamBridge;
    
    public PublishService(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }
    
    public boolean publishAndWait(String payload) throws Exception {
        CorrelationData correlationData = new CorrelationData();
        Message<String> msg = MessageBuilder.withPayload(payload)
                .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                .setHeader(SolaceBinderHeaders.CONFIRM_CORRELATION, correlationData)
                .build();
        
        streamBridge.send("confirmPublisher-out-0", msg);
        
        try {
            // Wait for broker ACK
            correlationData.getFuture().get(5, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }
}

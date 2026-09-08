package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class NullPayloadApp {
    private static final Logger log = LoggerFactory.getLogger(NullPayloadApp.class);

    public record ReceivedPayload(String payload, boolean nullPayload) {}

    public static final BlockingQueue<ReceivedPayload> RECEIVED_PAYLOADS = new LinkedBlockingQueue<>();

    private static final String TOPIC = "example/nullpayload/topic";

    private final AtomicBoolean published = new AtomicBoolean();
    private final StreamBridge streamBridge;
    private final JCSMPSession jcsmpSession;

    public NullPayloadApp(StreamBridge streamBridge, JCSMPSession jcsmpSession) {
        this.streamBridge = streamBridge;
        this.jcsmpSession = jcsmpSession;
    }

    public static void main(String[] args) {
        SpringApplication.run(NullPayloadApp.class, args);
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 60000)
    public void publishOneOrdinaryAndOnePayloadLessMessage() throws JCSMPException {
        if (!published.compareAndSet(false, true)) {
            return;
        }
        publishThroughTheBinding("order-42");
        publishWithoutAPayload();
    }

    private void publishThroughTheBinding(String payload) {
        // StreamBridge.send(...) can throw a MessagingException (e.g. once the producer's sendRetryTimeoutMs window is
        // exhausted), so always wrap the publish in a try/catch even though the binder retries transient failures.
        try {
            streamBridge.send("nullPayloadPublisher-out-0", MessageBuilder.withPayload(payload)
                    .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                    .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                    .build());
            log.info("Published {} through the producer binding", payload);
        } catch (MessagingException e) {
            log.error("Failed to publish {} through the producer binding", payload, e);
        }
    }

    /**
     * A producer binding always writes a payload, so it can never send the message this example is about.
     * The session bean the binder publishes is the shortest way to imitate the outside publisher — a REST
     * gateway, a JMS client, an MQTT bridge — that leaves the payload off entirely.
     */
    private void publishWithoutAPayload() throws JCSMPException {
        XMLMessageProducer producer = jcsmpSession.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
            @Override
            public void responseReceivedEx(Object key) {
                // The binder's own producer bindings handle confirmations; see the publisher-confirms example.
            }

            @Override
            public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
                log.error("Failed to publish the payload-less message", cause);
            }
        });
        try {
            producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class),
                    JCSMPFactory.onlyInstance().createTopic(TOPIC));
            log.info("Published a message with no payload at all");
        } finally {
            producer.close();
        }
    }

    @Bean
    public Consumer<Message<String>> nullPayloadConsumer() {
        return msg -> {
            boolean nullPayload = Boolean.TRUE.equals(msg.getHeaders().get(SolaceBinderHeaders.NULL_PAYLOAD, Boolean.class));
            String payload = msg.getPayload();
            log.info("Received '{}' | solace_scst_nullPayload={}", payload, nullPayload);
            RECEIVED_PAYLOADS.offer(new ReceivedPayload(payload, nullPayload));
        };
    }
}

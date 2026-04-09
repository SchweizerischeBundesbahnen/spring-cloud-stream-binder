package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.binding.BindingsLifecycleController;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class MaxUnacknowledgedMessagesApp {
    static final String BINDING_NAME = "loadBalancedConsumer-in-0";
    static final String DESTINATION = "example/max-unacknowledged/topic";
    static final String GROUP = "max-unacknowledged-group";
    static final String QUEUE_NAME = "scst/wk/" + GROUP + "/plain/" + DESTINATION;
    static final long MAX_DELIVERED_UNACKED_MSGS_PER_FLOW = 1;

    private static final Logger log = LoggerFactory.getLogger(MaxUnacknowledgedMessagesApp.class);
    private static final Map<String, AtomicInteger> PROCESSED_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> PROCESSED_MESSAGES = new ConcurrentHashMap<>();

    private final AtomicBoolean burstPublished = new AtomicBoolean();
    private final StreamBridge streamBridge;
    private final BindingsLifecycleController bindingsLifecycleController;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${app.instance-name:anonymous}")
    private String instanceName;

    @Value("${app.consumer.enabled:false}")
    private boolean consumerEnabled;

    @Value("${app.publisher.enabled:false}")
    private boolean publisherEnabled;

    @Value("${app.processing-delay-ms:0}")
    private long processingDelayMs;

    @Value("${app.total-messages:20}")
    private int totalMessages;

    @Value("${app.semp.host:http://localhost:8081}")
    private String sempHost;

    @Value("${app.semp.username:admin}")
    private String sempUsername;

    @Value("${app.semp.password:admin}")
    private String sempPassword;

    @Value("${solace.java.msgVpn}")
    private String msgVpn;

    public MaxUnacknowledgedMessagesApp(StreamBridge streamBridge,
                                        BindingsLifecycleController bindingsLifecycleController) {
        this.streamBridge = streamBridge;
        this.bindingsLifecycleController = bindingsLifecycleController;
    }

    public static void main(String[] args) {
        SpringApplication.run(MaxUnacknowledgedMessagesApp.class, args);
    }

    @Bean
    ApplicationRunner consumerQueueProvisioner() {
        return args -> {
            if (!consumerEnabled) {
                return;
            }

            ensureQueueWithConfiguredMaxDeliveredUnackedMsgsPerFlow();
            bindingsLifecycleController.start(BINDING_NAME);
        };
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 60000)
    public void publishBurst() {
        if (!publisherEnabled || !burstPublished.compareAndSet(false, true)) {
            return;
        }

        for (int messageIndex = 0; messageIndex < totalMessages; messageIndex++) {
            String payload = "msg-" + messageIndex;
            streamBridge.send("publisher-out-0", MessageBuilder.withPayload(payload)
                    .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                    .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                    .build());
            log.info("{} published {}", instanceName, payload);
        }
    }

    @Bean
    @ConditionalOnProperty(name = "app.consumer.enabled", havingValue = "true")
    public Consumer<String> loadBalancedConsumer() {
        return payload -> {
            if (processingDelayMs > 0) {
                try {
                    Thread.sleep(processingDelayMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while simulating consumer work", ex);
                }
            }

            PROCESSED_COUNTS.computeIfAbsent(instanceName, key -> new AtomicInteger()).incrementAndGet();
            PROCESSED_MESSAGES.computeIfAbsent(instanceName, key -> new CopyOnWriteArrayList<>()).add(payload);
            log.info("{} processed {}", instanceName, payload);
        };
    }

    static void reset() {
        PROCESSED_COUNTS.clear();
        PROCESSED_MESSAGES.clear();
    }

    static int processedBy(String name) {
        return PROCESSED_COUNTS.getOrDefault(name, new AtomicInteger()).get();
    }

    static int totalProcessed() {
        return PROCESSED_COUNTS.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    static List<String> messagesFor(String name) {
        return PROCESSED_MESSAGES.getOrDefault(name, List.of());
    }

    private void ensureQueueWithConfiguredMaxDeliveredUnackedMsgsPerFlow() throws IOException, InterruptedException {
        String queueUri = queueUri();
        HttpResponse<String> queueResponse = send(HttpRequest.newBuilder(URI.create(queueUri)).GET().build());

        if (queueResponse.statusCode() == 404 || isMissingQueueResponse(queueResponse)) {
            createExampleQueue();
            return;
        }

        if (queueResponse.statusCode() != 200) {
            throw new IllegalStateException("Failed to inspect example queue via SEMP: HTTP "
                    + queueResponse.statusCode() + " body=" + queueResponse.body());
        }

        String expectedMaxDeliveredUnackedMsgsPerFlow = "\"maxDeliveredUnackedMsgsPerFlow\":"
                + MAX_DELIVERED_UNACKED_MSGS_PER_FLOW;
        boolean queueMatchesExampleSettings = queueResponse.body().contains(expectedMaxDeliveredUnackedMsgsPerFlow)
                && queueResponse.body().contains("\"permission\":\"modify-topic\"")
                && queueResponse.body().contains("\"accessType\":\"non-exclusive\"")
                && queueResponse.body().contains("\"ingressEnabled\":true")
                && queueResponse.body().contains("\"egressEnabled\":true");
        if (!queueMatchesExampleSettings) {
            log.info("Recreating example queue {} so it uses maxDeliveredUnackedMsgsPerFlow={}",
                    QUEUE_NAME, MAX_DELIVERED_UNACKED_MSGS_PER_FLOW);
            HttpResponse<String> deleteResponse = send(HttpRequest.newBuilder(URI.create(queueUri)).DELETE().build());
            if (deleteResponse.statusCode() != 200 && deleteResponse.statusCode() != 204) {
                throw new IllegalStateException("Failed to delete example queue via SEMP: HTTP "
                        + deleteResponse.statusCode() + " body=" + deleteResponse.body());
            }
            createExampleQueue();
        }
    }

    private void createExampleQueue() throws IOException, InterruptedException {
        String requestBody = "{"
                + "\"queueName\":\"" + QUEUE_NAME + "\","
                + "\"accessType\":\"non-exclusive\","
                + "\"egressEnabled\":true,"
                + "\"ingressEnabled\":true,"
                + "\"permission\":\"modify-topic\","
                + "\"maxDeliveredUnackedMsgsPerFlow\":" + MAX_DELIVERED_UNACKED_MSGS_PER_FLOW
                + "}";
        log.info("Provisioning example queue {} via SEMP with maxDeliveredUnackedMsgsPerFlow={}",
                QUEUE_NAME, MAX_DELIVERED_UNACKED_MSGS_PER_FLOW);
        HttpResponse<String> createResponse = send(HttpRequest.newBuilder(URI.create(queuesUri()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build());
        if (createResponse.statusCode() != 200 && createResponse.statusCode() != 201) {
            throw new IllegalStateException("Failed to create example queue via SEMP: HTTP "
                    + createResponse.statusCode() + " body=" + createResponse.body());
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
                .header("Authorization", basicAuth());

        request.headers().map().forEach((headerName, values) -> values.forEach(value -> builder.header(headerName, value)));
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String basicAuth() {
        String credentials = sempUsername + ":" + sempPassword;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String queuesUri() {
        return trimTrailingSlash(sempHost) + "/SEMP/v2/config/msgVpns/" + encodedMsgVpn() + "/queues";
    }

    private String queueUri() {
        return queuesUri() + "/" + URLEncoder.encode(QUEUE_NAME, StandardCharsets.UTF_8);
    }

    private boolean isMissingQueueResponse(HttpResponse<String> response) {
        return response.statusCode() == 400 && response.body().contains("\"status\":\"NOT_FOUND\"");
    }

    private String encodedMsgVpn() {
        return URLEncoder.encode(msgVpn, StandardCharsets.UTF_8);
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
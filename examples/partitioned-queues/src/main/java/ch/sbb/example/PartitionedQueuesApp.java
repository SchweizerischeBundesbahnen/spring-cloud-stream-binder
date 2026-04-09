package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.binding.BindingsLifecycleController;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.time.Duration;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootApplication
@EnableScheduling
public class PartitionedQueuesApp {
    static final String BINDING_NAME = "partitionedConsumer-in-0";
    static final String DESTINATION = "example/partitioned/topic";
    static final String GROUP = "partitioned-group";
    static final String QUEUE_NAME = "scst/wk/" + GROUP + "/plain/" + DESTINATION;

    private static final Logger log = LoggerFactory.getLogger(PartitionedQueuesApp.class);
    public static final ConcurrentMap<String, String> MSG_TO_THREAD = new ConcurrentHashMap<>();

    private static final String[] KEYS = {"Key-A", "Key-B"};

    private final AtomicBoolean publisherEnabled = new AtomicBoolean();
    private final AtomicInteger count = new AtomicInteger();
    private final StreamBridge streamBridge;
    private final BindingsLifecycleController bindingsLifecycleController;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${app.partition-count:2}")
    private int partitionCount;

    @Value("${app.semp.host:http://localhost:8081}")
    private String sempHost;

    @Value("${app.semp.username:admin}")
    private String sempUsername;

    @Value("${app.semp.password:admin}")
    private String sempPassword;

    @Value("${solace.java.msgVpn}")
    private String msgVpn;

    public PartitionedQueuesApp(StreamBridge streamBridge,
                                BindingsLifecycleController bindingsLifecycleController) {
        this.streamBridge = streamBridge;
        this.bindingsLifecycleController = bindingsLifecycleController;
    }

    public static void main(String[] args) { SpringApplication.run(PartitionedQueuesApp.class, args); }

    @Bean
    ApplicationRunner partitionedQueueProvisioner() {
        return args -> {
            ensurePartitionedQueue();
            bindingsLifecycleController.start(BINDING_NAME);
            publisherEnabled.set(true);
        };
    }

    @Scheduled(initialDelay = 1000, fixedRate = 500)
    public void publish() {
        if (!publisherEnabled.get()) {
            return;
        }

        int currentCount = count.getAndIncrement();
        if (currentCount < 10) {
            String key = KEYS[currentCount % 2];
            String payload = "msg-" + currentCount;
            Message<String> msg = MessageBuilder.withPayload(payload)
                    .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                    .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                    .setHeader(SolaceBinderHeaders.PARTITION_KEY, key)
                    .build();
            streamBridge.send("partitionedPublisher-out-0", msg);
            log.info("Published: {} with partitionKey={}", payload, key);
        }
    }

    @Bean
    public Consumer<Message<String>> partitionedConsumer() {
        return msg -> {
            String payload = msg.getPayload();
            String thread = Thread.currentThread().getName();
            log.info("Received '{}' on thread '{}'", payload, thread);
            MSG_TO_THREAD.put(payload, thread);
        };
    }

    private void ensurePartitionedQueue() throws IOException, InterruptedException {
        String queueUri = queueUri();
        HttpResponse<String> queueResponse = send(HttpRequest.newBuilder(URI.create(queueUri)).GET().build());

        if (queueResponse.statusCode() == 404 || isMissingQueueResponse(queueResponse)) {
            createPartitionedQueue();
            return;
        }

        if (queueResponse.statusCode() != 200) {
            throw new IllegalStateException("Failed to inspect example queue via SEMP: HTTP "
                    + queueResponse.statusCode() + " body=" + queueResponse.body());
        }

        String expectedPartitionCount = "\"partitionCount\":" + partitionCount;
        boolean queueMatchesExampleSettings = queueResponse.body().contains(expectedPartitionCount)
            && queueResponse.body().contains("\"egressEnabled\":true")
            && queueResponse.body().contains("\"ingressEnabled\":true")
            && queueResponse.body().contains("\"permission\":\"modify-topic\"");
        if (!queueMatchesExampleSettings) {
            log.info("Recreating example queue {} so it uses partitionCount={} with ingress/egress enabled",
                QUEUE_NAME, partitionCount);
            HttpResponse<String> deleteResponse = send(HttpRequest.newBuilder(URI.create(queueUri)).DELETE().build());
            if (deleteResponse.statusCode() != 200 && deleteResponse.statusCode() != 204) {
                throw new IllegalStateException("Failed to delete example queue via SEMP: HTTP "
                        + deleteResponse.statusCode() + " body=" + deleteResponse.body());
            }
            createPartitionedQueue();
        }
    }

    private void createPartitionedQueue() throws IOException, InterruptedException {
        String requestBody = "{" +
                "\"queueName\":\"" + QUEUE_NAME + "\"," +
                "\"accessType\":\"non-exclusive\"," +
            "\"egressEnabled\":true," +
            "\"ingressEnabled\":true," +
                "\"permission\":\"modify-topic\"," +
                "\"partitionCount\":" + partitionCount +
                "}";
        log.info("Provisioning partitioned example queue {} via SEMP with partitionCount={}", QUEUE_NAME, partitionCount);
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

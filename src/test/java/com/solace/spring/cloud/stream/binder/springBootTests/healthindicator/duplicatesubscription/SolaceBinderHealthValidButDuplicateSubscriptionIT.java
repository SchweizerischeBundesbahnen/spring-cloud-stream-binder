package com.solace.spring.cloud.stream.binder.springBootTests.healthindicator.duplicatesubscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.spring.cloud.stream.binder.extension.DynamicPropertiesTestContextCustomizerFactory;
import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;


@Isolated
public class SolaceBinderHealthValidButDuplicateSubscriptionIT {

    private static final Class<?> APP = SpringCloudStreamHealthRestartableApp.class;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static ConfigurableApplicationContext startApp(String... extraProps) {
        List<String> baseProps = new ArrayList<>();
        baseProps.add("server.port=0");
        baseProps.add("spring.main.banner-mode=off");
        baseProps.add("management.metrics.export.wavefront.enabled=false");
        baseProps.add("management.wavefront.enabled=false"); // if wavefront starter is present

        // allow callers to add/override
        baseProps.addAll(Arrays.asList(extraProps));

        return new SpringApplicationBuilder(APP)
                .initializers((ApplicationContextInitializer<GenericApplicationContext>) gac -> {
                    // Example: override/register a bean for tests
                    gac.registerBean(DuplicateSubscriptionMessageConsumer.class, DuplicateSubscriptionMessageConsumer::new, bd -> bd.setPrimary(true));
                    gac.registerBean(DynamicPropertiesTestContextCustomizerFactory.DynamicPropertiesContextCustomizer.class, () -> new DynamicPropertiesTestContextCustomizerFactory.DynamicPropertiesContextCustomizer(false, false));
                }, new DynamicPropertiesTestContextCustomizerFactory.DynamicPropertiesApplicationContextCustomizer(false, false))
                .profiles("duplicate-queue-subscription") // set your test profile(s)
                .properties(baseProps.toArray(String[]::new))                        // per-run overrides
                .run();
    }

    private final HttpClient client = HttpClient.newHttpClient();

    private Message<String> createMessageWithPayload(String payload, String destination) {
        return MessageBuilder
                .withPayload(payload)
                .setHeader(
                        SolaceHeaders.TIME_TO_LIVE,
                        TimeUnit.SECONDS.toMillis(30)
                )
                .setHeader(
                        BinderHeaders.TARGET_DESTINATION,
                        destination
                )
                .build();
    }

    /**
     * Here we don't use a SpringBootTest since we want to start the app that will create a durable queue.
     * After the restart we want to ensure the existing subscriptions are not causing problems.
     * If we instead used a SpringBootTest, that would break the end-of-test hooks leading to illegal-state exceptions,
     * as SpringBootTest is not prepared for spring restarts.
     */
    @Test
    void applyConfig_withDuplicateSubscription_healthShouldBeUp() throws Exception {
        ConfigurableApplicationContext context = startApp();

        Environment environment = context.getEnvironment();
        StreamBridge streamBridge = context.getBean(StreamBridge.class);

        int port = Integer.parseInt(environment.getProperty("local.server.port"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:%s/actuator/health".formatted(port)))
                .headers("Content-Type", "application/json;charset=UTF-8")
                .GET()
                .build();
        String content = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        //first test requirement: UP after initial spring starts
        assertEquals("UP", objectMapper.readTree(content).get("status").asText());

        String uuidForFirstSub = UUID.randomUUID().toString();

        streamBridge.send("bridgeFakeOutlet-out-0", createMessageWithPayload(uuidForFirstSub, "valid/subscription"));

        String uuidForOtherSub = UUID.randomUUID().toString();

        streamBridge.send("bridgeFakeOutlet-out-0", createMessageWithPayload(uuidForOtherSub, "other/valid/sub"));

        await().alias("verifying simple subscription and accepting no message lost")
                .pollInterval(Duration.ofSeconds(1))
                .atMost(2, TimeUnit.MINUTES)
                .until(() -> DuplicateSubscriptionMessageConsumer.getReceivedMessages().contains(uuidForFirstSub));
        await().alias("verifying additional subscription and accepting no message lost")
                .pollInterval(Duration.ofSeconds(1))
                .atMost(2, TimeUnit.MINUTES).until(() -> DuplicateSubscriptionMessageConsumer.getReceivedMessages().contains(uuidForOtherSub));

        HttpResponse<String> restartResponse = client.send(HttpRequest.newBuilder().uri(new URI("http://localhost:%s/actuator/restart".formatted(port)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, restartResponse.statusCode());

        await().alias("random port must change on restart")
                .pollInterval(Duration.ofSeconds(1))
                .atMost(2, TimeUnit.MINUTES).until(() -> {
                    String serverPort = environment.getProperty("local.server.port");
                    if (serverPort == null) {
                        return false;
                    }
                    int potentialNewTomcatPort = Integer.parseInt(serverPort);
                    if (potentialNewTomcatPort == 0) {
                        return false;
                    }
                    return potentialNewTomcatPort != port;
                });

        //port has changed since restart for this reason we need to pick up the new port:
        String afterRestart = client.send(HttpRequest.newBuilder()
                .uri(new URI("http://localhost:%s/actuator/health".formatted(environment.getProperty("local.server.port"))))
                .headers("Content-Type", "application/json;charset=UTF-8")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString()).body();
        //first test requirement: UP after initial spring starts
        assertEquals("UP", objectMapper.readTree(afterRestart).get("status").asText());
    }
}

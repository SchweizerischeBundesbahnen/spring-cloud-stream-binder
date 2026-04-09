package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class MaxUnacknowledgedMessagesIT {

    @Container
    static SolaceContainer solace = new SolaceContainer("solace/solace-pubsub-standard:latest")
            .withExposedPorts(8080, 55555);

    @Test
    void slowConsumerWithMaxUnacknowledgedMessagesOneLeavesMostOfTheLoadToFastConsumer() throws Exception {
        MaxUnacknowledgedMessagesApp.reset();

        try (ConfigurableApplicationContext slowConsumer = startApp("slow-consumer");
             ConfigurableApplicationContext fastConsumer = startApp("fast-consumer");
             ConfigurableApplicationContext publisher = startApp("publisher")) {

            await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                assertThat(MaxUnacknowledgedMessagesApp.processedBy("slow-consumer")).isGreaterThan(0);
                assertThat(MaxUnacknowledgedMessagesApp.processedBy("fast-consumer")).isGreaterThan(0);
            });

            int slowCount = MaxUnacknowledgedMessagesApp.processedBy("slow-consumer");
            int fastCount = MaxUnacknowledgedMessagesApp.processedBy("fast-consumer");

            assertThat(slowCount).isGreaterThan(0);
            assertThat(fastCount).isGreaterThan(slowCount);
            assertThat(MaxUnacknowledgedMessagesApp.messagesFor("fast-consumer")).isNotEmpty();
            assertThat(readQueueConfig()).contains("\"maxDeliveredUnackedMsgsPerFlow\":1");
        }
    }

    private ConfigurableApplicationContext startApp(String profile) {
        return new SpringApplicationBuilder(MaxUnacknowledgedMessagesApp.class)
                .profiles(profile)
                .properties(
                        "spring.main.banner-mode=off",
                        "spring.main.web-application-type=none",
                        "solace.java.host=" + solace.getOrigin(Service.SMF),
                        "solace.java.msgVpn=" + solace.getVpn(),
                        "solace.java.client-username=" + solace.getUsername(),
                        "solace.java.client-password=" + solace.getPassword(),
                        "app.semp.host=http://" + solace.getHost() + ":" + solace.getMappedPort(8080),
                        "app.semp.username=admin",
                        "app.semp.password=admin",
                        "solace.java.reconnectRetries=0")
                .run();
    }

    private String readQueueConfig() throws Exception {
        String queueName = MaxUnacknowledgedMessagesApp.QUEUE_NAME.replace("/", "%2F");
        String uri = "http://" + solace.getHost() + ":" + solace.getMappedPort(8080)
                + "/SEMP/v2/config/msgVpns/default/queues/" + queueName;
        String authHeader = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uri))
                        .header("Authorization", authHeader)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PartitionedQueuesIT {

    @Container
    static SolaceContainer solace = new SolaceContainer("solace/solace-pubsub-standard:latest")
            .withExposedPorts(8080, 55555);

    @DynamicPropertySource
    static void solaceProps(DynamicPropertyRegistry r) {
        r.add("solace.java.host", () -> solace.getOrigin(Service.SMF));
        r.add("solace.java.msgVpn", solace::getVpn);
        r.add("solace.java.client-username", solace::getUsername);
        r.add("solace.java.client-password", solace::getPassword);
        r.add("solace.java.reconnectRetries", () -> "0");
        r.add("app.semp.host", () -> "http://" + solace.getHost() + ":" + solace.getMappedPort(8080));
        r.add("app.semp.username", () -> "admin");
        r.add("app.semp.password", () -> "admin");
    }

    @Test
    void messagesWithPartitionKeysAreReceived() throws Exception {
        long end = System.currentTimeMillis() + 30000;
        while (PartitionedQueuesApp.MSG_TO_THREAD.size() < 10 && System.currentTimeMillis() < end) {
            Thread.sleep(100);
        }

        // All 10 messages with alternating partition keys are received and processed
        assertThat(PartitionedQueuesApp.MSG_TO_THREAD).hasSize(10);

        // Verify each expected message was received
        for (int i = 0; i < 10; i++) {
            assertThat(PartitionedQueuesApp.MSG_TO_THREAD)
                    .as("msg-%d should have been received", i)
                    .containsKey("msg-" + i);
        }

        // Verify messages were processed by at least one worker thread
        long distinctThreads = PartitionedQueuesApp.MSG_TO_THREAD.values().stream().distinct().count();
        assertThat(distinctThreads)
                .as("Messages should be processed by at least 1 thread")
                .isGreaterThanOrEqualTo(1);

        String queueName = PartitionedQueuesApp.QUEUE_NAME.replace("/", "%2F");
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
        assertThat(response.body()).contains("\"partitionCount\":2");
    }
}

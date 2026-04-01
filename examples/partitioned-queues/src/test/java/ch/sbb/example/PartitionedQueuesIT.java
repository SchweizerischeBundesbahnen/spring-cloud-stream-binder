package ch.sbb.example;

import org.junit.jupiter.api.BeforeAll;
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
import java.util.Base64;
import java.util.concurrent.TimeUnit;

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
    }

    @BeforeAll
    static void setupPartitionedQueue() throws Exception {
        // Natively partition the queue using Solace SEMPv2 so that messages are perfectly split 
        // across the two consumers!
        SempsHelper.createPartitionedQueue(solace, "example/partitioned/topic.partitioned-group", "example/partitioned/topic", 2);
    }

    @Test
    void messagesArePartitioned() throws InterruptedException {
        // Wait for all 10 messages from the infinite publisher
        long end = System.currentTimeMillis() + 30000;
        while (PartitionedQueuesApp.MSG_TO_THREAD.size() < 10 && System.currentTimeMillis() < end) {
            Thread.sleep(100);
        }
        
        // Verify all 10 messages arrived across the two threads.
        assertThat(PartitionedQueuesApp.MSG_TO_THREAD).hasSize(10);
        assertThat(PartitionedQueuesApp.MSG_TO_THREAD.values()).contains("example/partitioned/topic-0");
        assertThat(PartitionedQueuesApp.MSG_TO_THREAD.values()).contains("example/partitioned/topic-1");
    }

    static class SempsHelper {
        private static final HttpClient client = HttpClient.newHttpClient();

        static void createPartitionedQueue(SolaceContainer solace, String queueName, String topicName, int partitions) throws Exception {
            String sempsBase = "http://" + solace.getHost() + ":" + solace.getMappedPort(8080) + "/SEMP/v2/config";
            String credentials = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes());

            // 1. Create the native Partitioned Queue
            String queueBody = """
                {
                  "queueName": "%s",
                  "accessType": "non-exclusive",
                  "permission": "consume",
                  "partitionCount": %d,
                  "egressEnabled": true,
                  "ingressEnabled": true
                }
                """.formatted(queueName, partitions);
            post(sempsBase + "/msgVpns/default/queues", queueBody, credentials);

            // 2. Add topic subscription
            String encodedQueueName = queueName.replace("/", "%2F");
            String subBody = """
                {
                  "subscriptionTopic": "%s"
                }
                """.formatted(topicName);
            post(sempsBase + "/msgVpns/default/queues/" + encodedQueueName + "/subscriptions", subBody, credentials);
        }

        private static void post(String url, String json, String credentials) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", credentials)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("SEMP post failed: " + response.body());
            }
        }
    }
}

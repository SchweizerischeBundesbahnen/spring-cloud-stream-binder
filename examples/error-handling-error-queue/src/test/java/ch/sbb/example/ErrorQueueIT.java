package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ErrorQueueIT {

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

    @Test
    void failingConsumerTriggersErrorQueue() throws Exception {
        long end = System.currentTimeMillis() + 30000;
        while (ErrorQueueApp.ATTEMPT_COUNT.get() < 1 && System.currentTimeMillis() < end) {
            Thread.sleep(100);
        }
        assertThat(ErrorQueueApp.ATTEMPT_COUNT.get()).as("Consumer received and failed at least once").isGreaterThanOrEqualTo(1);

        // Verify natively via SEMP that the message was moved to the error queue
        String errorQueueName = "scst/error/wk/error-group/plain/example/error/topic";
        String encodedQueueName = errorQueueName.replace("/", "%2F");
        String uri = "http://localhost:" + solace.getMappedPort(8080) + "/SEMP/v2/monitor/msgVpns/default/queues/" + encodedQueueName;
        
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        String authHeader = "Basic " + java.util.Base64.getEncoder().encodeToString("admin:admin".getBytes());
        boolean messageFound = false;
        
        // Polling loop to wait for broker error delivery
        for (int i = 0; i < 20; i++) {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(uri))
                    .header("Authorization", authHeader)
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                String body = response.body();
                // A freshly provisioned dead letter queue with messages in it will report physical msg spool usage
                if (!body.contains("\"msgSpoolUsage\":0")) {
                    System.out.println("SEMP Error Queue stats: " + body);
                    messageFound = true;
                    break;
                }
            }
            Thread.sleep(500);
        }
        
        assertThat(messageFound).as("Message physically spooled in the Solace broker's Error Queue").isTrue();
    }
}

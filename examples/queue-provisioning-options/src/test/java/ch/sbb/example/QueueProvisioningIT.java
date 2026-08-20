package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class QueueProvisioningIT {

    @Container
    static SolaceContainer solace = new SolaceContainer("solace/solace-pubsub-standard:10.25.0")
            .withExposedPorts(8080, 55555);

    @DynamicPropertySource
    static void solaceProps(DynamicPropertyRegistry r) {
        // the client connects to Solace
        r.add("solace.java.host", () -> solace.getOrigin(Service.SMF));
        r.add("solace.java.msgVpn", solace::getVpn);
        r.add("solace.java.client-username", solace::getUsername);
        r.add("solace.java.client-password", solace::getPassword);
        r.add("solace.java.reconnectRetries", () -> "0");
    }

    @Test
    void consumerReceivesMessagesFromDestinationAndAdditionalSubscriptions() throws InterruptedException {
        Set<String> expectedPayloads = new HashSet<>(QueueProvisioningApp.PAYLOAD_PER_TOPIC.values());

        Set<String> receivedPayloads = new HashSet<>();
        while (receivedPayloads.size() < expectedPayloads.size()) {
            String payload = QueueProvisioningApp.RECEIVED.poll(30, TimeUnit.SECONDS);
            assertThat(payload)
                    .as("expected one message per subscription, received so far: %s", receivedPayloads)
                    .isNotNull();
            receivedPayloads.add(payload);
        }

        assertThat(receivedPayloads).isEqualTo(expectedPayloads);
    }
}

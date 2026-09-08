package ch.sbb.example;

import ch.sbb.example.NullPayloadApp.ReceivedPayload;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The empty payload a producer sends never reaches the consumer as an empty payload: Solace turns it
 * into a null payload on the wire. This proves that the {@code solace_scst_nullPayload} header is
 * what tells the two apart, and that the payload itself arrives as the null equivalent.
 */
@SpringBootTest
@Testcontainers
class NullPayloadIT {

    @Container
    static SolaceContainer solace = new SolaceContainer("solace/solace-pubsub-standard:10.26.0")
            .withExposedPorts(8080, 55555);

    @DynamicPropertySource
    static void solaceProps(DynamicPropertyRegistry r) {
        r.add("solace.java.host", () -> solace.getOrigin(Service.SMF));
        r.add("solace.java.msgVpn", solace::getVpn);
        r.add("solace.java.client-username", solace::getUsername);
        r.add("solace.java.client-password", solace::getPassword);
        r.add("solace.java.reconnectRetries", () -> "0");
    }

    @Test
    void anEmptyStringPayload_publishedNatively_arrivesFlaggedAsANullPayload() throws InterruptedException {
        List<ReceivedPayload> received = new ArrayList<>();
        while (received.size() < 2) {
            ReceivedPayload next = NullPayloadApp.RECEIVED_PAYLOADS.poll(30, TimeUnit.SECONDS);
            assertThat(next).as("expected two messages, got %s", received).isNotNull();
            received.add(next);
        }

        assertThat(received).contains(new ReceivedPayload("order-42", false));
        assertThat(received).contains(new ReceivedPayload("", true));
    }
}

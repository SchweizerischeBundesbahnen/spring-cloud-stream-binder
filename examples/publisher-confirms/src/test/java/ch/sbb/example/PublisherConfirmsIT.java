package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
class PublisherConfirmsIT {

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
    }

    @Autowired
    private PublishService publishService;

    @Test
    void publisherReceivesBrokerConfirmation() throws Exception {
        boolean confirmed = publishService.publishAndWait("msg-with-confirm");
        assertThat(confirmed).isTrue();

        String received = null;
        long end = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < end) {
            String candidate = PublisherConfirmsApp.RECEIVED.poll(1, TimeUnit.SECONDS);
            if ("msg-with-confirm".equals(candidate)) {
                received = candidate;
                break;
            }
        }

        assertThat(received).isEqualTo("msg-with-confirm");
    }
}

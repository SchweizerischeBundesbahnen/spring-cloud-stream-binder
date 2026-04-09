package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SolaceHeadersIT {

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

    @Test
    void solaceHeadersAreMappedAndExcludedAsConfigured() throws InterruptedException {
        SolaceHeadersApp.ReceivedHeaders receivedHeaders = SolaceHeadersApp.RECEIVED_HEADERS.poll(30, TimeUnit.SECONDS);
        assertThat(receivedHeaders).isNotNull();
        assertThat(receivedHeaders.correlationId()).startsWith("corr-id-");
        assertThat(receivedHeaders.customOrgId()).isNull();
        assertThat(receivedHeaders.timeToLive()).isEqualTo(Duration.ofSeconds(30).toMillis());
        assertThat(receivedHeaders.dmqEligible()).isTrue();
    }
}

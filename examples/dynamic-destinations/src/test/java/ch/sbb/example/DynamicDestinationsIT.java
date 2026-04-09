package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DynamicDestinationsIT {

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

    @LocalServerPort
    private int port;

    private RestTemplate restTemplate = new RestTemplate();

    @Test
    void canSendAndReceiveOnDynamicDestinations() throws InterruptedException {
        String dest1 = "example/dynamic/topic1";
        String dest2 = "example/dynamic/topic2";

        restTemplate.postForObject("http://localhost:" + port + "/send?destination={destination}", "test-msg-1", String.class, dest1);
        restTemplate.postForObject("http://localhost:" + port + "/send?destination={destination}", "test-msg-2", String.class, dest2);

        String msg1 = DynamicDestinationsApp.RECEIVED.poll(30, TimeUnit.SECONDS);
        String msg2 = DynamicDestinationsApp.RECEIVED.poll(30, TimeUnit.SECONDS);

        assertThat(msg1).isNotNull();
        assertThat(msg2).isNotNull();
        assertThat(java.util.List.of(msg1, msg2)).containsExactlyInAnyOrder("test-msg-1", "test-msg-2");
    }
}

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
class RedeliveryIT {

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
    void messageIsRetriedAndEventuallySucceeds() throws InterruptedException {
        long end = System.currentTimeMillis() + 60000;
        while (RedeliveryApp.SUCCESS_COUNT.get() < 1 && System.currentTimeMillis() < end) {
            Thread.sleep(100);
        }
        assertThat(RedeliveryApp.SUCCESS_COUNT.get()).as("Message eventually processed after retries").isGreaterThanOrEqualTo(1);
        assertThat(RedeliveryApp.ATTEMPT_COUNT.get()).isGreaterThanOrEqualTo(3);
    }
}

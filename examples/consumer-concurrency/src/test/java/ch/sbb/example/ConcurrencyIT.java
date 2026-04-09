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
class ConcurrencyIT {

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
    void messagesAreProcessedConcurrently() throws InterruptedException {
        long end = System.currentTimeMillis() + 60000;
        while (ConcurrencyApp.THREADS.size() < 20 && System.currentTimeMillis() < end) {
            Thread.sleep(100);
        }
        assertThat(ConcurrencyApp.THREADS).as("All 20 messages should be processed").hasSize(20);

        long distinctThreads = ConcurrencyApp.THREADS.stream().distinct().count();
        assertThat(distinctThreads)
                .as("More than one worker thread should participate in the burst")
                .isGreaterThan(1);
    }
}

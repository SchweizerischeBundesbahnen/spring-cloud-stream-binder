package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
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
    }

    @Test
    void solaceHeadersArePresentAndCustomIsExcluded() throws InterruptedException {
        Message<String> received = SolaceHeadersApp.RECEIVED.poll(30, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        
        assertThat(received.getHeaders())
            .containsEntry(SolaceHeaders.CORRELATION_ID, "corr-123")
            .containsEntry(SolaceHeaders.PRIORITY, 100)
            .containsEntry(SolaceHeaders.APPLICATION_MESSAGE_TYPE, "demo/json")
            .containsKey(SolaceHeaders.TIME_TO_LIVE)
            .containsKey(SolaceHeaders.SENDER_TIMESTAMP)
            .doesNotContainKey("custom-header"); // Should be excluded
    }
}

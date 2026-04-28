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
class DefaultHeadersIT {

    @Container
    static SolaceContainer solace = new SolaceContainer("solace/solace-pubsub-standard:latest");

    @DynamicPropertySource
    static void solaceProps(DynamicPropertyRegistry r) {
        r.add("solace.java.host", () -> solace.getOrigin(Service.SMF));
        r.add("solace.java.msgVpn", solace::getVpn);
        r.add("solace.java.client-username", solace::getUsername);
        r.add("solace.java.client-password", solace::getPassword);
        r.add("solace.java.reconnectRetries", () -> "0");
    }

    @Test
    void defaultHeadersAreAppliedAndOverridable() throws InterruptedException {
        // Producer alternates: even index -> uses default header, odd index -> overrides it.
        // Single binding + single consumer => order is preserved.
        DefaultHeadersApp.ReceivedHeaders defaultedMsg = DefaultHeadersApp.RECEIVED_HEADERS.poll(30, TimeUnit.SECONDS);
        DefaultHeadersApp.ReceivedHeaders overriddenMsg = DefaultHeadersApp.RECEIVED_HEADERS.poll(30, TimeUnit.SECONDS);

        assertThat(defaultedMsg).as("first message should be received").isNotNull();
        assertThat(overriddenMsg).as("second message should be received").isNotNull();

        // Default header applied when the producer did not set it
        assertThat(defaultedMsg.customDefaultHeader()).isEqualTo("my-default-value");

        // Producer-set value wins over the default
        assertThat(overriddenMsg.customDefaultHeader()).isEqualTo("overridden-value");

        // Solace default headers (timeToLive, senderId) apply to BOTH messages,
        // including the one that overrides an unrelated custom header.
        assertThat(defaultedMsg.timeToLive()).isEqualTo(23000L);
        assertThat(overriddenMsg.timeToLive()).isEqualTo(23000L);

        assertThat(defaultedMsg.senderId())
                .startsWith("my-project_")
                .hasSizeGreaterThan("my-project_".length());
        assertThat(overriddenMsg.senderId()).isEqualTo(defaultedMsg.senderId());
    }
}

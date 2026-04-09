package ch.sbb.example;

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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class MicrometerTracingIT {

    @Container
    static SolaceContainer solace = new SolaceContainer("solace/solace-pubsub-standard:latest")
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
    void traceIdsMatchBetweenProducerAndConsumer() throws Exception {
        List<MicrometerTracingApp.TraceObservation> observations = new ArrayList<>();
        long end = System.currentTimeMillis() + 30000;
        while (observations.size() < 6 && System.currentTimeMillis() < end) {
            MicrometerTracingApp.TraceObservation observation = MicrometerTracingApp.TRACING_LOGS.poll(1, TimeUnit.SECONDS);
            if (observation != null) {
                observations.add(observation);
            }
        }

        assertThat(observations).hasSize(6);

        Map<String, List<MicrometerTracingApp.TraceObservation>> byPayload = observations.stream()
                .collect(Collectors.groupingBy(MicrometerTracingApp.TraceObservation::payload));
        assertThat(byPayload).hasSize(3);

        for (Map.Entry<String, List<MicrometerTracingApp.TraceObservation>> entry : byPayload.entrySet()) {
            MicrometerTracingApp.TraceObservation publisherObservation = entry.getValue().stream()
                    .filter(observation -> observation.origin().equals("PUBLISHER"))
                    .findFirst()
                    .orElseThrow();
            MicrometerTracingApp.TraceObservation consumerObservation = entry.getValue().stream()
                    .filter(observation -> observation.origin().equals("CONSUMER"))
                    .findFirst()
                    .orElseThrow();

            assertThat(publisherObservation.traceId())
                    .as("Publisher should expose a trace ID for %s", entry.getKey())
                    .isNotBlank()
                    .isNotEqualTo("none");
            assertThat(consumerObservation.traceId())
                    .as("Consumer should keep the same trace ID for %s", entry.getKey())
                    .isEqualTo(publisherObservation.traceId());
            assertThat(consumerObservation.spanId())
                    .as("Consumer should run in a different span for %s", entry.getKey())
                    .isNotBlank()
                    .isNotEqualTo("none")
                    .isNotEqualTo(publisherObservation.spanId());
        }
    }
}

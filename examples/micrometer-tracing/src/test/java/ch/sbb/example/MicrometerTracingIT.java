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
    }

    @Test
    void traceIdsMatchBetweenProducerAndConsumer() throws Exception {
        // 3 messages sent = 6 events (3 publish, 3 consume)
        List<String> logs = new ArrayList<>();
        for (int i=0; i<6; i++) {
            String x = MicrometerTracingApp.TRACING_LOGS.poll(30, TimeUnit.SECONDS);
            if (x != null) logs.add(x);
        }
        
        List<String> publisherLogs = logs.stream().filter(s -> s.startsWith("PUBLISHER:")).collect(Collectors.toList());
        List<String> consumerLogs = logs.stream().filter(s -> s.startsWith("CONSUMER:")).collect(Collectors.toList());
        
        assertThat(publisherLogs).hasSize(3);
        assertThat(consumerLogs).hasSize(3);
        
        // Ensure publishers set a trace id and consumers received the same trace id
        for (String pLog : publisherLogs) {
            String traceId = pLog.split(":")[1];
            assertThat(traceId).isNotEqualTo("none");
            
            // At least one consumer log should have this trace ID since we produced 3.
            boolean match = consumerLogs.stream().anyMatch(cLog -> cLog.contains(traceId));
            assertThat(match).as("Trace propagation worked for " + traceId).isTrue();
        }
    }
}

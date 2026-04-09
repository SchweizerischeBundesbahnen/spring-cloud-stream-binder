package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
class MetricsMonitoringIT {

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
    void prometheusMetricsAreExposed() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            assertThat(MetricsMonitoringApp.RECEIVED.poll(30, TimeUnit.SECONDS)).isNotNull();
        }

        ResponseEntity<String> metricsResponse = null;
        ResponseEntity<String> payloadMetricResponse = null;
        long end = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < end) {
            metricsResponse = restTemplate.getForEntity("http://localhost:" + port + "/actuator/metrics", String.class);
            payloadMetricResponse = restTemplate.getForEntity("http://localhost:" + port + "/actuator/metrics/solace.message.size.payload", String.class);
            if (payloadMetricResponse.getStatusCode() == HttpStatus.OK && payloadMetricResponse.getBody() != null
                    && payloadMetricResponse.getBody().contains("solace.message.size.payload")) {
                break;
            }
            Thread.sleep(250);
        }

        assertThat(metricsResponse).isNotNull();
        assertThat(metricsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metricsResponse.getBody()).contains("solace.message.size.payload");

        assertThat(payloadMetricResponse).isNotNull();
        assertThat(payloadMetricResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(payloadMetricResponse.getBody()).contains("solace.message.size.payload");

        ResponseEntity<String> response = restTemplate.getForEntity("http://localhost:" + port + "/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        String metricsData = response.getBody();
        assertThat(metricsData).isNotNull();
        assertThat(metricsData).contains("solace_");
        assertThat(metricsData).contains("solace_message_size_payload"); // Prometheus format of solace.message.size.payload
    }
}

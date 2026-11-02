package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MetricsMonitoringIT {

    @Container
    static SolaceContainer solace = new SolaceContainer("solace/solace-pubsub-standard:10.26.0")
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

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void prometheusMetricsAreExposed() throws IOException, InterruptedException {
        for (int i = 0; i < 5; i++) {
            assertThat(MetricsMonitoringApp.RECEIVED.poll(30, TimeUnit.SECONDS)).isNotNull();
        }

        HttpResponse<String> metricsResponse = null;
        HttpResponse<String> payloadMetricResponse = null;
        long end = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < end) {
            metricsResponse = get("/actuator/metrics");
            payloadMetricResponse = get("/actuator/metrics/solace.message.size.payload");
            if (payloadMetricResponse.statusCode() == HttpStatus.OK.value() && payloadMetricResponse.body() != null
                    && payloadMetricResponse.body().contains("solace.message.size.payload")) {
                break;
            }
            Thread.sleep(250);
        }

        assertThat(metricsResponse).isNotNull();
        assertThat(metricsResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(metricsResponse.body()).contains("solace.message.size.payload");

        assertThat(payloadMetricResponse).isNotNull();
        assertThat(payloadMetricResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(payloadMetricResponse.body()).contains("solace.message.size.payload");

        HttpResponse<String> response = get("/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());

        String metricsData = response.body();
        assertThat(metricsData).isNotNull();
        assertThat(metricsData).contains("solace_");
        assertThat(metricsData).contains("solace_message_size_payload"); // Prometheus format of solace.message.size.payload
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HealthIndicatorIT {

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void actuatorHealth_withAConnectedSession_reportsConnectionBindingAndProvisioningUpExactlyOnce() throws IOException, InterruptedException {
        HttpResponse<String> response = null;
        Map<String, Object> body = null;
        long end = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < end) {
            response = getHealth();
            body = objectMapper.readValue(response.body(), Map.class);
            if (response.statusCode() == HttpStatus.OK.value() && body != null && "UP".equals(body.get("status"))) {
                break;
            }
            Thread.sleep(250);
        }

        assertThat(response).isNotNull();
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());

        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("UP");

        Map<String, Object> components = (Map<String, Object>) body.get("components");
        assertThat(components).containsKey("binders").doesNotContainKey("solaceBinder");

        Map<String, Object> binders = (Map<String, Object>) components.get("binders");
        assertThat(binders.get("status")).isEqualTo("UP");

        Map<String, Object> binderComponents = (Map<String, Object>) binders.get("components");
        assertThat(binderComponents).containsKey("solace");

        Map<String, Object> solaceBinder = (Map<String, Object>) binderComponents.get("solace");
        assertThat(solaceBinder.get("status")).isEqualTo("UP");

        Map<String, Object> solaceBinderComponents = (Map<String, Object>) solaceBinder.get("components");
        assertThat((Map<String, Object>) solaceBinderComponents.get("connection")).containsEntry("status", "UP");
        assertThat((Map<String, Object>) solaceBinderComponents.get("provisioning")).containsEntry("status", "UP");

        Map<String, Object> bindings = (Map<String, Object>) solaceBinderComponents.get("bindings");
        assertThat(bindings.get("status")).isEqualTo("UP");
        Map<String, Object> bindingComponents = (Map<String, Object>) bindings.get("components");
        assertThat((Map<String, Object>) bindingComponents.get("healthConsumer-in-0")).containsEntry("status", "UP");
    }

    private HttpResponse<String> getHealth() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

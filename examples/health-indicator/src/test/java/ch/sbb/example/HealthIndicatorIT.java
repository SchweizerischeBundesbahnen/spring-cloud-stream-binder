package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HealthIndicatorIT {

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
    @SuppressWarnings("unchecked")
    void actuatorHealthReturnsSolaceBinderUp() throws InterruptedException {
        ResponseEntity<Map> response = null;
        long end = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < end) {
            response = restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", Map.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null && "UP".equals(response.getBody().get("status"))) {
                break;
            }
            Thread.sleep(250);
        }

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("UP");
        
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        assertThat(components).containsKey("binders");
        
        Map<String, Object> binders = (Map<String, Object>) components.get("binders");
        assertThat(binders.get("status")).isEqualTo("UP");
        
        Map<String, Object> binderComponents = (Map<String, Object>) binders.get("components");
        assertThat(binderComponents).containsKey("solace");
        
        Map<String, Object> solaceBinder = (Map<String, Object>) binderComponents.get("solace");
        assertThat(solaceBinder.get("status")).isEqualTo("UP");
    }
}

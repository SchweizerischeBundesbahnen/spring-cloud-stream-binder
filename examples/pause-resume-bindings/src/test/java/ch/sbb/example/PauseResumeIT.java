package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.util.concurrent.TimeUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PauseResumeIT {

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

    @LocalServerPort
    private int port;

    private RestTemplate restTemplate = new RestTemplate();

    @Test
    void canPauseAndResumeBindingsViaActuator() throws Exception {
        // 1. Send initial message and verify received
        ResponseEntity<String> initialPublishResponse = publish("msg1");
        assertThat(initialPublishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String m1 = PauseResumeApp.RECEIVED.poll(30, TimeUnit.SECONDS);
        assertThat(m1).isEqualTo("msg1");

        // 2. Pause the consumer
        ResponseEntity<Object> pauseResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/actuator/bindings/pausableConsumer-in-0",
                Map.of("state", "PAUSED"),
                Object.class);
        assertThat(pauseResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 3. Send second message while paused, verify NOT received
        ResponseEntity<String> pausedPublishResponse = publish("msg2");
        assertThat(pausedPublishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String m2 = PauseResumeApp.RECEIVED.poll(2, TimeUnit.SECONDS);
        assertThat(m2).isNull(); // Expected to be null since we're paused

        // 4. Resume the consumer
        ResponseEntity<Object> resumeResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/actuator/bindings/pausableConsumer-in-0",
                Map.of("state", "RESUMED"),
                Object.class);
        assertThat(resumeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 5. Verify queued message is now received
        String resumedMsg = PauseResumeApp.RECEIVED.poll(30, TimeUnit.SECONDS);
        assertThat(resumedMsg).isEqualTo("msg2");
    }

    private ResponseEntity<String> publish(String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/send",
                new HttpEntity<>(payload, headers),
                String.class);
    }
}

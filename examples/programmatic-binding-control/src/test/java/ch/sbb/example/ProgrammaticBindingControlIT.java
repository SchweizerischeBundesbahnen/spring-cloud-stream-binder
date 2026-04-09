package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ProgrammaticBindingControlIT {

    @Container
    static SolaceContainer solace = new SolaceContainer("solace/solace-pubsub-standard:latest")
            .withExposedPorts(8080, 55555);

    @DynamicPropertySource
    static void solaceProps(DynamicPropertyRegistry registry) {
        registry.add("solace.java.host", () -> solace.getOrigin(Service.SMF));
        registry.add("solace.java.msgVpn", solace::getVpn);
        registry.add("solace.java.client-username", solace::getUsername);
        registry.add("solace.java.client-password", solace::getPassword);
        registry.add("solace.java.reconnectRetries", () -> "0");
    }

    @LocalServerPort
    private int port;

    @Test
    void bindingCanBeStartedStoppedAndStartedAgain() throws Exception {
        ProgrammaticBindingControlApp.RECEIVED.clear();
        RestTemplate restTemplate = new RestTemplate();

        ResponseEntity<String> startResponse = restTemplate.postForEntity(
                url("/bindings/start"),
                HttpEntity.EMPTY,
                String.class);
        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> initialPublish = publish(restTemplate, "msg-1");
        assertThat(initialPublish.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ProgrammaticBindingControlApp.RECEIVED.poll(30, TimeUnit.SECONDS)).isEqualTo("msg-1");

        ResponseEntity<String> stopResponse = restTemplate.postForEntity(
                url("/bindings/stop"),
                HttpEntity.EMPTY,
                String.class);
        assertThat(stopResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> stoppedPublish = publish(restTemplate, "msg-2");
        assertThat(stoppedPublish.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ProgrammaticBindingControlApp.RECEIVED.poll(2, TimeUnit.SECONDS)).isNull();

        ResponseEntity<String> restartResponse = restTemplate.postForEntity(
                url("/bindings/start"),
                HttpEntity.EMPTY,
                String.class);
        assertThat(restartResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ProgrammaticBindingControlApp.RECEIVED.poll(30, TimeUnit.SECONDS)).isEqualTo("msg-2");
    }

    private ResponseEntity<String> publish(RestTemplate restTemplate, String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return restTemplate.postForEntity(
                url("/send"),
                new HttpEntity<>(payload, headers),
                String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
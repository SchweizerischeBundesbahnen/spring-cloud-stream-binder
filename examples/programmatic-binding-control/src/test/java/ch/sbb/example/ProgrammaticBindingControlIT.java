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
class ProgrammaticBindingControlIT {

    @Container
    static SolaceContainer solace = new SolaceContainer("solace/solace-pubsub-standard:10.26.0")
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

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void bindingCanBeStartedStoppedAndStartedAgain() throws Exception {
        ProgrammaticBindingControlApp.RECEIVED.clear();

        HttpResponse<String> startResponse = post("/bindings/start", HttpRequest.BodyPublishers.noBody());
        assertThat(startResponse.statusCode()).isEqualTo(HttpStatus.OK.value());

        HttpResponse<String> initialPublish = publish("msg-1");
        assertThat(initialPublish.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(ProgrammaticBindingControlApp.RECEIVED.poll(30, TimeUnit.SECONDS)).isEqualTo("msg-1");

        HttpResponse<String> stopResponse = post("/bindings/stop", HttpRequest.BodyPublishers.noBody());
        assertThat(stopResponse.statusCode()).isEqualTo(HttpStatus.OK.value());

        HttpResponse<String> stoppedPublish = publish("msg-2");
        assertThat(stoppedPublish.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(ProgrammaticBindingControlApp.RECEIVED.poll(2, TimeUnit.SECONDS)).isNull();

        HttpResponse<String> restartResponse = post("/bindings/start", HttpRequest.BodyPublishers.noBody());
        assertThat(restartResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(ProgrammaticBindingControlApp.RECEIVED.poll(30, TimeUnit.SECONDS)).isEqualTo("msg-2");
    }

    private HttpResponse<String> publish(String payload) throws IOException, InterruptedException {
        return post("/send", HttpRequest.BodyPublishers.ofString(payload));
    }

    private HttpResponse<String> post(String path, HttpRequest.BodyPublisher body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "text/plain")
                .POST(body)
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PauseResumeIT {

    @Container
    static SolaceContainer solace = new SolaceContainer("solace/solace-pubsub-standard:10.26.0")
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

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void canPauseAndResumeBindingsViaActuator() throws Exception {
        // 1. Send initial message and verify received
        HttpResponse<String> initialPublishResponse = publish("msg1");
        assertThat(initialPublishResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
        String m1 = PauseResumeApp.RECEIVED.poll(30, TimeUnit.SECONDS);
        assertThat(m1).isEqualTo("msg1");

        // 2. Pause the consumer
        HttpResponse<String> pauseResponse = changeBindingState("PAUSED");
        assertThat(pauseResponse.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());

        // 3. Send second message while paused, verify NOT received
        HttpResponse<String> pausedPublishResponse = publish("msg2");
        assertThat(pausedPublishResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
        String m2 = PauseResumeApp.RECEIVED.poll(2, TimeUnit.SECONDS);
        assertThat(m2).isNull(); // Expected to be null since we're paused

        // 4. Resume the consumer
        HttpResponse<String> resumeResponse = changeBindingState("RESUMED");
        assertThat(resumeResponse.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());

        // 5. Verify queued message is now received
        String resumedMsg = PauseResumeApp.RECEIVED.poll(30, TimeUnit.SECONDS);
        assertThat(resumedMsg).isEqualTo("msg2");
    }

    private HttpResponse<String> publish(String payload) throws IOException, InterruptedException {
        return post("/send", "text/plain", payload);
    }

    private HttpResponse<String> changeBindingState(String state) throws IOException, InterruptedException {
        return post("/actuator/bindings/pausableConsumer-in-0", "application/json", "{\"state\":\"" + state + "\"}");
    }

    private HttpResponse<String> post(String path, String contentType, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DynamicDestinationsIT {

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
    void canSendAndReceiveOnDynamicDestinations() throws IOException, InterruptedException {
        String dest1 = "example/dynamic/topic1";
        String dest2 = "example/dynamic/topic2";

        assertThat(send(dest1, "test-msg-1").statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(send(dest2, "test-msg-2").statusCode()).isEqualTo(HttpStatus.OK.value());

        String msg1 = DynamicDestinationsApp.RECEIVED.poll(30, TimeUnit.SECONDS);
        String msg2 = DynamicDestinationsApp.RECEIVED.poll(30, TimeUnit.SECONDS);

        assertThat(msg1).isNotNull();
        assertThat(msg2).isNotNull();
        assertThat(java.util.List.of(msg1, msg2)).containsExactlyInAnyOrder("test-msg-1", "test-msg-2");
    }

    private HttpResponse<String> send(String destination, String payload) throws IOException, InterruptedException {
        URI uri = URI.create("http://localhost:" + port + "/send?destination="
                + URLEncoder.encode(destination, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

package com.solace.spring.cloud.stream.binder.springBootTests.multibinder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.spring.cloud.stream.binder.extension.BinderIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Disabled // STTRS-2869 re-enable when health-page contributors are reliable
@Isolated
@BinderIntegrationTest(multiBinderEnabled = true)
@SpringBootTest(classes = {SpringCloudStreamMultiBinderApp.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(profiles = {"multibinder-broken-subscription"})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
public class MultiBinderHealthBrokenConfigIT {
    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final Pattern ERROR_BREAKING_BINDING = Pattern.compile("Failed to create consumer binding; retrying");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void applyBrokenConfig_healthShouldBeDownAndKeepDownAfterAtLeast3Retries(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:%s/actuator/health".formatted(port)))
                .headers("Content-Type", "application/json;charset=UTF-8")
                .GET()
                .build();

        //give application time to first provisioning breaks
        await().pollInterval(Duration.ofSeconds(1)).atMost(2, TimeUnit.MINUTES).until(() -> {
            String firstHealthResponseBody = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
            //first test requirement: DOWN after initial spring starts
            return objectMapper.readTree(firstHealthResponseBody).get("status").asText().equals("DOWN");
        });

        //second test requirement: keeping DOWN:
        await().pollInterval(Duration.ofSeconds(1)).atMost(10, TimeUnit.MINUTES).until(() -> ERROR_BREAKING_BINDING.matcher(output.getOut()).results().count() >= 3);

        String finalHealthResponseBody = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        assertEquals("DOWN", objectMapper.readTree(finalHealthResponseBody).get("status").asText());
    }
}

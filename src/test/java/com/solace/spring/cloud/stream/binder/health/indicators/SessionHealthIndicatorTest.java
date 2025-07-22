package com.solace.spring.cloud.stream.binder.health.indicators;

import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.impl.SessionEventArgsImpl;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.*;

class SessionHealthIndicatorTest {
    @Test
    public void testInitialHealth() {
        assertNotNull(new SessionHealthIndicator().health());
    }

    @Test
    void testUp() {
        SessionHealthIndicator healthIndicator = new SessionHealthIndicator();
        healthIndicator.up();
        assertEquals(healthIndicator.health(), Health.up().build());
        assertTrue(healthIndicator.getHealth(true).getDetails().isEmpty());
    }

    @Test
    void testReconnecting() {
        SessionHealthIndicator healthIndicator = new SessionHealthIndicator();
        healthIndicator.reconnecting(null);
        assertEquals(healthIndicator.health().getStatus(), Status.DOWN);
        assertTrue(healthIndicator.getHealth(true).getDetails().isEmpty());
    }

    @Test
    void testReconnectingAlwaysGoesDown() {
        SessionHealthIndicator healthIndicator = new SessionHealthIndicator();

        // Test that reconnecting always immediately goes down
        healthIndicator.reconnecting(null);
        assertEquals(healthIndicator.health().getStatus(), Status.DOWN);

        // Test that subsequent reconnecting calls also go down
        healthIndicator.reconnecting(null);
        assertEquals(healthIndicator.health().getStatus(), Status.DOWN);

        // Test that after going up, reconnecting still goes down
        healthIndicator.up();
        assertEquals(healthIndicator.health().getStatus(), Status.UP);
        healthIndicator.reconnecting(null);
        assertEquals(healthIndicator.health().getStatus(), Status.DOWN);
    }

    @Test
    void testDown() {
        SessionHealthIndicator healthIndicator = new SessionHealthIndicator();
        healthIndicator.down(null);
        assertEquals(healthIndicator.health().getStatus(), Status.DOWN);
        assertTrue(healthIndicator.getHealth(true).getDetails().isEmpty());
    }

    @CartesianTest(name = "[{index}] status={0} withException={1} responseCode={2} info={3}")
    public void testDetails(@CartesianTest.Values(strings = {"DOWN", "RECONNECTING", "UP"}) String status, @CartesianTest.Values(booleans = {false, true}) boolean withException, @CartesianTest.Values(ints = {-1, 0, 1}) int responseCode, @CartesianTest.Values(strings = {"", "some-info"}) String info, SoftAssertions softly) {
        SessionHealthIndicator healthIndicator = new SessionHealthIndicator();
        Exception healthException = withException ? new Exception("test") : null;
        SessionEventArgs sessionEventArgs = new SessionEventArgsImpl(null, info, healthException, responseCode);
        String expectedStatus = status;

        switch (status) {
            case "DOWN":
                healthIndicator.down(sessionEventArgs);
                break;
            case "RECONNECTING":
                healthIndicator.reconnecting(sessionEventArgs);
                expectedStatus = "DOWN"; // reconnecting now goes to DOWN
                break;
            case "UP":
                healthIndicator.up();
                break;
            default:
                throw new IllegalArgumentException("Test error: No handling for status=" + status);
        }
        Health health = healthIndicator.health();

        softly.assertThat(health.getStatus()).isEqualTo(new Status(expectedStatus));

        if (withException && !expectedStatus.equals("UP")) {
            softly.assertThat(health.getDetails()).isNotEmpty().extractingByKey("error").isEqualTo(healthException.getClass().getName() + ": " + healthException.getMessage());
        } else {
            softly.assertThat(health.getDetails()).doesNotContainKey("error");
        }

        if (responseCode != 0 && !expectedStatus.equals("UP")) {
            softly.assertThat(health.getDetails()).extractingByKey("responseCode").isEqualTo(responseCode);
        } else {
            softly.assertThat(health.getDetails()).doesNotContainKey("responseCode");
        }

        if (!info.isEmpty() && !expectedStatus.equals("UP")) {
            softly.assertThat(health.getDetails()).extractingByKey("info").isEqualTo(info);
        } else {
            softly.assertThat(health.getDetails()).doesNotContainKey("info");
        }
    }

    @ParameterizedTest(name = "[{index}] status={0}")
    @ValueSource(strings = {"DOWN", "RECONNECTING", "UP"})
    public void testWithoutDetails(String status) {
        SessionHealthIndicator healthIndicator = new SessionHealthIndicator();
        SessionEventArgs sessionEventArgs = new SessionEventArgsImpl(null, "some-info", new RuntimeException("test"), 1);
        String expectedStatus = status;

        switch (status) {
            case "DOWN":
                healthIndicator.down(sessionEventArgs);
                break;
            case "RECONNECTING":
                healthIndicator.reconnecting(sessionEventArgs);
                expectedStatus = "DOWN"; // reconnecting now goes to DOWN
                break;
            case "UP":
                healthIndicator.up();
                break;
            default:
                throw new IllegalArgumentException("Test error: No handling for status=" + status);
        }
        Health health = healthIndicator.getHealth(false);
        assertEquals(new Status(expectedStatus), health.getStatus());
        assertTrue(health.getDetails().isEmpty());
    }
}
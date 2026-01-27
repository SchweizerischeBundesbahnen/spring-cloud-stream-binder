package com.solace.spring.cloud.stream.binder.health.indicators;

import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import com.solacesystems.jcsmp.FlowEventArgs;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

class SolaceHealthIndicatorTest {

    @Test
    public void testInitialHealth() {
        assertThat(new SolaceHealthIndicator().health()).isNotNull();
    }

    @Test
    public void testUp(SoftAssertions softly) {
        SolaceHealthIndicator healthIndicator = new SolaceHealthIndicator();
        healthIndicator.healthUp();
        Health health = healthIndicator.health();
        softly.assertThat(health.getStatus()).isEqualTo(Status.UP);
        softly.assertThat(health.getDetails()).isEmpty();
    }

    @Test
    public void testDown(SoftAssertions softly) {
        SolaceHealthIndicator healthIndicator = new SolaceHealthIndicator();
        healthIndicator.healthDown(null);
        Health health = healthIndicator.health();
        softly.assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        softly.assertThat(health.getDetails()).isEmpty();
    }

    @Test
    public void testReconnecting(SoftAssertions softly) {
        SolaceHealthIndicator healthIndicator = new SolaceHealthIndicator();
        healthIndicator.healthReconnecting(null);
        Health health = healthIndicator.health();
        softly.assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        softly.assertThat(health.getDetails()).isEmpty();
    }

    @CartesianTest(name = "[{index}] status={0} responseCode={1} info={2}")
    public void testDetails(@CartesianTest.Values(strings = {"DOWN", "RECONNECTING", "UP"}) String status,
                            @CartesianTest.Values(booleans = {false, true}) boolean withException,
                            @CartesianTest.Values(ints = {-1, 0, 1}) int responseCode,
                            @CartesianTest.Values(strings = {"", "some-info"}) String info,
                            SoftAssertions softly) {
        SolaceHealthIndicator healthIndicator = new SolaceHealthIndicator();
        Exception healthException = withException ? new Exception("test") : null;
        FlowEventArgs flowEventArgs = new FlowEventArgs(null, info, healthException, responseCode);
        String expectedStatus = status;

        switch (status) {
            case "DOWN":
                healthIndicator.healthDown(flowEventArgs);
                break;
            case "RECONNECTING":
                healthIndicator.healthReconnecting(flowEventArgs);
                expectedStatus = "DOWN"; // reconnecting now goes to DOWN
                break;
            case "UP":
                healthIndicator.healthUp();
                break;
            default:
                throw new IllegalArgumentException("Test error: No handling for status=" + status);
        }
        Health health = healthIndicator.health();

        softly.assertThat(health.getStatus()).isEqualTo(new Status(expectedStatus));

        if (withException && !expectedStatus.equals("UP")) {
            softly.assertThat(health.getDetails())
                    .isNotEmpty()
                    .extractingByKey("error")
                    .isEqualTo(healthException.getClass().getName() + ": " + healthException.getMessage());
        } else {
            softly.assertThat(health.getDetails()).doesNotContainKey("error");
        }

        if (responseCode != 0 && !expectedStatus.equals("UP")) {
            softly.assertThat(health.getDetails())
                    .extractingByKey("responseCode")
                    .isEqualTo(responseCode);
        } else {
            softly.assertThat(health.getDetails()).doesNotContainKey("responseCode");
        }

        if (!info.isEmpty() && !expectedStatus.equals("UP")) {
            softly.assertThat(health.getDetails())
                    .extractingByKey("info")
                    .isEqualTo(info);
        } else {
            softly.assertThat(health.getDetails()).doesNotContainKey("info");
        }
    }

    @ParameterizedTest(name = "[{index}] status={0}")
    @ValueSource(strings = {"DOWN", "RECONNECTING", "UP"})
    public void testWithoutDetails(String status, SoftAssertions softly) {
        SolaceHealthIndicator healthIndicator = new SolaceHealthIndicator();
        FlowEventArgs flowEventArgs = new FlowEventArgs(null, "some-info", new RuntimeException("test"), 1);
        String expectedStatus = status;

        switch (status) {
            case "DOWN":
                healthIndicator.healthDown(flowEventArgs);
                break;
            case "RECONNECTING":
                healthIndicator.healthReconnecting(flowEventArgs);
                expectedStatus = "DOWN"; // reconnecting now goes to DOWN
                break;
            case "UP":
                healthIndicator.healthUp();
                break;
            default:
                throw new IllegalArgumentException("Test error: No handling for status=" + status);
        }
        Health health = healthIndicator.health(false);
        softly.assertThat(health.getStatus()).isEqualTo(new Status(expectedStatus));
        softly.assertThat(health.getDetails()).isEmpty();
    }
}
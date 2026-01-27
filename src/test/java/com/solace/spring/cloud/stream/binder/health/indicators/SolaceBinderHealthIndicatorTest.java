package com.solace.spring.cloud.stream.binder.health.indicators;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SolaceBinderHealthIndicatorTest {
    private SessionHealthIndicator healthIndicator;

    @BeforeEach
    void BeforeEach() {
        this.healthIndicator = new SessionHealthIndicator();
    }

    @Test
    public void testUp() {
        healthIndicator.up();
        Health health = healthIndicator.health();
        assertEquals(Status.UP, health.getStatus());
        assertTrue(health.getDetails().isEmpty());
    }

    @Test
    public void testDown() {
        healthIndicator.down(null);
        Health health = healthIndicator.health();
        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().isEmpty());
    }

    @Test
    public void testReconnecting() {
        healthIndicator.reconnecting(null);
        Health health = healthIndicator.health();
        assertEquals(Status.DOWN, health.getStatus()); // reconnecting now goes to DOWN
        assertTrue(health.getDetails().isEmpty());
    }
}

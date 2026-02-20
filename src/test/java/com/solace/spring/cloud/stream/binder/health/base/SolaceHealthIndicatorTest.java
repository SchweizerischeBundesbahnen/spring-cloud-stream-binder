package com.solace.spring.cloud.stream.binder.health.base;

import com.solacesystems.jcsmp.FlowEvent;
import com.solacesystems.jcsmp.FlowEventArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SolaceHealthIndicatorTest {

    private SolaceHealthIndicator solaceHealthIndicator;

    @BeforeEach
    void setUp() {
        this.solaceHealthIndicator = new SolaceHealthIndicator();
    }

    @Test
    void healthUp() {
        this.solaceHealthIndicator.healthUp();
        assertEquals(this.solaceHealthIndicator.health(), Health.up().build());
    }

    @Test
    void healthReconnecting() {
        this.solaceHealthIndicator.healthReconnecting(null);
        assertEquals(this.solaceHealthIndicator.health(), Health.down().build());
    }

    @Test
    void healthDown() {
        this.solaceHealthIndicator.healthDown(null);
        assertEquals(this.solaceHealthIndicator.health(), Health.down().build());
    }

    @Test
    void addFlowEventDetails() {
        // as SessionEventArgs constructor has package level access modifier, we have to test with FlowEventArgs only
        FlowEventArgs flowEventArgs = new FlowEventArgs(FlowEvent.FLOW_DOWN, "String_infoStr",
                new Exception("Test Exception"), 500);
        Health health = this.solaceHealthIndicator.addEventDetails(Health.down(), flowEventArgs).build();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("java.lang.Exception: Test Exception", health.getDetails().get("error"));
        assertEquals(500, health.getDetails().get("responseCode"));
    }

    @Test
    void getHealth() {
        this.solaceHealthIndicator.setHealth(Health.up().build());
        assertEquals(this.solaceHealthIndicator.health(), Health.up().build());
    }

    @Test
    void setHealth() {
        this.solaceHealthIndicator.setHealth(Health.down().build());
        assertEquals(this.solaceHealthIndicator.health(), Health.down().build());
    }
}
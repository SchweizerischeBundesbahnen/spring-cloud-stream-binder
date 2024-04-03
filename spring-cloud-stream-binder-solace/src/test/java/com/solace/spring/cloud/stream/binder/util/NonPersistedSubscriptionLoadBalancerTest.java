package com.solace.spring.cloud.stream.binder.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonPersistedSubscriptionLoadBalancerTest {

    @Test
    void isMatchingTopicFilter_matchingTopicsA() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "vehicle/car/fiat",
                "animals/domestic/*",
                "animals/wild/wulf/wearwulf"
        ));

        assertTrue(lb.isMatchingTopicFilter("vehicle/car/fiat"));
        assertTrue(lb.isMatchingTopicFilter("animals/domestic/cat"));
        assertTrue(lb.isMatchingTopicFilter("animals/wild/wulf/wearwulf"));
    }

    @Test
    void isMatchingTopicFilter_matchingTopicsB() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/*/cat"
        ));

        assertTrue(lb.isMatchingTopicFilter("animals/domestic/cat"));
    }

    @Test
    void isMatchingTopicFilter_matchingTopicsC() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/domestic/dog*"
        ));

        assertTrue(lb.isMatchingTopicFilter("animals/domestic/dog"));
        assertTrue(lb.isMatchingTopicFilter("animals/domestic/doggy"));
    }

    @Test
    void isMatchingTopicFilter_matchingTopicsD() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/>",
                "vehicle/*/fiat/>"
        ));

        assertTrue(lb.isMatchingTopicFilter("animals/domestic/cat"));
        assertTrue(lb.isMatchingTopicFilter("vehicle/car/fiat/500"));
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsA() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/*"
        ));

        assertFalse(lb.isMatchingTopicFilter("animals/domestic/cat"));
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsB() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/domestic"
        ));

        assertFalse(lb.isMatchingTopicFilter("animals/domestic/cat"));
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsC() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/domestic/cat"
        ));

        assertFalse(lb.isMatchingTopicFilter("animals/domestic"));
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsD() {
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "animals/domestic/*"
        ));

        assertFalse(lb.isMatchingTopicFilter("animals/domestic"));
    }

    @Test
    void perf() {
        long start = System.currentTimeMillis();
        NonPersistedSubscriptionLoadBalancer lb = new NonPersistedSubscriptionLoadBalancer(Set.of(
                "tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-request",
                "tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-vrcs",
                "tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-fooo",
                "tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-baaa",
                "tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-grrr",
                "tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-daaa",
                "tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-tzz",
                "tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-dsfsd",
                "tms/monitoring/monalesy/i/v1/metric/*/tms/outpost/testing/>"
        ));

        int iterations = 1_000_000;
        for (int i = 0; i < iterations; i++) {
            lb.isMatchingTopicFilter("tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-request/" +i);
        }

        System.out.println("Runtime: " + (System.currentTimeMillis() - start) + "ms");
        System.out.println("Runtime per item: " + ((double)(System.currentTimeMillis() - start) / iterations * 60) + "ms");
    }
}

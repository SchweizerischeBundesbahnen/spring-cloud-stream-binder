package com.solace.spring.cloud.stream.binder.properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SolaceConsumerPropertiesTest {

    @Test
    void testDefaultHeaderExclusionsListIsEmpty() {
        assertTrue(new SolaceConsumerProperties().getHeaderExclusions().isEmpty());
    }
}

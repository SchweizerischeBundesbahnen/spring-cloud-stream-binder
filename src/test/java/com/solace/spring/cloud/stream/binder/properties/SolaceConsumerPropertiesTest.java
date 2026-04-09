package com.solace.spring.cloud.stream.binder.properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

public class SolaceConsumerPropertiesTest {

    @Test
    void testDefaultHeaderExclusionsListIsEmpty() {
        assertTrue(new SolaceConsumerProperties().getHeaderExclusions().isEmpty());
    }

    @Test
    void testSubAckWindowSizePropertyRoundTrip() {
        SolaceConsumerProperties properties = new SolaceConsumerProperties();
        properties.setSubAckWindowSize(123);

        assertThat(properties.getSubAckWindowSize()).isEqualTo(123);
    }
}

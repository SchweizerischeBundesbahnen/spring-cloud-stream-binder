package com.solace.spring.cloud.stream.binder.provisioning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SolaceProducerDestinationTest {

    @Test
    void testGetName() {
        SolaceProducerDestination dest = new SolaceProducerDestination("my/topic");
        assertThat(dest.getName()).isEqualTo("my/topic");
    }

    @Test
    void testGetNameForPartitionReturnsName() {
        SolaceProducerDestination dest = new SolaceProducerDestination("my/topic");
        assertThat(dest.getNameForPartition(0)).isEqualTo("my/topic");
        assertThat(dest.getNameForPartition(5)).isEqualTo("my/topic");
    }

    @Test
    void testToString() {
        SolaceProducerDestination dest = new SolaceProducerDestination("my/topic");
        String str = dest.toString();

        assertThat(str).contains("my/topic");
        assertThat(str).contains("SolaceProducerDestination");
    }
}

package com.solace.spring.cloud.stream.binder.provisioning;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SolaceConsumerDestinationTest {

    @Test
    void testGetName() {
        SolaceConsumerDestination dest = createDestination("queue-1", "topic", "group1",
                false, "error-queue", Set.of());
        assertThat(dest.getName()).isEqualTo("queue-1");
    }

    @Test
    void testGetEndpointName() {
        SolaceConsumerDestination dest = createDestination("queue-1", "topic", "group1",
                false, "error-queue", Set.of());
        assertThat(dest.getEndpointName()).isEqualTo("queue-1");
    }

    @Test
    void testGetBindingDestinationName() {
        SolaceConsumerDestination dest = createDestination("queue-1", "my/topic", "group1",
                false, null, Set.of());
        assertThat(dest.getBindingDestinationName()).isEqualTo("my/topic");
    }

    @Test
    void testGetPhysicalGroupName() {
        SolaceConsumerDestination dest = createDestination("queue-1", "topic", "group1",
                false, null, Set.of());
        assertThat(dest.getPhysicalGroupName()).isEqualTo("group1");
    }

    @Test
    void testIsTemporary() {
        SolaceConsumerDestination temp = createDestination("queue-1", "topic", "group1",
                true, null, Set.of());
        SolaceConsumerDestination perm = createDestination("queue-1", "topic", "group1",
                false, null, Set.of());

        assertThat(temp.isTemporary()).isTrue();
        assertThat(perm.isTemporary()).isFalse();
    }

    @Test
    void testGetErrorQueueName() {
        SolaceConsumerDestination dest = createDestination("queue-1", "topic", "group1",
                false, "error-q", Set.of());
        assertThat(dest.getErrorQueueName()).isEqualTo("error-q");
    }

    @Test
    void testNullErrorQueueName() {
        SolaceConsumerDestination dest = createDestination("queue-1", "topic", "group1",
                false, null, Set.of());
        assertThat(dest.getErrorQueueName()).isNull();
    }

    @Test
    void testGetAdditionalSubscriptions() {
        Set<String> subs = Set.of("sub1", "sub2");
        SolaceConsumerDestination dest = createDestination("queue-1", "topic", "group1",
                false, null, subs);
        assertThat(dest.getAdditionalSubscriptions()).containsExactlyInAnyOrder("sub1", "sub2");
    }

    @Test
    void testToString() {
        SolaceConsumerDestination dest = createDestination("queue-1", "my/topic", "group1",
                false, "error-q", Set.of());
        String str = dest.toString();

        assertThat(str).contains("queue-1");
        assertThat(str).contains("my/topic");
        assertThat(str).contains("group1");
        assertThat(str).contains("error-q");
    }

    private SolaceConsumerDestination createDestination(String endpointName, String bindingDestName,
                                                         String physicalGroupName, boolean isTemporary,
                                                         String errorQueueName, Set<String> additionalSubs) {
        return new SolaceConsumerDestination(endpointName, bindingDestName, physicalGroupName,
                isTemporary, errorQueueName, additionalSubs);
    }
}

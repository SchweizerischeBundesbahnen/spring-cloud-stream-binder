package com.solace.spring.cloud.stream.binder.properties;

import com.solace.spring.cloud.stream.binder.util.DestinationType;
import com.solacesystems.jcsmp.DeliveryMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SolaceProducerPropertiesTest {

    @Test
    void testDefaultDestinationType() {
        assertThat(new SolaceProducerProperties().getDestinationType()).isEqualTo(DestinationType.TOPIC);
    }

    @Test
    void testDefaultDeliveryMode() {
        assertThat(new SolaceProducerProperties().getDeliveryMode()).isEqualTo(DeliveryMode.PERSISTENT);
    }

    @Test
    void testDefaultHeaderExclusionsIsEmpty() {
        assertThat(new SolaceProducerProperties().getHeaderExclusions()).isEmpty();
    }

    @Test
    void testDefaultNonserializableHeaderConvertToString() {
        assertThat(new SolaceProducerProperties().isNonserializableHeaderConvertToString()).isFalse();
    }

    @Test
    void testDefaultQueueNameExpression() {
        assertThat(new SolaceProducerProperties().getQueueNameExpression()).isNotEmpty();
    }

    @Test
    void testDefaultQueueNameExpressionsForRequiredGroupsIsEmpty() {
        assertThat(new SolaceProducerProperties().getQueueNameExpressionsForRequiredGroups()).isEmpty();
    }

    @Test
    void testDefaultQueueAdditionalSubscriptionsIsEmpty() {
        assertThat(new SolaceProducerProperties().getQueueAdditionalSubscriptions()).isEmpty();
    }

    @Test
    void testSetDestinationType() {
        SolaceProducerProperties props = new SolaceProducerProperties();
        props.setDestinationType(DestinationType.QUEUE);
        assertThat(props.getDestinationType()).isEqualTo(DestinationType.QUEUE);
    }

    @Test
    void testSetDeliveryMode() {
        SolaceProducerProperties props = new SolaceProducerProperties();
        props.setDeliveryMode(DeliveryMode.DIRECT);
        assertThat(props.getDeliveryMode()).isEqualTo(DeliveryMode.DIRECT);
    }
}

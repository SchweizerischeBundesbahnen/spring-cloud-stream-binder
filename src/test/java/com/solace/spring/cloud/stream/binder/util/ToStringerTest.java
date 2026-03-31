package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.EndpointProperties;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.Queue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToStringerTest {

    @Test
    void testEndpointPropertiesNull() {
        assertThat(ToStringer.toString((EndpointProperties) null)).isEqualTo("EndpointProperties{NULL}");
    }

    @Test
    void testEndpointPropertiesToString() {
        EndpointProperties ep = new EndpointProperties();
        ep.setAccessType(EndpointProperties.ACCESSTYPE_NONEXCLUSIVE);
        ep.setPermission(EndpointProperties.PERMISSION_CONSUME);

        String result = ToStringer.toString(ep);

        assertThat(result).startsWith("EndpointProperties{");
        assertThat(result).contains("mAccessType=");
        assertThat(result).contains("mMaxMsgSize=");
        assertThat(result).contains("mPermission=");
    }

    @Test
    void testEndpointNull() {
        assertThat(ToStringer.toString((com.solacesystems.jcsmp.Endpoint) null)).isEqualTo("Endpoint{NULL}");
    }

    @Test
    void testEndpointToString() {
        Queue queue = JCSMPFactory.onlyInstance().createQueue("test-queue");

        String result = ToStringer.toString(queue);

        assertThat(result).startsWith("Endpoint{");
        assertThat(result).contains("test-queue");
        assertThat(result).contains("_durable=");
    }

    @Test
    void testConsumerFlowPropertiesToString() {
        ConsumerFlowProperties cfp = new ConsumerFlowProperties();
        Queue queue = JCSMPFactory.onlyInstance().createQueue("test-queue");
        cfp.setEndpoint(queue);

        String result = ToStringer.toString(cfp);

        assertThat(result).startsWith("ConsumerFlowProperties{");
        assertThat(result).contains("endpoint=");
        assertThat(result).contains("test-queue");
        assertThat(result).contains("startState=");
        assertThat(result).contains("ackMode=");
        assertThat(result).contains("windowSize=");
    }
}

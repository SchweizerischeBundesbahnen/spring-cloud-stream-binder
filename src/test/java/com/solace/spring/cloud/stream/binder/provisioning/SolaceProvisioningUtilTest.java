package com.solace.spring.cloud.stream.binder.provisioning;

import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.ProducerFlowProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

public class SolaceProvisioningUtilTest {

    @Test
    public void testGetConsumerFlowProperties() {
        SolaceConsumerProperties solaceConsumerProperties = new SolaceConsumerProperties();
        solaceConsumerProperties.setSubAckWindowSize(100);
        solaceConsumerProperties.setFlowAckTimerInMsecs(50);
        solaceConsumerProperties.setFlowAckThreshold(60);
        solaceConsumerProperties.setFlowWindowedAckMaxSize(255);

        ExtendedConsumerProperties<SolaceConsumerProperties> extendedProperties =
                new ExtendedConsumerProperties<>(solaceConsumerProperties);

        ConsumerFlowProperties result = SolaceProvisioningUtil.getConsumerFlowProperties("testEndpoint", extendedProperties);

        assertThat(result.getTransportWindowSize()).isEqualTo(100);
        assertThat(result.getAckTimerInMsecs()).isEqualTo(50);
        assertThat(result.getAckThreshold()).isEqualTo(60);
        assertThat(result.getWindowedAckMaxSize()).isEqualTo(255);
    }


    @Test
    public void testGetProducerFlowPropertiesUsesPubAckWindowSizeOverride() {
        JCSMPSession session = mock(JCSMPSession.class);
        when(session.getProperty(JCSMPProperties.PUB_ACK_WINDOW_SIZE)).thenReturn(255);

        SolaceProducerProperties solaceProducerProperties = new SolaceProducerProperties();
        solaceProducerProperties.setPubAckWindowSize(100);
        ExtendedProducerProperties<SolaceProducerProperties> extendedProperties =
                new ExtendedProducerProperties<>(solaceProducerProperties);

        ProducerFlowProperties result = SolaceProvisioningUtil.getProducerFlowProperties(session, extendedProperties);

        assertThat(result.getWindowSize()).isEqualTo(100);
    }

    @Test
    public void testGetProducerFlowPropertiesFallsBackToSessionPubAckWindowSize() {
        JCSMPSession session = mock(JCSMPSession.class);
        when(session.getProperty(JCSMPProperties.PUB_ACK_WINDOW_SIZE)).thenReturn(255);

        SolaceProducerProperties solaceProducerProperties = new SolaceProducerProperties();
        ExtendedProducerProperties<SolaceProducerProperties> extendedProperties =
                new ExtendedProducerProperties<>(solaceProducerProperties);

        ProducerFlowProperties result = SolaceProvisioningUtil.getProducerFlowProperties(session, extendedProperties);

        assertThat(result.getWindowSize()).isEqualTo(255);
    }

    @Test
    public void testGetConsumerFlowPropertiesEmpty() {
        SolaceConsumerProperties solaceConsumerProperties = new SolaceConsumerProperties();

        ExtendedConsumerProperties<SolaceConsumerProperties> extendedProperties =
                new ExtendedConsumerProperties<>(solaceConsumerProperties);

        ConsumerFlowProperties result = SolaceProvisioningUtil.getConsumerFlowProperties("testEndpoint", extendedProperties);

        assertThat(result).isNotNull();
        // Since no properties were set locally, they should be null/default in ConsumerFlowProperties and gracefully rely on Global Fallbacks
        ConsumerFlowProperties defaultProps = new ConsumerFlowProperties();
        assertThat(result.getTransportWindowSize()).isEqualTo(defaultProps.getTransportWindowSize());
        assertThat(result.getAckTimerInMsecs()).isEqualTo(defaultProps.getAckTimerInMsecs());
        assertThat(result.getAckThreshold()).isEqualTo(defaultProps.getAckThreshold());
        assertThat(result.getWindowedAckMaxSize()).isEqualTo(defaultProps.getWindowedAckMaxSize());
    }
}

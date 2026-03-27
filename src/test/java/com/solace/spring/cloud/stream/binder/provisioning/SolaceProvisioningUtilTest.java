package com.solace.spring.cloud.stream.binder.provisioning;

import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;

import static org.assertj.core.api.Assertions.assertThat;

public class SolaceProvisioningUtilTest {

    @Test
    public void testGetConsumerFlowProperties() {
        SolaceConsumerProperties solaceConsumerProperties = new SolaceConsumerProperties();
        solaceConsumerProperties.setMaxUnacknowledgedMessages(100);
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

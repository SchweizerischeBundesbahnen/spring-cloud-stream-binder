package com.solace.spring.cloud.stream.binder.provisioning;

import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.util.QualityOfService;
import com.solacesystems.jcsmp.JCSMPSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SolaceEndpointProvisionerTest {

    @Mock
    private JCSMPSession jcsmpSession;

    @Test
    void testProvisionConsumerDestinationWithAtMostOnce() {
        SolaceEndpointProvisioner provisioner = new SolaceEndpointProvisioner(jcsmpSession, Optional.empty());

        SolaceConsumerProperties solaceConsumerProperties = new SolaceConsumerProperties();
        solaceConsumerProperties.setQualityOfService(QualityOfService.AT_MOST_ONCE);

        ExtendedConsumerProperties<SolaceConsumerProperties> properties = new ExtendedConsumerProperties<>(solaceConsumerProperties);

        ConsumerDestination destination = provisioner.provisionConsumerDestination("test-topic", null, properties);

        assertThat(destination).isNotNull();
        assertThat(destination.getName()).isEqualTo("test-topic");

        // Verify that no queue was created via the JCSMPSession
        verifyNoInteractions(jcsmpSession);
    }
}

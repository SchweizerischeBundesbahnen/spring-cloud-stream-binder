package com.solace.spring.cloud.stream.binder.config;

import com.solace.spring.cloud.stream.binder.SolaceMessageChannelBinder;
import com.solace.spring.cloud.stream.binder.health.SolaceBinderHealthAccessor;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceExtendedBindingProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceQueueProvisioner;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solacesystems.jcsmp.Context;
import com.solacesystems.jcsmp.JCSMPSession;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.lang.Nullable;

@RequiredArgsConstructor
@Configuration
@Import(SolaceHealthIndicatorsConfiguration.class)
@EnableConfigurationProperties({SolaceExtendedBindingProperties.class})
public class SolaceMessageChannelBinderConfiguration {
    private final SolaceExtendedBindingProperties solaceExtendedBindingProperties;
    private final JCSMPSession jcsmpSession;
    private final Context context;
    private final JCSMPSessionEventHandler jcsmpSessionEventHandler;


    @Bean
    SolaceMessageChannelBinder solaceMessageChannelBinder(SolaceQueueProvisioner solaceQueueProvisioner, @Nullable SolaceBinderHealthAccessor solaceBinderHealthAccessor, @Nullable SolaceMeterAccessor solaceMeterAccessor) {
        SolaceMessageChannelBinder binder = new SolaceMessageChannelBinder(jcsmpSession, context, solaceQueueProvisioner);
        binder.setExtendedBindingProperties(solaceExtendedBindingProperties);
        binder.setSolaceMeterAccessor(solaceMeterAccessor);
        if (solaceBinderHealthAccessor != null) {
            binder.setSolaceBinderHealthAccessor(solaceBinderHealthAccessor);
        }
        return binder;
    }

    @Bean
    SolaceQueueProvisioner provisioningProvider() {
        return new SolaceQueueProvisioner(jcsmpSession, jcsmpSessionEventHandler);
    }

}

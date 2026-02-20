package com.solace.spring.cloud.stream.binder.config;

import com.solace.spring.cloud.stream.binder.SolaceMessageChannelBinder;
import com.solace.spring.cloud.stream.binder.config.autoconfigure.JCSMPSessionConfiguration;
import com.solace.spring.cloud.stream.binder.health.SolaceBinderHealthAccessor;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceExtendedBindingProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import com.solacesystems.jcsmp.Context;
import com.solacesystems.jcsmp.JCSMPSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Optional;

@RequiredArgsConstructor
@Configuration
@Import(JCSMPSessionConfiguration.class)
@EnableConfigurationProperties({SolaceExtendedBindingProperties.class})
public class SolaceMessageChannelBinderConfiguration {
    private final SolaceExtendedBindingProperties solaceExtendedBindingProperties;
    private final JCSMPSession jcsmpSession;
    private final Context context;

    @Bean
    SolaceMessageChannelBinder solaceMessageChannelBinder(SolaceEndpointProvisioner solaceEndpointProvisioner,
                                                          BeanFactory beanFactory,
                                                          Optional<SolaceMeterAccessor> solaceMeterAccessor,
                                                          Optional<TracingProxy> tracingProxy,
                                                          Optional<SolaceBinderHealthAccessor> solaceBinderHealthAccessor) {
        SolaceMessageChannelBinder binder = new SolaceMessageChannelBinder(jcsmpSession,
                context,
                solaceEndpointProvisioner,
                beanFactory,
                solaceMeterAccessor,
                tracingProxy,
                solaceBinderHealthAccessor);
        binder.setExtendedBindingProperties(solaceExtendedBindingProperties);
        return binder;
    }
}

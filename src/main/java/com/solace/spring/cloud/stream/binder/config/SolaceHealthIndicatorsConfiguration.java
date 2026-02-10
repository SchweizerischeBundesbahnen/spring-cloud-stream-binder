package com.solace.spring.cloud.stream.binder.config;

import com.solace.spring.cloud.stream.binder.health.SolaceBinderHealthAccessor;
import com.solace.spring.cloud.stream.binder.health.contributors.BindingsHealthContributor;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.handlers.SolaceSessionEventHandler;
import com.solace.spring.cloud.stream.binder.health.indicators.ProvisioningHealthIndicator;
import com.solace.spring.cloud.stream.binder.health.indicators.SessionHealthIndicator;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.SolaceSessionOAuth2TokenProvider;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
@ConditionalOnEnabledHealthIndicator("binders")
public class SolaceHealthIndicatorsConfiguration {

    @Bean
    public SolaceBinderHealthAccessor solaceBinderHealthAccessor(
            SolaceBinderHealthContributor solaceBinderHealthContributor) {
        return new SolaceBinderHealthAccessor(solaceBinderHealthContributor);
    }

    @Bean
    public SolaceBinderHealthContributor solaceBinderHealthContributor() {
        if (log.isDebugEnabled()) {
            log.debug("Creating Solace Connection Health Indicators Hierarchy");
        }
        return new SolaceBinderHealthContributor(
                new SessionHealthIndicator(),
                new BindingsHealthContributor(),
                new ProvisioningHealthIndicator());
    }

    @Bean
    public SolaceSessionEventHandler solaceSessionEventHandler(
            JCSMPProperties jcsmpProperties,
            @Nullable SolaceSessionOAuth2TokenProvider solaceSessionOAuth2TokenProvider,
            SolaceBinderHealthContributor healthContributor) {
        if (log.isDebugEnabled()) {
            log.debug("Creating Solace Session Event Handler for monitoring Health");
        }
        return new SolaceSessionEventHandler(jcsmpProperties, solaceSessionOAuth2TokenProvider, healthContributor.getSolaceSessionHealthIndicator());
    }
}

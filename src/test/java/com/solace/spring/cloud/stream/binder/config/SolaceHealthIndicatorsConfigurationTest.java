package com.solace.spring.cloud.stream.binder.config;

import com.solace.spring.cloud.stream.binder.config.autoconfigure.JCSMPSessionConfiguration;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solacesystems.jcsmp.JCSMPProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The indicator hierarchy belongs to the binder, which is what updates it. Built in the application
 * context as well, it reaches {@code /actuator/health} a second time as a {@code solaceBinder}
 * component that no session ever touches, and an operator cannot tell the two apart.
 */
class SolaceHealthIndicatorsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("management.health.binders.enabled=true")
            .withBean(JCSMPProperties.class, JCSMPProperties::new);

    @Test
    void theSessionAutoConfiguration_onItsOwn_publishesNoHealthContributor() {
        contextRunner.withConfiguration(AutoConfigurations.of(JCSMPSessionConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SolaceBinderHealthContributor.class);
                });
    }

    @Test
    void theBinderConfiguration_publishesTheHealthContributor() {
        contextRunner.withUserConfiguration(SolaceHealthIndicatorsConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SolaceBinderHealthContributor.class);
                });
    }
}

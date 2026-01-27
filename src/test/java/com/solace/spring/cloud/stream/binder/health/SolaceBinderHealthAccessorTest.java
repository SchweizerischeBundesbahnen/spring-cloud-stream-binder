package com.solace.spring.cloud.stream.binder.health;

import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import com.solace.spring.cloud.stream.binder.health.contributors.BindingsHealthContributor;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.indicators.ProvisioningHealthIndicator;
import com.solace.spring.cloud.stream.binder.health.indicators.SessionHealthIndicator;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.HealthContributors;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
public class SolaceBinderHealthAccessorTest {
    @Test
    public void testBindingHealthIndicator() {
        SolaceBinderHealthContributor healthContributor = new SolaceBinderHealthContributor(
                new SessionHealthIndicator(),
                new BindingsHealthContributor(),
                new ProvisioningHealthIndicator());
        SolaceBinderHealthAccessor healthAccessor = new SolaceBinderHealthAccessor(healthContributor);

        String bindingName = "binding-name";
        SolaceHealthIndicator bindingHealthIndicator = healthAccessor.createBindingHealthIndicator(bindingName);

        assertThat(StreamSupport.stream(healthContributor.getSolaceBindingsHealthContributor().spliterator(), false))
                .singleElement()
                .satisfies(n -> assertThat(n.name()).isEqualTo(bindingName))
                .extracting(HealthContributors.Entry::contributor)
                .asInstanceOf(InstanceOfAssertFactories.type(SolaceHealthIndicator.class));

        healthAccessor.removeBindingHealthIndicator(bindingName);

        assertThat(StreamSupport.stream(healthContributor.getSolaceBindingsHealthContributor().spliterator(), false))
                .isEmpty();
    }
}

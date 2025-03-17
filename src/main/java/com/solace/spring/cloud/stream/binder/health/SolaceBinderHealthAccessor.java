package com.solace.spring.cloud.stream.binder.health;

import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import com.solace.spring.cloud.stream.binder.health.contributors.BindingsHealthContributor;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;

import java.util.Optional;

/**
 * <p>Proxy class for the Solace binder to access health components.
 * Always use this instead of directly using health components in Solace binder code.</p>
 * <p>Allows for the Solace binder to still function correctly without actuator on the classpath.</p>
 */
public class SolaceBinderHealthAccessor {
    private final SolaceBinderHealthContributor solaceBinderHealthContributor;

    public SolaceBinderHealthAccessor(SolaceBinderHealthContributor solaceBinderHealthContributor) {
        this.solaceBinderHealthContributor = solaceBinderHealthContributor;
    }

    public SolaceHealthIndicator createBindingHealthIndicator(String bindingName) {
        return Optional.ofNullable(solaceBinderHealthContributor.getSolaceBindingsHealthContributor())
                .map(b -> b.getContributor(bindingName))
                .orElseGet(() -> {
                    SolaceHealthIndicator newBindingHealth = new SolaceHealthIndicator();
                    solaceBinderHealthContributor.getSolaceBindingsHealthContributor()
                            .addBindingContributor(bindingName, newBindingHealth);
                    return newBindingHealth;
                });
    }

    public void removeBindingHealthIndicator(String bindingName) {
        BindingsHealthContributor solaceBindingsHealthContributor = solaceBinderHealthContributor.getSolaceBindingsHealthContributor();
        if (solaceBindingsHealthContributor != null) {
            solaceBindingsHealthContributor.removeBindingContributor(bindingName);
        }
    }
}

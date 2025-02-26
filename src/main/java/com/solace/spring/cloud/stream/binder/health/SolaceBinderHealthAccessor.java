package com.solace.spring.cloud.stream.binder.health;

import com.solace.spring.cloud.stream.binder.health.contributors.BindingHealthContributor;
import com.solace.spring.cloud.stream.binder.health.contributors.BindingsHealthContributor;
import com.solace.spring.cloud.stream.binder.health.contributors.FlowsHealthContributor;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.indicators.FlowHealthIndicator;

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

    public FlowHealthIndicator createFlowHealthIndicator(String bindingName, String flowId) {
        FlowHealthIndicator flowHealthIndicator = new FlowHealthIndicator();
        Optional.ofNullable(solaceBinderHealthContributor.getSolaceBindingsHealthContributor())
                .map(b -> b.getContributor(bindingName))
                .orElseGet(() -> {
                    BindingHealthContributor newBindingHealth = new BindingHealthContributor(new FlowsHealthContributor());
                    solaceBinderHealthContributor.getSolaceBindingsHealthContributor()
                            .addBindingContributor(bindingName, newBindingHealth);
                    return newBindingHealth;
                })
                .getFlowsHealthContributor()
                .addFlowHealthIndicator(flowId, flowHealthIndicator);
        return flowHealthIndicator;
    }

    public void removeFlowHealthIndicator(String bindingName, String flowId) {
        BindingsHealthContributor solaceBindingsHealthContributor = solaceBinderHealthContributor.getSolaceBindingsHealthContributor();
        if (solaceBindingsHealthContributor != null) {
            BindingHealthContributor contributor = solaceBindingsHealthContributor.getContributor(bindingName);
            FlowsHealthContributor flowsHealthContributor = contributor.getFlowsHealthContributor();
            if (flowsHealthContributor != null) {
                flowsHealthContributor.removeFlowHealthIndicator(flowId);
            }
        }
    }
}

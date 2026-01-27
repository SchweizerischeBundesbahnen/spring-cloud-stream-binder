package com.solace.spring.cloud.stream.binder.health.contributors;

import com.solace.spring.cloud.stream.binder.health.indicators.ProvisioningHealthIndicator;
import com.solace.spring.cloud.stream.binder.health.indicators.SessionHealthIndicator;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributor;


import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class SolaceBinderHealthContributor implements CompositeHealthContributor {
    private final SessionHealthIndicator sessionHealthIndicator;
    private final BindingsHealthContributor bindingsHealthContributor;
    private final ProvisioningHealthIndicator provisioningHealthIndicator;
    private static final String CONNECTION = "connection";
    private static final String BINDINGS = "bindings";
    private static final String PROVISIONING = "provisioning";

    public SolaceBinderHealthContributor(SessionHealthIndicator sessionHealthIndicator,
                                         BindingsHealthContributor bindingsHealthContributor,
                                         ProvisioningHealthIndicator provisioningHealthIndicator) {
        this.sessionHealthIndicator = sessionHealthIndicator;
        this.bindingsHealthContributor = bindingsHealthContributor;
        this.provisioningHealthIndicator = provisioningHealthIndicator;
    }

    @Override
    public HealthContributor getContributor(String name) {
        return switch (name) {
            case CONNECTION -> sessionHealthIndicator;
            case BINDINGS -> bindingsHealthContributor;
            case PROVISIONING -> provisioningHealthIndicator;
            default -> null;
        };
    }

    public SessionHealthIndicator getSolaceSessionHealthIndicator() {
        return sessionHealthIndicator;
    }

    public BindingsHealthContributor getSolaceBindingsHealthContributor() {
        return bindingsHealthContributor;
    }

    public ProvisioningHealthIndicator getProvisioningHealthIndicator() {
        return provisioningHealthIndicator;
    }

    @Override
    public Stream<Entry> stream() {
        List<Entry> contributors = new ArrayList<>();
        contributors.add(new Entry(CONNECTION, sessionHealthIndicator));
        contributors.add(new Entry(BINDINGS, bindingsHealthContributor));
        contributors.add(new Entry(PROVISIONING, provisioningHealthIndicator));
        return contributors.stream();
    }
}

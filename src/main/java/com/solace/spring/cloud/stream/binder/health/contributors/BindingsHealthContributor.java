package com.solace.spring.cloud.stream.binder.health.contributors;

import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import org.springframework.boot.health.contributor.CompositeHealthContributor;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public class BindingsHealthContributor implements CompositeHealthContributor {
    private final Map<String, SolaceHealthIndicator> bindingHealthContributor = new HashMap<>();

    public void addBindingContributor(String bindingName, SolaceHealthIndicator bindingHealthIndicator) {
        this.bindingHealthContributor.put(bindingName, bindingHealthIndicator);
    }

    public void removeBindingContributor(String bindingName) {
        bindingHealthContributor.remove(bindingName);
    }

    @Override
    public SolaceHealthIndicator getContributor(String bindingName) {
        return bindingHealthContributor.get(bindingName);
    }

    @Override
    public Stream<Entry> stream() {
        return bindingHealthContributor.entrySet()
                .stream()
                .map(entry -> new Entry(entry.getKey(), entry.getValue()));
    }
}

package com.solace.spring.cloud.stream.binder.health.contributors;

import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.NamedContributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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
    public Iterator<NamedContributor<HealthContributor>> iterator() {
        return bindingHealthContributor.entrySet()
                .stream()
                .map((entry) -> NamedContributor.of(entry.getKey(), (HealthContributor) entry.getValue()))
                .iterator();
    }
}

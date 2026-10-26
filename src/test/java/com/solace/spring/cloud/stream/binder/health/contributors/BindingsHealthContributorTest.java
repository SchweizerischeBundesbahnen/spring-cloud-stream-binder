package com.solace.spring.cloud.stream.binder.health.contributors;

import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A health probe reads the binding health indicators on its own thread while consumer bindings start and stop on
 * another, so reading them must survive a binding that is added or removed in the middle of the read. Changing the
 * contributor from inside its own stream puts that change between two reads on every run, where two threads would
 * hit it only by chance.
 */
class BindingsHealthContributorTest {
    private static final String RUNNING_BINDING = "running-in-0";
    private static final String STARTING_BINDING = "starting-in-0";
    private static final String STOPPING_BINDING = "stopping-in-0";

    @Test
    void aHealthRead_duringTheStartOfABinding_listsTheBindingsThatWereAlreadyRunning() {
        BindingsHealthContributor contributor = new BindingsHealthContributor();
        contributor.addBindingContributor(RUNNING_BINDING, new SolaceHealthIndicator());
        List<String> bindingsRead = new ArrayList<>();

        contributor.stream().forEach(entry -> {
            contributor.addBindingContributor(STARTING_BINDING, new SolaceHealthIndicator());
            bindingsRead.add(entry.name());
        });

        assertThat(bindingsRead).contains(RUNNING_BINDING);
    }

    @Test
    void aHealthRead_duringTheStopOfABinding_listsTheBindingsThatKeepRunning() {
        BindingsHealthContributor contributor = new BindingsHealthContributor();
        contributor.addBindingContributor(RUNNING_BINDING, new SolaceHealthIndicator());
        contributor.addBindingContributor(STOPPING_BINDING, new SolaceHealthIndicator());
        List<String> bindingsRead = new ArrayList<>();

        contributor.stream().forEach(entry -> {
            contributor.removeBindingContributor(STOPPING_BINDING);
            bindingsRead.add(entry.name());
        });

        assertThat(bindingsRead).contains(RUNNING_BINDING);
    }
}

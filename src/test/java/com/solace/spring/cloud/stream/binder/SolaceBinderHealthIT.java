package com.solace.spring.cloud.stream.binder;

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.config.SolaceHealthIndicatorsConfiguration;
import com.solace.spring.cloud.stream.binder.health.SolaceBinderHealthAccessor;
import com.solace.spring.cloud.stream.binder.health.contributors.BindingsHealthContributor;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.indicators.SessionHealthIndicator;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.test.junit.extension.SpringCloudStreamExtension;
import com.solace.spring.cloud.stream.binder.test.spring.ConsumerInfrastructureUtil;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SolaceSpringCloudStreamAssertions;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.config.model.ConfigMsgVpnQueue;
import com.solacesystems.jcsmp.JCSMPProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.junitpioneer.jupiter.cartesian.CartesianTest.Values;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.MessageChannel;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.solace.spring.cloud.stream.binder.test.util.RetryableAssertions.retryAssert;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringJUnitConfig(classes = {
        SolaceHealthIndicatorsConfiguration.class,
        SolaceJavaAutoConfiguration.class
}, initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(PubSubPlusExtension.class)
@ExtendWith(SpringCloudStreamExtension.class)
public class SolaceBinderHealthIT {

    @CartesianTest(name = "[{index}] channelType={0}, autoStart={1} concurrency={2}")
    public <T> void testConsumerFlowHealthProvisioning(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            @Values(booleans = {true, false}) boolean autoStart,
            @Values(ints = {1, 3}) int concurrency,
            SpringCloudStreamContext context) throws Exception {

        SolaceTestBinder binder = context.getBinder();

        BindingsHealthContributor bindingsHealthContributor = new BindingsHealthContributor();
        setupBinder(bindingsHealthContributor, binder);

        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.populateBindingName(RandomStringUtils.randomAlphanumeric(10));
        consumerProperties.setAutoStartup(autoStart);
        consumerProperties.setConcurrency(concurrency);

        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        context.binderBindUnbindLatency();

        if (!autoStart) {
            assertThat(bindingsHealthContributor.iterator().hasNext()).isFalse();
            log.info("Starting binding...");
            consumerBinding.start();
        }

        assertThat(bindingsHealthContributor)
                .asInstanceOf(InstanceOfAssertFactories.type(BindingsHealthContributor.class))
                .satisfies(SolaceSpringCloudStreamAssertions.isSingleBindingHealthAvailable(consumerProperties.getBindingName(), Status.UP));

        log.info("Pausing binding...");
        consumerBinding.pause();
        assertThat(bindingsHealthContributor)
                .asInstanceOf(InstanceOfAssertFactories.type(BindingsHealthContributor.class))
                .satisfies(SolaceSpringCloudStreamAssertions.isSingleBindingHealthAvailable(consumerProperties.getBindingName(), Status.UP));

        log.info("Stopping binding...");
        consumerBinding.stop();
        assertThat(bindingsHealthContributor)
                .asInstanceOf(InstanceOfAssertFactories.type(BindingsHealthContributor.class))
                .extracting(c -> c.getContributor(consumerProperties.getBindingName()))
                .isNull();

        log.info("Starting binding...");
        consumerBinding.start();
        assertThat(bindingsHealthContributor)
                .asInstanceOf(InstanceOfAssertFactories.type(BindingsHealthContributor.class))
                .satisfies(SolaceSpringCloudStreamAssertions.isSingleBindingHealthAvailable(consumerProperties.getBindingName(), Status.UP));

        log.info("Resuming binding...");
        consumerBinding.resume();
        assertThat(bindingsHealthContributor)
                .asInstanceOf(InstanceOfAssertFactories.type(BindingsHealthContributor.class))
                .satisfies(SolaceSpringCloudStreamAssertions.isSingleBindingHealthAvailable(consumerProperties.getBindingName(), Status.UP));

        consumerBinding.unbind();
    }


    @CartesianTest(name = "[{index}] channelType={0}, concurrency={1} healthStatus={2}")
    public <T> void testConsumerFlowHealthUnhealthy(
            @Values(classes = {DirectChannel.class}) Class<T> channelType,
            @Values(ints = {1, 3}) int concurrency,
            @Values(strings = {"DOWN", "RECONNECTING"}) String healthStatus,
            SempV2Api sempV2Api,
            SpringCloudStreamContext context) throws Exception {

        SolaceTestBinder binder = context.getBinder();

        BindingsHealthContributor bindingsHealthContributor = new BindingsHealthContributor();
        setupBinder(bindingsHealthContributor, binder);

        ConsumerInfrastructureUtil<T> consumerInfrastructureUtil = context.createConsumerInfrastructureUtil(channelType);
        T moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.populateBindingName(RandomStringUtils.randomAlphanumeric(10));
        consumerProperties.setConcurrency(concurrency);

        Binding<T> consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        context.binderBindUnbindLatency();

        assertThat(bindingsHealthContributor)
                .asInstanceOf(InstanceOfAssertFactories.type(BindingsHealthContributor.class))
                .satisfies(SolaceSpringCloudStreamAssertions.isSingleBindingHealthAvailable(consumerProperties.getBindingName(), Status.UP));

        String vpnName = (String) context.getJcsmpSession().getProperty(JCSMPProperties.VPN_NAME);
        String queueName = binder.getConsumerQueueName(consumerBinding);
        log.info(String.format("Disabling egress for queue %s", queueName));
        switch (healthStatus) {
            case "DOWN" -> sempV2Api.config().deleteMsgVpnQueue(vpnName, queueName);
            case "RECONNECTING" -> sempV2Api.config()
                    .updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(false), vpnName, queueName, null, null);
            default -> throw new IllegalArgumentException("No test for health status: " + healthStatus);
        }

        // Map RECONNECTING to DOWN status since reconnecting now immediately goes to DOWN
        Status expectedStatus = healthStatus.equals("RECONNECTING") ? Status.DOWN : new Status(healthStatus);

        retryAssert(2, TimeUnit.MINUTES,
                () -> assertThat(bindingsHealthContributor)
                        .asInstanceOf(InstanceOfAssertFactories.type(BindingsHealthContributor.class))
                        .satisfies(SolaceSpringCloudStreamAssertions.isSingleBindingHealthAvailable(
                                consumerProperties.getBindingName(), expectedStatus)));

        if (healthStatus.equals("RECONNECTING")) {
            sempV2Api.config()
                    .updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(true), vpnName, queueName, null, null);
            retryAssert(2, TimeUnit.MINUTES,
                    () -> assertThat(bindingsHealthContributor)
                            .asInstanceOf(InstanceOfAssertFactories.type(BindingsHealthContributor.class))
                            .satisfies(SolaceSpringCloudStreamAssertions.isSingleBindingHealthAvailable(
                                    consumerProperties.getBindingName(), Status.UP)));
        }

        consumerBinding.unbind();
    }

    private static void setupBinder(BindingsHealthContributor bindingsHealthContributor, SolaceTestBinder binder) throws NoSuchFieldException, IllegalAccessException {
        SolaceBinderHealthAccessor solaceBinderHealthAccessor = new SolaceBinderHealthAccessor(
                new SolaceBinderHealthContributor(new SessionHealthIndicator(),
                        bindingsHealthContributor));
        Field solaceBinderHealthAccessorField = binder.getBinder().getClass().getDeclaredField("solaceBinderHealthAccessor");
        solaceBinderHealthAccessorField.setAccessible(true);
        solaceBinderHealthAccessorField.set(binder.getBinder(), Optional.of(solaceBinderHealthAccessor));
    }
}
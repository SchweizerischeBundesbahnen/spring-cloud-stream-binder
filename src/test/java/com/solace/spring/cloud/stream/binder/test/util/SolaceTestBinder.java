package com.solace.spring.cloud.stream.binder.test.util;

import com.solace.spring.cloud.stream.binder.SolaceMessageChannelBinder;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceProvisioningUtil;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.config.ApiException;
import com.solacesystems.jcsmp.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.cloud.stream.binder.AbstractTestBinder;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.messaging.MessageChannel;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class SolaceTestBinder
        extends AbstractTestBinder<SolaceMessageChannelBinder, ExtendedConsumerProperties<SolaceConsumerProperties>, ExtendedProducerProperties<SolaceProducerProperties>> {

    private final JCSMPSession jcsmpSession;
    private final SempV2Api sempV2Api;
    private final Context context;
    @Getter
    private final AnnotationConfigApplicationContext applicationContext;
    private final Set<String> endpoints = new HashSet<>();
    private final Map<String, String> bindingNameToQueueName = new HashMap<>();
    private final Map<String, String> bindingNameToErrorQueueName = new HashMap<>();

    public SolaceTestBinder(JCSMPSession jcsmpSession, Context context, SempV2Api sempV2Api, BeanFactory beanFactory) {
        this.applicationContext = new AnnotationConfigApplicationContext(Config.class);
        this.jcsmpSession = jcsmpSession;
        this.sempV2Api = sempV2Api;
        this.context = context;
        SolaceMessageChannelBinder binder = new SolaceMessageChannelBinder(jcsmpSession, context, new SolaceEndpointProvisioner(jcsmpSession, Optional.empty()), beanFactory, Optional.empty(), Optional.empty(), Optional.empty());
        binder.setApplicationContext(this.applicationContext);
        this.setBinder(binder);
    }

    public SolaceTestBinder(SolaceTestBinder original, TracingProxy tracingProxy, BeanFactory beanFactory) {
        this.applicationContext = new AnnotationConfigApplicationContext(Config.class);
        this.jcsmpSession = original.jcsmpSession;
        this.sempV2Api = original.sempV2Api;
        this.context = original.context;
        SolaceMessageChannelBinder binder = new SolaceMessageChannelBinder(jcsmpSession, this.context, new SolaceEndpointProvisioner(jcsmpSession, Optional.empty()), beanFactory, Optional.empty(), Optional.of(tracingProxy), Optional.empty());
        binder.setApplicationContext(this.applicationContext);
        this.setBinder(binder);
    }

    @Override
    public Binding<MessageChannel> bindConsumer(String name, String group, MessageChannel moduleInputChannel, ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        preBindCaptureConsumerResources(name, group, properties);
        Binding<MessageChannel> binding = super.bindConsumer(name, group, moduleInputChannel, properties);
        captureConsumerResources(binding, group, properties.getExtension());
        return binding;
    }

    @Override
    public Binding<MessageChannel> bindProducer(String name, MessageChannel moduleOutputChannel,
                                                ExtendedProducerProperties<SolaceProducerProperties> properties) {
        if (properties.getRequiredGroups() != null) {
            Arrays.stream(properties.getRequiredGroups())
                    .forEach(g -> preBindCaptureProducerResources(name, g, properties));
        }

        return super.bindProducer(name, moduleOutputChannel, properties);
    }

    public String getConsumerQueueName(Binding<?> binding) {
        return bindingNameToQueueName.get(binding.getBindingName());
    }

    public String getConsumerErrorQueueName(Binding<?> binding) {
        return bindingNameToErrorQueueName.get(binding.getBindingName());
    }

    private void preBindCaptureConsumerResources(String name, String group, ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties) {
        if (SolaceProvisioningUtil.isAnonEndpoint(group, consumerProperties.getExtension().getQualityOfService()))
            return; // we don't know any anon resource names before binding

        SolaceProvisioningUtil.QueueNames queueNames = SolaceProvisioningUtil.getQueueNames(name, group, consumerProperties, false);

        // values set here may be overwritten after binding
        endpoints.add(queueNames.getConsumerGroupQueueName());
        if (consumerProperties.getExtension().isAutoBindErrorQueue()) {
            endpoints.add(queueNames.getErrorQueueName());
        }
    }

    private void captureConsumerResources(Binding<?> binding, String group, SolaceConsumerProperties consumerProperties) {
        String endpointName = extractBindingDestination(binding);
        bindingNameToQueueName.put(binding.getBindingName(), endpointName);
        if (!SolaceProvisioningUtil.isAnonEndpoint(group, consumerProperties.getQualityOfService())) {
            endpoints.add(endpointName);
        }
        if (consumerProperties.isAutoBindErrorQueue()) {
            String errorQueueName = extractErrorQueueName(binding);
            endpoints.add(errorQueueName);
            bindingNameToErrorQueueName.put(binding.getBindingName(), errorQueueName);
        }
    }

    private void preBindCaptureProducerResources(String name, String group, ExtendedProducerProperties<SolaceProducerProperties> producerProperties) {
        String queueName = SolaceProvisioningUtil.getQueueName(name, group, producerProperties);
        endpoints.add(queueName);
    }

    private String extractBindingDestination(Binding<?> binding) {
        String destination = (String) binding.getExtendedInfo().getOrDefault("bindingDestination", "");
        assertThat(destination).startsWith("SolaceConsumerDestination");
        Matcher matcher = Pattern.compile("endpointName='(.*?)'").matcher(destination);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String extractErrorQueueName(Binding<?> binding) {
        String destination = (String) binding.getExtendedInfo().getOrDefault("bindingDestination", "");
        assertThat(destination).startsWith("SolaceConsumerDestination");
        Matcher matcher = Pattern.compile("errorQueueName='(.*?)'").matcher(destination);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @Override
    public void cleanup() {
        for (String endpointEntry : endpoints) {
            try {
                log.info(String.format("De-provisioning endpoint %s", endpointEntry));
                Endpoint endpoint;
                try {
                    endpoint = JCSMPFactory.onlyInstance().createQueue(endpointEntry);
                } catch (Exception e) {
                    //This is possible as we eagerly add endpoints to cleanup in preBindCaptureConsumerResources()
                    log.info("Skipping de-provisioning as queue name is invalid; queue was never provisioned {}", endpointEntry);
                    continue;
                }
                jcsmpSession.deprovision(endpoint, JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
            } catch (JCSMPException | AccessDeniedException e) {
                try {
                    String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
                    sempV2Api.config().deleteMsgVpnQueue(vpnName, endpointEntry);
                } catch (ApiException e1) {
                    RuntimeException toThrow = new RuntimeException(e);
                    toThrow.addSuppressed(e1);
                    throw toThrow;
                }
            }
        }
    }

    @Configuration
    @EnableIntegration
    static class Config {
    }
}

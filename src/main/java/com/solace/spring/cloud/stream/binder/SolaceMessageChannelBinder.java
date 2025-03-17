package com.solace.spring.cloud.stream.binder;

import com.solace.spring.cloud.stream.binder.health.SolaceBinderHealthAccessor;
import com.solace.spring.cloud.stream.binder.inbound.queue.JCSMPInboundQueueMessageProducer;
import com.solace.spring.cloud.stream.binder.inbound.topic.JCSMPInboundTopicMessageMultiplexer;
import com.solace.spring.cloud.stream.binder.inbound.topic.JCSMPInboundTopicMessageProducer;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.outbound.JCSMPOutboundMessageHandler;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceExtendedBindingProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceProvisioningUtil;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import com.solace.spring.cloud.stream.binder.util.*;
import com.solacesystems.jcsmp.*;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.stream.binder.*;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.support.RetryTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
public class SolaceMessageChannelBinder
        extends AbstractMessageChannelBinder<ExtendedConsumerProperties<SolaceConsumerProperties>,
        ExtendedProducerProperties<SolaceProducerProperties>,
        SolaceEndpointProvisioner>
        implements ExtendedPropertiesBinder<MessageChannel,
        SolaceConsumerProperties, SolaceProducerProperties>, DisposableBean {

    private final JCSMPSession jcsmpSession;
    private final JCSMPInboundTopicMessageMultiplexer jcsmpInboundTopicMessageMultiplexer;
    private final Context jcsmpContext;
    private final JCSMPSessionProducerManager sessionProducerManager;
    private final String errorHandlerProducerKey = UUID.randomUUID().toString();
    private final Optional<SolaceMeterAccessor> solaceMeterAccessor;
    private final Optional<TracingProxy> tracingProxy;
    private final Optional<SolaceBinderHealthAccessor> solaceBinderHealthAccessor;

    @Setter
    private SolaceExtendedBindingProperties extendedBindingProperties = new SolaceExtendedBindingProperties();
    private static final SolaceMessageHeaderErrorMessageStrategy errorMessageStrategy = new SolaceMessageHeaderErrorMessageStrategy();

    public SolaceMessageChannelBinder(JCSMPSession jcsmpSession,
                                      Context jcsmpContext,
                                      SolaceEndpointProvisioner solaceEndpointProvisioner,
                                      Optional<SolaceMeterAccessor> solaceMeterAccessor,
                                      Optional<TracingProxy> tracingProxy,
                                      Optional<SolaceBinderHealthAccessor> solaceBinderHealthAccessor) {
        super(new String[0], solaceEndpointProvisioner);
        this.jcsmpSession = jcsmpSession;
        this.jcsmpContext = jcsmpContext;
        this.solaceMeterAccessor = solaceMeterAccessor;
        this.tracingProxy = tracingProxy;
        this.solaceBinderHealthAccessor = solaceBinderHealthAccessor;
        this.sessionProducerManager = new JCSMPSessionProducerManager(jcsmpSession);
        this.jcsmpInboundTopicMessageMultiplexer = new JCSMPInboundTopicMessageMultiplexer(jcsmpSession, this.solaceMeterAccessor, this.tracingProxy);
    }

    @Override
    public String getBinderIdentity() {
        return "solace-" + super.getBinderIdentity();
    }

    @Override
    public void destroy() {
        if (jcsmpSession != null) {
            log.info("Closing JCSMP session {}", jcsmpSession.getSessionName());
        }
        if (sessionProducerManager != null) {
            sessionProducerManager.release(errorHandlerProducerKey);
        }
        if (jcsmpSession != null) {
            jcsmpSession.closeSession();
        }
        if (jcsmpContext != null) {
            jcsmpContext.destroy();
        }
    }

    @Override
    protected MessageHandler createProducerMessageHandler(ProducerDestination destination,
                                                          ExtendedProducerProperties<SolaceProducerProperties> producerProperties,
                                                          MessageChannel errorChannel) {
        JCSMPOutboundMessageHandler handler = new JCSMPOutboundMessageHandler(
                destination,
                jcsmpSession,
                errorChannel,
                sessionProducerManager,
                producerProperties,
                solaceMeterAccessor,
                tracingProxy);

        if (errorChannel != null) {
            handler.setErrorMessageStrategy(errorMessageStrategy);
        }

        return handler;
    }

    @Override
    protected MessageProducer createConsumerEndpoint(ConsumerDestination destination, String group, ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        if (properties.isBatchMode()) {
            throw new IllegalArgumentException("Batched consumers are not supported");
        }
        if (properties.getExtension() != null && properties.getExtension().getQualityOfService() == QualityOfService.AT_MOST_ONCE) {
            return createTopicMessageProducer(destination, group, properties);
        }
        return createQueueMessageProducer(destination, group, properties);
    }

    protected MessageProducer createQueueMessageProducer(ConsumerDestination destination,
                                                         String group,
                                                         ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties) {

        SolaceConsumerDestination consumerDestination = (SolaceConsumerDestination) destination;
        EndpointProperties endpointProperties = getConsumerEndpointProperties(consumerProperties);
        Optional<RetryTemplate> retryTemplate;
        Optional<RecoveryCallback<?>> recoveryCallback;
        Optional<ErrorQueueInfrastructure> errorQueueInfrastructure;

        if (consumerProperties.getExtension().isAutoBindErrorQueue()) {
            errorQueueInfrastructure = Optional.of(new ErrorQueueInfrastructure(
                    sessionProducerManager,
                    errorHandlerProducerKey,
                    consumerDestination.getErrorQueueName(),
                    consumerProperties.getExtension()));
        } else {
            errorQueueInfrastructure = Optional.empty();
        }

        ErrorInfrastructure errorInfra = registerErrorInfrastructure(destination, group, consumerProperties);
        if (consumerProperties.getMaxAttempts() > 1) {
            retryTemplate = Optional.of(buildRetryTemplate(consumerProperties));
            recoveryCallback = Optional.of(errorInfra.getRecoverer());
        } else {
            retryTemplate = Optional.empty();
            recoveryCallback = Optional.empty();
        }

        JCSMPInboundQueueMessageProducer adapter = new JCSMPInboundQueueMessageProducer(
                consumerDestination,
                jcsmpSession,
                consumerProperties,
                endpointProperties,
                getConsumerPostStart(consumerDestination, consumerProperties),
                solaceMeterAccessor,
                tracingProxy,
                solaceBinderHealthAccessor,
                retryTemplate,
                recoveryCallback,
                errorQueueInfrastructure);

        if (retryTemplate.isEmpty()){
            adapter.setErrorChannel(errorInfra.getErrorChannel());
        }
        adapter.setErrorMessageStrategy(errorMessageStrategy);
        return adapter;

    }

    protected MessageProducer createTopicMessageProducer(ConsumerDestination destination, String group, ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        JCSMPInboundTopicMessageProducer topicMessageProducer = this.jcsmpInboundTopicMessageMultiplexer.createTopicMessageProducer(destination, group, properties);
        AbstractMessageChannelBinder.ErrorInfrastructure errorInfra = registerErrorInfrastructure(destination, group, properties);

        topicMessageProducer.setErrorChannel(errorInfra.getErrorChannel());
        topicMessageProducer.setErrorMessageStrategy(errorMessageStrategy);
        return topicMessageProducer;
    }


    @Override
    protected PolledConsumerResources createPolledConsumerResources(String name, String group,
                                                                    ConsumerDestination destination,
                                                                    ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties) {
        throw new UnsupportedOperationException("PolledConsumerResources are not supported");
    }

    @Override
    protected void postProcessPollableSource(DefaultPollableMessageSource bindingTarget) {
        throw new UnsupportedOperationException("PolledConsumerResources are not supported");
    }

    @Override
    protected MessageHandler getErrorMessageHandler(ConsumerDestination destination, String group,
                                                    ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties) {
        return new SolaceErrorMessageHandler();
    }

    @Override
    protected MessageHandler getPolledConsumerErrorMessageHandler(ConsumerDestination destination, String group,
                                                                  ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties) {
        throw new UnsupportedOperationException("PolledConsumerResources are not supported");
    }

    @Override
    protected ErrorMessageStrategy getErrorMessageStrategy() {
        return errorMessageStrategy;
    }

    @Override
    public SolaceConsumerProperties getExtendedConsumerProperties(String channelName) {
        return extendedBindingProperties.getExtendedConsumerProperties(channelName);
    }

    @Override
    public SolaceProducerProperties getExtendedProducerProperties(String channelName) {
        return extendedBindingProperties.getExtendedProducerProperties(channelName);
    }

    @Override
    public String getDefaultsPrefix() {
        return this.extendedBindingProperties.getDefaultsPrefix();
    }

    @Override
    public Class<? extends BinderSpecificPropertiesProvider> getExtendedPropertiesEntryClass() {
        return this.extendedBindingProperties.getExtendedPropertiesEntryClass();
    }

    /**
     * WORKAROUND (SOL-4272) ----------------------------------------------------------
     * Temporary endpoints are only provisioned when the consumer is created.
     * Ideally, these should be done within the provisioningProvider itself.
     */
    private EndpointProperties getConsumerEndpointProperties(ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        return SolaceProvisioningUtil.getEndpointProperties(properties.getExtension());
    }

    /**
     * WORKAROUND (SOL-4272) ----------------------------------------------------------
     * Temporary endpoints are only provisioned when the consumer is created.
     * Ideally, these should be done within the provisioningProvider itself.
     */
    private Consumer<Endpoint> getConsumerPostStart(SolaceConsumerDestination destination,
                                                    ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        return (endpoint) -> {
            if (endpoint instanceof Queue queue) {
                provisioningProvider.addSubscriptionToQueue(queue, destination.getBindingDestinationName(), properties.getExtension(), true);

                //Process additional subscriptions
                for (String subscription : destination.getAdditionalSubscriptions()) {
                    provisioningProvider.addSubscriptionToQueue(queue, subscription, properties.getExtension(), false);
                }
            }
        };
    }
}

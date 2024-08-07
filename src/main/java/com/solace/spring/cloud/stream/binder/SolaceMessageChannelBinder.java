package com.solace.spring.cloud.stream.binder;

import com.solace.spring.cloud.stream.binder.health.SolaceBinderHealthAccessor;
import com.solace.spring.cloud.stream.binder.inbound.*;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.outbound.JCSMPOutboundMessageHandler;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceExtendedBindingProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceProvisioningUtil;
import com.solace.spring.cloud.stream.binder.util.*;
import com.solacesystems.jcsmp.*;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.stream.binder.*;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.lang.Nullable;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean consumersRemoteStopFlag = new AtomicBoolean(false);
    private final String errorHandlerProducerKey = UUID.randomUUID().toString();
    @Setter
    private SolaceMeterAccessor solaceMeterAccessor;
    @Setter
    private SolaceExtendedBindingProperties extendedBindingProperties = new SolaceExtendedBindingProperties();
    private static final SolaceMessageHeaderErrorMessageStrategy errorMessageStrategy = new SolaceMessageHeaderErrorMessageStrategy();
    @Nullable
    private SolaceBinderHealthAccessor solaceBinderHealthAccessor;

    public SolaceMessageChannelBinder(JCSMPSession jcsmpSession, SolaceEndpointProvisioner solaceEndpointProvisioner) {
        this(jcsmpSession, null, solaceEndpointProvisioner);
    }

    public SolaceMessageChannelBinder(JCSMPSession jcsmpSession, Context jcsmpContext, SolaceEndpointProvisioner solaceEndpointProvisioner) {
        super(new String[0], solaceEndpointProvisioner);
        this.jcsmpSession = jcsmpSession;
        this.jcsmpContext = jcsmpContext;
        this.sessionProducerManager = new JCSMPSessionProducerManager(jcsmpSession);
        this.jcsmpInboundTopicMessageMultiplexer = new JCSMPInboundTopicMessageMultiplexer(jcsmpSession, () -> this.solaceMeterAccessor);
    }

    @Override
    public String getBinderIdentity() {
        return "solace-" + super.getBinderIdentity();
    }

    @Override
    public void destroy() {
        log.info(String.format("Closing JCSMP session %s", jcsmpSession.getSessionName()));
        sessionProducerManager.release(errorHandlerProducerKey);
        consumersRemoteStopFlag.set(true);
        jcsmpSession.closeSession();
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
                solaceMeterAccessor);

        if (errorChannel != null) {
            handler.setErrorMessageStrategy(errorMessageStrategy);
        }

        return handler;
    }

    @Override
    protected MessageProducer createConsumerEndpoint(ConsumerDestination destination, String group, ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        if (properties.getExtension() != null && properties.getExtension().getQualityOfService() == QualityOfService.AT_MOST_ONCE) {
            return createTopicMessageProducer(destination, group, properties);
        }
        if (!properties.isBatchMode() && properties.getExtension().isTransacted()) {
            throw new IllegalArgumentException("Non-batched, transacted consumers are not supported");
        }

        if (properties.getExtension().isTransacted() && properties.getExtension().isAutoBindErrorQueue()) {
            throw new IllegalArgumentException("transacted consumers do not support error queues");
        }
        return createQueueMessageProducer(destination, group, properties);
    }

    protected MessageProducer createQueueMessageProducer(ConsumerDestination destination, String group, ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        SolaceConsumerDestination solaceDestination = (SolaceConsumerDestination) destination;

        JCSMPInboundQueueMessageProducer adapter = new JCSMPInboundQueueMessageProducer(
                solaceDestination,
                jcsmpSession,
                properties,
                getConsumerEndpointProperties(properties),
                solaceMeterAccessor);

        if (solaceBinderHealthAccessor != null) {
            adapter.setSolaceBinderHealthAccessor(solaceBinderHealthAccessor);
        }

        adapter.setRemoteStopFlag(consumersRemoteStopFlag);
        adapter.setPostStart(getConsumerPostStart(solaceDestination, properties));

        if (properties.getExtension().isAutoBindErrorQueue()) {
            adapter.setErrorQueueInfrastructure(new ErrorQueueInfrastructure(
                    sessionProducerManager,
                    errorHandlerProducerKey,
                    solaceDestination.getErrorQueueName(),
                    properties.getExtension()));
        }

        ErrorInfrastructure errorInfra = registerErrorInfrastructure(destination, group, properties);
        if (properties.getMaxAttempts() > 1) {
            adapter.setRetryTemplate(buildRetryTemplate(properties));
            adapter.setRecoveryCallback(errorInfra.getRecoverer());
        } else {
            adapter.setErrorChannel(errorInfra.getErrorChannel());
        }

        adapter.setErrorMessageStrategy(errorMessageStrategy);
        return adapter;

    }

    protected MessageProducer createTopicMessageProducer(ConsumerDestination destination, String group, ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        JCSMPInboundTopicMessageProducer topicMessageProducer = this.jcsmpInboundTopicMessageMultiplexer.createTopicMessageProducer(destination, group, properties);
        AbstractMessageChannelBinder.ErrorInfrastructure errorInfra = registerErrorInfrastructure(destination, null, properties);

        topicMessageProducer.setErrorChannel(errorInfra.getErrorChannel());
        topicMessageProducer.setErrorMessageStrategy(errorMessageStrategy);
        return topicMessageProducer;
    }


    @Override
    protected PolledConsumerResources createPolledConsumerResources(String name, String group,
                                                                    ConsumerDestination destination,
                                                                    ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties) {
        if (!consumerProperties.isBatchMode() && consumerProperties.getExtension().isTransacted()) {
            throw new IllegalArgumentException("Non-batched, transacted consumers are not supported");
        }
        if (consumerProperties.getExtension().isTransacted() && consumerProperties.getExtension().isAutoBindErrorQueue()) {
            throw new IllegalArgumentException("transacted consumers do not support error queues");
        }
        if (consumerProperties.getConcurrency() > 1) {
            log.warn("Polled consumers do not support concurrency > 1, it will be ignored...");
        }
        if (consumerProperties.isBatchMode()) {
            log.error("BatchMode is deprecated and should not be used.");
        }

        SolaceConsumerDestination solaceDestination = (SolaceConsumerDestination) destination;

        EndpointProperties endpointProperties = getConsumerEndpointProperties(consumerProperties);
        JCSMPMessageSource messageSource = new JCSMPMessageSource(solaceDestination,
                jcsmpSession,
                consumerProperties.isBatchMode() ? new BatchCollector(consumerProperties.getExtension()) : null,
                consumerProperties,
                endpointProperties,
                solaceMeterAccessor);

        if (solaceBinderHealthAccessor != null) {
            messageSource.setSolaceBinderHealthAccessor(solaceBinderHealthAccessor);
        }

        messageSource.setRemoteStopFlag(consumersRemoteStopFlag::get);
        messageSource.setPostStart(getConsumerPostStart(solaceDestination, consumerProperties));

        if (consumerProperties.getExtension().isAutoBindErrorQueue()) {
            messageSource.setErrorQueueInfrastructure(new ErrorQueueInfrastructure(sessionProducerManager,
                    errorHandlerProducerKey,
                    solaceDestination.getErrorQueueName(),
                    consumerProperties.getExtension()));
        }

        ErrorInfrastructure errorInfra = registerErrorInfrastructure(destination, group, consumerProperties, true);
        return new PolledConsumerResources(messageSource, errorInfra);
    }

    @Override
    protected void postProcessPollableSource(DefaultPollableMessageSource bindingTarget) {
        bindingTarget.setAttributesProvider((accessor, message) -> {
            Object sourceData = StaticMessageHeaderAccessor.getSourceData(message);
            if (sourceData == null || sourceData instanceof XMLMessage || sourceData instanceof List) {
                accessor.setAttribute(SolaceMessageHeaderErrorMessageStrategy.ATTR_SOLACE_RAW_MESSAGE, sourceData);
            }
        });
    }

    @Override
    protected MessageHandler getErrorMessageHandler(ConsumerDestination destination, String group,
                                                    ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties) {
        return new SolaceErrorMessageHandler();
    }

    @Override
    protected MessageHandler getPolledConsumerErrorMessageHandler(ConsumerDestination destination, String group,
                                                                  ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties) {
        final MessageHandler handler = getErrorMessageHandler(destination, group, consumerProperties);
        if (handler != null) {
            return handler;
        } else {
            return super.getPolledConsumerErrorMessageHandler(destination, group, consumerProperties);
        }
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

    public void setSolaceBinderHealthAccessor(@Nullable SolaceBinderHealthAccessor solaceBinderHealthAccessor) {
        this.solaceBinderHealthAccessor = solaceBinderHealthAccessor;
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

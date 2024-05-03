package com.solace.spring.cloud.stream.binder.provisioning;

import com.solace.spring.cloud.stream.binder.properties.SolaceCommonProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.util.DestinationType;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solacesystems.jcsmp.*;
import lombok.RequiredArgsConstructor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.cloud.stream.provisioning.ProvisioningException;
import org.springframework.cloud.stream.provisioning.ProvisioningProvider;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class SolaceQueueProvisioner implements ProvisioningProvider<ExtendedConsumerProperties<SolaceConsumerProperties>, ExtendedProducerProperties<SolaceProducerProperties>> {

    private final JCSMPSession jcsmpSession;
    private final JCSMPSessionEventHandler jcsmpSessionEventHandler;
    private static final Log logger = LogFactory.getLog(SolaceQueueProvisioner.class);


    @Override
    public ProducerDestination provisionProducerDestination(String name, ExtendedProducerProperties<SolaceProducerProperties> properties) throws ProvisioningException {

        if (properties.isPartitioned()) {
            logger.warn("Partitioning is not supported with this version of Solace's cloud stream binder.\n" + "Provisioning will continue under the assumption that it is disabled...");
        }

        if (properties.getExtension().getDestinationType() == DestinationType.QUEUE) {
            if (properties.getRequiredGroups() != null && properties.getRequiredGroups().length > 0) {
                throw new ProvisioningException(String.format("Producer requiredGroups are not supported when destinationType=%s", DestinationType.QUEUE));
            }
            provisionQueueIfRequired(name, properties);
            return new SolaceProducerDestination(name);
        }

        String topicName = SolaceProvisioningUtil.getTopicName(name, properties.getExtension());

        Set<String> requiredGroups = new HashSet<>(Arrays.asList(properties.getRequiredGroups()));
        Map<String, String[]> requiredGroupsExtraSubs = properties.getExtension().getQueueAdditionalSubscriptions();

        for (String groupName : requiredGroups) {
            String queueName = SolaceProvisioningUtil.getQueueName(topicName, groupName, properties);
            logger.info(String.format("Creating durable queue %s for required consumer group %s", queueName, groupName));
            Queue queue = provisionQueueIfRequired(queueName, properties);
            addSubscriptionToQueue(queue, topicName, properties.getExtension(), true);

            for (String extraTopic : requiredGroupsExtraSubs.getOrDefault(groupName, new String[0])) {
                addSubscriptionToQueue(queue, extraTopic, properties.getExtension(), false);
            }
        }

        Set<String> ignoredExtraSubs = requiredGroupsExtraSubs.keySet().stream().filter(g -> !requiredGroups.contains(g)).collect(Collectors.toSet());

        if (ignoredExtraSubs.size() > 0) {
            logger.warn(String.format("Groups [%s] are not required groups. The additional subscriptions defined for them were ignored...", String.join(", ", ignoredExtraSubs)));
        }

        return new SolaceProducerDestination(topicName);
    }

    @Override
    public ConsumerDestination provisionConsumerDestination(String name, String group, ExtendedConsumerProperties<SolaceConsumerProperties> properties) throws ProvisioningException {

        if (properties.isPartitioned()) {
            logger.warn("Partitioning is not supported with this version of Solace's cloud stream binder.\n" + "Provisioning will continue under the assumption that it is disabled...");
        }

        boolean isAnonQueue = SolaceProvisioningUtil.isAnonQueue(group, properties.getExtension().getQualityOfService());
        boolean isDurableQueue = SolaceProvisioningUtil.isDurableQueue(group, properties.getExtension().getQualityOfService());
        SolaceProvisioningUtil.QueueNames queueNames = SolaceProvisioningUtil.getQueueNames(name, group, properties, isAnonQueue);
        String groupQueueName = queueNames.getConsumerGroupQueueName();

        EndpointProperties endpointProperties = SolaceProvisioningUtil.getEndpointProperties(properties.getExtension());
        boolean doDurableQueueProvisioning = properties.getExtension().isProvisionDurableQueue();

        if (properties.getConcurrency() > 1) {
            if (endpointProperties.getAccessType().equals(EndpointProperties.ACCESSTYPE_EXCLUSIVE)) {
                String msg = "Concurrency > 1 is not supported when using exclusive queues, " + "either configure a concurrency of 1 or use a non-exclusive queue";
                logger.warn(msg);
                throw new ProvisioningException(msg);
            } else if (!StringUtils.hasText(group)) {
                String msg = "Concurrency > 1 is not supported when using anonymous consumer groups, " + "either configure a concurrency of 1 or define a consumer group";
                logger.warn(msg);
                throw new ProvisioningException(msg);
            }
        }

        logger.info(isAnonQueue ? String.format("Creating anonymous (temporary) queue %s", groupQueueName) : String.format("Creating %s queue %s for consumer group %s", isDurableQueue ? "durable" : "temporary", groupQueueName, group));
        Queue queue = provisionQueue(groupQueueName, isDurableQueue, endpointProperties, doDurableQueueProvisioning, properties.isAutoStartup());

        Set<String> additionalSubscriptions = new HashSet<>(Arrays.asList(properties.getExtension().getQueueAdditionalSubscriptions()));

        String errorQueueName = null;
        if (properties.getExtension().isAutoBindErrorQueue()) {
            errorQueueName = provisionErrorQueue(queueNames.getErrorQueueName(), properties).getName();
        }

        return new SolaceConsumerDestination(queue.getName(), name, queueNames.getPhysicalGroupName(), !isDurableQueue, errorQueueName, additionalSubscriptions);
    }

    private Queue provisionQueueIfRequired(String queueName, ExtendedProducerProperties<SolaceProducerProperties> properties) {
        EndpointProperties endpointProperties = SolaceProvisioningUtil.getEndpointProperties(properties.getExtension());
        boolean doDurableQueueProvisioning = properties.getExtension().isProvisionDurableQueue();
        return provisionQueue(queueName, true, endpointProperties, doDurableQueueProvisioning, properties.isAutoStartup());
    }

    private Queue provisionQueue(String name, boolean isDurable, EndpointProperties endpointProperties, boolean doDurableProvisioning, boolean testFlowCxn) {
        return provisionQueue(name, isDurable, endpointProperties, doDurableProvisioning, testFlowCxn, "Durable queue");
    }

    private Queue provisionQueue(String name, boolean isDurable, EndpointProperties endpointProperties, boolean doDurableProvisioning, boolean testFlowCxn, String durableQueueType) throws ProvisioningException {
        Queue queue;
        try {
            if (isDurable) {
                queue = JCSMPFactory.onlyInstance().createQueue(name);
                if (doDurableProvisioning) {
                    jcsmpSession.provision(queue, endpointProperties, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
                } else {
                    logger.info(String.format("%s provisioning is disabled, %s will not be provisioned nor will its configuration be validated", durableQueueType, name));
                }
            } else {
                // EndpointProperties will be applied during consumer creation
                queue = jcsmpSession.createTemporaryQueue(name);
            }
        } catch (Exception e) {
            String action = isDurable ? "provision durable" : "create temporary";
            String msg = String.format("Failed to %s queue %s", action, name);
            logger.warn(msg, e);
            throw new ProvisioningException(msg, e);
        }

        if (isDurable && testFlowCxn) {
            try {
                logger.info(String.format("Testing consumer flow connection to queue %s (will not start it)", name));
                final ConsumerFlowProperties testFlowProperties = new ConsumerFlowProperties().setEndpoint(queue).setStartState(false);
                jcsmpSession.createFlow(null, testFlowProperties, endpointProperties).close();
                logger.info(String.format("Connected test consumer flow to queue %s, closing it", name));
            } catch (JCSMPException e) {
                String msg = String.format("Failed to connect test consumer flow to queue %s", name);

                if (!doDurableProvisioning) {
                    msg += ". Provisioning is disabled, queue was not provisioned nor was its configuration validated.";
                }
                logger.warn(msg, e);
                throw new ProvisioningException(msg, e);
            }
        } else {
            logger.trace(String.format("Skipping test consumer flow connection for queue %s", name));
        }

        return queue;
    }

    private Queue provisionErrorQueue(String errorQueueName, ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        logger.info(String.format("Provisioning error queue %s", errorQueueName));
        EndpointProperties endpointProperties = SolaceProvisioningUtil.getErrorQueueEndpointProperties(properties.getExtension());
        return provisionQueue(errorQueueName, true, endpointProperties, properties.getExtension().isProvisionErrorQueue(), properties.isAutoStartup(), "Error Queue");
    }

    public void addSubscriptionToQueue(Queue queue, String topicName, SolaceCommonProperties properties, boolean isDestinationSubscription) {
        if (!isDestinationSubscription && queue.isDurable() && !properties.isAddDestinationAsSubscriptionToQueue()) {
            logger.debug(String.format("Provision subscriptions to durable queues was disabled, queue %s will not be subscribed to topic %s", queue.getName(), topicName));
            return;
        }

        if (isDestinationSubscription && !properties.isAddDestinationAsSubscriptionToQueue()) {
            logger.debug(String.format("Adding destination as subscription was disabled, queue %s will not be subscribed to topic %s", queue.getName(), topicName));
            return;
        }

        logger.info(String.format("Subscribing queue %s to topic %s", queue.getName(), topicName));
        try {
            Topic topic = JCSMPFactory.onlyInstance().createTopic(topicName);
            try {
                jcsmpSession.addSubscription(queue, topic, JCSMPSession.WAIT_FOR_CONFIRM);
                registerTastToReapplySubscriptionAfterReconnect(queue, topicName, topic);
            } catch (JCSMPErrorResponseException e) {
                if (e.getSubcodeEx() == JCSMPErrorResponseSubcodeEx.SUBSCRIPTION_ALREADY_PRESENT) {
                    logger.info(String.format("Queue %s is already subscribed to topic %s, SUBSCRIPTION_ALREADY_PRESENT error will be ignored...", queue.getName(), topicName));
                } else {
                    throw e;
                }
            }
        } catch (JCSMPException e) {
            String msg = String.format("Failed to add subscription of %s to queue %s", topicName, queue.getName());
            logger.warn(msg, e);
            throw new ProvisioningException(msg, e);
        }
    }

    private void registerTastToReapplySubscriptionAfterReconnect(Queue queue, String topicName, Topic topic) {
        final AtomicReference<Runnable> reapplySubscriptionOnReconnect = new AtomicReference<>();
        reapplySubscriptionOnReconnect.set(() -> {
            try {
                logger.info(String.format("Subscribing after reconnect: queue %s and topic  %s.", queue.getName(), topicName));
                jcsmpSession.addSubscription(queue, topic, JCSMPSession.WAIT_FOR_CONFIRM);
                logger.warn(String.format("The subscription has been re-established. !You might have lost messages! queue %s and topic  %s.", queue.getName(), topicName));
            } catch (JCSMPErrorResponseException e) {
                if (e.getSubcodeEx() == JCSMPErrorResponseSubcodeEx.SUBSCRIPTION_ALREADY_PRESENT) {
                    logger.info(String.format("Queue %s is already subscribed to topic %s, SUBSCRIPTION_ALREADY_PRESENT error will be ignored...", queue.getName(), topicName));
                } else {
                    logger.error(String.format("Could not add subscription after reconnect %s %s.", queue.getName(), topicName), e);
                    jcsmpSessionEventHandler.removeAfterReconnectTask(reapplySubscriptionOnReconnect.get());
                }
            } catch (Exception e) {
                logger.error(String.format("Could not add subscription after reconnect %s %s.", queue.getName(), topicName), e);
                jcsmpSessionEventHandler.removeAfterReconnectTask(reapplySubscriptionOnReconnect.get());
            }
        });
        jcsmpSessionEventHandler.addAfterReconnectTask(reapplySubscriptionOnReconnect.get());
    }
}

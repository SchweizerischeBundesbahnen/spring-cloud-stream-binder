package com.solace.spring.cloud.stream.binder.provisioning;

import com.solace.spring.cloud.stream.binder.properties.SolaceCommonProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.util.DestinationType;
import com.solacesystems.jcsmp.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class SolaceEndpointProvisioner
        implements ProvisioningProvider<ExtendedConsumerProperties<SolaceConsumerProperties>, ExtendedProducerProperties<SolaceProducerProperties>> {

    private final JCSMPSession jcsmpSession;

    @Override
    public ProducerDestination provisionProducerDestination(String name,
                                                            ExtendedProducerProperties<SolaceProducerProperties> properties)
            throws ProvisioningException {

        if (properties.isPartitioned()) {
            log.warn("Partitioning is not supported with this version of Solace's cloud stream binder. " +
                    "Provisioning will continue under the assumption that it is disabled...");
        }

        switch (properties.getExtension().getDestinationType()) {
            case QUEUE -> {
                if (properties.getRequiredGroups() != null && properties.getRequiredGroups().length > 0) {
                    throw new ProvisioningException(String.format("Producer requiredGroups are not supported when destinationType=%s", DestinationType.QUEUE));
                }
                provisionQueueIfRequired(name, properties);
                return new SolaceProducerDestination(name);
            }
            case TOPIC -> {
                String topicName = SolaceProvisioningUtil.getTopicName(name, properties.getExtension());

                Set<String> requiredGroups = new HashSet<>(Arrays.asList(properties.getRequiredGroups()));
                Map<String, String[]> requiredGroupsExtraSubs = properties.getExtension().getQueueAdditionalSubscriptions();

                for (String groupName : requiredGroups) {
                    String queueName = SolaceProvisioningUtil.getQueueName(topicName, groupName, properties);
                    log.info("Creating durable endpoint {} for required consumer group {}", queueName, groupName);
                    Queue queue = provisionQueueIfRequired(queueName, properties);
                    addSubscriptionToQueue(queue, topicName, properties.getExtension(), true);

                    for (String extraTopic : requiredGroupsExtraSubs.getOrDefault(groupName, new String[0])) {
                        addSubscriptionToQueue(queue, extraTopic, properties.getExtension(), false);
                    }
                }

                Set<String> ignoredExtraSubs = requiredGroupsExtraSubs.keySet()
                        .stream()
                        .filter(g -> !requiredGroups.contains(g))
                        .collect(Collectors.toSet());

                if (ignoredExtraSubs.size() > 0) {
                    log.warn("Groups [{}] are not required groups. The additional subscriptions defined for them were ignored...",
                            String.join(", ", ignoredExtraSubs));
                }

                return new SolaceProducerDestination(topicName);
            }
            default -> throw new ProvisioningException(String.format("Destination type %s is not supported for producers",
                    properties.getExtension().getDestinationType()));
        }
    }

    @Override
    public ConsumerDestination provisionConsumerDestination(String name, String group,
                                                            ExtendedConsumerProperties<SolaceConsumerProperties> properties)
            throws ProvisioningException {

        if (properties.isPartitioned()) {
            log.warn("Partitioning is not supported with this version of Solace's cloud stream binder. " +
                    "Provisioning will continue under the assumption that it is disabled...");
        }

        boolean isAnonEndpoint = SolaceProvisioningUtil.isAnonEndpoint(group, properties.getExtension().getQualityOfService());
        boolean isDurableEndpoint = SolaceProvisioningUtil.isDurableEndpoint(group, properties.getExtension().getQualityOfService());
        SolaceProvisioningUtil.QueueNames queueNames = SolaceProvisioningUtil.getQueueNames(name, group, properties, isAnonEndpoint);
        String groupQueueName = queueNames.getConsumerGroupQueueName();

        EndpointProperties endpointProperties = SolaceProvisioningUtil.getEndpointProperties(properties.getExtension());

        log.info(isAnonEndpoint ?
                String.format("Creating anonymous (temporary) queue %s", groupQueueName) :
                String.format("Creating queue %s %s for consumer group %s",
                        isDurableEndpoint ? "durable" : "temporary", groupQueueName, group));
        Endpoint endpoint = provisionEndpoint(groupQueueName, isDurableEndpoint, endpointProperties, properties.getExtension().isProvisionDurableQueue());

        Set<String> additionalSubscriptions = Set.of(properties.getExtension().getQueueAdditionalSubscriptions());

        String errorQueueName = null;
        if (properties.getExtension().isAutoBindErrorQueue()) {
            errorQueueName = provisionErrorQueue(queueNames.getErrorQueueName(), properties).getName();
        }

        return new SolaceConsumerDestination(endpoint.getName(), name, queueNames.getPhysicalGroupName(), !isDurableEndpoint,
                errorQueueName, additionalSubscriptions);
    }

    private Queue provisionQueueIfRequired(String queueName, ExtendedProducerProperties<SolaceProducerProperties> properties) {
        EndpointProperties endpointProperties = SolaceProvisioningUtil.getEndpointProperties(properties.getExtension());
        boolean doDurableQueueProvisioning = properties.getExtension().isProvisionDurableQueue();
        return provisionEndpoint(queueName, true, endpointProperties, doDurableQueueProvisioning);
    }

    private Queue provisionEndpoint(
            String name,
            boolean isDurable,
            EndpointProperties endpointProperties,
            boolean doDurableProvisioning) throws ProvisioningException {

        Queue endpoint;
        try {
            if (isDurable) {
                endpoint = JCSMPFactory.onlyInstance().createQueue(name);
                if (doDurableProvisioning) {
                    jcsmpSession.provision(endpoint, endpointProperties, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
                } else {
                    log.debug("Provisioning is disabled, {} will not be provisioned nor will its configuration be validated",
                            name);
                }
            } else {
                // EndpointProperties will be applied during consumer creation
                endpoint = jcsmpSession.createTemporaryQueue(name);
            }
        } catch (Exception e) {
            String action = isDurable ? "provision durable" : "create temporary";
            String msg = String.format("Failed to %s endpoint %s", action, name);
            log.warn(msg, e);
            throw new ProvisioningException(msg, e);
        }

        return endpoint;
    }

    private Queue provisionErrorQueue(String errorQueueName, ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        log.info("Provisioning error queue {}", errorQueueName);
        EndpointProperties endpointProperties = SolaceProvisioningUtil.getErrorQueueEndpointProperties(properties.getExtension());
        return provisionEndpoint(errorQueueName,
                true,
                endpointProperties,
                properties.getExtension().isProvisionErrorQueue());
    }

    public void addSubscriptionToQueue(Queue queue, String topicName, SolaceCommonProperties properties, boolean isDestinationSubscription) {
        if (isDestinationSubscription && !properties.isAddDestinationAsSubscriptionToQueue()) {
            log.debug("Adding destination as subscription was disabled, queue {} will not be subscribed to topic {}",
                    queue.getName(), topicName);
            return;
        }

        log.info("Subscribing queue {} to topic {}", queue.getName(), topicName);
        try {
            Topic topic = JCSMPFactory.onlyInstance().createTopic(topicName);
            try {
                jcsmpSession.addSubscription(queue, topic, JCSMPSession.WAIT_FOR_CONFIRM);
            } catch (JCSMPErrorResponseException e) {
                if (e.getSubcodeEx() == JCSMPErrorResponseSubcodeEx.SUBSCRIPTION_ALREADY_PRESENT) {
                    log.info("Queue {} is already subscribed to topic {}, SUBSCRIPTION_ALREADY_PRESENT error will be ignored...",
                            queue.getName(), topicName);
                } else {
                    throw e;
                }
            }
        } catch (JCSMPException e) {
            String msg = String.format("Failed to add subscription of %s to queue %s", topicName, queue.getName());
            log.warn(msg, e);
            throw new ProvisioningException(msg, e);
        }
    }

    private String getEndpointTypeLabel(Endpoint endpoint) {
        return endpoint instanceof TopicEndpoint ? "topic endpoint" : "queue";
    }
}

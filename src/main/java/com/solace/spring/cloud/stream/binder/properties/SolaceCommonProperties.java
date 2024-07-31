package com.solace.spring.cloud.stream.binder.properties;

import com.solacesystems.jcsmp.EndpointProperties;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SolaceCommonProperties {
    /**
     * Whether to provision durable queues for non-anonymous consumer groups.
     * This should only be set to false if you have externally pre-provisioned the required queue on the message broker.
     */
    private boolean provisionDurableQueue = true;

    /**
     * Whether to add the Destination as a subscription to queue during provisioning.
     */
    private boolean addDestinationAsSubscriptionToQueue = true;

    // Queue Properties -------
    /**
     * Access type for the consumer group queue.
     */
    private int queueAccessType = EndpointProperties.ACCESSTYPE_NONEXCLUSIVE;
    /**
     * Permissions for the consumer group queue.
     */
    private int queuePermission = EndpointProperties.PERMISSION_CONSUME;
    /**
     * If specified, whether to notify sender if a message fails to be enqueued to the consumer group queue.
     */
    private Integer queueDiscardBehaviour = null;
    /**
     * Sets the maximum message redelivery count on consumer group queue. (Zero means retry forever).
     */
    private Integer queueMaxMsgRedelivery = null;
    /**
     * Maximum message size for the consumer group queue.
     */
    private Integer queueMaxMsgSize = null;
    /**
     * Message spool quota for the consumer group queue.
     */
    private Integer queueQuota = null;
    /**
     * Whether the consumer group queue respects Message TTL.
     */
    private Boolean queueRespectsMsgTtl = null;
}

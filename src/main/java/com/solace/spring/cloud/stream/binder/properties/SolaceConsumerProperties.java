package com.solace.spring.cloud.stream.binder.properties;

import com.solace.spring.cloud.stream.binder.util.QualityOfService;
import com.solacesystems.jcsmp.EndpointProperties;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

import static com.solace.spring.cloud.stream.binder.properties.SolaceExtendedBindingProperties.DEFAULTS_PREFIX;

@Getter
@Setter
@SuppressWarnings("ConfigurationProperties")
@ConfigurationProperties(DEFAULTS_PREFIX + ".consumer")
public class SolaceConsumerProperties extends SolaceCommonProperties {

    /**
     * An array of additional topic subscriptions to be applied on the consumer group queue.
     * These subscriptions may also contain wildcards.
     */
    private String[] queueAdditionalSubscriptions = new String[0];

    /**
     * A SpEL expression for creating the consumer group’s queue name.
     * Modifying this can cause naming conflicts between the queue names of consumer groups.
     * While the default SpEL expression will consistently return a value adhering to <<Generated Queue Name Syntax>>,
     * directly using the SpEL expression string is not supported. The default value for this config option is subject to change without notice.
     */
    private String queueNameExpression = "'scst/' + (isAnonymous ? 'an/' : 'wk/') + (group?.trim() + '/') + 'plain/' + destination.trim().replaceAll('[*>]', '_')";

    /**
     * A SQL-92 selector expression to use for selection of messages for consumption. Max of 2000 characters.
     */
    @Deprecated
    private String selector = null;

    // Error Queue Properties ---------
    /**
     * Whether to automatically create a durable error queue to which messages will be republished when message processing failures are encountered.
     * Only applies once all internal retries have been exhausted.
     */
    private boolean autoBindErrorQueue = false;
    /**
     * Whether to provision durable queues for error queues when autoBindErrorQueue is true.
     * This should only be set to false if you have externally pre-provisioned the required queue on the message broker.
     */
    private boolean provisionErrorQueue = true;
    /**
     * A SpEL expression for creating the error queue’s name.
     * Modifying this can cause naming conflicts between the error queue names.
     * While the default SpEL expression will consistently return a value adhering to <<Generated Queue Name Syntax>>,
     * directly using the SpEL expression string is not supported. The default value for this config option is subject to change without notice.
     */
    private String errorQueueNameExpression = "'scst/error/' + (isAnonymous ? 'an/' : 'wk/') + (group?.trim() + '/') + 'plain/' + destination.trim().replaceAll('[*>]', '_')";

    /**
     * Maximum number of attempts to send a failed message to the error queue.
     * When all delivery attempts have been exhausted, the failed message will be requeued.
     */
    private long errorQueueMaxDeliveryAttempts = 3;
    /**
     * Access type for the error queue.
     */
    private int errorQueueAccessType = EndpointProperties.ACCESSTYPE_NONEXCLUSIVE;
    /**
     * Permissions for the error queue.
     */
    private int errorQueuePermission = EndpointProperties.PERMISSION_CONSUME;
    /**
     * If specified, whether to notify sender if a message fails to be enqueued to the error queue.
     */
    private Integer errorQueueDiscardBehaviour = null;
    /**
     * Sets the maximum message redelivery count on the error queue. (Zero means retry forever).
     */
    private Integer errorQueueMaxMsgRedelivery = null;
    /**
     * Maximum message size for the error queue.
     */
    private Integer errorQueueMaxMsgSize = null;
    /**
     * Message spool quota for the error queue.
     */
    private Integer errorQueueQuota = null;
    /**
     * Whether the error queue respects Message TTL.
     */
    private Boolean errorQueueRespectsMsgTtl = null;
    /**
     * The eligibility for republished messages to be moved to a Dead Message Queue.
     */
    private Boolean errorMsgDmqEligible = null;
    /**
     * The number of milliseconds before republished messages are discarded or moved to a Dead Message Queue.
     */
    private Long errorMsgTtl = null;
    /**
     * Indicated if messages should be consumed using a queue or directly via topic.
     */
    private QualityOfService qualityOfService = QualityOfService.AT_LEAST_ONCE;
    /**
     * Time in milliseconds till a long running consumer is logged as warning, defaults to 2000 ms.
     */
    private long maxProcessingTimeMs = 2000;
    // ------------------------

    /**
     * The list of headers to exclude when converting consumed Solace message to Spring message.
     */
    private List<String> headerExclusions = new ArrayList<>();
}

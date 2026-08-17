package com.solace.spring.cloud.stream.binder.properties;

import com.solace.spring.cloud.stream.binder.util.DestinationType;
import com.solacesystems.jcsmp.DeliveryMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.solace.spring.cloud.stream.binder.properties.SolaceExtendedBindingProperties.DEFAULTS_PREFIX;

@Setter
@Getter
@SuppressWarnings("ConfigurationProperties")
@ConfigurationProperties(DEFAULTS_PREFIX + ".producer")
public class SolaceProducerProperties extends SolaceCommonProperties {

    /**
     * The type of destination messages are published to.
     */
    private DestinationType destinationType = DestinationType.TOPIC;

    /**
     * A SpEL expression for creating the consumer group’s queue name.
     * Modifying this can cause naming conflicts between the queue names of consumer groups.
     * While the default SpEL expression will consistently return a value adhering to <<Generated Queue Name Syntax>>,
     * directly using the SpEL expression string is not supported. The default value for this config option is subject to change without notice.
     */
    private String queueNameExpression = "'scst/' + (isAnonymous ? 'an/' : 'wk/') + (group?.trim() + '/') + 'plain/' + destination.trim().replaceAll('[*>]', '_')";

    /**
     * A mapping of required consumer groups to queue name SpEL expressions.
     * By default, queueNameExpression will be used to generate a required group’s queue name if it isn’t specified within this configuration option.
     * Modifying this can cause naming conflicts between the queue names of consumer groups.
     * While the default SpEL expression will consistently return a value adhering to <<Generated Queue Name Syntax>>,
     * directly using the SpEL expression string is not supported. The default value for this config option is subject to change without notice.
     */
    private Map<String, String> queueNameExpressionsForRequiredGroups = new HashMap<>();
    /**
     * A mapping of required consumer groups to arrays of additional topic subscriptions to be applied on each consumer group’s queue.
     * These subscriptions may also contain wildcards.
     */
    private Map<String, String[]> queueAdditionalSubscriptions = new HashMap<>();
    /**
     * The list of headers to exclude from the published message. Excluding Solace message headers is not supported.
     */
    private List<String> headerExclusions = new ArrayList<>();
    /**
     * Set of default headers that will be added to the published message. If a header occurs in the published message as well, the default header will be ignored.
     */
    private Map<String, Object> defaultHeader = new HashMap<>();


    /**
     * When set to true, irreversibly convert non-serializable headers to strings. An exception is thrown otherwise.
     */
    private boolean nonserializableHeaderConvertToString = false;

    /**
     * Indicated if messages should be sending fire and forget or producer has to wait for broker persistence ack.
     */
    private DeliveryMode deliveryMode = DeliveryMode.PERSISTENT;

    /**
     * Maps to the client-side {@code ProducerFlowProperties.setWindowSize(int)} value.
     * This is the JCSMP publish acknowledgment window size: how many persistent publish acknowledgements may be
     * outstanding on the wire for this producer flow at the same time.
     * When unset, the producer flow inherits the session-level {@code JCSMPProperties.PUB_ACK_WINDOW_SIZE} default.
     * Default: null (inherit {@code JCSMPProperties.PUB_ACK_WINDOW_SIZE})
     */
    private Integer pubAckWindowSize;

    /**
     * Time window in milliseconds during which a failed synchronous publish ({@code producer.send(...)}) is retried
     * before the send ultimately fails. When a publish attempt throws, the binder keeps retrying until this duration
     * elapses, after which it re-throws the failure as an {@link org.springframework.messaging.MessagingException}.
     * <p>
     * Set to {@code 0} to disable retrying: the message handler then performs a single publish attempt and immediately
     * propagates any failure as a {@link org.springframework.messaging.MessagingException}.
     * <p>
     * Note: retrying only mitigates transient, synchronous publish failures. Regardless of this setting,
     * {@code StreamBridge.send(...)} / the outbound message handler can still throw a
     * {@link org.springframework.messaging.MessagingException}, so callers must always be prepared to catch it.
     * Default: 60000 (60 seconds).
     */
    private long sendRetryTimeoutMs = 60000;
}

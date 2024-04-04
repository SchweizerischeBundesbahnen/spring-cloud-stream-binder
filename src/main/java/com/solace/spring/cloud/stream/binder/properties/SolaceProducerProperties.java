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
	private Map<String,String[]> queueAdditionalSubscriptions = new HashMap<>();
	/**
	 * The list of headers to exclude from the published message. Excluding Solace message headers is not supported.
	 */
	private List<String> headerExclusions = new ArrayList<>();
	/**
	 * When set to true, irreversibly convert non-serializable headers to strings. An exception is thrown otherwise.
	 */
	private boolean nonserializableHeaderConvertToString = false;
    /**
     * Indicated if messages should be sending fire and forget or producer has to wait for broker persistence ack.
     */
    private DeliveryMode deliveryMode = DeliveryMode.PERSISTENT;
}

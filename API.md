# Spring Cloud Stream Binder for Solace PubSub+

An implementation of Spring's Cloud Stream Binder for integrating with Solace PubSub+ message brokers. The Spring Cloud Stream Binder project provides a higher-level abstraction towards messaging that standardizes the development of distributed message-based systems.

> [!IMPORTANT]
> * Spring Cloud Stream consumer bindings with Solace PubSub+ Binder v5.x and later requires a Solace PubSub+ Broker version 10.2.1 or newer. The [Native Message NACK](https://docs.solace.com/Release-Notes/Release-Info-appliance-sw-releases.htm#Event_Broker_Releases#:~:text=Broker%20Support%20For%20Message%20NACK) feature on which Solace consumer binding depends on, was introduced in Solace PubSub+ Broker version 10.2.1.
> * Spring Cloud Stream producer bindings with Solace PubSub+ Binder v5.x and later is compatible with PubSub+ Broker version prior to 10.2.1.
> * See [Solace PubSub+ Binder Migration Guide](MIGRATION.md) for details, if you are upgrading from older version to Solace Binder v5.x or later.

## Overview

The Solace implementation of the Spring Cloud Stream Binder maps the following concepts from Spring to Solace:

*   Destinations to topics/subscriptions
    *   Producer bindings always sends messages to topics
*   Consumer groups to durable queues
    *   A consumer group's queue is subscribed to its destination subscription (default)
    *   Consumer bindings always receives messages from queues
*   Anonymous consumer groups to temporary queues (When no group is specified; used for SCS Publish-Subscribe Model)

In Solace, the above setup is called topic-to-queue mapping. So a typical message flow would then appear as follows:

1.  Producer bindings publish messages to their destination topics
2.  Each consumer groups' queue receives the messages published to their destination topic
3.  The PubSub+ broker distributes messages in a round-robin fashion to each consumer binding for a particular consumer group

> [!NOTE]
> Round-robin distribution only occurs if the consumer group's queue is configured for non-exclusive access. If the queue has exclusive access, then only one consumer will receive messages.

> [!IMPORTANT]
> Since consumer bindings always consumes from queues it is required that Assured Delivery is enabled on the Solace PubSub+ Message VPN being used (Assured Delivery is automatically enabled if using Solace Cloud). Additionally, the client username's client profile must be allowed to send and receive guaranteed messages.

For the sake of brevity, it will be assumed that you have a basic understanding of the Spring Cloud Stream project. If not, then please refer to [Spring's documentation](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/). This document will solely focus on discussing components unique to Solace.

## Spring Cloud Stream Binder

This project extends the Spring Cloud Stream Binder project. If you are new to Spring Cloud Stream, [check out their documentation](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/).

The following is a brief excerpt from that document:

> Spring Cloud Stream is a framework for building message-driven microservice applications. Spring Cloud Stream builds upon Spring Boot to create standalone, production-grade Spring applications and uses Spring Integration to provide connectivity to message brokers. It provides opinionated configuration of middleware from several vendors, introducing the concepts of persistent publish-subscribe semantics, consumer groups, and partitions.
> — [Introducing Spring Cloud Stream – Spring Cloud Stream Reference Documentation](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#spring-cloud-stream-overview-introducing)

## Using it in your Application

### Updating your build

The releases from this project are hosted in [Maven Central](https://central.sonatype.com/artifact/ch.sbb/spring-cloud-stream-binder-solace).

The easiest way to get started is to include the `spring-cloud-stream-binder-solace` in your application.

Here is how to include the spring cloud stream starter in your project using Gradle and Maven.

#### Using it with Gradle

```groovy
// Solace Spring Cloud Stream Binder
implementation("ch.sbb:spring-cloud-stream-binder-solace:9.0.0")
```

#### Using it with Maven

```xml
<!-- Solace Spring Cloud Stream Binder -->
<dependency>
  <groupId>ch.sbb</groupId>
  <artifactId>spring-cloud-stream-binder-solace</artifactId>
  <version>9.0.0</version>
</dependency>
```

### Creating a Simple Solace Binding

Starting in Spring Cloud Stream version 3 the recommended way to define binding and binding names is to use the Functional approach, which uses Spring Cloud Functions. You can learn more in the [Spring Cloud Function support](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#spring_cloud_function) and [Functional Binding Names](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#_functional_binding_names) sections of the reference guide.

Given this example app:

```java
@SpringBootApplication
public class SampleAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(SampleAppApplication.class, args);
	}

	@Bean
	public Function<String, String> uppercase() {
	    return value -> value.toUpperCase();
	}
}
```

An applicable Solace configuration file may look like:

```yaml
solace:
  java:
    host: tcp://localhost:55555
    msgVpn: default
    clientUsername: default
    clientPassword: default
    connectRetries: -1
    reconnectRetries: -1
#    apiProperties:
#      ssl_trust_store: <path_to_trust_store>
#      ssl_trust_store_password: <trust_store_password>
#      ssl_validate_certificate: true

spring:
  cloud:
    function:
      definition: uppercase
    stream:
      bindings:
        uppercase-in-0:
          destination: queuename
          group: myconsumergroup
        uppercase-out-0:
          destination: uppercase/topic
```

The Solace session properties (`solace.java.*`) originate from the [JCSMP Spring Boot Auto-Configuration project](https://github.com/SolaceProducts/solace-spring-boot/tree/master/solace-spring-boot-starters/solace-java-spring-boot-starter#updating-your-application-properties). See [Solace Session Properties](#solace-session-properties) for more info.

> [!TIP]
> If you need to configure multiple Solace binders, you can nest the Solace session properties under the binder definition:  
> ```yaml
> spring:
>   cloud:
>     stream:
>       binders:
>         solace-broker:
>           type: solace
>           environment:
>             solace:
>               java:
>                 host: tcp://localhost:55555
>                 msgVpn: default
>                 clientUsername: default
>                 clientPassword: default
> ```
> This approach allows different binders to connect to different broker instances.

For more samples see [Solace Spring Cloud Samples](https://github.com/SolaceSamples/solace-samples-spring) repository.

For step-by-step instructions refer [Solace Spring Cloud Stream tutorial](https://tutorials.solace.dev/spring/spring-cloud-stream/) and check out the [blogs](https://solace.com/blog/?fwp_blog_search=spring%20cloud%20stream).

## Configuration Options

### Solace Binder Configuration Options

Configuration of the Solace Spring Cloud Stream Binder is done through [Spring Boot's externalized configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-external-config.html). This is where users can control the binder's configuration options as well as the Solace Java API properties.

For general binder configuration options and properties, refer to the [Spring Cloud Stream Reference Documentation](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#_configuration_options).

#### Solace Session Properties

The binder's Solace session is configurable using properties prefixed by `solace.java` or `spring.cloud.stream.binders.<binder-name>.environment.solace.java`.

> [!IMPORTANT]
> This binder leverages the JCSMP Spring Boot Auto-Configuration project to configure its session. See the [JCSMP Spring Boot Auto-Configuration documentation](https://github.com/SolaceProducts/solace-spring-boot/tree/master/solace-spring-boot-starters/solace-java-spring-boot-starter#configure-the-application-to-use-your-solace-pubsub-service-credentials) for more info on how to configure these properties.

See [Creating a Simple Solace Binding](#creating-a-simple-solace-binding) for a simple example of how to configure a session for this binder.

> [!TIP]
> Additional session properties not available under the usual `solace.java` prefix can be set using `solace.java.apiProperties.<property>`, where `<property>` is the name of a [JCSMPProperties constant](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/JCSMPProperties.html) (e.g. `ssl_trust_store`).
> See [JCSMP Spring Boot Auto-Configuration documentation](https://github.com/SolaceProducts/solace-spring-boot/tree/master/solace-spring-boot-starters/solace-java-spring-boot-starter#updating-your-application-properties) for more info about `solace.java.apiProperties`.

#### Solace Consumer Properties

The following properties are available for Solace consumers only and must be prefixed with `spring.cloud.stream.solace.bindings.<bindingName>.consumer.` where `bindingName` looks something like `functionName-in-0` as defined in [Functional Binding Names](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#_functional_binding_names).

See [SolaceCommonProperties](src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceCommonProperties.java) and [SolaceConsumerProperties](src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceConsumerProperties.java) for the most updated list.

`provisionDurableQueue`
:   Whether to provision durable queues for non-anonymous consumer groups. This should only be set to `false` if you have externally pre-provisioned the required queue on the message broker.
    Default: `true`
    See: [Generated Queue Name Syntax](#generated-queue-name-syntax)

`addDestinationAsSubscriptionToQueue`
:   Whether to add the Destination as a subscription to queue during provisioning.
    Default: `true`

`queueNameExpression`
:   A SpEL expression for creating the consumer group’s queue name.
    Default: `"'scst/' + (isAnonymous ? 'an/' : 'wk/') + (group?.trim() + '/') + 'plain/' + destination.trim().replaceAll('[*>]', '_')"`
    See: [Generated Queue Name Syntax](#generated-queue-name-syntax)

> [!WARNING]
> Modifying this can cause naming conflicts between the queue names of consumer groups.

> [!WARNING]
> While the default SpEL expression consistently returns a value adhering to [Generated Queue Name Syntax](#generated-queue-name-syntax), directly configuring this SpEL expression string as your custom default is not supported. The default value for this config option is subject to change without notice.

> [!CAUTION]
> The Solace broker has a maximum queue name length limit (typically ~200 characters). When using the default SpEL expression with long destination names, the generated queue name may exceed this limit and cause provisioning failures. Consider using shorter destination names or a custom `queueNameExpression` if you encounter this issue.

`queueAccessType`
:   Access type for the consumer group queue.
    Default: `0` (ACCESSTYPE_NONEXCLUSIVE)
    See: [EndpointProperties.setAccessType(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setAccessType(int))

`queuePermission`
:   Permissions for the consumer group queue.
    Default: `2` (PERMISSION_CONSUME)
    See: [EndpointProperties.setPermission(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setPermission(int))

`queueDiscardBehaviour`
:   If specified, whether to notify sender if a message fails to be enqueued to the consumer group queue.
    Default: `null`
    See: [EndpointProperties.setDiscardBehavior(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setDiscardBehavior(int))

`queueMaxMsgRedelivery`
:   Sets the maximum message redelivery count on consumer group queue. (Zero means retry forever).
    Default: `null`
    See: [EndpointProperties.setMaxMsgRedelivery(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setMaxMsgRedelivery(int))

`queueMaxMsgSize`
:   Maximum message size for the consumer group queue.
    Default: `null`
    See: [EndpointProperties.setMaxMsgSize(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setMaxMsgSize(int))

`queueQuota`
:   Message spool quota for the consumer group queue.
    Default: `null`
    See: [EndpointProperties.setQuota(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setQuota(int))

`queueRespectsMsgTtl`
:   Whether the consumer group queue respects Message TTL.
    Default: `null`
    See: [EndpointProperties.setRespectsMsgTTL(boolean)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setRespectsMsgTTL(boolean))

`queueAdditionalSubscriptions`
:   An array of additional topic subscriptions to be applied on the consumer group queue. These subscriptions may also contain wildcards.
    Default: `String[0]`
    See: [Overview](#overview) for more info on how this binder uses topic-to-queue mapping to implement Spring Cloud Streams consumer groups.

`maxUnacknowledgedMessages`
:   The maximum number of unacknowledged messages that can be outstanding on the flow. Use this to limit the number of messages in the locally buffered "messageQueue" and protect the heap from overflow.
    Default: `null` (If unconfigured, the flow falls back to inheriting the global JCSMPSession property `SUB_ACK_WINDOW_SIZE` or `spring.solace.java.properties.sub_ack_window_size`).
    See: [ConsumerFlowProperties.setTransportWindowSize(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/ConsumerFlowProperties.html#setTransportWindowSize(int))

`flowAckTimerInMsecs`
:   The Ack timer in milliseconds for the consumer flow. Used for grouping acknowledgements.
    Default: `null` (If unconfigured, inherits from global JCSMPSession property `spring.solace.java.properties.sub_ack_time`).
    See: [ConsumerFlowProperties.setAckTimerInMsecs(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/ConsumerFlowProperties.html#setAckTimerInMsecs(int))

`flowAckThreshold`
:   The Ack threshold for the consumer flow.
    Default: `null` (If unconfigured, falls back to the JCSMP default threshold).
    See: [ConsumerFlowProperties.setAckThreshold(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/ConsumerFlowProperties.html#setAckThreshold(int))

`flowWindowedAckMaxSize`
:   The windowed Ack max size for the consumer flow.
    Default: `null` (If unconfigured, falls back to the JCSMP default).
    See: [ConsumerFlowProperties.setWindowedAckMaxSize(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/ConsumerFlowProperties.html#setWindowedAckMaxSize(int))

`autoBindErrorQueue`
:   Whether to automatically create a durable error queue to which messages will be republished when message processing failures are encountered. Only applies once all internal retries have been exhausted.
    Default: `false`

> [!TIP]
> Your ACL Profile must allow for publishing to this queue if you decide to use `autoBindErrorQueue`.

`provisionErrorQueue`
:   Whether to provision durable queues for error queues when `autoBindErrorQueue` is `true`. This should only be set to `false` if you have externally pre-provisioned the required queue on the message broker.
    Default: `true`
    See: [Generated Error Queue Name Syntax](#generated-error-queue-name-syntax)

`errorQueueNameExpression`
:   A SpEL expression for creating the error queue’s name.
    Default: `"'scst/error/' + (isAnonymous ? 'an/' : 'wk/') + (group?.trim() + '/') + 'plain/' + destination.trim().replaceAll('[*>]', '_')"`
    See: [Generated Error Queue Name Syntax](#generated-error-queue-name-syntax)

> [!WARNING]
> Modifying this can cause naming conflicts between the error queue names.

> [!WARNING]
> While the default SpEL expression consistently returns a value adhering to [Generated Error Queue Name Syntax](#generated-error-queue-name-syntax), directly configuring this SpEL expression string as your custom default is not supported. The default value for this config option is subject to change without notice.

`errorQueueMaxDeliveryAttempts`
:   Maximum number of attempts to send a failed message to the error queue. When all delivery attempts have been exhausted, the failed message will be requeued.
    Default: `3`

`errorQueueAccessType`
:   Access type for the error queue.
    Default: `0` (ACCESSTYPE_NONEXCLUSIVE)
    See: [EndpointProperties.setAccessType(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setAccessType(int))

`errorQueuePermission`
:   Permissions for the error queue.
    Default: `2` (PERMISSION_CONSUME)
    See: [EndpointProperties.setPermission(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setPermission(int))

`errorQueueDiscardBehaviour`
:   If specified, whether to notify sender if a message fails to be enqueued to the error queue.
    Default: `null`
    See: [EndpointProperties.setDiscardBehavior(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setDiscardBehavior(int))

`errorQueueMaxMsgRedelivery`
:   Sets the maximum message redelivery count on the error queue. (Zero means retry forever).
    Default: `null`
    See: [EndpointProperties.setMaxMsgRedelivery(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setMaxMsgRedelivery(int))

`errorQueueMaxMsgSize`
:   Maximum message size for the error queue.
    Default: `null`
    See: [EndpointProperties.setMaxMsgSize(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setMaxMsgSize(int))

`errorQueueQuota`
:   Message spool quota for the error queue.
    Default: `null`
    See: [EndpointProperties.setQuota(int)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setQuota(int))

`errorQueueRespectsMsgTtl`
:   Whether the error queue respects Message TTL.
    Default: `null`
    See: [EndpointProperties.setRespectsMsgTTL(boolean)](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/com/solacesystems/jcsmp/EndpointProperties.html#setRespectsMsgTTL(boolean))

`errorMsgDmqEligible`
:   The eligibility for republished messages to be moved to a Dead Message Queue.
    Default: `null`

`errorMsgTtl`
:   The number of milliseconds before republished messages are discarded or moved to a Dead Message Queue.
    Default: `null`

`headerExclusions`
:   The list of headers to exclude when converting consumed Solace message to Spring message.
    Default: Empty `List<String>`

`qualityOfService`
:   The QoS (Quality of Service) to consume Messages. Possible Values:
    *   `AT_MOST_ONCE`
        *   QoS=0
        *   Using topics: Messages may be lost or discarded.
        *   This mode improves performance and reduces latency.
        *   When using `AT_MOST_ONCE` make sure the publisher uses `deliveryMode=DIRECT` to avoid having the messages persisted on publish.
    *   `AT_LEAST_ONCE`
        *   QoS=1
        *   Using a persistent queue: It is guaranteed that the message arrives at least once.
        *   This mode persists messages on storage and therefore is slower.
    Default: `AT_LEAST_ONCE`

`watchdogTimeoutMs`
:   Time in milliseconds before a long-running message processing thread is logged as a warning. This is used to detect potential deadlocks or stuck threads. A warning is logged once per message when processing time exceeds this threshold.
    Default: `300000` (5 minutes)

#### Solace Producer Properties

The following properties are available for Solace producers only and must be prefixed with `spring.cloud.stream.solace.bindings.<bindingName>.producer.` where `bindingName` looks something like `functionName-out-0` as defined in [Functional Binding Names](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#_functional_binding_names).

See [SolaceCommonProperties](src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceCommonProperties.java) and [SolaceProducerProperties](src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceProducerProperties.java) for the most updated list.

`destinationType`
:   Specifies whether the configured `destination` is a `topic` or a `queue`.
    When set to `topic`, the `destination` name is a topic subscription added on a queue.
    When set to `queue`, the producer binds to a queue matching the `destination` name. The queue can be auto-provisioned with `provisionDurableQueue=true` however, all naming prefix and queue name generation options do not apply. A queue will be provisioned using the `destination` name explicitly.
    Default: `topic`

`headerExclusions`
:   The list of headers to exclude from the published message. Excluding Solace message headers is not supported.
    Default: Empty `List<String>`

`nonserializableHeaderConvertToString`
:   When set to `true`, irreversibly convert non-serializable headers to strings. An exception is thrown otherwise.
    Default: `false`

> [!IMPORTANT]
> Non-serializable headers should have a meaningful `toString()` implementation. Otherwise enabling this feature may result in potential data loss.

`provisionDurableQueue`
:   Whether to provision durable queues for non-anonymous consumer groups or queue destinations. This should only be set to `false` if you have externally pre-provisioned the required queue on the message broker.
    Default: `true`
    See: [Generated Queue Name Syntax](#generated-queue-name-syntax)

`addDestinationAsSubscriptionToQueue`
:   Whether to add the Destination as a subscription to queue during provisioning.
    Default: `true`

> [!NOTE]
> Does not apply when `destinationType=queue`.

`queueNameExpression`
:   A SpEL expression for creating the consumer group’s queue name.
    Default: `"'scst/' + (isAnonymous ? 'an/' : 'wk/') + (group?.trim() + '/') + 'plain/' + destination.trim().replaceAll('[*>]', '_')"`
    See: [Generated Queue Name Syntax](#generated-queue-name-syntax)

> [!WARNING]
> Modifying this can cause naming conflicts between the queue names of consumer groups.

> [!WARNING]
> While the default SpEL expression consistently returns a value adhering to [Generated Queue Name Syntax](#generated-queue-name-syntax), directly configuring this SpEL expression string as your custom default is not supported. The default value for this config option is subject to change without notice.

`queueNameExpressionsForRequiredGroups`
:   A mapping of required consumer groups to queue name SpEL expressions.
    By default, queueNameExpression will be used to generate a required group’s queue name if it isn’t specified within this configuration option.
    Default: `Empty Map<String, String>`
    See: [Generated Queue Name Syntax](#generated-queue-name-syntax)

> [!WARNING]
> Modifying this can cause naming conflicts between the queue names of consumer groups.

> [!WARNING]
> While the default SpEL expression consistently returns a value adhering to [Generated Queue Name Syntax](#generated-queue-name-syntax), directly configuring this SpEL expression string as your custom default is not supported. The default value for this config option is subject to change without notice.

`queueAccessType`
:   Access type for binder provisioned queues.
    Default: `0` (ACCESSTYPE_NONEXCLUSIVE)
    See: [The `ACCESSTYPE_` prefixed constants for other possible values](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/constant-values.html#com.solacesystems.jcsmp.EndpointProperties.ACCESSTYPE_EXCLUSIVE)

`queuePermission`
:   Permissions for binder provisioned queues.
    Default: `2` (PERMISSION_CONSUME)
    See: [The `PERMISSION_` prefixed constants for other possible values](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/constant-values.html#com.solacesystems.jcsmp.EndpointProperties.PERMISSION_CONSUME)

`queueDiscardBehaviour`
:   Queue discard behaviour for binder provisioned queues. Whether to notify sender if a message fails to be enqueued to the endpoint. A null value means use the appliance default.
    Default: `null`

`queueMaxMsgRedelivery`
:   Sets the maximum message redelivery count for binder provisioned queues. (Zero means retry forever).
    Default: `null`

`queueMaxMsgSize`
:   Maximum message size for binder provisioned queues.
    Default: `null`

`queueQuota`
:   Message spool quota for binder provisioned queues.
    Default: `null`

`queueRespectsMsgTtl`
:   Whether the binder provisioned queues respect Message TTL.
    Default: `null`

`queueAdditionalSubscriptions`
:   A mapping of required consumer groups to arrays of additional topic subscriptions to be applied on each consumer group's queue. These subscriptions may also contain wildcards.
    Default: Empty `Map<String,String[]>`
    See: [Overview](#overview) for more info on how this binder uses topic-to-queue mapping to implement Spring Cloud Streams consumer groups.

> [!NOTE]
> Does not apply when `destinationType=queue`.


`deliveryMode`
:   See [https://docs.solace.com/API/API-Developer-Guide/Message-Delivery-Modes.htm](https://docs.solace.com/API/API-Developer-Guide/Message-Delivery-Modes.htm) for documentation. The `deliveryMode` on the producer will be used to send messages on the configured binder. Possible values: `PERSISTENT`, `DIRECT`.
    If using `qualityOfService: AT_MOST_ONCE` to reduce latency it is suggested to set the `deliveryMode` to `DIRECT` to avoid having the messages persisted on publish.
    Default: `PERSISTENT`

#### Solace Connection Health-Check Properties

The Solace connection health indicator immediately reports `DOWN` status when the connection is down or reconnecting. This ensures that health checks accurately reflect the current connection state without any delay or threshold configuration.

The following events cause the health indicator to report `DOWN`:

*   **Connection loss**: The TCP connection to the Solace broker is lost and all reconnection attempts have been exhausted.
*   **Reconnecting**: The binder is actively attempting to reconnect to the broker (transitional state).
*   **Provisioning failure**: Queue or endpoint provisioning fails during binding setup (e.g., insufficient permissions, broker-side errors).
*   **Session destruction**: The JCSMP session is explicitly closed or destroyed.

### Solace Message Headers

Solace-defined Spring headers to get/set Solace metadata from/to Spring `Message` headers.

> [!WARNING]
> `solace_` is a header space reserved for Solace-defined headers. Creating new `solace_`-prefixed headers is not supported. Doing so may cause unexpected side-effects in future versions of this binder.

> [!CAUTION]
> Refer to each header's documentation for their expected usage scenario. Using headers outside of their intended type and access-control is not supported.

> [!NOTE]
> Header inheritance applies to Solace message headers in processor message handlers:
> > When the non-void handler method returns, if the return value is already a `Message`, that `Message` becomes the payload. However, when the return value is not a `Message`, the new `Message` is constructed with the return value as the payload while inheriting headers from the input `Message` minus the headers defined or filtered by `SpringIntegrationProperties.messageHandlerNotPropagatedHeaders`.
> > — [Mechanics,Spring Cloud Stream Reference Documentation](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#_mechanics)

#### Solace Headers

These headers are to get/set Solace message properties.

> [!TIP]
> Use [SolaceHeaders](src/main/java/com/solace/spring/cloud/stream/binder/messaging/SolaceHeaders.java) instead of hardcoding the header names. This class also contains the same documentation that you see here.

| Header Name | Type | Access | Description |
| --- | --- | --- | --- |
| `solace_applicationMessageId` | String | Read/Write | The message ID (a string for an application-specific message identifier). This is the `JMSMessageID` header field if publishing/consuming to/from JMS. |
| `solace_applicationMessageType` | String | Read/Write | The application message type. This is the `JMSType` header field if publishing/consuming to/from JMS. |
| `solace_correlationId` | String | Read/Write | The correlation ID. |
| `solace_deliveryCount` | Integer | Read | The number of times the message has been delivered. Note that, while the Delivery Count feature is in controlled availability, `Enable Client Delivery Count` must be enabled on the queue and consumer bindings may need to be restarted after `Enable Client Delivery Count` is turned on. |
| `solace_destination` | Destination | Read | The destination this message was published to. |
| `solace_discardIndication` | Boolean | Read | Whether one or more messages have been discarded prior to the current message. |
| `solace_dmqEligible` | Boolean | Read/Write | Whether the message is eligible to be moved to a Dead Message Queue. |
| `solace_expiration` | Long | Read/Write | The UTC time (in milliseconds, from midnight, January 1, 1970 UTC) when the message is supposed to expire. |
| `solace_httpContentEncoding` | String | Read/Write | The HTTP content encoding header value from interaction with an HTTP client. |
| `solace_isReply` | Boolean | Read/Write | Indicates whether this message is a reply. |
| `solace_priority` | Integer | Read/Write | Priority value in the range of 0–255, or -1 if it is not set. |
| `solace_receiveTimestamp` | Long | Read | The receive timestamp (in milliseconds, from midnight, January 1, 1970 UTC). |
| `solace_redelivered` | Boolean | Read | Indicates if the message has been delivered by the broker to the API before. |
| `solace_replicationGroupMessageId` | ReplicationGroupMessageId | Read | Specifies a Replication Group Message ID as a replay start location. |
| `solace_replyTo` | Destination | Read/Write | The replyTo destination for the message. |
| `solace_senderId` | String | Read/Write | The Sender ID for the message. |
| `solace_senderTimestamp` | Long | Read/Write | The send timestamp (in milliseconds, from midnight, January 1, 1970 UTC). |
| `solace_sequenceNumber` | Long | Read/Write | The sequence number. |
| `solace_timeToLive` | Long | Read/Write | The number of milliseconds before the message is discarded or moved to a Dead Message Queue. |
| `solace_userData` | byte[] | Read/Write | When an application sends a message, it can optionally attach application-specific data along with the message, such as user data. |

#### Solace Binder Headers

These headers are to get/set Solace Spring Cloud Stream Binder properties.

These can be used for:

*   Getting/Setting Solace Binder metadata
*   Directive actions for the binder when producing/consuming messages

> [!TIP]
> Use [SolaceBinderHeaders](src/main/java/com/solace/spring/cloud/stream/binder/messaging/SolaceBinderHeaders.java) instead of hardcoding the header names. This class also contains the same documentation that you see here.

| Header Name | Type | Access | Default Value | Description |
| --- | --- | --- | --- | --- |
| `solace_scst_chunkCount` | Integer | Internal Binder Use Only | | The length of the array of chunks. |
| `solace_scst_chunkId` | Long | Internal Binder Use Only | | The unique identifier of the chunk sequence. |
| `solace_scst_chunkIndex` | Integer | Internal Binder Use Only | | The zero-based index of the current message in the array of chunks. |
| `solace_scst_confirmCorrelation` | CorrelationData | Write | | A CorrelationData instance for messaging confirmations. Only works with `qualityOfService: AT_LEAST_ONCE` (Default). |
| `solace_scst_largeMessageSupport` | Boolean | Write | | Set to `true` to enable sending of large messages (only on producer side). Default is `false`. If using groups only partitioned queues are supported; otherwise, the message chunks may get delivered to the wrong consumer. |
| `solace_scst_messageVersion` | Integer | Read | 1 | A static number set by the publisher to indicate the Spring Cloud Stream Solace message version. |
| `solace_scst_nullPayload` | Boolean | Read | | Present and true to indicate when the PubSub+ message payload was null. |
| `solace_scst_partitionKey` | String | Write | | The partition key for PubSub+ partitioned queues. |
| `solace_scst_serializedHeaders` | String | Internal Binder Use Only | | A JSON String array of header names where each entry indicates that that header’s value was serialized by a Solace Spring Cloud Stream binder before publishing it to a broker. |
| `solace_scst_serializedHeadersEncoding` | String | Internal Binder Use Only | "base64" | The encoding algorithm used to encode the headers indicated by `solace_scst_serializedHeaders`. |
| `solace_scst_serializedPayload` | Boolean | Internal Binder Use Only | | Is `true` if a Solace Spring Cloud Stream binder has serialized the payload before publishing it to a broker. Is undefined otherwise. |
| `solace_scst_targetDestinationType` | String | Write | | Only applicable when `scst_targetDestination` is set.<br>`topic`: the dynamic destination is a topic.<br>`queue`: the dynamic destination is a queue.<br>When absent, the binding's configured destination-type is used. |

## Native Payload Types

Below are the payload types natively supported by this binder (before/after [Content Type Negotiation](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#content-type-management)):

| Payload Type | PubSub+ Message Type | Notes |
| --- | --- | --- |
| byte[] | Binary Message | Basic PubSub+ payload type. |
| String | Text Message | Basic PubSub+ payload type. |
| SDTStream | Stream Message | Basic PubSub+ payload type. |
| SDTMap | Map Message | Basic PubSub+ payload type. |
| String | XML-Content Message | Basic PubSub+ payload type. Only available for consumption. |
| Serializable | Bytes Message | This is not a basic payload type supported by the PubSub+ broker, but is one defined and coordinated by this binder. **Publishing:** When a `Serializable` payload which doesn't satisfy any of the basic PubSub+ payload types is given to the binder to publish, the binder will serialize this payload to a `byte[]` and set the user property, `solace_scst_serializedPayload`, to `true`. **Consuming:** When the binder consumes a binary message which has the `solace_scst_serializedPayload` user property set to `true`, the binder will deserialize the binary attachment. |

> [!TIP]
> Typically, the Spring Cloud Stream framework will convert a published payload into a `byte[]` before giving it to the binder. In which case, this binder will publish a binary message.
> If this occurs, but you wish to publish other message types, then one option is to set `useNativeEncoding=true` on your producer (but read the caveats carefully before enabling this feature), and have your message handler return a payload of one of this binder's supported native payload types; e.g. return `Message<SDTStream>` to publish a stream message.
> See [Content Type Negotiation](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#content-type-management) for more info on how Spring Cloud Streams converts payloads and other options to control message conversion.

### Empty Payload VS Null Payload

Spring messages can't contain null payloads, however, message handlers can differentiate between null payloads and empty payloads by looking at the `solace_scst_nullPayload` header. The binder adds the `solace_scst_nullPayload` header when a Solace message with null payload is consumed from the wire. When that is the case, the binder sets the Spring message's payload to a null equivalent payload. Null equivalent payloads are one of the following: empty `byte[]`, empty `String`, empty `SDTMap`, or empty `SDTStream`.

> [!NOTE]
> Applications can't differentiate between null payloads and empty payloads when consuming binary messages or XML-content messages from the wire. This is because Solace always converts empty payloads to null payloads when those message types are published.

## Generated Queue Name Syntax

By default, generated consumer group queue names have the following form:

```
<prefix>/<familiarity-modifier>/<group>/<destination-encoding>/<encoded-destination>
```

`prefix`
:   A static prefix `scst`.

`familiarity-modifier`
:   Indicates the durability of the consumer group (`wk` for well-known or `an` for anonymous).

`group`
:   The consumer `group` name.

`destination-encoding`
:   Indicates the encoding scheme used to encode the destination in the queue name (currently only `plain` is supported).

`encoded-destination`
:   The encoded `destination` as per `<destination-encoding>`.

The `queueNameExpression` property's default SpEL expression conforms to the above format, however, users can provide any valid SpEL expression in order to generate custom queue names. Valid expressions evaluate against the following context:

| Context Variable | Description |
| --- | --- |
| `destination` | The binding’s destination name. |
| `group` | The binding’s consumer group name. |
| `isAnonymous` | Indicates whether the consumer is an anonymous consumer group |
| `properties.solace` | The configured Solace binding properties. |
| `properties.spring` | The configured Spring binding properties. |

### Generated Error Queue Name Syntax

By default, generated error queue names have the following form:

```
<prefix>/error/<familiarity-modifier>/<group>/<destination-encoding>/<encoded-destination>
```

The definitions of each segment of the error queue matches that from [Generated Queue Name Syntax](#generated-queue-name-syntax), with the following exceptions:

`group`
:   The consumer `group` name.

> [!TIP]
> As a workaround since it's impossible to configure a non-durable queue with programmatic start there is the "group: non-durable" magic word to declare the queue as non-durable.

The `errorQueueNameExpression` property's default SpEL expression conforms to the above format. Users can provide any valid SpEL expression in order to generate custom error queue names using the same evaluation context as described in [Generated Queue Name Syntax](#generated-queue-name-syntax).

## Consumer Concurrency

Configure Spring Cloud Stream's [concurrency consumer property](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#_consumer_properties) to enable concurrent message consumption for a particular consumer binding.

*   Concurrent processing is now supported for exclusive queues and for anonymous consumer groups.
*   This enables higher throughput when you cannot or do not want to use non-exclusive queues.
*   There will be no guarantee of ordering any more when concurrent processing is used.

> [!NOTE]
> Setting `provisionDurableQueue` to `false` disables endpoint configuration validation. In this scenario, it is the developer's responsibility to ensure queue settings meet your expectations.

#### How to Enable

*   Simply set the Spring Cloud Stream consumer property `concurrency` to a value > 1 on your binding.
*   Works for both well-known (durable) groups and anonymous (temporary queue) consumers, regardless of queue access type.

#### Example

```yaml
spring:
  cloud:
    stream:
      bindings:
        input:
          destination: orders
          group: accounting
          consumer:
            concurrency: 4
```

#### Caveats Specific to Exclusive Queues

*   With exclusive access type, the broker still delivers to a single flow, but the binder now dispatches messages to multiple processing threads, allowing parallel handling within the same consumer instance.
*   Ensure your message handling logic is thread-safe.
*   There will be no guarantee of ordering any more.

#### Caveats Specific to Anonymous Groups

*   Anonymous groups use temporary queues; multiple processing threads will share the same underlying flow.
*   Make sure any stateful components are safe for concurrent use.
*   There will be no guarantee of ordering any more.

### Inbound Message Flow

The Solace binder uses a specific threading model to handle inbound messages efficiently and support concurrency.

1.  **Solace Dispatcher Thread**:
    *   The Solace JCSMP API uses a single internal thread (Context Thread) to receive messages from the broker.
    *   This thread invokes the `onReceive` callback in the binder.
    *   **Crucial**: This thread must *never* block. Blocking it would stall the entire connection and stop all message consumption for that session.
2.  **Internal Message Queue**:
    *   To decouple the Solace Dispatcher Thread from application processing, the binder places received messages into an internal, in-memory `BlockingQueue`.
    *   This queue acts as a buffer. Its effective size is limited by the `maxUnacknowledgedMessages` (or `max-guaranteed-message-size`) setting on the flow.
    *   This mechanism provides backpressure: if the application is slow, the internal queue fills up, and the binder stops acknowledging messages to the broker, eventually causing the broker to stop sending more messages until space becomes available.
3.  **Worker Threads**:
    *   The binder starts a pool of worker threads (determined by the `concurrency` setting).
    *   These threads constantly poll the internal `BlockingQueue` for new messages.
    *   When a message is available, a worker thread picks it up and processes it.
4.  **Application Processing**:
    *   The worker thread invokes the Spring Cloud Stream consumer (your application code).
    *   This is where your business logic executes.
    *   Since multiple worker threads can be active, multiple messages can be processed in parallel (concurrently).
5.  **Acknowledgment**:
    *   Once the application finishes processing (successfully or with an error handled by the framework), the worker thread handles the message acknowledgment (ACK/NACK) back to the broker.

## Partitioning

> [!NOTE]
> The Solace PubSub+ broker supports partitioning natively.
> This only works with `qualityOfService: AT_LEAST_ONCE` (Default).
> The partitioning abstraction as described in the [Spring Cloud Stream documentation](https://docs.spring.io/spring-cloud-stream/docs/current/reference/html/spring-cloud-stream.html#partitioning) is not supported.

To publish messages that are intended for partitioned queues, you must provide a partition key by setting the `solace_scst_partitionKey` message header (accessible through the `SolaceBinderHeaders.PARTITION_KEY` constant).

For example:

```java
public class MyMessageBuilder {
    public Message<String> buildMeAMessage() {
        return MessageBuilder.withPayload("payload")
            .setHeader(SolaceBinderHeaders.PARTITION_KEY, "partition-key")
            .build();
    }
}
```

As for consuming messages from partitioned queues, this is handled transparently by the PubSub+ broker. That is to say, consuming messages from a partitioned queue is no different from consuming messages from any other queue.

> [!WARNING]
> **Note on Message Ordering:** Solace Partitioned Queues guarantee that all messages with the same partition key are delivered in order to the exact same consumer flow. However, strict message ordering is *only* guaranteed if your consumer process is single-threaded. **Setting `concurrency` > 1 may break the message order per partition.** Additionally, if your application logic offloads processing to asynchronous threads (e.g., using `CompletableFuture`, `@Async`, or manually managed thread pools), the processing order may become non-deterministic despite the broker delivering them in order.

See [Partitioned Queues](https://docs.solace.com/Messaging/Guaranteed-Msg/Queues.htm#partitioned-queues) for more.

## Manual Message Acknowledgment

> [!NOTE]
> Only works with `qualityOfService: AT_LEAST_ONCE` (Default).

Manual acknowledgment allows the application to control exactly when a message is considered successfully processed. This is useful for scenarios where processing is asynchronous or depends on external systems.

### How It Works

1.  **Worker Thread Receives Message**: A worker thread picks up a message from the internal queue.
2.  **Application Processing**: The message is passed to your consumer.
3.  **Disable Auto-Ack**: Your code calls `noAutoAck()` on the acknowledgment callback. This tells the binder *not* to acknowledge the message automatically when your method returns.
4.  **Manual Action**: Your code (or a separate thread/callback) explicitly calls `AckUtils.accept()`, `AckUtils.reject()`, or `AckUtils.requeue()`.
5.  **Broker Notification**: The binder sends the corresponding signal (ACK or NACK) to the Solace broker.

### Usage Example

Message handlers can disable auto-acknowledgement and manually invoke the acknowledgement callback as follows:

```java
public void consume(Message<?> message) {
    AcknowledgmentCallback acknowledgmentCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(message); // (1)
    acknowledgmentCallback.noAutoAck(); // (2)
    try {
        // ... process message ...
        AckUtils.accept(acknowledgmentCallback); // (3)
    } catch (Exception e) {
        // Handle error, potentially reject or requeue
        AckUtils.reject(acknowledgmentCallback); // (4)
    }
}
```

1.  Get the message's acknowledgement callback header
2.  Disable auto-acknowledgement
3.  Acknowledge the message with the `ACCEPT` status
4.  Reject the message (moves to error queue or discard)

Refer to the [AckUtils documentation](https://docs.spring.io/spring-integration/api/org/springframework/integration/acks/AckUtils.html) and [AcknowledgmentCallback documentation](https://javadoc.io/doc/org.springframework.integration/spring-integration-core/latest/org/springframework/integration/acks/AcknowledgmentCallback.html) for more info on these objects.

> [!TIP]
> If manual acknowledgement is to be done outside of the message handler's thread, then make sure auto-acknowledgement is disabled within the message handler's thread and not an external one. Otherwise, the binder will auto-acknowledge the message when the message handler returns.

### Acknowledgment Actions

For each acknowledgement status, the binder will perform the following actions:

| Status | Action |
| --- | --- |
| ACCEPT | **Positive Acknowledgment (ACK)**. The message is removed from the broker's queue. |
| REJECT | **Negative Acknowledgment (NACK) - Failed**.<br>• If `autoBindErrorQueue` is `true`: The binder republishes the message to the error queue and then `ACCEPT`s the original message to remove it from the source queue.<br>• If `autoBindErrorQueue` is `false`: The binder sends a `REJECTED` settlement outcome to the broker. The broker will then discard the message or move it to the DMQ (if configured). Refer to [Failed Consumer Message Error Handling](#failed-consumer-message-error-handling) for more info. |
| REQUEUE | **Negative Acknowledgment (NACK) - Redeliver**. The binder sends a `FAILED` settlement outcome to the broker. The broker will redeliver the message to the consumer flow. Redelivery continues until the message is `ACCEPT`ed or the queue's max redelivery count is exceeded (at which point it moves to DMQ). Refer to [Message Redelivery](#message-redelivery) for more info. |

> [!IMPORTANT]
> Acknowledgements may throw `SolaceAcknowledgmentException` depending on the current state of the consumer. Particularly if doing asynchronous acknowledgements, your invocation to acknowledge a message should catch `SolaceAcknowledgmentException` and deal with it accordingly.
> **Example:**
> (refer to [Message Redelivery](#message-redelivery) for background info)
> A `SolaceAcknowledgmentException` with cause `IllegalStateException` may be thrown when trying to asynchronously `ACCEPT` a message and consumer flow is closed. Though for this particular example, since the message that failed to `ACCEPT` will be redelivered, this exception can be caught and ignored if you have no business logic to revert.

> [!NOTE]
> Manual acknowledgements do not support any application-internal error handling strategies (i.e. retry template, error channel forwarding, etc). Also, throwing an exception in the message handler will always acknowledge the message in some way regardless if auto-acknowledgment is disabled.

> [!TIP]
> If asynchronously acknowledging messages, then if these messages aren’t acknowledged in a timely manner, it is likely for the message consumption rate to stall due to the consumer queue’s configured "Maximum Delivered Unacknowledged Messages per Flow".
> This property can be configured for dynamically created queues by using [queue templates](https://docs.solace.com/Configuring-and-Managing/Configuring-Endpoint-Templates.htm#Configur). However note that as per [our documentation](https://docs.solace.com/PubSub-Basics/Endpoints.htm#Which), anonymous consumer group queues (i.e. temporary queues) will not match a queue template’s name filter. Only the queue template defined in the client profile’s "Copy Settings From Queue Template" setting will apply to those.

## Dynamic Producer Destinations

Spring Cloud Stream has a reserved message header called `scst_targetDestination` (retrievable via `BinderHeaders.TARGET_DESTINATION`), which allows for messages to be redirected from their bindings' configured destination to the target destination specified by this header.

For this binder's implementation of this header, the target destination defines the *exact* Solace topic or queue to which a message will be sent. i.e. No post-processing is done.

This binder also adds a reserved message header called `solace_scst_targetDestinationType` (retrievable via `SolaceBinderHeaders.TARGET_DESTINATION_TYPE`), which allows to override the configured producer `destination-type`.
Possible values are `topic` or `queue`. If not specified, the system defaults to sending to a `topic`.

```java
public class MyMessageBuilder {
    public Message<String> buildMeAMessage() {
        return MessageBuilder.withPayload("payload")
            .setHeader(BinderHeaders.TARGET_DESTINATION, "some-dynamic-destination") // (1)
            // .setHeader(SolaceBinderHeaders.TARGET_DESTINATION_TYPE, "queue")      // (2)
            .build();
    }
}
```

1.  This message will be sent to the `some-dynamic-destination` topic, ignoring the producer's configured destination.
2.  Optionally, the configured producer `destination-type` can be overridden (e.g., to "queue"). By default, dynamic destinations are assumed to be topics.

> [!NOTE]
> Those headers are cleared from the message before it is sent off to the message broker. So you should attach that information to your message payload if you want to get that information on the consumer-side.

> [!NOTE]
> **Dynamic Producer Destinations with StreamBridge**
> This binder does not support the usage of [StreamBridge's dynamic destination feature](https://docs.spring.io/spring-cloud-stream/docs/current/reference/html/spring-cloud-stream.html#_streambridge_and_dynamic_destinations), which automatically creates and caches unknown output bindings on-the-fly.
> Instead, set the `scst_targetDestination` message header and send the message to a pre-defined output binding:
> ```java
> public void sendMessage(StreamBridge streamBridge, String myDynamicDestination, Message<?> message) {
>   Message<?> messageWithDestination = MessageBuilder.fromMessage(message)
>       .setHeader(BinderHeaders.TARGET_DESTINATION, myDynamicDestination)
>       .build();
>   streamBridge.send("some-pre-defined-output-binding", messageWithDestination);
> }
> ```
> Then in your application's configuration file, configure your predefined output binding:
> ```shell
> spring.cloud.stream.output-bindings=some-pre-defined-output-binding
> ```
> For more info, see [Sending arbitrary data to an output (e.g. Foreign event-driven sources)](https://docs.spring.io/spring-cloud-stream/docs/current/reference/html/spring-cloud-stream.html#_sending_arbitrary_data_to_an_output_e_g_foreign_event_driven_sources).

## Failed Consumer Message Error Handling

The Spring cloud stream framework already provides a number of application-internal reprocessing strategies for failed messages during message consumption. You can read more about that [here](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#spring-cloud-stream-overview-error-handling):

However, after all internal error handling strategies have been exhausted, the Solace implementation of the binder would either:

*   Redeliver the failed message (default)
*   Republish the message to another queue (an error queue) for an external application/binding to process

### Message Redelivery

A simple error handling strategy in which failed messages are redelivered from the consumer group's queue. This is very similar to simply enabling the retry template (setting `maxAttempts` to a value greater than `1`), but allows for the failed messages to be re-processed by the message broker.

> [!IMPORTANT]
> The internal implementation of redelivery has changed from Solace Binder v5.0.0. Previously, redelivery was initiated by rebinding consumer flows; however, as of v5.0.0 and later, the Solace API now leverages the Solace broker's native NACK (Negative Acknowledgement) capabilities.
> Here is what happens under the hood when this is triggered:
> 1.  Say the current message is marked for 'REQUEUE'. Any subsequent messages that are currently spooled on the client side, despite having been acknowledged `ACCEPTed` by binder, the Solace broker will discard their ACK.
> 2.  The Solace Broker will redeliver all messages starting with the one tagged as 'REQUEUE', if the message's max redelivery count is not exceeded.
> The redelivery may result in message duplication, and the application should be designed to handle this.

### Error Queue Republishing

First, it must be noted that an Error Queue is different from a [Dead Message Queue (DMQ)](https://docs.solace.com/Configuring-and-Managing/Setting-Dead-Msg-Queues.htm). In particular, a DMQ is used to capture re-routed failed messages as a consequence of Solace PubSub+ messaging features such as TTL expiration or exceeding a message's max redelivery count. Whereas the purpose of an Error Queue is to capture re-routed messages which have been successfully consumed from the message broker, yet cannot be processed by the application.

An Error Queue can be provisioned for a particular consumer group by setting the `autoBindErrorQueue` consumer config option to `true`. This Error Queue is simply another durable queue which is named as per the [Generated Error Queue Name Syntax](#generated-error-queue-name-syntax) section. And like the queues used for consumer groups, its endpoint properties can be configured by means of any consumer properties whose names begin with "errorQueue".

> [!NOTE]
> Error Queues should not be used with anonymous consumer groups.
> Since the names of anonymous consumer groups, and in turn the name of their would-be Error Queues, are randomly generated at runtime, it would provide little value to create bindings to these Error Queues because of their unpredictable naming and temporary existence. Also, your environment will be polluted with orphaned Error Queues whenever these consumers rebind.

### Mutating Messages while using Spring's Retry Template

When locally reprocessing failed messages with [Spring's Retry Template](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#_retry_template) (i.e. when consumer `maxAttempts > 0`), mutations of nested objects within the Spring `Message<?>` may persist between retries.

**Example: Mutating `SDTMap` payload and failing the message**

```java
public Function<Message<SDTMap>, Message<SDTMap>> transform() {
    return message -> {
        if (!message.getPayload().containsKey("new-key")) { // (1)
            message.getPayload().putString("new-key", "value");
        }

        // failing message processing to trigger retry template
        throw new RuntimeException("Failed processing");
    };
}
```

1.  Here, this example only invokes this if-statement if the `SDTMap` payload does not contain the key `"new-key"`.
    If the consumer binding was configured with `maxAttempts > 1`, then on the following reprocessing attempts, the payload will still contain the key `"new-key"` from the previous attempt.

If this behavior is undesirable, then you should configure your consumers `maxAttempts` to `1` and rely on [Message Redelivery](#message-redelivery) to handle reprocessing.

## Consumer Bindings Pause/Resume

The Solace binder supports pausing and resuming consumer bindings. See [Spring Cloud Stream documentation](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#binding_visualization_control) to learn how to pause and resume consumer bindings.

> [!NOTE]
> There is no guarantee that the effect of pausing a binding will be instantaneous: messages already in-flight or being processed by the binder may still be delivered after the call to pause returns.

## Failed Producer Message Error Handling

By default, asynchronous producer errors aren't handled by the framework. Producer error channels can be enabled using the [`errorChannelEnabled` producer config option](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#_producer_properties).

Beyond that, this binder also supports using a `Future` to wait for publish confirmations. See [Publisher Confirms](#publisher-confirms) for more info.

## Publisher Confirmations

For each message you can create a new [`CorrelationData`](../../solace-spring-cloud-stream-binder/solace-spring-cloud-stream-binder-core/src/main/java/com/solace/spring/cloud/stream/binder/util/CorrelationData.java) instance and set it as the value of your message's `SolaceBinderHeaders.CONFIRM_CORRELATION` header.

> [!NOTE]
> `CorrelationData` can be extended to add more correlation info. The `SolaceBinderHeaders.CONFIRM_CORRELATION` header is not reflected in the actual message published to the broker.

Now using `CorrelationData.getFuture().get()`, you can wait for a publish acknowledgment from the broker. If the publish failed, then this future will throw an exception.

For example:

```java
@Autowired
private StreamBridge streamBridge;

public void send(String payload, long timeout, TimeUnit unit) {
    CorrelationData correlationData = new CorrelationData();
    Message<SensorReading> message = MessageBuilder.withPayload(payload)
            .setHeader(SolaceBinderHeaders.CONFIRM_CORRELATION, correlationData)
            .build();

    streamBridge.send("output-destination", message);

    try {
        correlationData.getFuture().get(timeout, unit);
        // Do success logic
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
        // Do failure logic
    }
}
```

## Solace Binder Health Indicator

Solace binders can report health statuses via the [Spring Boot Actuator health endpoint](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/spring-cloud-stream.html#_health_indicator). To enable this feature, add Spring Boot Actuator to the classpath. To manually disable this feature, set `management.health.binders.enabled=false`.

| Health Status | Description |
| --- | --- |
| UP | Status indicating that the binder is functioning as expected. |
| RECONNECTING | Status indicating that the binder is actively trying to reconnect to the message broker. This is a custom health status. It isn't included in the health severity order list (`management.endpoint.health.status.order`) and returns the default HTTP status code of `200`. To customize these, see [Writing Custom HealthIndicators](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints.health.writing-custom-health-indicators). |
| DOWN | Status indicating that the binder has suffered an unexpected failure. This status is reported when: (1) all reconnection attempts to the broker have been exhausted, (2) the JCSMP session has been destroyed, or (3) queue/endpoint provisioning has failed. User intervention is likely required. |

## Solace Binder Metrics

Leveraging [Spring Metrics](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.metrics), the Solace PubSub+ binder exposes the following metrics:

> [!NOTE]
> Time-based metrics (`backpressure`, `wait.time`, `processing.time`) are registered as `DistributionSummary` with a `milliseconds` base unit, **not** as `Timer`. This means Micrometer does **not** auto-convert the values to seconds. The recorded values are always in milliseconds.

| Name | Type | Tags | Description |
| --- | --- | --- | --- |
| `solace.message.size.payload` | `DistributionSummary` Base Units: `bytes` | *   `name: <bindingName>` | Message payload size. This is the payload size of the messages received (if `name` is a consumer binding) or published (if `name` is a producer binding) from/to a PubSub+ broker. |
| `solace.message.size.total` | `DistributionSummary` Base Units: `bytes` | *   `name: <bindingName>` | Total message size. This is the total size of the messages received (if `name` is a consumer binding) or published (if `name` is a producer binding) from/to a PubSub+ broker. |
| `solace.message.queue.size` | `DistributionSummary` Base Units: `messages` | *   `name: <bindingName>` | Internal message queue size. Number of messages waiting in the binder's internal queue to be processed by worker threads. Updates periodically (every 1s). |
| `solace.message.active.size` | `DistributionSummary` Base Units: `messages` | *   `name: <bindingName>` | Messages currently being processed. Number of messages actively being processed by worker threads. Updates periodically (every 1s). |
| `solace.message.queue.backpressure` | `DistributionSummary` Base Units: `milliseconds` | *   `name: <bindingName>` | Queue backpressure (wait time of oldest message). The time in milliseconds that the oldest message currently waiting in the queue has been waiting. This metric represents the current maximum wait time for a message to check out of the queue. Updates periodically (every 1s). |
| `solace.message.queue.wait.time` | `DistributionSummary` Base Units: `milliseconds` | *   `name: <bindingName>` | Queue wait time. The time in milliseconds that a message spent waiting in the internal queue before processing started. Recorded for every message just before the user handler is invoked. |
| `solace.message.processing.time` | `DistributionSummary` Base Units: `milliseconds` | *   `name: <bindingName>` | Message processing duration. How long each message took to process, measured from when the worker thread received the message until processing completed. This includes the time spent in the user's message handler. |

### Backpressure SLO Recommendations

To monitor backpressure effectively, configure alerts based on the following metrics:

1.  **solace.message.queue.backpressure** - Alert when p99 exceeds your SLO threshold
2.  **solace.message.queue.size** - Alert when consistently higher than `concurrency` setting
3.  **Broker queue depth** - Monitor via Solace SEMP API or exporter

> [!IMPORTANT]
> The Prometheus alert examples below use `quantile` labels which are only available if you configure percentile histograms or SLO boundaries in your `application.yaml`. Without this configuration, Prometheus will not expose quantile values.
>
> ```yaml
> management:
>   metrics:
>     distribution:
>       percentiles-histogram:
>         solace.message.queue.backpressure: true
>         solace.message.processing.time: true
>       slo:
>         solace.message.queue.backpressure: 1000,5000,10000,30000,60000
> ```

Example Prometheus alert:

```yaml
- alert: SolaceBinderBackpressure
  expr: solace_message_queue_backpressure{quantile="0.99"} > 60000
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "High backpressure on {{ $labels.name }}"
```

#### Mitigation Strategies

To address backpressure detected via metrics:

*   **Optimize the application**: If possible try to increase the processing speed in the application. This would ease the congestion on the queue, but is the most complex option and might not always be possible.
*   **Increase concurrency**: Having more worker threads increases throughput because more messages are consumed. This solution is simple to do, but comes at the cost of increased resource usage.

## Watchdog

### Purpose

The watchdog thread monitors message processing to detect *deadlocked or stuck threads*. It does NOT detect backpressure - use metrics for that (see [Backpressure Monitoring](#backpressure-monitoring)).

### Deadlock Detection

When a message has been processing for longer than `watchdogTimeoutMs` (default: 5 minutes), a warning is logged once per message:

```log
WARN Message processing exceeded 300000 ms (potential deadlock): thread=binding-0, messageId=xxx, destination=topic/name
```

This indicates a thread may be stuck and requires investigation.

### Configuration

`watchdogTimeoutMs`
:   Time in milliseconds before a long-running message processing thread triggers a warning. Used to detect potential deadlocks or stuck threads.
    Default: `300000` (5 minutes)

> [!NOTE]
> Set this higher than your expected maximum message processing time.

### Backpressure Monitoring

For backpressure detection, use the `solace.message.queue.backpressure` metric instead of log warnings. See [Solace Binder Metrics](#solace-binder-metrics) and [Backpressure SLO Recommendations](#backpressure-slo-recommendations).

### Mitigation

To address issues detected by the watchdog:

*   **For deadlocked threads**: Investigate the application code for potential deadlocks, infinite loops, or blocking operations. Check thread dumps to identify what the stuck thread is doing.
*   **For backpressure** (detected via metrics): See mitigation strategies below in [Backpressure SLO Recommendations](#backpressure-slo-recommendations).

## Micrometer Tracing

The binder supports Micrometer tracing. To enable, ensure the needed Beans are available: Tracer and Propagator.

## Resources

For more information about Spring Cloud Streams try these resources:

*   [Spring Docs - Spring Cloud Stream Reference Documentation](https://docs.spring.io/spring-cloud-stream/docs/4.1.x/reference/html/)
*   [GitHub Samples - Spring Cloud Stream Sample Applications](https://github.com/spring-cloud/spring-cloud-stream-samples)
*   [Github Source - Spring Cloud Stream Source Code](https://github.com/spring-cloud/spring-cloud-stream)

For more information about Solace technology in general please visit these resources:

*   The Solace Developer Portal website at: [https://solace.dev](https://solace.dev)
*   Ask the [Solace community](https://solace.community)

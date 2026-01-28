# Solace Special Headers

This document describes the Solace-defined Spring headers used to interact with Solace message properties and the Solace Spring Cloud Stream Binder.

For more details on the underlying Solace API, please refer to the [Solace Java API Documentation](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/index.html).

## Solace Message Headers (`SolaceHeaders`)

These headers map directly to Solace `XMLMessage` properties. They are defined in `com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders`.

| Constant                                     | Type                        | Access     | Description                                                                                                                                                         |
|:---------------------------------------------|:----------------------------|:-----------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SolaceHeaders.APPLICATION_MESSAGE_ID`       | `String`                    | Read/Write | The message ID (a string for an application-specific message identifier). This is the `JMSMessageID` header field if publishing/consuming to/from JMS.              |
| `SolaceHeaders.APPLICATION_MESSAGE_TYPE`     | `String`                    | Read/Write | The application message type. This is the `JMSType` header field if publishing/consuming to/from JMS.                                                               |
| `SolaceHeaders.CORRELATION_ID`               | `String`                    | Read/Write | The correlation ID.                                                                                                                                                 |
| `SolaceHeaders.DELIVERY_COUNT`               | `Integer`                   | Read       | The number of times the message has been delivered.                                                                                                                 |
| `SolaceHeaders.DESTINATION`                  | `Destination`               | Read       | The destination this message was published to.                                                                                                                      |
| `SolaceHeaders.DISCARD_INDICATION`           | `Boolean`                   | Read       | Whether one or more messages have been discarded prior to the current message. Only for non-persisted messaging.                                                    |
| `SolaceHeaders.DMQ_ELIGIBLE`                 | `Boolean`                   | Read/Write | Whether the message is eligible to be moved to a Dead Message Queue.                                                                                                |
| `SolaceHeaders.EXPIRATION`                   | `Long`                      | Read/Write | The UTC time (in milliseconds, from midnight, January 1, 1970 UTC) when the message is supposed to expire. Prefer TIME_TO_LIVE.                                     |
| `SolaceHeaders.HTTP_CONTENT_ENCODING`        | `String`                    | Read/Write | The HTTP content encoding header value from interaction with an HTTP client.                                                                                        |
| `SolaceHeaders.IS_REPLY`                     | `Boolean`                   | Read/Write | Indicates whether this message is a reply.                                                                                                                          |
| `SolaceHeaders.PRIORITY`                     | `Integer`                   | Read/Write | Priority value in the range of 0–255, or -1 if it is not set.                                                                                                       |
| `SolaceHeaders.REPLICATION_GROUP_MESSAGE_ID` | `ReplicationGroupMessageId` | Read       | The replication group message ID (Specifies a Replication Group Message ID as a replay start location).                                                             |
| `SolaceHeaders.RECEIVE_TIMESTAMP`            | `Long`                      | Read       | The receive timestamp (in milliseconds, from midnight, January 1, 1970 UTC).                                                                                        |
| `SolaceHeaders.REDELIVERED`                  | `Boolean`                   | Read       | Indicates if the message has been delivered by the broker to the API before.                                                                                        |
| `SolaceHeaders.REPLY_TO`                     | `Destination`               | Read/Write | The replyTo destination for the message. Dont use this message directly. Prefer [request lib](https://github.com/solacecommunity/spring-cloud-stream-request-reply) |
| `SolaceHeaders.SENDER_ID`                    | `String`                    | Read/Write | The Sender ID for the message.                                                                                                                                      |
| `SolaceHeaders.SENDER_TIMESTAMP`             | `Long`                      | Read/Write | The send timestamp (in milliseconds, from midnight, January 1, 1970 UTC).                                                                                           |
| `SolaceHeaders.SEQUENCE_NUMBER`              | `Long`                      | Read/Write | The sequence number.                                                                                                                                                |
| `SolaceHeaders.TIME_TO_LIVE`                 | `Long`                      | Read/Write | The number of milliseconds before the message is discarded or moved to a Dead Message Queue.                                                                        |

## Solace Binder Headers (`SolaceBinderHeaders`)

These headers are used to get/set Solace Spring Cloud Stream Binder properties. They are defined in `com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders`.

| Constant                                          | Type              | Access   | Description                                                                                                                                                                     |
|:--------------------------------------------------|:------------------|:---------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SolaceBinderHeaders.PARTITION_KEY`               | `String`          | Write    | The partition key for PubSub+ partitioned queues.                                                                                                                               |
| `SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT`       | `Boolean`         | -        | When **true** large messages are split in 4MB chunks and reassembled in the consumer. The Queue needs to be partitioned to support this feature.                                |
| `SolaceBinderHeaders.CHUNK_ID`                    | `Long`            | Internal | The id (should be more or less unique) of the array of chunks.                                                                                                                  |
| `SolaceBinderHeaders.CHUNK_INDEX`                 | `Integer`         | Internal | The index of the current message in the array of chunks. Zero-Based.                                                                                                            |
| `SolaceBinderHeaders.CHUNK_COUNT`                 | `Integer`         | Internal | The length of the array of chunks.                                                                                                                                              |
| `SolaceBinderHeaders.MESSAGE_VERSION`             | `Integer`         | Read     | A static number set by the publisher to indicate the Spring Cloud Stream Solace message version. Default: 1.                                                                    |
| `SolaceBinderHeaders.SERIALIZED_PAYLOAD`          | `Boolean`         | Internal | Is `true` if a Solace Spring Cloud Stream binder has serialized the payload before publishing it to a broker.                                                                   |
| `SolaceBinderHeaders.SERIALIZED_HEADERS`          | `String`          | Internal | A JSON String array of header names where each entry indicates that that header’s value was serialized by a Solace Spring Cloud Stream binder before publishing it to a broker. |
| `SolaceBinderHeaders.SERIALIZED_HEADERS_ENCODING` | `String`          | Internal | The encoding algorithm used to encode the headers indicated by `serializedHeaders`. Default: "base64".                                                                          |
| `SolaceBinderHeaders.CONFIRM_CORRELATION`         | `CorrelationData` | Write    | A CorrelationData instance for messaging confirmations.                                                                                                                         |
| `SolaceBinderHeaders.NULL_PAYLOAD`                | `Boolean`         | Read     | Present and true to indicate when the PubSub+ message payload was null.                                                                                                         |
| `SolaceBinderHeaders.TARGET_DESTINATION_TYPE`     | `String`          | Write    | Only applicable when `scst_targetDestination` is set. Values: `topic`, `queue`.                                                                                                 |

## Usage example

```java
@Service
class MyBusinessLogic {
    private final StreamBridge streamBridge;

    public void send(Message<?> message) {
        Message<PayloadObject> message = MessageBuilder
                .withPayload(payload)
                .setHeader(SolaceHeaders.TIME_TO_LIVE, TimeUnit.SECONDS.toMillis(30))
                .setHeader(BinderHeaders.TARGET_DESTINATION, baseDestination + "/" + location)
                .build();

        streamBridge.send("emitTemperatureSensorDynamic-out-0", message);
    }
}
```
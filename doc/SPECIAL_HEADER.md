# Solace Special Headers

This document describes the Solace-defined Spring headers used to interact with Solace message properties and the Solace Spring Cloud Stream Binder.

For more details on the underlying Solace API, please refer to the [Solace Java API Documentation](https://docs.solace.com/API-Developer-Online-Ref-Documentation/java/index.html).

Access levels in the tables below follow the current header metadata in `SolaceHeaderMeta` and `SolaceBinderHeaderMeta`.

## Solace Message Headers (`SolaceHeaders`)

These headers map directly to Solace `XMLMessage` properties. They are defined in `com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders`.

| Constant                                     | Type                        | Access     | Default Value                       | Description                                                                                                                                                         |
|:---------------------------------------------|:----------------------------|:-----------|:------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SolaceHeaders.APPLICATION_MESSAGE_ID`       | `String`                    | Read/Write | `null`                              | The message ID (a string for an application-specific message identifier). This is the `JMSMessageID` header field if publishing/consuming to/from JMS.              |
| `SolaceHeaders.APPLICATION_MESSAGE_TYPE`     | `String`                    | Read/Write | `null`                              | The application message type. This is the `JMSType` header field if publishing/consuming to/from JMS.                                                              |
| `SolaceHeaders.CORRELATION_ID`               | `String`                    | Read/Write | `null`                              | The correlation ID.                                                                                                                                                 |
| `SolaceHeaders.DELIVERY_COUNT`               | `Integer`                   | Read       | Feature-dependent                   | The number of times the message has been delivered.                                                                                                                 |
| `SolaceHeaders.DESTINATION`                  | `Destination`               | Read       | Broker-set                          | The destination this message was published to.                                                                                                                      |
| `SolaceHeaders.DISCARD_INDICATION`           | `Boolean`                   | Read       | `false`                             | Whether one or more messages have been discarded prior to the current message. On Direct consumers, use this to detect upstream message loss.                      |
| `SolaceHeaders.DMQ_ELIGIBLE`                 | `Boolean`                   | Read/Write | `true` on binder-published messages | Whether the message is eligible to be moved to a Dead Message Queue. Set this to `true` on publishers if you expect expired or rejected messages to reach a DMQ.   |
| `SolaceHeaders.EXPIRATION`                   | `Long`                      | Read/Write | `0`                                 | The UTC time (in milliseconds, from midnight, January 1, 1970 UTC) when the message is supposed to expire. Do not publish conflicting values for both `EXPIRATION` and `TIME_TO_LIVE`; identical copied inbound values are treated as the same lifetime. Meaningful receive-side values require `CALCULATE_MESSAGE_EXPIRATION=true`. |
| `SolaceHeaders.HTTP_CONTENT_ENCODING`        | `String`                    | Read/Write | `null`                              | The HTTP content encoding header value from interaction with an HTTP client.                                                                                        |
| `SolaceHeaders.IS_REPLY`                     | `Boolean`                   | Read/Write | `false`                             | Indicates whether this message is a reply.                                                                                                                          |
| `SolaceHeaders.PRIORITY`                     | `Integer`                   | Read/Write | `-1`                                | Priority value in the range of 0–255, or -1 if it is not set.                                                                                                       |
| `SolaceHeaders.REPLICATION_GROUP_MESSAGE_ID` | `ReplicationGroupMessageId` | Read       | `null`                              | The replication group message ID (Specifies a Replication Group Message ID as a replay start location).                                                             |
| `SolaceHeaders.RECEIVE_TIMESTAMP`            | `Long`                      | Read       | `0`                                 | The receive timestamp (in milliseconds, from midnight, January 1, 1970 UTC). Meaningful receive-side values require `GENERATE_RCV_TIMESTAMPS=true`.               |
| `SolaceHeaders.REDELIVERED`                  | `Boolean`                   | Read       | `false`                             | Indicates if the message has been delivered by the broker to the API before.                                                                                        |
| `SolaceHeaders.REPLY_TO`                     | `Destination`               | Read/Write | `null`                              | The replyTo destination for the message.                                                                                                                            |
| `SolaceHeaders.SENDER_ID`                    | `String`                    | Read/Write | `null`                              | The Sender ID for the message.                                                                                                                                      |
| `SolaceHeaders.SENDER_TIMESTAMP`             | `Long`                      | Read/Write | `0`                                 | The send timestamp (in milliseconds, from midnight, January 1, 1970 UTC). Meaningful receive-side values require `GENERATE_SEND_TIMESTAMPS=true` unless the publisher set it explicitly. |
| `SolaceHeaders.SEQUENCE_NUMBER`              | `Long`                      | Read/Write | `0`                                 | The sequence number. Meaningful receive-side values require `GENERATE_SEQUENCE_NUMBERS=true` unless the publisher set it explicitly.                               |
| `SolaceHeaders.TIME_TO_LIVE`                 | `Long`                      | Read/Write | `0`                                 | The number of milliseconds before the message is discarded or moved to a Dead Message Queue. Prefer setting this from a duration such as `Duration.ofSeconds(30).toMillis()`. Do not publish conflicting values for both `TIME_TO_LIVE` and `EXPIRATION`; identical copied inbound values are treated as the same lifetime. |
| `SolaceHeaders.USER_DATA`                    | `byte[]`                    | Read/Write | `null`                              | When an application sends a message, it can optionally attach application-specific data along with the message, such as user data.                                  |

## Solace Binder Headers (`SolaceBinderHeaders`)

These headers are used to get/set Solace Spring Cloud Stream Binder properties. They are defined in `com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders`.

| Constant                                          | Type              | Access   | Default Value                                 | Description                                                                                                                                                                     |
|:--------------------------------------------------|:------------------|:---------|:----------------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SolaceBinderHeaders.PARTITION_KEY`               | `String`          | Write    | Unset                                         | The partition key for PubSub+ partitioned queues.                                                                                                                               |
| `SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT`       | `Boolean`         | Write    | `false`                                       | Set to `true` to enable sending of large messages. If using consumer groups, only partitioned queues are supported; otherwise, chunks can be delivered to different consumers. |
| `SolaceBinderHeaders.CHUNK_ID`                    | `Long`            | Internal Binder Use Only | Unset unless chunking is active               | The unique identifier of the chunk sequence.                                                                                                              |
| `SolaceBinderHeaders.CHUNK_INDEX`                 | `Integer`         | Internal Binder Use Only | Unset unless chunking is active               | The zero-based index of the current message in the array of chunks.                                                                                    |
| `SolaceBinderHeaders.CHUNK_COUNT`                 | `Integer`         | Internal Binder Use Only | Unset unless chunking is active               | The length of the array of chunks.                                                                                                                      |
| `SolaceBinderHeaders.MESSAGE_VERSION`             | `Integer`         | Read     | `1`                                           | A static number set by the publisher to indicate the Spring Cloud Stream Solace message version.                                                         |
| `SolaceBinderHeaders.SERIALIZED_PAYLOAD`          | `Boolean`         | Internal Binder Use Only | Unset                                         | Is `true` if a Solace Spring Cloud Stream binder has serialized the payload before publishing it to a broker. Is undefined otherwise.                       |
| `SolaceBinderHeaders.SERIALIZED_HEADERS`          | `String`          | Internal Binder Use Only | Unset                                         | A JSON String array of header names where each entry indicates that the header's value was serialized by a Solace Spring Cloud Stream binder before publishing it to a broker.  |
| `SolaceBinderHeaders.SERIALIZED_HEADERS_ENCODING` | `String`          | Internal Binder Use Only | `"base64"` when serialized headers are present | The encoding algorithm used to encode the headers indicated by `SolaceBinderHeaders.SERIALIZED_HEADERS`.                           |
| `SolaceBinderHeaders.CONFIRM_CORRELATION`         | `CorrelationData` | Write    | Unset                                         | A CorrelationData instance for messaging confirmations.                                                                                                                         |
| `SolaceBinderHeaders.NULL_PAYLOAD`                | `Boolean`         | Read     | Absent unless inbound payload was null        | Present and true to indicate when the PubSub+ message payload was null.                                                                                                         |
| `SolaceBinderHeaders.TARGET_DESTINATION_TYPE`     | `String`          | Write    | Binding destination type                       | Only applicable when `BinderHeaders.TARGET_DESTINATION` is set. Values: `topic`, `queue`. When absent, the binding's configured destination type is used.                    |

Most `SolaceBinderHeaders` entries are binder metadata or binder directives rather than user properties on the wire. In particular, `CONFIRM_CORRELATION`, `LARGE_MESSAGE_SUPPORT`, and `TARGET_DESTINATION_TYPE` are consumed locally by the binder and are not written to the outgoing SMF message as `solace_scst_*` properties.

## Usage example

```java
@Service
class MyBusinessLogic {
    private final StreamBridge streamBridge;

    MyBusinessLogic(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public void send(String payload, String dynamicDestination, boolean sendToQueue) {
        Message<String> outbound = MessageBuilder
                .withPayload(payload)
            .setHeader(SolaceHeaders.TIME_TO_LIVE, java.time.Duration.ofSeconds(30).toMillis())
            .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                .setHeader(BinderHeaders.TARGET_DESTINATION, dynamicDestination)
                .setHeader(SolaceBinderHeaders.TARGET_DESTINATION_TYPE,
                        sendToQueue ? "queue" : "topic")
                .build();

        streamBridge.send("emitTemperatureSensorDynamic-out-0", outbound);
    }
}
```
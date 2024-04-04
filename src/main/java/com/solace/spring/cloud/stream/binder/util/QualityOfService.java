package com.solace.spring.cloud.stream.binder.util;

/**
 * Quality of Service (QoS) is an agreement between the message sender and receiver that defines the level of delivery guarantee for a specific message.
 */
public enum QualityOfService {
    /**
     * Using topics: Messages may be lost or discarded.
     * This mode improves performance and reduces latency.
     * QOS=0
     */
    AT_MOST_ONCE,
    /**
     * Using a persistent queue: It is guaranteed that the message arrives at least once.
     * This mode persists messages on storage and therefore is slower.
     * QOS=1
     */
    AT_LEAST_ONCE
}

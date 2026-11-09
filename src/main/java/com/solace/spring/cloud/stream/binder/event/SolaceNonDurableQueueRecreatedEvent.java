package com.solace.spring.cloud.stream.binder.event;

/**
 * Published as an application event when the broker has dropped the temporary queue of an anonymous consumer binding
 * while the binder was disconnected, and the queue was created anew, empty, on reconnect. The messages still in the
 * dropped queue are lost, and so is every message published to the binding's subscriptions until the binder
 * subscribed the new queue.
 * <p>
 * The event is published on a thread of its own, never on the thread that dispatches Solace messages, so a listener
 * may take its time. Listen with {@code @EventListener}.
 *
 * @param bindingName the name of the consumer binding whose queue was recreated
 * @param queueName   the name of the recreated temporary queue
 */
public record SolaceNonDurableQueueRecreatedEvent(String bindingName, String queueName) {
}

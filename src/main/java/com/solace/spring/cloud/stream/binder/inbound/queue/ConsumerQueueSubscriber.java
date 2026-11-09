package com.solace.spring.cloud.stream.binder.inbound.queue;

import com.solace.spring.cloud.stream.binder.event.SolaceNonDurableQueueRecreatedEvent;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solacesystems.jcsmp.Endpoint;
import com.solacesystems.jcsmp.Queue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import java.util.function.Consumer;

/**
 * Subscribes the queue of a consumer binding to its topics each time its flow starts or comes back after a
 * reconnect.
 * <p>
 * The broker drops a temporary queue some time after its client disconnected, and JCSMP creates it anew, empty,
 * when the flow rebinds. The recreated queue lacks the subscriptions the old one had, which is how a recreation is
 * told apart from a queue that survived the disconnect: such a queue answers every subscription with
 * {@code SUBSCRIPTION_ALREADY_PRESENT}. A recreation is announced with a {@link SolaceNonDurableQueueRecreatedEvent},
 * published off the calling thread because that thread is the JCSMP dispatcher on a reconnect.
 */
@Slf4j
@RequiredArgsConstructor
public class ConsumerQueueSubscriber implements Consumer<Endpoint> {
    private static final TaskExecutor EVENT_EXECUTOR = new SimpleAsyncTaskExecutor("solace-queue-recreated-");

    private final SolaceEndpointProvisioner provisioningProvider;
    private final SolaceConsumerDestination destination;
    private final ExtendedConsumerProperties<SolaceConsumerProperties> properties;
    private final ApplicationEventPublisher eventPublisher;
    private boolean subscribedBefore;

    @Override
    public synchronized void accept(Endpoint endpoint) {
        if (!(endpoint instanceof Queue queue)) {
            return;
        }
        boolean anySubscriptionMissing = provisioningProvider.addSubscriptionToQueue(
                queue, destination.getBindingDestinationName(), properties.getExtension(), true);
        for (String subscription : destination.getAdditionalSubscriptions()) {
            anySubscriptionMissing |= provisioningProvider.addSubscriptionToQueue(
                    queue, subscription, properties.getExtension(), false);
        }

        if (subscribedBefore && anySubscriptionMissing && destination.isTemporary()) {
            publishRecreation(new SolaceNonDurableQueueRecreatedEvent(properties.getBindingName(), queue.getName()));
        }
        subscribedBefore = true;
    }

    private void publishRecreation(SolaceNonDurableQueueRecreatedEvent event) {
        log.warn("Non-durable queue {} of binding {} was recreated, messages published while it was gone are lost",
                event.queueName(), event.bindingName());
        EVENT_EXECUTOR.execute(() -> {
            try {
                eventPublisher.publishEvent(event);
            } catch (RuntimeException e) {
                log.error("Failed to publish {}", event, e);
            }
        });
    }
}

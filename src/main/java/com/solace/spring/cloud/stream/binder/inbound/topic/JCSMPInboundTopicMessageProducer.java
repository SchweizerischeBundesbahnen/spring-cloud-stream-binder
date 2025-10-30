package com.solace.spring.cloud.stream.binder.inbound.topic;

import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import com.solace.spring.cloud.stream.binder.util.XMLMessageMapper;
import com.solacesystems.jcsmp.BytesXMLMessage;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.context.OrderlyShutdownCapable;
import org.springframework.integration.core.Pausable;
import org.springframework.integration.endpoint.MessageProducerSupport;
import org.springframework.messaging.Message;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Setter
public class JCSMPInboundTopicMessageProducer extends MessageProducerSupport implements OrderlyShutdownCapable, Pausable {
    private final String id = UUID.randomUUID().toString();
    private final SolaceConsumerDestination consumerDestination;
    private final String group;
    private final ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Optional<SolaceMeterAccessor> solaceMeterAccessor;
    private final Optional<TracingProxy> tracingProxy;
    private final ExecutorService executorService;
    private final JCSMPInboundTopicMessageMultiplexer.LivecycleHooks livecycleHooks;
    private final XMLMessageMapper xmlMessageMapper = new XMLMessageMapper();
    private final List<BytesXMLMessage> pauseQueue = new ArrayList<>();
    private final AcknowledgmentCallback noop = status -> {
    };

    public JCSMPInboundTopicMessageProducer(SolaceConsumerDestination consumerDestination,
                                            String group,
                                            ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties,
                                            Optional<SolaceMeterAccessor> solaceMeterAccessor,
                                            Optional<TracingProxy> tracingProxy,
                                            JCSMPInboundTopicMessageMultiplexer.LivecycleHooks livecycleHooks) {
        this.consumerDestination = consumerDestination;
        this.group = group;
        this.consumerProperties = consumerProperties;
        this.solaceMeterAccessor = solaceMeterAccessor;
        this.tracingProxy = tracingProxy;
        this.executorService = Executors.newFixedThreadPool(Math.max(1, consumerProperties.getConcurrency()));
        this.livecycleHooks = livecycleHooks;
    }

    public void onReceive(final BytesXMLMessage msg) {
        if (this.paused.get()) {
            synchronized (this.pauseQueue) {
                pauseQueue.add(msg);
            }
            return;
        }
        executorService.execute(() -> {
            try {
                Message<?> message;
                // since the BytesXMLMessage is not thread safe we can't access it with multiple threads and therefore need a lock to avoid race conditions
                synchronized (msg) {
                    message = xmlMessageMapper.map(msg, noop, consumerProperties.getExtension());
                }
                Consumer<Message<?>> sendToCustomerConsumer = this::sendMessageWithProcessingTimeTracking;
                if (tracingProxy.isPresent() && msg.getProperties() != null && tracingProxy.get().hasTracingHeader(msg.getProperties())) {
                    sendToCustomerConsumer = tracingProxy.get().wrapInTracingContext(msg.getProperties(), sendToCustomerConsumer);
                }
                sendToCustomerConsumer.accept(message);
                solaceMeterAccessor.ifPresent(meterAccessor -> meterAccessor.recordMessage(consumerProperties.getBindingName(), msg));
            } catch (Exception ex) {
                log.error("onReceive", ex);
            }
        });
    }

    private void sendMessageWithProcessingTimeTracking(Message<?> message) {
        if (this.solaceMeterAccessor.isPresent()) {
            long beforeMessageProcessing = System.nanoTime();
            this.sendMessage(message);
            long afterMessageProcessing = System.nanoTime();
            this.solaceMeterAccessor.get().recordMessageProcessingTimeDuration(consumerProperties.getBindingName(), TimeUnit.NANOSECONDS.toMillis((afterMessageProcessing - beforeMessageProcessing)));
        } else {
            this.sendMessage(message);
        }
    }

    public Set<String> getAllTopics() {
        Set<String> topics = new HashSet<>();
        String prefix = "";
        if (!StringUtils.isEmpty(this.group)) {
            if (this.group.contains("/")) {
                log.warn("group contains invalid characters /, it will be replaced with -: {}", this.group);
            }
            prefix = "#share/" + this.group.replaceAll("/", "-") + "/";
        }
        topics.add(prefix + consumerDestination.getBindingDestinationName());
        if (!CollectionUtils.isEmpty(consumerDestination.getAdditionalSubscriptions())) {
            for (String additionalSubscription : consumerDestination.getAdditionalSubscriptions()) {
                topics.add(prefix + additionalSubscription);
            }
        }
        return topics;
    }

    @Override
    protected void doStart() {
        if (isRunning()) {
            log.warn(String.format("Nothing to do. Inbound message channel adapter %s is already running", id));
            return;
        }
        this.livecycleHooks.start(this);
    }

    @Override
    protected void doStop() {
        if (!isRunning()) return;
        this.livecycleHooks.stop(this);
    }

    @Override
    public int beforeShutdown() {
        this.stop();
        return 0;
    }

    @Override
    public int afterShutdown() {
        return 0;
    }

    @Override
    public void pause() {
        log.info(String.format("Pausing inbound adapter %s", id));
        paused.set(true);
    }

    @Override
    public void resume() {
        log.info(String.format("Resuming inbound adapter %s", id));
        paused.set(false);
        executorService.execute(() -> {
            synchronized (this.pauseQueue) {
                Iterator<BytesXMLMessage> iterator = this.pauseQueue.iterator();
                while (iterator.hasNext()) {
                    this.onReceive(iterator.next());
                    iterator.remove();
                }
            }
        });
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }
}

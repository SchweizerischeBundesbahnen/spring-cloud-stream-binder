package com.solace.spring.cloud.stream.binder.inbound.topic;

import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import com.solace.spring.cloud.stream.binder.util.LargeMessageSupport;
import com.solacesystems.jcsmp.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.messaging.MessagingException;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RequiredArgsConstructor
public class JCSMPInboundTopicMessageMultiplexer {
    private final JCSMPSession jcsmpSession;
    private final BeanFactory beanFactory;
    private final Optional<SolaceMeterAccessor> solaceMeterAccessorSupplier;
    private final Optional<TracingProxy> tracingProxy;
    private final List<JCSMPInboundTopicMessageProducer> jcsmpInboundTopicMessageProducers = new ArrayList<>();
    private final AtomicReference<XMLMessageConsumer> msgConsumer = new AtomicReference<>(null);
    private final LargeMessageSupport largeMessageSupport = new LargeMessageSupport();

    private final LivecycleHooks livecycleHooks = new LivecycleHooks() {
        @Override
        public void start(JCSMPInboundTopicMessageProducer producer) {
            log.info("started producer " + producer);
            synchronized (jcsmpInboundTopicMessageProducers) {
                jcsmpInboundTopicMessageProducers.add(producer);
            }
            updateTopics();
            largeMessageSupport.startHousekeeping();
        }

        @Override
        public void stop(JCSMPInboundTopicMessageProducer producer) {
            log.info("stopped producer " + producer);
            synchronized (jcsmpInboundTopicMessageProducers) {
                jcsmpInboundTopicMessageProducers.remove(producer);
            }
            updateTopics();
        }
    };

    private final TopicFilterTree<JCSMPInboundTopicMessageProducer> topicFilterTree = new TopicFilterTree<>();
    private final Set<String> appliedSubscriptions = new HashSet<>();

    private void ensureXMLMessageConsumer() {
        if (msgConsumer.get() != null) {
            return;
        }
        synchronized (msgConsumer) {
            if (msgConsumer.get() != null) {
                return;
            }
            try {
                this.msgConsumer.set(jcsmpSession.getMessageConsumer(new XMLMessageListener() {
                    @Override
                    public void onReceive(final BytesXMLMessage msg) {
                        LargeMessageSupport.MessageContext messageContext = largeMessageSupport.assemble(msg, null);
                        if (messageContext == null) {
                            return;
                        }
                        JCSMPInboundTopicMessageMultiplexer.this.onReceive(messageContext.bytesMessage());
                    }

                    @Override
                    public void onException(final JCSMPException e) {
                        JCSMPInboundTopicMessageMultiplexer.this.onException(e);
                    }
                }));
                this.msgConsumer.get().start();
            } catch (JCSMPException e) {
                String msg = "Failed to get message consumer for topics";
                log.warn(msg, e);
                throw new MessagingException(msg, e);
            }
        }
    }

    private void onReceive(final BytesXMLMessage msg) {
        String topic = msg.getDestination().getName();
        for (JCSMPInboundTopicMessageProducer messageProducer : topicFilterTree.getMatching(topic)) {
            messageProducer.onReceive(msg);
        }
    }

    private void onException(final JCSMPException e) {
        String msg = "Received error while trying to read message from topic";
        if ((e instanceof JCSMPTransportException || e instanceof ClosedFacilityException)) {
            log.debug(msg, e);
        } else {
            log.warn(msg, e);
        }
    }

    private void updateTopics() {
        Set<String> allTopics = new HashSet<>();
        synchronized (jcsmpInboundTopicMessageProducers) {
            topicFilterTree.clear();
            for (var producer : jcsmpInboundTopicMessageProducers) {
                Set<String> producerTopics = producer.getAllTopics();
                allTopics.addAll(producerTopics);
                for (String topic : producerTopics) {
                    topicFilterTree.addTopic(topic, producer);
                }
            }
        }
        Set<String> toRemove = new HashSet<>();
        Set<String> toAdd = new HashSet<>(allTopics);
        appliedSubscriptions.forEach(topicName -> {
            if (!allTopics.contains(topicName)) {
                toRemove.add(topicName);
            }
            toAdd.remove(topicName);
        });
        try {
            for (String topic : toRemove) {
                try {
                    jcsmpSession.removeSubscription(JCSMPFactory.onlyInstance().createTopic(topic));
                    appliedSubscriptions.remove(topic);
                    log.info("remove subscription for topic: " + topic);
                } catch (Exception ex) {
                    log.warn("could not remove subscription, continuing", ex);
                }
            }
            for (String topic : toAdd) {
                jcsmpSession.addSubscription(JCSMPFactory.onlyInstance().createTopic(topic));
                appliedSubscriptions.add(topic);
                log.info("add subscription for topic: " + topic);
            }
        } catch (JCSMPException e) {
            String msg = "Failed to get message consumer for topic consumer";
            log.warn(msg, e);
            throw new MessagingException(msg, e);
        }
    }

    public JCSMPInboundTopicMessageProducer createTopicMessageProducer(ConsumerDestination destination, String group, ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        this.ensureXMLMessageConsumer();
        return new JCSMPInboundTopicMessageProducer((SolaceConsumerDestination) destination, group, properties, beanFactory, this.solaceMeterAccessorSupplier, tracingProxy, livecycleHooks);
    }

    public interface LivecycleHooks {
        void start(JCSMPInboundTopicMessageProducer producer);

        void stop(JCSMPInboundTopicMessageProducer producer);
    }
}

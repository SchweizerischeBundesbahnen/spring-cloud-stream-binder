package com.solace.spring.cloud.stream.binder.inbound;

import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceConsumerDestination;
import com.solacesystems.jcsmp.*;
import lombok.RequiredArgsConstructor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.messaging.MessagingException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@RequiredArgsConstructor
public class JCSMPInboundTopicMessageMultiplexer {
    private static final Log logger = LogFactory.getLog(JCSMPInboundTopicMessageMultiplexer.class);
    private final JCSMPSession jcsmpSession;
    private final Supplier<SolaceMeterAccessor> solaceMeterAccessorSupplier;
    private final List<JCSMPInboundTopicMessageProducer> jcsmpInboundTopicMessageProducers = new ArrayList<>();
    private final AtomicReference<XMLMessageConsumer> msgConsumer = new AtomicReference<>(null);

    private final LivecycleHooks livecycleHooks = new LivecycleHooks() {
        @Override
        public void start(JCSMPInboundTopicMessageProducer producer) {
            logger.info("started producer " + producer);
            synchronized (jcsmpInboundTopicMessageProducers) {
                jcsmpInboundTopicMessageProducers.add(producer);
            }
            updateTopics();
        }

        @Override
        public void stop(JCSMPInboundTopicMessageProducer producer) {
            logger.info("stopped producer " + producer);
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
                        JCSMPInboundTopicMessageMultiplexer.this.onReceive(msg);
                    }

                    @Override
                    public void onException(final JCSMPException e) {
                        JCSMPInboundTopicMessageMultiplexer.this.onException(e);
                    }
                }));
                this.msgConsumer.get().start();
            } catch (JCSMPException e) {
                String msg = "Failed to get message consumer for topics";
                logger.warn(msg, e);
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
            logger.debug(msg, e);
        } else {
            logger.warn(msg, e);
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
                jcsmpSession.removeSubscription(JCSMPFactory.onlyInstance().createTopic(topic));
                appliedSubscriptions.remove(topic);
                logger.info("remove subscription for topic: " + topic);
            }
            for (String topic : toAdd) {
                jcsmpSession.addSubscription(JCSMPFactory.onlyInstance().createTopic(topic));
                appliedSubscriptions.add(topic);
                logger.info("add subscription for topic: " + topic);
            }
        } catch (JCSMPException e) {
            String msg = "Failed to get message consumer for topic consumer";
            logger.warn(msg, e);
            throw new MessagingException(msg, e);
        }
    }

    public JCSMPInboundTopicMessageProducer createTopicMessageProducer(ConsumerDestination destination, String group, ExtendedConsumerProperties<SolaceConsumerProperties> properties) {
        this.ensureXMLMessageConsumer();
        return new JCSMPInboundTopicMessageProducer((SolaceConsumerDestination) destination,group, properties, this.solaceMeterAccessorSupplier.get(), livecycleHooks);
    }

    public interface LivecycleHooks {
        void start(JCSMPInboundTopicMessageProducer producer);

        void stop(JCSMPInboundTopicMessageProducer producer);
    }
}

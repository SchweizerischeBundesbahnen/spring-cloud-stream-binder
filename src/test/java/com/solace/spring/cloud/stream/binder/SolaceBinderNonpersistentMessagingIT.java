package com.solace.spring.cloud.stream.binder;

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.test.junit.extension.SpringCloudStreamExtension;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.spring.cloud.stream.binder.util.QualityOfService;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solacesystems.jcsmp.DeliveryMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for direct messaging with topics and without persistence.
 */
@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class, initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(PubSubPlusExtension.class)
@ExtendWith(SpringCloudStreamExtension.class)
public class SolaceBinderNonpersistentMessagingIT {

    @Test
    public void testSimpleTopicTest(SpringCloudStreamContext context, TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel output = context.createBindableChannel("output", new BindingProperties());
        DirectChannel input = context.createBindableChannel("input", new BindingProperties());

        String topic = "testSimpleTopicTest/direct/messagingSimpleTopicTest";
        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        producerProperties.getExtension().setDeliveryMode(DeliveryMode.NON_PERSISTENT);
        Binding<MessageChannel> producerBinding = binder.bindProducer(topic, output, producerProperties);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setQualityOfService(QualityOfService.AT_MOST_ONCE);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(topic, null, input, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes()).setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE).setHeader(BinderHeaders.TARGET_DESTINATION, topic).build();

        context.binderBindUnbindLatency();

        AtomicReference<Message<?>> result = new AtomicReference<>(null);
        input.subscribe(result::set);


        output.send(message);
        int wait = 10;
        while (wait > 0 && result.get() == null) {
            wait--;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }

        assertThat(result.get()).isNotNull();
        assertThat(result.get().getPayload()).isEqualTo("foo".getBytes());

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testTopicWithGroupTest(SpringCloudStreamContext context, TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel output = context.createBindableChannel("output", new BindingProperties());
        DirectChannel input = context.createBindableChannel("input", new BindingProperties());

        String topic = "testTopicWithGroupTest/direct/messagingSimpleTopicTest";
        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        producerProperties.getExtension().setDeliveryMode(DeliveryMode.NON_PERSISTENT);
        Binding<MessageChannel> producerBinding = binder.bindProducer(topic, output, producerProperties);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.getExtension().setQualityOfService(QualityOfService.AT_MOST_ONCE);
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(topic, "foo", input, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes()).setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE).setHeader(BinderHeaders.TARGET_DESTINATION, topic).build();

        context.binderBindUnbindLatency();

        AtomicReference<Message<?>> result = new AtomicReference<>(null);
        input.subscribe(result::set);


        output.send(message);
        int wait = 10;
        while (wait > 0 && result.get() == null) {
            wait--;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }

        assertThat(result.get()).isNotNull();
        assertThat(result.get().getPayload()).isEqualTo("foo".getBytes());

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testMultipleListenerTest(SpringCloudStreamContext context, TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        String baseTopic = "testMultipleListenerTest/direct/messaging/a";

        Semaphore semaphore = new Semaphore(0);
        List<List<Message<?>>> results = new ArrayList<>();
        List<Binding<MessageChannel>> consumerBindings = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            DirectChannel input = context.createBindableChannel("input" + i, new BindingProperties());
            ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
            consumerProperties.getExtension().setQualityOfService(QualityOfService.AT_MOST_ONCE);
            Binding<MessageChannel> consumerBinding = binder.bindConsumer(baseTopic + i, null, input, consumerProperties);
            consumerBindings.add(consumerBinding);
            ArrayList<Message<?>> messages = new ArrayList<>();
            results.add(messages);
            input.subscribe(m -> {
                messages.add(m);
                semaphore.release();
            });
        }
        context.binderBindUnbindLatency();
        DirectChannel output = context.createBindableChannel("output", new BindingProperties());
        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        producerProperties.getExtension().setDeliveryMode(DeliveryMode.NON_PERSISTENT);
        Binding<MessageChannel> producerBinding = binder.bindProducer(baseTopic, output, producerProperties);
        context.binderBindUnbindLatency();

        for (int i = 0; i < 100; i++) {
            Message<?> message = MessageBuilder.withPayload(("foo" + i).getBytes()).setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE).setHeader(BinderHeaders.TARGET_DESTINATION, baseTopic + i).build();
            output.send(message);
        }

        assertThat(semaphore.tryAcquire(100, 10, TimeUnit.SECONDS)).isTrue();

        assertThat(results.stream().flatMap(Collection::stream)).hasSize(100);
        for (int i = 0; i < 100; i++) {
            List<Message<?>> resultList = results.get(i);
            assertThat(resultList).hasSize(1);
            assertThat(resultList.get(0).getPayload()).isEqualTo(("foo" + i).getBytes());
        }

        producerBinding.unbind();
        consumerBindings.forEach(Binding::unbind);
    }


    @Test
    public void testWildcardListenerTest(SpringCloudStreamContext context, TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        String baseTopic = "testWildcardListenerTest/direct/messaging/a";

        Semaphore semaphore = new Semaphore(0);
        List<List<Message<?>>> results = new ArrayList<>();
        List<Binding<MessageChannel>> consumerBindings = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            DirectChannel input = context.createBindableChannel("input" + i, new BindingProperties());
            ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
            consumerProperties.getExtension().setQualityOfService(QualityOfService.AT_MOST_ONCE);
            String listenerTopic = baseTopic + i;
            if (i % 2 == 0) {
                listenerTopic = listenerTopic.replaceAll("direct", "*");
            }
            if (i % 3 == 0) {
                listenerTopic = listenerTopic.replaceAll("messaging", "*");
            }
            if (i % 75 == 0) {
                listenerTopic = listenerTopic.replaceAll("/a.*", "/>");
            }
            Binding<MessageChannel> consumerBinding = binder.bindConsumer(listenerTopic, null, input, consumerProperties);
            consumerBindings.add(consumerBinding);
            ArrayList<Message<?>> messages = new ArrayList<>();
            results.add(messages);
            input.subscribe(m -> {
                messages.add(m);
                semaphore.release();
            });
        }
        context.binderBindUnbindLatency();
        DirectChannel output = context.createBindableChannel("output", new BindingProperties());
        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = context.createProducerProperties(testInfo);
        producerProperties.getExtension().setDeliveryMode(DeliveryMode.NON_PERSISTENT);
        Binding<MessageChannel> producerBinding = binder.bindProducer(baseTopic, output, producerProperties);
        context.binderBindUnbindLatency();

        for (int i = 0; i < 1000; i++) {
            Message<?> message = MessageBuilder
                    .withPayload(("foo" + (i % 100)).getBytes())
                    .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                    .setHeader(BinderHeaders.TARGET_DESTINATION, baseTopic + (i % 100))
                    .build();
            output.send(message);
        }
        assertThat(semaphore.tryAcquire(3000 - 20, 30, TimeUnit.SECONDS)).isTrue();

        assertThat(results.stream().flatMap(Collection::stream)).hasSize(3000 - 20);
        for (int i = 0; i < 100; i++) {
            List<Message<?>> resultList = results.get(i);
            if (i % 75 == 0) {
                assertThat(resultList).hasSize(1000);
            } else {
                assertThat(resultList).hasSize(10);
            }
        }

        producerBinding.unbind();
        consumerBindings.forEach(Binding::unbind);
    }
}

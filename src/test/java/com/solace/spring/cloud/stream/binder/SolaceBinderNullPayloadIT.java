package com.solace.spring.cloud.stream.binder;

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.test.junit.extension.SpringCloudStreamExtension;
import com.solace.spring.cloud.stream.binder.test.junit.param.provider.JCSMPMessageTypeArgumentsProvider;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SimpleJCSMPEventHandler;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solacesystems.jcsmp.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.ObjectAssert;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junitpioneer.jupiter.cartesian.CartesianArgumentsSource;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.MessageChannel;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.solace.spring.cloud.stream.binder.test.util.SolaceSpringCloudStreamAssertions.hasNestedHeader;
import static com.solace.spring.cloud.stream.binder.test.util.SolaceSpringCloudStreamAssertions.noNestedHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@Slf4j
@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(PubSubPlusExtension.class)
@ExtendWith(SpringCloudStreamExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
public class SolaceBinderNullPayloadIT {

    @CartesianTest(name = "[{index}] messageType={0}")
    public void testNullPayload(
            @CartesianArgumentsSource(JCSMPMessageTypeArgumentsProvider.class) Class<? extends Message> messageType,
            JCSMPSession jcsmpSession,
            SpringCloudStreamContext context,
            SoftAssertions softly) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String dest = RandomStringUtils.randomAlphanumeric(10);
        String group = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(dest, group, moduleInputChannel, consumerProperties);

        final CountDownLatch latch = new CountDownLatch(1);
        moduleInputChannel.subscribe(msg -> {
            log.info("Received message {}", StaticMessageHeaderAccessor.getId(msg));

            softly.assertThat(msg).satisfies(hasNestedHeader(SolaceBinderHeaders.NULL_PAYLOAD, Boolean.class,
                    nullPayload -> assertThat(nullPayload).isTrue()));

            AbstractListAssert<?, List<?>, Object, ObjectAssert<Object>> payloadsAssert =
                    softly.assertThat(Collections.singletonList(msg.getPayload()));

            payloadsAssert.allSatisfy(payload -> {
                if (messageType == BytesMessage.class) {
                    assertThat(payload instanceof byte[]).isTrue();
                    assertThat(((byte[]) payload).length).isEqualTo(0);
                } else if (messageType == TextMessage.class) {
                    assertThat(payload instanceof String).isTrue();
                    assertThat(payload).isEqualTo("");
                } else if (messageType == MapMessage.class) {
                    assertThat(payload instanceof SDTMap).isTrue();
                    assertThat(((SDTMap) payload).isEmpty()).isTrue();
                } else if (messageType == StreamMessage.class) {
                    assertThat(payload instanceof SDTStream).isTrue();
                    assertThat(((SDTStream) payload).hasRemaining()).isFalse();
                } else if (messageType == XMLContentMessage.class) {
                    assertThat(payload instanceof String).isTrue();
                    assertThat((String) payload).isEqualTo("");
                } else {
                    fail("received unexpected message type %s", messageType);
                }
            });

            latch.countDown();
        });

        XMLMessageProducer producer = jcsmpSession.getMessageProducer(new SimpleJCSMPEventHandler());

        for (int i = 0; i < (1); i++) {
            //Not setting payload
            producer.send(JCSMPFactory.onlyInstance().createMessage(messageType),
                    JCSMPFactory.onlyInstance().createTopic(dest));
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume

        consumerBinding.unbind();
        producer.close();
    }

    @CartesianTest(name = "[{index}] messageType={0}")
    public void testEmptyPayload(
            @CartesianArgumentsSource(JCSMPMessageTypeArgumentsProvider.class) Class<? extends Message> messageType,
            JCSMPSession jcsmpSession,
            SpringCloudStreamContext context,
            SoftAssertions softly) throws Exception {
        SolaceTestBinder binder = context.getBinder();

        DirectChannel moduleInputChannel = context.createBindableChannel("input", new BindingProperties());

        String dest = RandomStringUtils.randomAlphanumeric(10);
        String group = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(dest, group, moduleInputChannel, consumerProperties);

        final CountDownLatch latch = new CountDownLatch(1);
        moduleInputChannel.subscribe(msg -> {
            log.info("Received message {}", StaticMessageHeaderAccessor.getId(msg));

            if (messageType == BytesMessage.class || messageType == XMLContentMessage.class) {
                //LIMITATION: BytesMessage doesn't support EMPTY payloads since publishing byte[0] is received as a null payload
                //LIMITATION: XMLContentMessage doesn't support EMPTY payloads since publishing "" is received as a null payload
                softly.assertThat(msg).satisfies(hasNestedHeader(SolaceBinderHeaders.NULL_PAYLOAD, Boolean.class,
                        nullPayload -> assertThat(nullPayload).isTrue()));
            } else {
                softly.assertThat(msg).satisfies(noNestedHeader(SolaceBinderHeaders.NULL_PAYLOAD));
            }

            AbstractListAssert<?, List<?>, Object, ObjectAssert<Object>> payloadsAssert =
                    softly.assertThat(Collections.singletonList(msg.getPayload()));

            payloadsAssert.allSatisfy(payload -> {
                if (messageType == BytesMessage.class) {
                    assertThat(payload instanceof byte[]).isTrue();
                    assertThat(((byte[]) payload).length).isEqualTo(0);
                } else if (messageType == TextMessage.class) {
                    assertThat(payload instanceof String).isTrue();
                    assertThat(payload).isEqualTo("");
                } else if (messageType == MapMessage.class) {
                    assertThat(payload instanceof SDTMap).isTrue();
                    assertThat(((SDTMap) payload).isEmpty()).isTrue();
                } else if (messageType == StreamMessage.class) {
                    assertThat(payload instanceof SDTStream).isTrue();
                    assertThat(((SDTStream) payload).hasRemaining()).isFalse();
                } else if (messageType == XMLContentMessage.class) {
                    assertThat(payload instanceof String).isTrue();
                    assertThat(payload).isEqualTo("");
                } else {
                    fail("received unexpected message type %s", messageType);
                }
            });
            latch.countDown();
        });

        XMLMessageProducer producer = jcsmpSession.getMessageProducer(new SimpleJCSMPEventHandler());

        for (int i = 0; i < (1); i++) {
            //Not setting payload
            XMLMessage solMsg = JCSMPFactory.onlyInstance().createMessage(messageType);
            //Setting empty payload
            if (messageType == BytesMessage.class) {
                ((BytesMessage) solMsg).setData(new byte[0]);
            } else if (messageType == TextMessage.class) {
                ((TextMessage) solMsg).setText("");
            } else if (messageType == MapMessage.class) {
                ((MapMessage) solMsg).setMap(JCSMPFactory.onlyInstance().createMap());
            } else if (messageType == StreamMessage.class) {
                ((StreamMessage) solMsg).setStream(JCSMPFactory.onlyInstance().createStream());
            } else if (messageType == XMLContentMessage.class) {
                ((XMLContentMessage) solMsg).setXMLContent("");
            }

            producer.send(solMsg, JCSMPFactory.onlyInstance().createTopic(dest));
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume

        consumerBinding.unbind();
        producer.close();
    }

}

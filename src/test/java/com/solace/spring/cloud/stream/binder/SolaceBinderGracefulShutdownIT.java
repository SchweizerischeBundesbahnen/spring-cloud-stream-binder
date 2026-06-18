package com.solace.spring.cloud.stream.binder;

import community.solace.spring.boot.starter.solaceclientconfig.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueueMsg;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * Integration test for graceful shutdown of an inbound Solace consumer binding.
 *
 * <p>Reproduces the bug fixed on the 7.x line: when a binding was stopped while messages were
 * already pulled into the binder's internal worker queue, {@code doStop()} closed the JCSMP
 * {@code FlowReceiver} <em>before</em> those messages were processed and ACKed. The ACK then ran
 * against a closed consumer, threw {@code IllegalStateException} on the worker thread (caught and
 * logged by the binder), and the messages were left unsettled and redelivered.
 *
 * <p>Graceful shutdown is opt-in: it only kicks in when {@code drainTimeoutMs > 0} is configured on
 * the consumer binding. This test sets it explicitly; with the default of 0 the binder closes the
 * flow immediately, exactly as before this feature.
 *
 * <p>The discriminating signal is therefore NOT "did {@code stop()} throw" (it never did) but
 * "were all in-flight messages actually settled" — verified here via the SEMP monitor API: after a
 * graceful stop the consumer queue must hold zero spooled messages.
 *
 * <p>Run with: {@code mvn -P it_tests -Dit.test=SolaceBinderGracefulShutdownIT verify} (requires Docker).
 */
@Slf4j
@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class, initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(PubSubPlusExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
@Isolated
@DirtiesContext
public class SolaceBinderGracefulShutdownIT extends SpringCloudStreamContext {

    private static final int MESSAGE_COUNT = 10;
    private static final long HANDLER_PROCESSING_MS = 300L;
    private static final long DRAIN_TIMEOUT_MS = 30_000L;

    @BeforeEach
    void setUp(JCSMPSession jcsmpSession, SempV2Api sempV2Api) {
        setJcsmpSession(jcsmpSession);
        setSempV2Api(sempV2Api);
    }

    @AfterEach
    void tearDown() {
        super.cleanup();
        close();
    }

    // NOT YET SUPPORTED ---------------------------------
    @Override
    @Test
    @Execution(ExecutionMode.SAME_THREAD)
    @Disabled("Partitioning is not supported")
    public void testPartitionedModuleSpEL(TestInfo testInfo) throws Exception {
        super.testPartitionedModuleSpEL(testInfo);
    }
    // ---------------------------------------------------

    @Test
    public void testGracefulShutdownSettlesInFlightMessages(JCSMPSession jcsmpSession,
                                                            SempV2Api sempV2Api,
                                                            SoftAssertions softly,
                                                            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination = randomAlphanumeric();
        String consumerGroup = randomAlphanumeric();

        Binding<MessageChannel> producerBinding =
                binder.bindProducer(destination, moduleOutputChannel, createProducerProperties(testInfo));

        // Single worker so that, once the first message is processing, the remaining messages pile up
        // inside the binder's internal queue — exactly the backlog that graceful shutdown must drain.
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        consumerProperties.setConcurrency(1);
        // Opt in to graceful shutdown (default is 0 = disabled / immediate close).
        consumerProperties.getExtension().setDrainTimeoutMs(DRAIN_TIMEOUT_MS);
        var consumerBinding = consumerInfrastructureUtil.createBinding(
                binder, destination, consumerGroup, moduleInputChannel, consumerProperties);

        binderBindUnbindLatency();

        AtomicInteger receivedCount = new AtomicInteger(0);
        AtomicReference<Throwable> handlerError = new AtomicReference<>();
        CountDownLatch firstMessageLatch = new CountDownLatch(1);

        // Default (auto) ACK: the binder ACKs after the handler returns. That post-handler ACK is the
        // operation that fails on a closed flow in the unfixed binder.
        moduleInputChannel.subscribe(msg -> {
            try {
                receivedCount.incrementAndGet();
                firstMessageLatch.countDown();
                Thread.sleep(HANDLER_PROCESSING_MS); // simulate processing -> keeps a backlog in flight
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                handlerError.set(t);
            }
        });

        String queueName = binder.getConsumerQueueName(consumerBinding);
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);

        try {
            // Send a fixed batch; the broker pushes the whole batch into the client window quickly.
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                Message<?> message = MessageBuilder
                        .withPayload(("graceful-shutdown-" + i).getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build();
                moduleOutputChannel.send(message);
            }

            // Wait until the consumer is actively processing, then give the broker a moment to deliver
            // the rest of the batch into the binder's internal queue.
            softly.assertThat(firstMessageLatch.await(30, TimeUnit.SECONDS))
                    .as("consumer should start processing the batch").isTrue();
            Thread.sleep(300);

            log.info("[DEBUG_LOG] Stopping binding gracefully. Processed so far: {}", receivedCount.get());
            Exception stopException = null;
            try {
                consumerBinding.stop(); // -> doStop(): stop() -> drain() -> close()
            } catch (Exception e) {
                stopException = e;
                log.error("[DEBUG_LOG] Exception while stopping binding", e);
            }

            // Give the broker time to reflect settlements / reclaim any unacked messages.
            Thread.sleep(TimeUnit.SECONDS.toMillis(3));

            List<MonitorMsgVpnQueueMsg> spooled = sempV2Api.monitor()
                    .getMsgVpnQueueMsgs(vpnName, queueName, MESSAGE_COUNT * 2, null, null, null)
                    .getData();

            softly.assertThat(stopException)
                    .as("graceful stop must not throw").isNull();
            softly.assertThat(handlerError.get())
                    .as("message handler must not see an exception").isNull();
            softly.assertThat(receivedCount.get())
                    .as("all messages must be processed during graceful drain").isEqualTo(MESSAGE_COUNT);
            softly.assertThat(spooled)
                    .as("no messages may remain unsettled on the queue after graceful shutdown (would indicate ACK-after-close)")
                    .isEmpty();
        } finally {
            try {
                consumerBinding.unbind();
            } catch (Exception e) {
                log.warn("[DEBUG_LOG] Exception during consumer binding cleanup", e);
            }
            try {
                producerBinding.unbind();
            } catch (Exception e) {
                log.warn("[DEBUG_LOG] Exception during producer binding cleanup", e);
            }
        }
    }

    private static String randomAlphanumeric() {
        return RandomGenerator.getDefault().ints(10, 0, 36)
                .mapToObj(i -> Character.toString("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".charAt(i)))
                .collect(Collectors.joining());
    }
}

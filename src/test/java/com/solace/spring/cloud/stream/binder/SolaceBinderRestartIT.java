package com.solace.spring.cloud.stream.binder;

import community.solace.spring.boot.starter.solaceclientconfig.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.test.spring.ConsumerInfrastructureUtil;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.test.integration.junit.jupiter.extension.ExecutorServiceExtension;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solacesystems.jcsmp.JCSMPSession;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.Mockito;
import org.springframework.beans.factory.BeanFactory;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for testing Solace binder restart functionality.
 * Tests starting bindings without autostart, programmatic start/stop/restart,
 * and message sending/receiving before and after restart.
 */
@Slf4j
@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class, initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(ExecutorServiceExtension.class)
@ExtendWith(PubSubPlusExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
@Isolated
@DirtiesContext
public class SolaceBinderRestartIT extends SpringCloudStreamContext {
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
    public void testBindingRestartWithMessaging(SoftAssertions softly, TestInfo testInfo) throws Exception {

        log.info("[DEBUG_LOG] Starting testBindingRestartWithMessaging");

        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        // Create channels
        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination = getRandomAlphanumeric();
        String consumerGroup = getRandomAlphanumeric();

        // Create producer binding (this will auto-start)
        Binding<MessageChannel> producerBinding = binder.bindProducer(destination, moduleOutputChannel, createProducerProperties(testInfo));

        // Create consumer binding WITHOUT autostart
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        consumerProperties.setAutoStartup(false);
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination, consumerGroup, moduleInputChannel, consumerProperties);

        log.info("[DEBUG_LOG] Created bindings - producer auto-started, consumer not auto-started");

        // Verify the consumer is not running initially
        assertThat(consumerBinding.isRunning()).isFalse();
        assertThat(consumerBinding.isPaused()).isFalse();

        binderBindUnbindLatency();

        // Step 1: Start the consumer binding programmatically
        log.info("[DEBUG_LOG] Starting consumer binding programmatically");
        consumerBinding.start();
        assertThat(consumerBinding.isRunning()).isTrue();

        // Wait for binding to be fully started
        Thread.sleep(2000);
        log.info("[DEBUG_LOG] Consumer binding started, isRunning: {}, isPaused: {}", consumerBinding.isRunning(), consumerBinding.isPaused());

        // Step 2: Test message flow BEFORE stopping the binding
        log.info("[DEBUG_LOG] Testing message flow before stopping binding");
        testMessageFlow(moduleOutputChannel, moduleInputChannel, consumerInfrastructureUtil, softly, "before-stop");

        // Step 3: Stop the consumer binding programmatically AFTER asserting message flow works
        log.info("[DEBUG_LOG] Stopping consumer binding programmatically after message flow test");
        consumerBinding.stop();
        assertThat(consumerBinding.isRunning()).isFalse();
        log.info("[DEBUG_LOG] Consumer binding stopped, isRunning: {}", consumerBinding.isRunning());

        // Step 4: Start the SAME consumer binding again (not creating a new one)
        log.info("[DEBUG_LOG] Starting the same consumer binding again");
        consumerBinding.start();
        assertThat(consumerBinding.isRunning()).isTrue();

        // Wait for binding to be fully started
        Thread.sleep(3000);
        log.info("[DEBUG_LOG] Same consumer binding restarted, isRunning: {}, isPaused: {}", consumerBinding.isRunning(), consumerBinding.isPaused());

        // Step 5: Test message flow AFTER restarting the same binding
        log.info("[DEBUG_LOG] Testing message flow after restarting the same binding");
        testMessageFlow(moduleOutputChannel, moduleInputChannel, consumerInfrastructureUtil, softly, "after-restart");

        // Cleanup
        log.info("[DEBUG_LOG] Cleaning up bindings");
        consumerBinding.unbind();
        producerBinding.unbind();

        log.info("[DEBUG_LOG] Test completed successfully");
    }

    @Test
    public void testStopBindingWhileReceivingMessages(SoftAssertions softly, TestInfo testInfo) throws Exception {
        log.info("[DEBUG_LOG] Starting testStopBindingWhileReceivingMessages");

        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        // Create channels
        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination = getRandomAlphanumeric();
        String consumerGroup = getRandomAlphanumeric();

        // Create producer binding
        Binding<MessageChannel> producerBinding = binder.bindProducer(destination, moduleOutputChannel, createProducerProperties(testInfo));

        // Create consumer binding
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination, consumerGroup, moduleInputChannel, consumerProperties);

        binderBindUnbindLatency();

        // Set up message reception tracking
        AtomicInteger receivedCount = new AtomicInteger(0);
        AtomicBoolean stopSending = new AtomicBoolean(false);
        AtomicReference<Exception> messageHandlerException = new AtomicReference<>();
        CountDownLatch messageHandlerLatch = new CountDownLatch(1);

        // Subscribe to messages with a handler that processes them slowly
        moduleInputChannel.subscribe(msg -> {
            try {
                int count = receivedCount.incrementAndGet();
                log.info("[DEBUG_LOG] Received message #{}: {}", count, new String((byte[]) msg.getPayload()));

                // Simulate slow message processing
                Thread.sleep(100);

                // Signal that we've started receiving messages
                if (count == 1) {
                    messageHandlerLatch.countDown();
                }
            } catch (Exception e) {
                log.error("[DEBUG_LOG] Error in message handler", e);
                messageHandlerException.set(e);
            }
        });

        // Start sending messages in a background thread
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> senderFuture = executor.submit(() -> {
            int messageCount = 0;
            while (!stopSending.get()) {
                try {
                    Message<?> message = MessageBuilder
                            .withPayload(("continuous-message-" + messageCount++).getBytes())
                            .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                            .build();
                    moduleOutputChannel.send(message);
                    log.info("[DEBUG_LOG] Sent message #{}", messageCount);
                    Thread.sleep(50); // Send messages every 50ms
                } catch (Exception e) {
                    if (!stopSending.get()) {
                        log.error("[DEBUG_LOG] Error sending message", e);
                    }
                    break;
                }
            }
            log.info("[DEBUG_LOG] Stopped sending messages after {} messages", messageCount);
        });

        try {
            // Wait for at least one message to be received
            boolean receivedFirstMessage = messageHandlerLatch.await(10, TimeUnit.SECONDS);
            softly.assertThat(receivedFirstMessage).as("Should receive at least one message").isTrue();

            // Let messages flow for a bit
            Thread.sleep(500);

            // Stop the binding while messages are being actively received
            log.info("[DEBUG_LOG] Stopping binding while messages are being received. Received so far: {}", receivedCount.get());

            // This is the critical test - stopping while receiving should not cause errors
            Exception stopException = null;
            try {
                consumerBinding.stop();
                log.info("[DEBUG_LOG] Binding stopped successfully");
            } catch (Exception e) {
                stopException = e;
                log.error("[DEBUG_LOG] Exception occurred while stopping binding", e);
            }

            // Stop sending messages
            stopSending.set(true);
            senderFuture.get(5, TimeUnit.SECONDS);

            // Verify no exceptions occurred during stop
            softly.assertThat(stopException).as("No exception should occur when stopping binding while receiving messages").isNull();
            softly.assertThat(messageHandlerException.get()).as("No exception should occur in message handler").isNull();
            softly.assertThat(receivedCount.get()).as("Should have received some messages").isGreaterThan(0);

            log.info("[DEBUG_LOG] Test completed. Total messages received: {}", receivedCount.get());

        } finally {
            stopSending.set(true);
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            // Cleanup
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


    @Test
    public void testMultipleStartStopCycles(SoftAssertions softly, TestInfo testInfo) throws Exception {
        log.info("[DEBUG_LOG] Starting testMultipleStartStopCycles");

        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        // Create channels
        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination = getRandomAlphanumeric();
        String consumerGroup = getRandomAlphanumeric();

        // Create producer binding (this will auto-start)
        Binding<MessageChannel> producerBinding = binder.bindProducer(destination, moduleOutputChannel, createProducerProperties(testInfo));

        // Create consumer binding WITHOUT autostart
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        consumerProperties.setAutoStartup(false);
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination, consumerGroup, moduleInputChannel, consumerProperties);

        log.info("[DEBUG_LOG] Created bindings - producer auto-started, consumer not auto-started");

        // Verify the consumer is not running initially
        assertThat(consumerBinding.isRunning()).isFalse();
        assertThat(consumerBinding.isPaused()).isFalse();

        binderBindUnbindLatency();

        try {
            // Perform multiple start/stop cycles
            int numberOfCycles = 5;
            for (int cycle = 1; cycle <= numberOfCycles; cycle++) {
                log.info("[DEBUG_LOG] Starting cycle #{}", cycle);

                // Start the consumer binding
                log.info("[DEBUG_LOG] Cycle #{}: Starting consumer binding", cycle);
                consumerBinding.start();
                assertThat(consumerBinding.isRunning()).as("Binding should be running after start in cycle " + cycle).isTrue();
                assertThat(consumerBinding.isPaused()).as("Binding should not be paused after start in cycle " + cycle).isFalse();

                // Wait for binding to be fully started
                Thread.sleep(1000);
                log.info("[DEBUG_LOG] Cycle #{}: Consumer binding started, isRunning: {}, isPaused: {}",
                        cycle, consumerBinding.isRunning(), consumerBinding.isPaused());

                // Test message flow to verify the binding is working correctly
                log.info("[DEBUG_LOG] Cycle #{}: Testing message flow", cycle);
                testMessageFlow(moduleOutputChannel, moduleInputChannel, consumerInfrastructureUtil, softly, "cycle-" + cycle);

                // Stop the consumer binding
                log.info("[DEBUG_LOG] Cycle #{}: Stopping consumer binding", cycle);
                consumerBinding.stop();
                assertThat(consumerBinding.isRunning()).as("Binding should not be running after stop in cycle " + cycle).isFalse();

                // Wait a bit between cycles to ensure clean state
                Thread.sleep(500);
                log.info("[DEBUG_LOG] Cycle #{}: Consumer binding stopped, isRunning: {}", cycle, consumerBinding.isRunning());
            }

            // Final verification: start one more time and test message flow
            log.info("[DEBUG_LOG] Final verification: Starting binding after {} cycles", numberOfCycles);
            consumerBinding.start();
            assertThat(consumerBinding.isRunning()).as("Binding should be running after final start").isTrue();

            Thread.sleep(1000);
            log.info("[DEBUG_LOG] Final verification: Testing message flow after {} cycles", numberOfCycles);
            testMessageFlow(moduleOutputChannel, moduleInputChannel, consumerInfrastructureUtil, softly, "final-verification");

            log.info("[DEBUG_LOG] Multiple start/stop cycles test completed successfully");

        } finally {
            // Cleanup
            log.info("[DEBUG_LOG] Cleaning up bindings");
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

    @Test
    public void testStopBindingDuringMessageProcessing(SoftAssertions softly, TestInfo testInfo) throws Exception {
        log.info("[DEBUG_LOG] Starting testStopBindingDuringMessageProcessing");

        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        // Create channels
        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination = getRandomAlphanumeric();
        String consumerGroup = getRandomAlphanumeric();

        // Create producer binding
        Binding<MessageChannel> producerBinding = binder.bindProducer(destination, moduleOutputChannel, createProducerProperties(testInfo));

        // Create consumer binding with autostart enabled
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination, consumerGroup, moduleInputChannel, consumerProperties);

        binderBindUnbindLatency();

        // Set up message processing tracking
        AtomicInteger receivedCount = new AtomicInteger(0);
        AtomicInteger processingCount = new AtomicInteger(0);
        AtomicReference<Exception> messageHandlerException = new AtomicReference<>();
        CountDownLatch processingStartedLatch = new CountDownLatch(1);
        AtomicBoolean stopProcessing = new AtomicBoolean(false);

        // Subscribe to messages with slow processing
        moduleInputChannel.subscribe(msg -> {
            try {
                int received = receivedCount.incrementAndGet();
                log.info("[DEBUG_LOG] Received message #{}: {}", received, new String((byte[]) msg.getPayload()));

                // Signal that processing has started
                if (received == 1) {
                    processingStartedLatch.countDown();
                }

                // Simulate slow message processing
                int processing = processingCount.incrementAndGet();
                log.info("[DEBUG_LOG] Processing message #{}", processing);

                // Process for a longer time to ensure we can stop during processing
                for (int i = 0; i < 10 && !stopProcessing.get(); i++) {
                    Thread.sleep(100);
                }

                log.info("[DEBUG_LOG] Finished processing message #{}", processing);

            } catch (Exception e) {
                log.error("[DEBUG_LOG] Error processing message", e);
                messageHandlerException.set(e);
            }
        });

        // Send a batch of messages
        int numMessages = 5;
        List<Message<?>> messages = IntStream.range(0, numMessages)
                .mapToObj(i -> MessageBuilder
                        .withPayload(("processing-test-message-" + i).getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        log.info("[DEBUG_LOG] Sending {} messages", numMessages);
        messages.forEach(moduleOutputChannel::send);

        try {
            // Wait for processing to start
            boolean processingStarted = processingStartedLatch.await(10, TimeUnit.SECONDS);
            softly.assertThat(processingStarted).as("Message processing should start").isTrue();

            // Let some messages be processed
            Thread.sleep(500);

            // Stop the binding while messages are being processed
            log.info("[DEBUG_LOG] Stopping binding while processing messages. Received: {}, Processing: {}",
                    receivedCount.get(), processingCount.get());

            // Signal to stop processing to avoid long waits
            stopProcessing.set(true);

            Exception stopException = null;
            try {
                consumerBinding.stop();
                log.info("[DEBUG_LOG] Binding stopped successfully");
            } catch (Exception e) {
                stopException = e;
                log.error("[DEBUG_LOG] Exception occurred while stopping binding", e);
            }

            // Verify no exceptions occurred
            softly.assertThat(stopException).as("No exception should occur when stopping binding during message processing").isNull();
            softly.assertThat(messageHandlerException.get()).as("No exception should occur in message handler").isNull();
            softly.assertThat(receivedCount.get()).as("Should have received some messages").isGreaterThan(0);

            log.info("[DEBUG_LOG] Test completed. Total messages received: {}, processed: {}",
                    receivedCount.get(), processingCount.get());

        } finally {
            stopProcessing.set(true);

            // Cleanup
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

    private static String getRandomAlphanumeric() {
        return RandomGenerator.getDefault().ints(10, 0, 36).mapToObj(i -> Character.toString("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".charAt(i))).collect(Collectors.joining());
    }

    /**
     * Helper method to test message flow
     */
    private void testMessageFlow(DirectChannel moduleOutputChannel, DirectChannel moduleInputChannel, ConsumerInfrastructureUtil<DirectChannel> consumerInfrastructureUtil, SoftAssertions softly, String phase) throws InterruptedException {

        int numMessages = 3;
        List<Message<?>> messages = IntStream.range(0, numMessages).mapToObj(i -> MessageBuilder.withPayload(("test-message-" + phase + "-" + i).getBytes()).setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE).build()).collect(Collectors.toList());

        log.info("[DEBUG_LOG] Sending {} messages during phase: {}", numMessages, phase);

        AtomicInteger receivedCount = new AtomicInteger(0);

        // Use the proper sendAndSubscribe method like in the original tests
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, numMessages, () -> messages.forEach(moduleOutputChannel::send), msg -> {
            log.info("[DEBUG_LOG] Received message during {}: {}", phase, new String((byte[]) msg.getPayload()));
            receivedCount.incrementAndGet();
            // Verify message content matches what we sent
            String payload = new String((byte[]) msg.getPayload());
            softly.assertThat(payload).startsWith("test-message-" + phase + "-");
        });

        softly.assertThat(receivedCount.get()).as("Received count should match sent count during phase: " + phase).isEqualTo(numMessages);

        log.info("[DEBUG_LOG] Message flow test completed for phase: {}", phase);
    }
}

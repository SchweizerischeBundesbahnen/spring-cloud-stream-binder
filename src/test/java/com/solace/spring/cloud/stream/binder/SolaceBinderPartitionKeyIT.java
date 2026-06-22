package com.solace.spring.cloud.stream.binder;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solacesystems.jcsmp.JCSMPSession;
import lombok.extern.slf4j.Slf4j;
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
import community.solace.spring.boot.starter.solaceclientconfig.SolaceJavaAutoConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for STTRS-3135 ("Partition-Key als Message Header").
 *
 * <p>Covers both variants:</p>
 * <ol>
 *   <li><b>Partition key as a message header</b> — when a message is published with a Solace
 *       queue-partition-key, the consumed Spring {@link Message} exposes it under
 *       {@link SolaceBinderHeaders#PARTITION_KEY} so the application can read it.</li>
 *   <li><b>Partition-aware consumption</b> — with consumer {@code partitionAware = true} and
 *       {@code concurrency > 1}, all messages sharing a partition key are processed sequentially by the
 *       same worker thread (per-partition ordering preserved), while different partition keys are
 *       processed in parallel.</li>
 * </ol>
 *
 * <p>Run with: {@code mvn -P it_tests -Dit.test=SolaceBinderPartitionKeyIT verify} (requires Docker).</p>
 */
@Slf4j
@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class, initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(PubSubPlusExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
@Isolated
@DirtiesContext
public class SolaceBinderPartitionKeyIT extends SpringCloudStreamContext {

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

    /**
     * Variant 1: the partition key of a consumed message is exposed under
     * {@link SolaceBinderHeaders#PARTITION_KEY}.
     */
    @Test
    public void testPartitionKeyIsExposedAsHeaderOnConsumedMessage(TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination = randomAlphanumeric();
        String consumerGroup = randomAlphanumeric();
        String partitionKey = "orders-" + randomAlphanumeric();

        Binding<MessageChannel> producerBinding =
                binder.bindProducer(destination, moduleOutputChannel, createProducerProperties(testInfo));
        var consumerBinding = consumerInfrastructureUtil.createBinding(
                binder, destination, consumerGroup, moduleInputChannel, createConsumerProperties());
        binderBindUnbindLatency();

        AtomicReference<Message<?>> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        moduleInputChannel.subscribe(msg -> {
            received.set(msg);
            latch.countDown();
        });

        try {
            moduleOutputChannel.send(MessageBuilder
                    .withPayload("hello".getBytes(StandardCharsets.UTF_8))
                    .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                    .setHeader(SolaceBinderHeaders.PARTITION_KEY, partitionKey)
                    .build());

            assertThat(latch.await(1, TimeUnit.MINUTES)).as("message should be received").isTrue();
            assertThat(received.get()).isNotNull();
            assertThat(received.get().getHeaders().get(SolaceBinderHeaders.PARTITION_KEY))
                    .as("consumed message must expose the partition key header")
                    .isEqualTo(partitionKey);
        } finally {
            consumerBinding.unbind();
            producerBinding.unbind();
        }
    }

    /**
     * Variant 2: with {@code partitionAware = true} and {@code concurrency > 1}, messages of the same
     * partition key are handled by a single worker thread and in receive order, while different partition
     * keys are spread across threads.
     */
    @Test
    public void testPartitionAwareConsumerPreservesPerPartitionOrder(TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination = randomAlphanumeric();
        String consumerGroup = randomAlphanumeric();

        int concurrency = 4;
        int keyCount = 6;
        int perKey = 30;
        int total = keyCount * perKey;

        Binding<MessageChannel> producerBinding =
                binder.bindProducer(destination, moduleOutputChannel, createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        consumerProperties.setConcurrency(concurrency);
        consumerProperties.getExtension().setPartitionAware(true);
        var consumerBinding = consumerInfrastructureUtil.createBinding(
                binder, destination, consumerGroup, moduleInputChannel, consumerProperties);
        binderBindUnbindLatency();

        Map<String, Set<String>> threadsByKey = new ConcurrentHashMap<>();
        Map<String, List<Integer>> seqByKey = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(total);

        moduleInputChannel.subscribe(msg -> {
            String key = (String) msg.getHeaders().get(SolaceBinderHeaders.PARTITION_KEY);
            String payload = new String((byte[]) msg.getPayload(), StandardCharsets.UTF_8);
            int seq = Integer.parseInt(payload.substring(payload.indexOf('#') + 1));
            threadsByKey.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                    .add(Thread.currentThread().getName());
            seqByKey.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(seq);
            try {
                Thread.sleep(2); // create contention so a non-affine dispatch would interleave threads
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            latch.countDown();
        });

        try {
            // Interleave keys but keep each key's sequence numbers strictly increasing in send order.
            for (int seq = 0; seq < perKey; seq++) {
                for (int k = 0; k < keyCount; k++) {
                    String key = "partition-" + k;
                    moduleOutputChannel.send(MessageBuilder
                            .withPayload((key + "#" + seq).getBytes(StandardCharsets.UTF_8))
                            .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                            .setHeader(SolaceBinderHeaders.PARTITION_KEY, key)
                            .build());
                }
            }

            assertThat(latch.await(2, TimeUnit.MINUTES)).as("all messages should be processed").isTrue();

            assertThat(threadsByKey).as("every partition key should have been seen").hasSize(keyCount);
            threadsByKey.forEach((key, threads) -> assertThat(threads)
                    .as("partition %s must be processed by exactly one worker thread", key)
                    .hasSize(1));
            seqByKey.forEach((key, seqs) -> assertThat(seqs)
                    .as("messages of partition %s must be processed in receive order", key)
                    .containsExactlyElementsOf(IntStream.range(0, perKey).boxed().toList()));

            Set<String> allThreads = threadsByKey.values().stream()
                    .flatMap(Set::stream).collect(Collectors.toSet());
            assertThat(allThreads)
                    .as("multiple worker threads should be used across partitions")
                    .hasSizeGreaterThanOrEqualTo(2);
        } finally {
            consumerBinding.unbind();
            producerBinding.unbind();
        }
    }

    private static String randomAlphanumeric() {
        return RandomGenerator.getDefault().ints(10, 0, 36)
                .mapToObj(i -> Character.toString("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".charAt(i)))
                .collect(Collectors.joining());
    }
}

package com.solace.spring.cloud.stream.binder;

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration;
import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceProvisioningUtil;
import com.solace.spring.cloud.stream.binder.test.spring.ConsumerInfrastructureUtil;
import com.solace.spring.cloud.stream.binder.test.spring.MessageGenerator;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SimpleJCSMPEventHandler;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.spring.cloud.stream.binder.util.CorrelationData;
import com.solace.spring.cloud.stream.binder.util.DestinationType;
import com.solace.test.integration.junit.jupiter.extension.ExecutorServiceExtension;
import com.solace.test.integration.junit.jupiter.extension.ExecutorServiceExtension.ExecSvc;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.config.model.ConfigMsgVpnQueue;
import com.solace.test.integration.semp.v2.monitor.ApiException;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueue;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueueTxFlow;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.Queue;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.junitpioneer.jupiter.cartesian.CartesianTest.Values;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.cloud.stream.binder.*;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.provisioning.ProvisioningException;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.context.IntegrationContextUtils;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.*;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.MimeTypeUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.solace.spring.cloud.stream.binder.test.util.RetryableAssertions.retryAssert;
import static com.solace.spring.cloud.stream.binder.test.util.SolaceSpringCloudStreamAssertions.*;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs all basic Spring Cloud Stream Binder functionality tests
 * inherited by {@link PartitionCapableBinderTests PartitionCapableBinderTests}
 * along with basic tests specific to the Solace Spring Cloud Stream Binder.
 */
@Slf4j
@SpringJUnitConfig(classes = SolaceJavaAutoConfiguration.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@ExtendWith(ExecutorServiceExtension.class)
@ExtendWith(PubSubPlusExtension.class)
@Execution(ExecutionMode.SAME_THREAD) // parent tests define static destinations
@Isolated
@DirtiesContext
public class SolaceBinderBasicIT extends SpringCloudStreamContext {

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
     * Basically the same as {@link #testSendAndReceive(TestInfo)}. Reimplemented it to test batch consumption as well.
     *
     * @see #testSendAndReceive(TestInfo)
     */
    @CartesianTest(name = "[{index}] endpointType={0}, numMessages={1}")
    @Execution(ExecutionMode.CONCURRENT)
    public <T> void testReceive(
            @Values(ints = {1, 256}) int numMessages,
            SempV2Api sempV2Api,
            SoftAssertions softly,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, numMessages)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        binderBindUnbindLatency();

        Iterator<Message<?>> messageIterator = messages.iterator();
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, numMessages,
                () -> messages.forEach(moduleOutputChannel::send),
                msg -> softly.assertThat(msg).satisfies(isValidMessage(consumerProperties,
                        List.of(messageIterator.next()))));

        String vpnName = (String) getJcsmpSession().getProperty(JCSMPProperties.VPN_NAME);

        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnQueueMsgs(vpnName, binder.getConsumerQueueName(consumerBinding), 2, null, null, null)
                .getData()).hasSize(0));
        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] namedConsumerGroup={0}")
    @Execution(ExecutionMode.CONCURRENT)
    public <T> void testReceiveBad(
            @Values(booleans = {false, true}) boolean namedConsumerGroup,
            SoftAssertions softly,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = namedConsumerGroup ? RandomStringUtils.randomAlphanumeric(10) : null;

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, createProducerProperties(testInfo));
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, group0, moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        binderBindUnbindLatency();

        AtomicInteger expectedDeliveryAttempt = new AtomicInteger(1);
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, consumerProperties.getMaxAttempts(),
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    softly.assertThat(msg).satisfies(isValidMessage(consumerProperties, messages));

                    softly.assertThat(StaticMessageHeaderAccessor.getDeliveryAttempt(msg))
                            .isNotNull()
                            .hasValue(expectedDeliveryAttempt.getAndIncrement());
                    callback.run();
                    throw new RuntimeException("bad");
                });

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] numMessages={0} withConfirmCorrelation={1}")
    public void testSend(@Values(ints = {1, 256}) int numMessages,
                         @Values(booleans = {false, true}) boolean withConfirmCorrelation,
                         SempV2Api sempV2Api,
                         SoftAssertions softly,
                         TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        ConsumerInfrastructureUtil<DirectChannel> consumerInfrastructureUtil = createConsumerInfrastructureUtil(
                DirectChannel.class);
        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = createProducerProperties(testInfo);
        producerProperties.setUseNativeEncoding(true);
        BindingProperties producerBindingProperties = new BindingProperties();
        producerBindingProperties.setProducer(producerProperties);
        DirectChannel moduleOutputChannel = createBindableChannel("output", producerBindingProperties);
        DirectChannel moduleInputChannel = consumerInfrastructureUtil
                .createChannel("input", new BindingProperties());
        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, producerProperties);
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        Binding<DirectChannel> consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel,
                consumerProperties);
        List<CorrelationData> correlationDataList = new ArrayList<>();
        List<Message<?>> messages = IntStream.range(0, numMessages)
                .mapToObj(i -> {
                    CorrelationData correlationData;
                    if (withConfirmCorrelation) {
                        correlationData = new CorrelationData();
                        correlationDataList.add(correlationData);
                    } else {
                        correlationData = null;
                    }
                    return MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                            .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                            .setHeader(SolaceBinderHeaders.CONFIRM_CORRELATION, correlationData)
                            .build();
                })
                .collect(Collectors.toList());
        binderBindUnbindLatency();
        Iterator<Message<?>> messageIterator = messages.iterator();
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, numMessages, () -> {

            messages.forEach(moduleOutputChannel::send);

        }, msg -> softly.assertThat(msg).satisfies(isValidMessage(consumerProperties, messageIterator.next())));

        String vpnName = (String) getJcsmpSession().getProperty(JCSMPProperties.VPN_NAME);

        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnQueueMsgs(vpnName, binder.getConsumerQueueName(consumerBinding), 2, null, null, null)
                .getData())
                .hasSize(0));

        assertThat(correlationDataList)
                .hasSize(withConfirmCorrelation ? (messages.size()) : 0)
                .allSatisfy(c -> assertThat(c.getFuture()).succeedsWithin(1, TimeUnit.MINUTES));

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testProducerErrorChannel(
            JCSMPSession jcsmpSession,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String destination0EC = String.format("%s%s%s%serrors", binder.getBinder().getBinderIdentity(),
                getDestinationNameDelimiter(), destination0, getDestinationNameDelimiter());

        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = createProducerProperties(testInfo);
        producerProperties.setErrorChannelEnabled(true);
        producerProperties.setUseNativeEncoding(true);
        producerProperties.populateBindingName(destination0);

        BindingProperties bindingProperties = new BindingProperties();
        bindingProperties.setProducer(producerProperties);

        DirectChannel moduleOutputChannel = createBindableChannel("output", bindingProperties);
        Binding<MessageChannel> producerBinding = binder.bindProducer(destination0, moduleOutputChannel, producerProperties);

        Message<?> message = MessageGenerator.generateMessage(
                        i -> UUID.randomUUID().toString().getBytes(),
                        i -> Map.of(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE))
                .build();

        final CompletableFuture<Message<?>> bindingSpecificErrorMessage = new CompletableFuture<>();
        log.info("Subscribing to binding-specific error channel");
        binder.getApplicationContext()
                .getBean(destination0EC, SubscribableChannel.class)
                .subscribe(bindingSpecificErrorMessage::complete);

        final CompletableFuture<Message<?>> globalErrorMessage = new CompletableFuture<>();
        log.info("Subscribing to global error channel");
        binder.getApplicationContext()
                .getBean(IntegrationContextUtils.ERROR_CHANNEL_BEAN_NAME, SubscribableChannel.class)
                .subscribe(globalErrorMessage::complete);

        jcsmpSession.closeSession();

        assertThatThrownBy(() -> moduleOutputChannel.send(message));

        assertThat(bindingSpecificErrorMessage)
                .succeedsWithin(10, TimeUnit.SECONDS)
                .satisfies(m -> assertThat(globalErrorMessage)
                        .succeedsWithin(10, TimeUnit.SECONDS)
                        .as("Expected error message sent to global and binding specific channels to be the same")
                        .isEqualTo(m))
                .asInstanceOf(InstanceOfAssertFactories.type(ErrorMessage.class))
                .satisfies(
                        m -> assertThat(m.getOriginalMessage()).isEqualTo(message),
                        m -> assertThat(m.getHeaders().get(IntegrationMessageHeaderAccessor.SOURCE_DATA))
                                .satisfies(d -> {
                                    assertThat(d).isInstanceOf(XMLMessage.class);
                                }))
                .extracting(ErrorMessage::getPayload)
                .asInstanceOf(InstanceOfAssertFactories.throwable(MessagingException.class))
                .cause()
                .isInstanceOf(ClosedFacilityException.class)
                .satisfies(e -> {
                    assertThat(e).hasNoSuppressedExceptions();
                });

        producerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] namedConsumerGroup={0} maxAttempts={1} throwMessagingExceptionWithMissingAckCallback={2}")
    @Execution(ExecutionMode.CONCURRENT)
    public void testConsumerRequeue(
            @Values(booleans = {false, true}) boolean namedConsumerGroup,
            @Values(ints = {1, 3}) int maxAttempts,
            @Values(booleans = {false, true}) boolean throwMessagingExceptionWithMissingAckCallback,
            SoftAssertions softly,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = namedConsumerGroup ? RandomStringUtils.randomAlphanumeric(10) : null;

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        consumerProperties.setMaxAttempts(maxAttempts);
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, group0, moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        binderBindUnbindLatency();

        final AtomicInteger numRetriesRemaining = new AtomicInteger(consumerProperties.getMaxAttempts());
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, numRetriesRemaining.get() + 1,
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    softly.assertThat(msg).satisfies(isValidMessage(consumerProperties, messages));
                    if (numRetriesRemaining.getAndDecrement() > 0) {
                        callback.run();
                        throw throwMessagingExceptionWithMissingAckCallback ?
                                new MessagingException(MessageBuilder.fromMessage(msg)
                                        .removeHeader(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK)
                                        .build(),
                                        "Throwing expected exception!") :
                                new RuntimeException("Throwing expected exception!");
                    } else {
                        log.info("Received message");
                        softly.assertThat(msg).satisfies(hasNestedHeader(SolaceHeaders.REDELIVERED, Boolean.class, v -> assertThat(v).isTrue()));
                        callback.run();
                    }
                });

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @CartesianTest(name = "[{index}] namedConsumerGroup={0} maxAttempts={1} throwMessagingExceptionWithMissingAckCallback={2}")
    @Execution(ExecutionMode.CONCURRENT)
    public void testConsumerErrorQueueRepublish(
            @Values(booleans = {false, true}) boolean namedConsumerGroup,
            @Values(ints = {1, 3}) int maxAttempts,
            @Values(booleans = {false, true}) boolean throwMessagingExceptionWithMissingAckCallback,
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = namedConsumerGroup ? RandomStringUtils.randomAlphanumeric(10) : null;

        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        consumerProperties.setMaxAttempts(maxAttempts);
        consumerProperties.getExtension().setAutoBindErrorQueue(true);
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, group0, moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build())
                .collect(Collectors.toList());

        binderBindUnbindLatency();

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, consumerProperties.getMaxAttempts(),
                () -> messages.forEach(moduleOutputChannel::send),
                (msg, callback) -> {
                    callback.run();
                    throw throwMessagingExceptionWithMissingAckCallback ?
                            new MessagingException(MessageBuilder.fromMessage(msg)
                                    .removeHeader(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK)
                                    .build(),
                                    "Throwing expected exception!") :
                            new RuntimeException("Throwing expected exception!");
                });

        assertThat(binder.getConsumerErrorQueueName(consumerBinding))
                .satisfies(errorQueueHasMessages(jcsmpSession, messages));
        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnQueueMsgs(vpnName, binder.getConsumerQueueName(consumerBinding), 2, null, null, null)
                .getData())
                .hasSize(0));

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testFailProducerProvisioningOnRequiredQueuePropertyChange(JCSMPSession jcsmpSession, TestInfo testInfo)
            throws Exception {
        SolaceTestBinder binder = getBinder();
        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        int defaultAccessType = createConsumerProperties().getExtension().getQueueAccessType();
        EndpointProperties endpointProperties = new EndpointProperties();
        endpointProperties.setAccessType((defaultAccessType + 1) % 2);
        Queue queue = JCSMPFactory.onlyInstance().createQueue(SolaceProvisioningUtil
                .getQueueName(destination0, group0, createProducerProperties(testInfo)));

        log.info(String.format("Pre-provisioning queue %s with AccessType %s to conflict with defaultAccessType %s",
                queue.getName(), endpointProperties.getAccessType(), defaultAccessType));
        jcsmpSession.provision(queue, endpointProperties, JCSMPSession.WAIT_FOR_CONFIRM);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        Binding<MessageChannel> producerBinding;
        try {
            ExtendedProducerProperties<SolaceProducerProperties> bindingProducerProperties = createProducerProperties(
                    testInfo);
            bindingProducerProperties.setRequiredGroups(group0);
            producerBinding = binder.bindProducer(destination0, moduleOutputChannel, bindingProducerProperties);
            producerBinding.unbind();
            fail("Expected producer provisioning to fail due to queue property change");
        } catch (ProvisioningException e) {
            assertThat(e).hasCauseInstanceOf(PropertyMismatchException.class);
            log.info(String.format("Successfully threw a %s exception with cause %s",
                    ProvisioningException.class.getSimpleName(), PropertyMismatchException.class.getSimpleName()));
        } finally {
            jcsmpSession.deprovision(queue, JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
        }
    }

    @Test
    public void testFailConsumerProvisioningOnQueuePropertyChange(JCSMPSession jcsmpSession) throws Exception {
        SolaceTestBinder binder = getBinder();

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        int defaultAccessType = createConsumerProperties().getExtension().getQueueAccessType();
        EndpointProperties endpointProperties = new EndpointProperties();
        endpointProperties.setAccessType((defaultAccessType + 1) % 2);
        Queue queue = JCSMPFactory.onlyInstance().createQueue(SolaceProvisioningUtil
                .getQueueNames(destination0, group0, createConsumerProperties(), false)
                .getConsumerGroupQueueName());

        log.info(String.format("Pre-provisioning queue %s with AccessType %s to conflict with defaultAccessType %s",
                queue.getName(), endpointProperties.getAccessType(), defaultAccessType));
        jcsmpSession.provision(queue, endpointProperties, JCSMPSession.WAIT_FOR_CONFIRM);

        DirectChannel moduleInputChannel = createBindableChannel("input", new BindingProperties());
        Binding<MessageChannel> consumerBinding;
        try {
            consumerBinding = binder.bindConsumer(
                    destination0, group0, moduleInputChannel, createConsumerProperties());
            consumerBinding.unbind();
            fail("Expected consumer provisioning to fail due to queue property change");
        } catch (ProvisioningException e) {
            assertThat(e).hasCauseInstanceOf(PropertyMismatchException.class);
            log.info(String.format("Successfully threw a %s exception with cause %s",
                    ProvisioningException.class.getSimpleName(), PropertyMismatchException.class.getSimpleName()));
        } finally {
            jcsmpSession.deprovision(queue, JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
        }
    }

    @Test
    public void testFailConsumerProvisioningOnErrorQueuePropertyChange(JCSMPSession jcsmpSession) throws Exception {
        SolaceTestBinder binder = getBinder();

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        int defaultAccessType = createConsumerProperties().getExtension().getQueueAccessType();
        EndpointProperties endpointProperties = new EndpointProperties();
        endpointProperties.setAccessType((defaultAccessType + 1) % 2);
        Queue errorQueue = JCSMPFactory.onlyInstance().createQueue(SolaceProvisioningUtil
                .getQueueNames(destination0, group0, createConsumerProperties(), false)
                .getErrorQueueName());

        log.info(String.format("Pre-provisioning error queue %s with AccessType %s to conflict with defaultAccessType %s",
                errorQueue.getName(), endpointProperties.getAccessType(), defaultAccessType));
        jcsmpSession.provision(errorQueue, endpointProperties, JCSMPSession.WAIT_FOR_CONFIRM);

        DirectChannel moduleInputChannel = createBindableChannel("input", new BindingProperties());
        Binding<MessageChannel> consumerBinding;
        try {
            ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
            consumerProperties.getExtension().setAutoBindErrorQueue(true);
            consumerBinding = binder.bindConsumer(destination0, group0, moduleInputChannel, consumerProperties);
            consumerBinding.unbind();
            fail("Expected consumer provisioning to fail due to error queue property change");
        } catch (ProvisioningException e) {
            assertThat(e).hasCauseInstanceOf(PropertyMismatchException.class);
            log.info(String.format("Successfully threw a %s exception with cause %s",
                    ProvisioningException.class.getSimpleName(), PropertyMismatchException.class.getSimpleName()));
        } finally {
            jcsmpSession.deprovision(errorQueue, JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
        }
    }

    @CartesianTest(name = "[{index}] addDestinationAsSubscriptionToQueue={0}")
    public void testConsumerAdditionalSubscriptions(
            @Values(booleans = {false, true}) boolean addDestinationAsSubscriptionToQueue,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();

        DirectChannel moduleOutputChannel0 = createBindableChannel("output0", new BindingProperties());
        DirectChannel moduleOutputChannel1 = createBindableChannel("output1", new BindingProperties());
        DirectChannel moduleInputChannel = createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String destination1 = "some-destl";
        String wildcardDestination1 = destination1.substring(0, destination1.length() - 1) + "*";

        Binding<MessageChannel> producerBinding0 = binder.bindProducer(
                destination0, moduleOutputChannel0, createProducerProperties(testInfo));
        Binding<MessageChannel> producerBinding1 = binder.bindProducer(
                destination1, moduleOutputChannel1, createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        // this flag shouldn't do anything for additional-subscriptions
        consumerProperties.getExtension().setAddDestinationAsSubscriptionToQueue(addDestinationAsSubscriptionToQueue);
        consumerProperties.getExtension()
                .setQueueAdditionalSubscriptions(new String[]{wildcardDestination1, "some-random-sub"});

        Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        binderBindUnbindLatency();

        final BlockingQueue<Destination> receivedMsgDestinations = new ArrayBlockingQueue<>(10);
        moduleInputChannel.subscribe(message1 -> {
            log.info(String.format("Received message %s", message1));
            Optional.ofNullable(message1.getHeaders().get(SolaceHeaders.DESTINATION, Destination.class))
                    .ifPresent(receivedMsgDestinations::add);
        });

        if (addDestinationAsSubscriptionToQueue) {
            log.info(String.format("Sending message to destination %s: %s", destination0, message));
            moduleOutputChannel0.send(message);
            assertThat(receivedMsgDestinations.poll(10, TimeUnit.SECONDS))
                    .extracting(Destination::getName)
                    .isEqualTo(destination0);
        }

        log.info(String.format("Sending message to destination %s: %s", destination1, message));
        moduleOutputChannel1.send(message);
        assertThat(receivedMsgDestinations.poll(10, TimeUnit.SECONDS))
                .extracting(Destination::getName)
                .isEqualTo(destination1);

        assertThat(receivedMsgDestinations)
                .as("An unexpected message was read")
                .isEmpty();

        TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume
        producerBinding0.unbind();
        producerBinding1.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testProducerAdditionalSubscriptions(TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();

        DirectChannel moduleOutputChannel0 = createBindableChannel("output0", new BindingProperties());
        DirectChannel moduleOutputChannel1 = createBindableChannel("output1", new BindingProperties());
        DirectChannel moduleInputChannel = createBindableChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String destination1 = "some-destl";
        String wildcardDestination1 = destination1.substring(0, destination1.length() - 1) + "*";
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedProducerProperties<SolaceProducerProperties> producerProperties = createProducerProperties(testInfo);
        Map<String, String[]> groupsAdditionalSubs = new HashMap<>();
        groupsAdditionalSubs.put(group0, new String[]{wildcardDestination1});
        producerProperties.setRequiredGroups(group0);
        producerProperties.getExtension().setQueueAdditionalSubscriptions(groupsAdditionalSubs);

        Binding<MessageChannel> producerBinding0 = binder.bindProducer(
                destination0, moduleOutputChannel0, producerProperties);
        Binding<MessageChannel> producerBinding1 = binder.bindProducer(
                destination1, moduleOutputChannel1, createProducerProperties(testInfo));

        Binding<MessageChannel> consumerBinding = binder.bindConsumer(
                destination0, group0, moduleInputChannel, createConsumerProperties());

        Message<?> message = MessageBuilder.withPayload("foo".getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();

        binderBindUnbindLatency();

        final CountDownLatch latch = new CountDownLatch(2);
        moduleInputChannel.subscribe(message1 -> {
            log.info(String.format("Received message %s", message1));
            latch.countDown();
        });

        log.info(String.format("Sending message to destination %s: %s", destination0, message));
        moduleOutputChannel0.send(message);

        log.info(String.format("Sending message to destination %s: %s", destination1, message));
        moduleOutputChannel1.send(message);

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        TimeUnit.SECONDS.sleep(1); // Give bindings a sec to finish processing successful message consume
        producerBinding0.unbind();
        producerBinding1.unbind();
        consumerBinding.unbind();
    }

    @Test
    public <T> void testConsumerReconnect(
            SempV2Api sempV2Api,
            SoftAssertions softly,
            @ExecSvc(scheduled = true, poolSize = 5) ScheduledExecutorService executor,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, group0, moduleInputChannel, consumerProperties);

        binderBindUnbindLatency();

        String vpnName = (String) getJcsmpSession().getProperty(JCSMPProperties.VPN_NAME);
        String queue0 = binder.getConsumerQueueName(consumerBinding);

        // Minimize message pre-fetch since we're not testing JCSMP, and this influences the test counters
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue()
                .maxDeliveredUnackedMsgsPerFlow((long) 255), vpnName, queue0, null, null);
        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue0, null)
                .getData()
                .getMaxDeliveredUnackedMsgsPerFlow())
                .isEqualTo(255));

        final AtomicInteger numMsgsConsumed = new AtomicInteger(0);
        final Set<String> uniquePayloadsReceived = new HashSet<>();
        consumerInfrastructureUtil.subscribe(moduleInputChannel, executor, message1 -> {
            @SuppressWarnings("unchecked")
            List<byte[]> payloads = consumerProperties.isBatchMode() ? (List<byte[]>) message1.getPayload() :
                    Collections.singletonList((byte[]) message1.getPayload());
            for (byte[] payload : payloads) {
                numMsgsConsumed.incrementAndGet();
                log.trace(String.format("Received message %s", new String(payload)));
                uniquePayloadsReceived.add(new String(payload));
            }
        });

        AtomicBoolean producerStop = new AtomicBoolean(false);
        Future<Integer> producerFuture = executor.submit(() -> {
            int numMsgsSent = 0;
            while (!producerStop.get() && !Thread.currentThread().isInterrupted()) {
                String payload = "foo-" + numMsgsSent;
                Message<?> message = MessageBuilder.withPayload(payload.getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build();
                log.trace(String.format("Sending message %s", payload));
                try {
                    moduleOutputChannel.send(message);
                    numMsgsSent += 1;
                } catch (MessagingException e) {
                    if (e.getCause() instanceof JCSMPInterruptedException) {
                        log.warn("Received interrupt exception during message produce");
                        break;
                    } else {
                        throw e;
                    }
                }
            }
            return numMsgsSent;
        });

        Thread.sleep(TimeUnit.SECONDS.toMillis(5));

        log.info(String.format("Disabling egress to queue %s", queue0));
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(false), vpnName, queue0, null, null);
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));

        log.info(String.format("Enabling egress to queue %s", queue0));
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue().egressEnabled(true), vpnName, queue0, null, null);
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));

        log.info("Stopping producer");
        producerStop.set(true);
        int numMsgsSent = producerFuture.get(5, TimeUnit.SECONDS);

        softly.assertThat(queue0).satisfies(q -> retryAssert(1, TimeUnit.MINUTES, () ->
                assertThat(sempV2Api.monitor()
                        .getMsgVpnQueueMsgs(vpnName, q, Integer.MAX_VALUE, null, null, null)
                        .getData()
                        .size())
                        .as("Expected queue %s to be empty after rebind", q)
                        .isEqualTo(0)));

        MonitorMsgVpnQueue queueState = sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue0, null)
                .getData();

        softly.assertThat(queueState.getDisabledBindFailureCount()).isGreaterThan(0);
        softly.assertThat(uniquePayloadsReceived.size()).isEqualTo(numMsgsSent);
        // Give margin of error. Redelivered messages might be untracked if it's consumer was shutdown before it could
        // be added to the consumed msg count.
        softly.assertThat(numMsgsConsumed.get()).isGreaterThanOrEqualTo(numMsgsSent);

        log.info("num-sent: {}, num-consumed: {}, num-redelivered: {}", numMsgsSent, numMsgsConsumed.get(),
                queueState.getRedeliveredMsgCount());
        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testConsumerRebind(
            SempV2Api sempV2Api,
            SoftAssertions softly,
            @ExecSvc(scheduled = true, poolSize = 5) ScheduledExecutorService executor,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        String group0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, createProducerProperties(testInfo));

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();

        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, group0, moduleInputChannel, consumerProperties);

        binderBindUnbindLatency();

        String vpnName = (String) getJcsmpSession().getProperty(JCSMPProperties.VPN_NAME);
        String queue0 = binder.getConsumerQueueName(consumerBinding);

        // Minimize message pre-fetch since we're not testing JCSMP, and this influences the test counters
        sempV2Api.config().updateMsgVpnQueue(new ConfigMsgVpnQueue()
                .maxDeliveredUnackedMsgsPerFlow((long) 255), vpnName, queue0, null, null);
        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue0, null)
                .getData()
                .getMaxDeliveredUnackedMsgsPerFlow())
                .isEqualTo(255));

        final AtomicInteger numMsgsConsumed = new AtomicInteger(0);
        final Set<String> uniquePayloadsReceived = new HashSet<>();
        consumerInfrastructureUtil.subscribe(moduleInputChannel, executor, message1 -> {
            @SuppressWarnings("unchecked")
            byte[] payload = (byte[]) message1.getPayload();
            numMsgsConsumed.incrementAndGet();
            log.trace(String.format("Received message %s", new String(payload)));
            uniquePayloadsReceived.add(new String(payload));
        });

        AtomicBoolean producerStop = new AtomicBoolean(false);
        Future<Integer> producerFuture = executor.submit(() -> {
            int numMsgsSent = 0;
            while (!producerStop.get() && !Thread.currentThread().isInterrupted()) {
                String payload = "foo-" + numMsgsSent;
                Message<?> message = MessageBuilder.withPayload(payload.getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .build();
                log.trace(String.format("Sending message %s", payload));
                try {
                    moduleOutputChannel.send(message);
                    numMsgsSent += 1;
                } catch (MessagingException e) {
                    if (e.getCause() instanceof JCSMPInterruptedException) {
                        log.warn("Received interrupt exception during message produce");
                        break;
                    } else {
                        throw e;
                    }
                }
            }
            return numMsgsSent;
        });

        Thread.sleep(TimeUnit.SECONDS.toMillis(5));

        log.info("Unbinding consumer");
        consumerBinding.unbind();

        log.info("Stopping producer");
        producerStop.set(true);
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));

        log.info("Rebinding consumer");
        consumerBinding = consumerInfrastructureUtil.createBinding(binder, destination0, group0, moduleInputChannel,
                consumerProperties);
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));

        int numMsgsSent = producerFuture.get(5, TimeUnit.SECONDS);

        softly.assertThat(queue0).satisfies(q -> retryAssert(1, TimeUnit.MINUTES, () ->
                assertThat(sempV2Api.monitor()
                        .getMsgVpnQueueMsgs(vpnName, queue0, Integer.MAX_VALUE, null, null, null)
                        .getData()
                        .size())
                        .as("Expected queue %s to be empty after rebind", queue0)
                        .isEqualTo(0)));

        long redeliveredMsgs = sempV2Api.monitor()
                .getMsgVpnQueue(vpnName, queue0, null)
                .getData()
                .getRedeliveredMsgCount();

        softly.assertThat(uniquePayloadsReceived.size()).isEqualTo(numMsgsSent);
        // Give margin of error. Redelivered messages might be untracked if it's consumer was shutdown before it could
        // be added to the consumed msg count.
        softly.assertThat(numMsgsConsumed.get() - redeliveredMsgs)
                .isBetween((long) numMsgsSent - 255, (long) numMsgsSent);

        log.info("num-sent: {}, num-consumed: {}, num-redelivered: {}", numMsgsSent, numMsgsConsumed.get(),
                redeliveredMsgs);
        producerBinding.unbind();
        consumerBinding.unbind();
    }

    @Test
    public void testRequestReplyWithRequestor(JCSMPSession jcsmpSession, SoftAssertions softly,
                                              TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        DirectChannel moduleInputChannel = createBindableChannel("input", new BindingProperties());

        String requestDestination = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer("willBeOverridden", moduleOutputChannel,
                createProducerProperties(testInfo));
        Binding<MessageChannel> consumerBinding = binder.bindConsumer(requestDestination,
                RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, createConsumerProperties());

        final String PROCESSED_SUFFIX = "_PROCESSED";
        String expectedCorrelationId = "theCorrelationId";

        //Prepare the Replier
        moduleInputChannel.subscribe(request -> {
            String reqCorrelationId = request.getHeaders().get(SolaceHeaders.CORRELATION_ID, String.class);
            Destination reqReplyTo = request.getHeaders().get(SolaceHeaders.REPLY_TO, Destination.class);
            String reqPayload = (String) request.getPayload();

            log.info(String.format("Received request with correlationId [ %s ], replyTo [ %s ], payload [ %s ]",
                    reqCorrelationId, reqReplyTo, reqPayload));

            softly.assertThat(reqCorrelationId).isEqualTo(expectedCorrelationId);

            //Send reply message
            Message<?> springMessage = MessageBuilder
                    .withPayload(reqPayload + PROCESSED_SUFFIX)
                    .setHeader(SolaceHeaders.IS_REPLY, true)
                    .setHeader(SolaceHeaders.CORRELATION_ID, reqCorrelationId)
                    .setHeader(BinderHeaders.TARGET_DESTINATION, reqReplyTo != null ? reqReplyTo.getName() : "")
                    .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                    .build();
            moduleOutputChannel.send(springMessage);
        });

        //Prepare the Requestor (need a producer and a consumer)
        XMLMessageProducer producer = jcsmpSession.getMessageProducer(new SimpleJCSMPEventHandler());
        XMLMessageConsumer consumer = jcsmpSession.getMessageConsumer((XMLMessageListener) null);
        consumer.start();

        TextMessage requestMsg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        String requestPayload = "This is the request";
        requestMsg.setText(requestPayload);
        requestMsg.setCorrelationId(expectedCorrelationId);

        //Send request and await for a reply
        Requestor requestor = jcsmpSession.createRequestor();
        BytesXMLMessage replyMsg = requestor.request(requestMsg, 10000, JCSMPFactory.onlyInstance().createTopic(requestDestination));
        assertNotNull(replyMsg, "Did not receive a reply within allotted time");

        softly.assertThat(replyMsg.getCorrelationId()).isEqualTo(expectedCorrelationId);
        softly.assertThat(replyMsg.isReplyMessage()).isTrue();
        String replyPayload = new String(((BytesMessage) replyMsg).getData());
        softly.assertThat(replyPayload).isEqualTo(requestPayload + PROCESSED_SUFFIX);

        producerBinding.unbind();
        consumerBinding.unbind();
        consumer.close();
        producer.close();
    }

    @Test
    public void testPauseResume(
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
        int defaultWindowSize = (int) jcsmpSession.getProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE);

        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());
        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        String queueName = binder.getConsumerQueueName(consumerBinding);

        assertEquals(defaultWindowSize, getQueueTxFlows(sempV2Api, vpnName, queueName, 1).get(0).getWindowSize());
        consumerBinding.pause();
        assertEquals(0, getQueueTxFlows(sempV2Api, vpnName, queueName, 1).get(0).getWindowSize());
        consumerBinding.resume();
        assertEquals(defaultWindowSize, getQueueTxFlows(sempV2Api, vpnName, queueName, 1).get(0).getWindowSize());

        consumerBinding.unbind();
    }

    @Test
    public void testPauseBeforeConsumerStart(
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);

        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());
        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        consumerProperties.setAutoStartup(false);
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        String queueName = binder.getConsumerQueueName(consumerBinding);

        assertThat(consumerBinding.isRunning()).isFalse();
        assertThat(consumerBinding.isPaused()).isFalse();

        consumerBinding.pause();
        assertThat(consumerBinding.isRunning()).isFalse();
        assertThat(consumerBinding.isPaused()).isTrue();
        assertThat(getQueueTxFlows(sempV2Api, vpnName, queueName, 1)).hasSize(0);

        consumerBinding.start();
        assertThat(consumerBinding.isRunning()).isTrue();
        assertThat(consumerBinding.isPaused()).isTrue();
        assertThat(getQueueTxFlows(sempV2Api, vpnName, queueName, 1))
                .hasSize(1)
                .allSatisfy(flow -> assertThat(flow.getWindowSize()).isEqualTo(0));

        consumerBinding.unbind();
    }

    @Test
    public void testPauseStateIsMaintainedWhenConsumerIsRestarted(
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);

        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());
        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        String queueName = binder.getConsumerQueueName(consumerBinding);
        consumerBinding.pause();
        assertEquals(0, getQueueTxFlows(sempV2Api, vpnName, queueName, 1).get(0).getWindowSize());

        consumerBinding.stop();
        assertThat(getQueueTxFlows(sempV2Api, vpnName, queueName, 1)).hasSize(0);
        consumerBinding.start();
        //Newly created flow is started in the pause state
        assertEquals(0, getQueueTxFlows(sempV2Api, vpnName, queueName, 1).get(0).getWindowSize());

        consumerBinding.unbind();
    }

    @Test
    public void testPauseOnStoppedConsumer(
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);

        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());
        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        String queueName = binder.getConsumerQueueName(consumerBinding);

        consumerBinding.stop();
        assertThat(getQueueTxFlows(sempV2Api, vpnName, queueName, 1)).hasSize(0);
        consumerBinding.pause();
        assertThat(getQueueTxFlows(sempV2Api, vpnName, queueName, 1)).hasSize(0);
        consumerBinding.start();
        assertEquals(0, getQueueTxFlows(sempV2Api, vpnName, queueName, 1).get(0).getWindowSize());

        consumerBinding.unbind();
    }

    @Test
    public void testResumeOnStoppedConsumer(
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
        int defaultWindowSize = (int) jcsmpSession.getProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE);

        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());
        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        String queueName = binder.getConsumerQueueName(consumerBinding);

        consumerBinding.pause();
        consumerBinding.stop();
        assertThat(getQueueTxFlows(sempV2Api, vpnName, queueName, 1)).hasSize(0);
        consumerBinding.start();
        consumerBinding.resume();
        assertEquals(defaultWindowSize, getQueueTxFlows(sempV2Api, vpnName, queueName, 1).get(0).getWindowSize());

        consumerBinding.unbind();
    }

    @Test
    public void testPauseResumeOnStoppedConsumer(
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);
        int defaultWindowSize = (int) jcsmpSession.getProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE);

        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());
        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        String queueName = binder.getConsumerQueueName(consumerBinding);

        consumerBinding.stop();
        assertThat(getQueueTxFlows(sempV2Api, vpnName, queueName, 1)).hasSize(0);
        consumerBinding.pause();
        consumerBinding.start();
        consumerBinding.resume();
        assertEquals(defaultWindowSize, getQueueTxFlows(sempV2Api, vpnName, queueName, 1).get(0).getWindowSize());

        consumerBinding.unbind();
    }

    @Test
    public void testFailResumeOnClosedConsumer(
            JCSMPSession jcsmpSession,
            SempV2Api sempV2Api) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);
        String vpnName = (String) jcsmpSession.getProperty(JCSMPProperties.VPN_NAME);

        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());
        String destination0 = RandomStringUtils.randomAlphanumeric(10);
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        String queueName = binder.getConsumerQueueName(consumerBinding);

        consumerBinding.pause();
        assertEquals(0, getQueueTxFlows(sempV2Api, vpnName, queueName, 1).get(0).getWindowSize());
        getJcsmpSession().closeSession();
        RuntimeException exception = assertThrows(RuntimeException.class, consumerBinding::resume);
        assertThat(exception).hasRootCauseInstanceOf(ClosedFacilityException.class);
        assertThat(consumerBinding.isPaused())
                .as("Failed resume should leave the binding in a paused state")
                .isTrue();

        consumerBinding.unbind();
    }

    @Test
    public void testProducerDestinationTypeSetAsQueue(JCSMPSession jcsmpSession) throws Exception {
        SolaceTestBinder binder = getBinder();

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        String destination = RandomStringUtils.randomAlphanumeric(15);

        SolaceProducerProperties solaceProducerProperties = new SolaceProducerProperties();
        solaceProducerProperties.setDestinationType(DestinationType.QUEUE);

        Binding<MessageChannel> producerBinding = null;
        FlowReceiver flowReceiver = null;
        try {
            producerBinding = binder.bindProducer(destination, moduleOutputChannel, new ExtendedProducerProperties<>(solaceProducerProperties));

            byte[] payload = RandomStringUtils.randomAlphanumeric(15).getBytes();
            Message<?> message = MessageBuilder.withPayload(payload)
                    .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                    .build();

            binderBindUnbindLatency();

            log.info(String.format("Sending message to message handler: %s", message));
            moduleOutputChannel.send(message);

            flowReceiver = jcsmpSession.createFlow(JCSMPFactory.onlyInstance().createQueue(destination), null, null);
            flowReceiver.start();
            BytesXMLMessage solMsg = flowReceiver.receive(5000);
            assertThat(solMsg)
                    .isNotNull()
                    .isInstanceOf(BytesMessage.class);
            assertThat(((BytesMessage) solMsg).getData()).isEqualTo(payload);
        } finally {
            if (flowReceiver != null) flowReceiver.close();
            if (producerBinding != null) producerBinding.unbind();
        }
    }

    @Test
    public void testProducerDestinationTypeSetAsTopic(JCSMPSession jcsmpSession) throws Exception {
        SolaceTestBinder binder = getBinder();

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        String destination = RandomStringUtils.randomAlphanumeric(15);

        SolaceProducerProperties solaceProducerProperties = new SolaceProducerProperties();
        solaceProducerProperties.setDestinationType(DestinationType.TOPIC);

        Binding<MessageChannel> producerBinding = null;
        XMLMessageConsumer consumer = null;
        try {
            producerBinding = binder.bindProducer(destination, moduleOutputChannel, new ExtendedProducerProperties<>(solaceProducerProperties));

            jcsmpSession.addSubscription(JCSMPFactory.onlyInstance().createTopic(destination));
            consumer = jcsmpSession.getMessageConsumer((XMLMessageListener) null);
            consumer.start();

            byte[] payload = RandomStringUtils.randomAlphanumeric(15).getBytes();
            Message<?> message = MessageBuilder.withPayload(payload)
                    .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                    .build();

            log.info(String.format("Sending message to message handler: %s", message));
            moduleOutputChannel.send(message);

            BytesXMLMessage solMsg = consumer.receive(5000);
            assertThat(solMsg)
                    .isNotNull()
                    .isInstanceOf(BytesMessage.class);
            assertThat(((BytesMessage) solMsg).getData()).isEqualTo(payload);
        } finally {
            if (consumer != null) consumer.close();
            if (jcsmpSession != null)
                jcsmpSession.removeSubscription(JCSMPFactory.onlyInstance().createTopic(destination));
            if (producerBinding != null) producerBinding.unbind();
        }
    }

    @Test
    public <T> void testConsumerHeaderExclusion(
            JCSMPProperties jcsmpProperties,
            SempV2Api sempV2Api,
            SoftAssertions softly,
            TestInfo testInfo) throws Exception {
        List<String> excludedHeaders = List.of("headerKey1", "headerKey2", "headerKey5",
                "solace_expiration", "solace_discardIndication", "solace_redelivered",
                "solace_dmqEligible", "solace_priority");

        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, createProducerProperties(testInfo));
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        consumerProperties.getExtension().setHeaderExclusions(excludedHeaders);
        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> {
                    MessageBuilder<byte[]> messageBuilder =
                            MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                                    .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE);
                    for (int ind = 0; ind < 10; ind++) {
                        messageBuilder.setHeader("headerKey" + ind, UUID.randomUUID().toString());
                    }
                    return messageBuilder.build();
                }).collect(Collectors.toList());

        binderBindUnbindLatency();

        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 1,
                () -> messages.forEach(msg -> {
                    softly.assertThat(msg.getHeaders()).containsKeys("headerKey1", "headerKey2", "headerKey5");
                    moduleOutputChannel.send(msg);
                }),
                msg -> {
                    softly.assertThat(msg).satisfies(isValidMessage(consumerProperties, messages));

                    MessageHeaders springMessageHeaders;

                    springMessageHeaders = msg.getHeaders();

                    softly.assertThat(springMessageHeaders)
                            .doesNotContainKeys(excludedHeaders.toArray(new String[0]));
                }
        );

        retryAssert(() -> assertThat(sempV2Api.monitor()
                .getMsgVpnQueueMsgs(jcsmpProperties.getStringProperty(JCSMPProperties.VPN_NAME),
                        binder.getConsumerQueueName(consumerBinding), 2, null, null, null)
                .getData())
                .hasSize(0));

        producerBinding.unbind();
        consumerBinding.unbind();
    }


    /**
     * Tests if selected message is received and other message is not
     */
    @Test
    public void testConsumerWithSelectorMismatch(
            JCSMPProperties jcsmpProperties,
            SempV2Api sempV2Api,
            SoftAssertions softly,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, createProducerProperties(testInfo));
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        consumerProperties.getExtension().setSelector("uProperty= 'willBeReceived'");

        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        Message<?> badMessage = MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .setHeader("uProperty", "willNotBeDispatched")
                .build();

        Message<?> goodMessage = MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .setHeader("uProperty", "willBeReceived")
                .build();
        List<Message<?>> messages = List.of(badMessage, goodMessage);

        binderBindUnbindLatency();

        String endpointName = binder.getConsumerQueueName(consumerBinding);
        // except only the good message
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 1,
                () -> messages.forEach(moduleOutputChannel::send),
                // receiving only good message
                msg -> softly.assertThat(msg).satisfies(isValidMessage(consumerProperties, goodMessage)));

        retryAssert(() -> assertThat(getQueueTxFlows(sempV2Api, jcsmpProperties.getStringProperty(JCSMPProperties.VPN_NAME), endpointName, 1).get(0).getSelector())
                .as("Selector not found for endpoint subscription %s", endpointName)
                // all variants of blank selectors will be defaulted to ""
                .isEqualTo("uProperty= 'willBeReceived'"));

        producerBinding.unbind();
        consumerBinding.unbind();
    }


    /**
     * Tests if selectors are added to queue or durable topic endpoint when connector subscribes for messages
     */
    @CartesianTest(name = "[{index}] selector=[{0}]")
    @Execution(ExecutionMode.CONCURRENT)
    public void testConsumerWithSelector(
            @Values(strings = {"", " ", "uProperty= 'selectorTest'"}) String selector,
            JCSMPProperties jcsmpProperties,
            SempV2Api sempV2Api,
            SoftAssertions softly,
            TestInfo testInfo) throws Exception {
        SolaceTestBinder binder = getBinder();
        var consumerInfrastructureUtil = createConsumerInfrastructureUtil(DirectChannel.class);

        DirectChannel moduleOutputChannel = createBindableChannel("output", new BindingProperties());
        var moduleInputChannel = consumerInfrastructureUtil.createChannel("input", new BindingProperties());

        String destination0 = RandomStringUtils.randomAlphanumeric(10);

        Binding<MessageChannel> producerBinding = binder.bindProducer(
                destination0, moduleOutputChannel, createProducerProperties(testInfo));
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = createConsumerProperties();
        consumerProperties.getExtension().setSelector(selector);

        var consumerBinding = consumerInfrastructureUtil.createBinding(binder,
                destination0, RandomStringUtils.randomAlphanumeric(10), moduleInputChannel, consumerProperties);

        List<Message<?>> messages = IntStream.range(0, 1)
                .mapToObj(i -> MessageBuilder.withPayload(UUID.randomUUID().toString().getBytes())
                        .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                        .setHeader("uProperty", "selectorTest")
                        .build())
                .collect(Collectors.toList());
        binderBindUnbindLatency();

        String endpointName = binder.getConsumerQueueName(consumerBinding);
        consumerInfrastructureUtil.sendAndSubscribe(moduleInputChannel, 1,
                () -> messages.forEach(moduleOutputChannel::send),
                msg -> softly.assertThat(msg).satisfies(isValidMessage(consumerProperties, messages)));

        retryAssert(() -> assertThat(getQueueTxFlows(sempV2Api, jcsmpProperties.getStringProperty(JCSMPProperties.VPN_NAME), endpointName, 1).get(0).getSelector()
        ).as("Selector not found for endpoint subscription %s", endpointName)
                // all variants of blank selectors will be defaulted to ""
                .isEqualTo((selector == null || selector.isBlank()) ? "" : selector));

        producerBinding.unbind();
        consumerBinding.unbind();
    }

    private List<MonitorMsgVpnQueueTxFlow> getQueueTxFlows(SempV2Api sempV2Api, String vpnName, String queueName, Integer count) throws ApiException {
        return sempV2Api.monitor()
                .getMsgVpnQueueTxFlows(vpnName, queueName, count, null, null, null)
                .getData();
    }
}
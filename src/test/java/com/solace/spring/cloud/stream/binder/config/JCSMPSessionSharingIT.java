package com.solace.spring.cloud.stream.binder.config;

import com.solace.spring.cloud.stream.binder.SolaceMessageChannelBinder;
import com.solace.spring.cloud.stream.binder.config.autoconfigure.JCSMPSessionConfiguration;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.handlers.SolaceSessionEventHandler;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceExtendedBindingProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.test.util.SimpleJCSMPEventHandler;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solace.spring.cloud.stream.binder.util.QualityOfService;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.InvalidPropertiesException;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.SessionEvent;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SolaceSessionOAuth2TokenProvider;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import com.solacesystems.jcsmp.XMLMessageProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.health.contributor.Status;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Every Spring context of a JVM that connects with the same properties shares one JCSMP session: the application
 * context and the context of its binder, and in a test suite every context Spring keeps cached. Each
 * {@link JCSMPSessionConfiguration} here plays one such context. A context that closes must not take the session
 * away from the others, and a context that connects must not hold up the contexts of other properties.
 * <p>
 * The session cache is static, so every test closes each context it opened.
 * <p>
 * The OAuth2 token server is the one system that is not there: a token provider that answers on cue holds a connect
 * open, the way an unavailable broker does for as long as the connect retries last.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(PubSubPlusExtension.class)
public class JCSMPSessionSharingIT {
    private static final int RECEIVE_TIMEOUT_MILLIS = 10_000;
    private static final Duration LONGEST_STEP_THAT_MUST_NOT_WAIT = Duration.ofSeconds(10);
    private static final Duration HANGING_CONNECT_ENDS_WITHIN = Duration.ofMinutes(1);

    private final List<JCSMPSessionConfiguration> openContexts = new ArrayList<>();
    private final List<AnnotationConfigApplicationContext> openIntegrationContexts = new ArrayList<>();
    private final AtomicBoolean tokenServerAnswers = new AtomicBoolean();
    private CompletableFuture<JCSMPSession> hangingConnect = CompletableFuture.completedFuture(null);

    @AfterEach
    void closeEveryContext() {
        tokenServerAnswers.set(true);
        await().atMost(HANGING_CONNECT_ENDS_WITHIN).until(hangingConnect::isDone);
        openContexts.forEach(JCSMPSessionConfiguration::destroy);
        openIntegrationContexts.forEach(AnnotationConfigApplicationContext::close);
    }

    @Test
    public void testContextsWithTheSamePropertiesShareOneSession(JCSMPProperties jcsmpProperties) {
        JCSMPSession sessionOfTheFirst = sessionOf(aContext(), jcsmpProperties);
        JCSMPSession sessionOfTheSecond = sessionOf(aContext(), jcsmpProperties);

        assertThat(sessionOfTheSecond).isSameAs(sessionOfTheFirst);
    }

    @Test
    public void testAProducerKeepsSendingWhenAnotherContextCloses(JCSMPProperties jcsmpProperties,
                                                                  JCSMPSession receivingSession) throws JCSMPException {
        Topic topic = JCSMPFactory.onlyInstance().createTopic("jcsmp-session-sharing/" + UUID.randomUUID());
        XMLMessageConsumer receiver = receiverOn(receivingSession, topic);
        JCSMPSessionConfiguration closingContext = aContext();
        SolaceMessageChannelBinder binderOfTheClosingContext = aBinderOn(sessionOf(closingContext, jcsmpProperties));
        XMLMessageProducer producer = sessionOf(aContext(), jcsmpProperties).getMessageProducer(new SimpleJCSMPEventHandler());

        closeTheWaySpringDoes(closingContext, binderOfTheClosingContext);
        TextMessage sent = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        sent.setText("sent after another context closed");
        producer.send(sent, topic);

        BytesXMLMessage received = receiver.receive(RECEIVE_TIMEOUT_MILLIS);
        assertThat(received).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) received).getText()).isEqualTo("sent after another context closed");
    }

    /**
     * JCSMP keeps one default producer per session, and every binder sends its direct messages on it. A binder that
     * managed it on its own would close it under every other binder on the session when its last binding stops.
     */
    @Test
    public void aDirectProducer_whenAnotherContextBindsOneAndCloses_keepsSending(JCSMPProperties jcsmpProperties,
                                                                                 JCSMPSession receivingSession) throws Exception {
        String topicName = "jcsmp-session-sharing/" + UUID.randomUUID();
        XMLMessageConsumer receiver = receiverOn(receivingSession, JCSMPFactory.onlyInstance().createTopic(topicName));
        DirectChannel outputOfTheStayingContext = new DirectChannel();
        aDirectProducerBinding(aBinderOf(aContext(), jcsmpProperties), topicName, outputOfTheStayingContext);
        JCSMPSessionConfiguration closingContext = aContext();
        SolaceMessageChannelBinder binderOfTheClosingContext = aBinderOf(closingContext, jcsmpProperties);
        Binding<MessageChannel> bindingOfTheClosingContext =
                aDirectProducerBinding(binderOfTheClosingContext, topicName, new DirectChannel());

        closeTheWaySpringDoes(closingContext, binderOfTheClosingContext, bindingOfTheClosingContext);
        outputOfTheStayingContext.send(MessageBuilder.withPayload("sent after another context closed").build());

        BytesXMLMessage received = receiver.receive(RECEIVE_TIMEOUT_MILLIS);
        assertThat(received).as("the direct producer of the staying context must still be open").isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) received).getText()).isEqualTo("sent after another context closed");
    }

    /**
     * JCSMP keeps one default consumer per session, and the topic consumers of every binder receive on it. A binder
     * that fetched its own would take it from every other binder on the session.
     */
    @Test
    public void aTopicConsumer_whenAnotherContextBindsOneOnAnotherTopicAndCloses_keepsReceiving(JCSMPProperties jcsmpProperties,
                                                                                               JCSMPSession publishingSession) throws Exception {
        String topicName = aTopicName();
        BlockingQueue<Message<?>> receivedByTheStayingContext = aTopicConsumerOf(aContext(), jcsmpProperties, topicName);

        bindATopicConsumerAndClose(aContext(), jcsmpProperties, aTopicName());
        publish(publishingSession, topicName, "sent after another context closed");

        Message<?> received = receivedByTheStayingContext.poll(RECEIVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertThat(received).as("the topic consumer of the staying context must still receive").isNotNull();
        assertThat(received.getPayload()).isEqualTo("sent after another context closed");
    }

    /**
     * Topic subscriptions belong to the session, so the broker refuses a second one on the same topic, and removing it
     * for one binder removes it for all.
     */
    @Test
    public void aTopicConsumer_whenAnotherContextBindsOneOnTheSameTopicAndCloses_keepsReceiving(JCSMPProperties jcsmpProperties,
                                                                                              JCSMPSession publishingSession) throws Exception {
        String topicName = aTopicName();
        BlockingQueue<Message<?>> receivedByTheStayingContext = aTopicConsumerOf(aContext(), jcsmpProperties, topicName);

        bindATopicConsumerAndClose(aContext(), jcsmpProperties, topicName);
        publish(publishingSession, topicName, "sent after another context closed");

        Message<?> received = receivedByTheStayingContext.poll(RECEIVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertThat(received).as("the topic consumer of the staying context must still receive").isNotNull();
        assertThat(received.getPayload()).isEqualTo("sent after another context closed");
    }

    @Test
    public void aContextOfOtherProperties_whileAnotherConnectHangs_getsItsSession(JCSMPProperties jcsmpProperties) {
        hangAConnect(jcsmpProperties);
        JCSMPSessionConfiguration context = aContext();

        CompletableFuture<JCSMPSession> session = CompletableFuture.supplyAsync(() -> sessionOf(context, jcsmpProperties));

        assertThat(session).as("a context must not wait for the connect of other properties")
                .succeedsWithin(LONGEST_STEP_THAT_MUST_NOT_WAIT);
        assertThat(session.join().isClosed()).isFalse();
    }

    @Test
    public void aClosingContext_whileAnotherConnectHangs_closesItsSession(JCSMPProperties jcsmpProperties) {
        JCSMPSessionConfiguration closingContext = aContext();
        JCSMPSession session = sessionOf(closingContext, jcsmpProperties);
        hangAConnect(jcsmpProperties);

        CompletableFuture<Void> closing = CompletableFuture.runAsync(closingContext::destroy);

        assertThat(closing).as("a closing context must not wait for a connect").succeedsWithin(LONGEST_STEP_THAT_MUST_NOT_WAIT);
        assertThat(session.isClosed()).isTrue();
    }

    /**
     * The context drops its share while the connect still runs, and the failing connect then gives back a share that is
     * already gone. Its own failure is the only error that may come out of either.
     */
    @Test
    public void aClosingContext_whileItsOwnConnectHangs_closesWithoutError(JCSMPProperties jcsmpProperties) {
        JCSMPSessionConfiguration hangingContext = hangAConnect(jcsmpProperties);

        CompletableFuture<Void> closing = CompletableFuture.runAsync(hangingContext::destroy);

        assertThat(closing).as("a context must close while its own connect still waits").succeedsWithin(LONGEST_STEP_THAT_MUST_NOT_WAIT);
        tokenServerAnswers.set(true);
        assertThat(hangingConnect).failsWithin(HANGING_CONNECT_ENDS_WITHIN)
                .withThrowableThat().withRootCauseInstanceOf(InvalidPropertiesException.class);
    }

    @Test
    public void aContextWhoseConnectFailed_closesWithoutError(JCSMPProperties jcsmpProperties) {
        JCSMPSessionConfiguration failedContext = aContext();
        assertThatThrownBy(() -> failedContext.jcsmpSession(propertiesThatAskForAToken(jcsmpProperties),
                Optional.empty(), Optional.empty(), Optional.of(() -> "not-a-token")))
                .hasRootCauseInstanceOf(InvalidPropertiesException.class);

        assertThatCode(failedContext::destroy).as("a context whose connect failed must close cleanly")
                .doesNotThrowAnyException();
    }

    @Test
    public void testTheLastContextThatClosesClosesTheSession(JCSMPProperties jcsmpProperties) {
        JCSMPSessionConfiguration firstContext = aContext();
        JCSMPSessionConfiguration lastContext = aContext();
        JCSMPSession session = sessionOf(firstContext, jcsmpProperties);
        sessionOf(lastContext, jcsmpProperties);

        firstContext.destroy();
        assertThat(session.isClosed()).as("another context still holds the session").isFalse();

        lastContext.destroy();
        assertThat(session.isClosed()).isTrue();
    }

    @Test
    public void testAContextOpenedAfterTheLastOneClosedConnectsANewSession(JCSMPProperties jcsmpProperties) {
        JCSMPSessionConfiguration closedContext = aContext();
        JCSMPSession closedSession = sessionOf(closedContext, jcsmpProperties);
        closedContext.destroy();

        JCSMPSession newSession = sessionOf(aContext(), jcsmpProperties);

        assertThat(newSession).isNotSameAs(closedSession);
        assertThat(newSession.isClosed()).isFalse();
    }

    @Test
    public void testADestroyedBinderLeavesTheSessionOpen(JCSMPProperties jcsmpProperties) {
        SolaceMessageChannelBinder binder = aBinderOn(sessionOf(aContext(), jcsmpProperties));
        JCSMPSession session = sessionOf(aContext(), jcsmpProperties);

        binder.destroy();

        assertThat(session.isClosed()).as("the binders of other contexts may still use the session").isFalse();
    }

    @Test
    public void testAClosedContextNoLongerReceivesTheEventsOfTheSession(JCSMPProperties jcsmpProperties) {
        JCSMPSessionConfiguration closingContext = aContext();
        SolaceBinderHealthContributor healthOfTheClosingContext = new SolaceHealthIndicatorsConfiguration().solaceBinderHealthContributor();
        sessionEventsSeenBy(closingContext, healthOfTheClosingContext, jcsmpProperties);
        SolaceBinderHealthContributor healthOfTheStayingContext = new SolaceHealthIndicatorsConfiguration().solaceBinderHealthContributor();
        JCSMPSessionEventHandler sessionEvents = sessionEventsSeenBy(aContext(), healthOfTheStayingContext, jcsmpProperties);

        closingContext.destroy();
        sessionEvents.handleEvent(aSessionThatWentDown());

        assertThat(statusOf(healthOfTheStayingContext)).isEqualTo(Status.DOWN);
        assertThat(statusOf(healthOfTheClosingContext)).as("a closed context must not hear from the session any more")
                .isEqualTo(Status.UP);
    }

    private JCSMPSessionConfiguration aContext() {
        JCSMPSessionConfiguration context = new JCSMPSessionConfiguration();
        openContexts.add(context);
        return context;
    }

    private static JCSMPSession sessionOf(JCSMPSessionConfiguration context, JCSMPProperties jcsmpProperties) {
        return context.jcsmpSession(jcsmpProperties, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Starts a context connecting with properties of its own and returns it once that connect waits for its token.
     */
    private JCSMPSessionConfiguration hangAConnect(JCSMPProperties jcsmpProperties) {
        AtomicBoolean tokenRequested = new AtomicBoolean();
        SolaceSessionOAuth2TokenProvider tokenServerThatHangs = () -> {
            tokenRequested.set(true);
            await().atMost(HANGING_CONNECT_ENDS_WITHIN).untilTrue(tokenServerAnswers);
            return "not-a-token";
        };
        JCSMPSessionConfiguration hangingContext = aContext();
        hangingConnect = CompletableFuture.supplyAsync(() -> hangingContext.jcsmpSession(propertiesThatAskForAToken(jcsmpProperties),
                Optional.empty(), Optional.empty(), Optional.of(tokenServerThatHangs)));
        await().atMost(LONGEST_STEP_THAT_MUST_NOT_WAIT).untilTrue(tokenRequested);
        return hangingContext;
    }

    /**
     * OAuth2 over the plain connection of the test broker: the connect asks the token provider first, and JCSMP
     * rejects the connect once it has the token.
     */
    private static JCSMPProperties propertiesThatAskForAToken(JCSMPProperties jcsmpProperties) {
        JCSMPProperties propertiesThatAskForAToken = (JCSMPProperties) jcsmpProperties.clone();
        propertiesThatAskForAToken.setProperty(JCSMPProperties.AUTHENTICATION_SCHEME, JCSMPProperties.AUTHENTICATION_SCHEME_OAUTH2);
        return propertiesThatAskForAToken;
    }

    private static SolaceMessageChannelBinder aBinderOn(JCSMPSession session) {
        return new SolaceMessageChannelBinder(session, new SolaceEndpointProvisioner(session, Optional.empty()),
                new DefaultListableBeanFactory(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Builds the binder the way the binder configuration of a context does, so it works on what that context hands it.
     */
    private SolaceMessageChannelBinder aBinderOf(JCSMPSessionConfiguration context, JCSMPProperties jcsmpProperties)
            throws Exception {
        SolaceMessageChannelBinder binder = new SolaceMessageChannelBinderConfiguration(new SolaceExtendedBindingProperties(),
                sessionOf(context, jcsmpProperties))
                .solaceMessageChannelBinder(context.jcsmpSessionProducerManager(jcsmpProperties, Optional.empty(), Optional.empty(), Optional.empty()),
                        context.jcsmpInboundTopicMessageMultiplexer(jcsmpProperties, Optional.empty(), Optional.empty(), Optional.empty()),
                        context.jcsmpProvisioningProvider(jcsmpProperties, Optional.empty(), Optional.empty(), Optional.empty()),
                        new DefaultListableBeanFactory(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        AnnotationConfigApplicationContext integrationContext = new AnnotationConfigApplicationContext(IntegrationConfig.class);
        openIntegrationContexts.add(integrationContext);
        binder.setApplicationContext(integrationContext);
        binder.afterPropertiesSet();
        return binder;
    }

    private static Binding<MessageChannel> aDirectProducerBinding(SolaceMessageChannelBinder binder, String topicName,
                                                                  DirectChannel output) {
        ExtendedProducerProperties<SolaceProducerProperties> producerProperties =
                new ExtendedProducerProperties<>(new SolaceProducerProperties());
        producerProperties.getExtension().setDeliveryMode(DeliveryMode.DIRECT);
        return binder.bindProducer(topicName, output, producerProperties);
    }

    private BlockingQueue<Message<?>> aTopicConsumerOf(JCSMPSessionConfiguration context, JCSMPProperties jcsmpProperties,
                                                       String topicName) throws Exception {
        BlockingQueue<Message<?>> received = new LinkedBlockingQueue<>();
        DirectChannel input = new DirectChannel();
        input.subscribe(received::add);
        aTopicConsumerBinding(aBinderOf(context, jcsmpProperties), topicName, input);
        return received;
    }

    private void bindATopicConsumerAndClose(JCSMPSessionConfiguration context, JCSMPProperties jcsmpProperties,
                                            String topicName) throws Exception {
        SolaceMessageChannelBinder binder = aBinderOf(context, jcsmpProperties);
        Binding<MessageChannel> binding = aTopicConsumerBinding(binder, topicName, new DirectChannel());
        closeTheWaySpringDoes(context, binder, binding);
    }

    private static Binding<MessageChannel> aTopicConsumerBinding(SolaceMessageChannelBinder binder, String topicName,
                                                                 DirectChannel input) {
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties =
                new ExtendedConsumerProperties<>(new SolaceConsumerProperties());
        consumerProperties.getExtension().setQualityOfService(QualityOfService.AT_MOST_ONCE);
        return binder.bindConsumer(topicName, null, input, consumerProperties);
    }

    private static String aTopicName() {
        return "jcsmp-session-sharing/" + UUID.randomUUID();
    }

    private static void publish(JCSMPSession publishingSession, String topicName, String text) throws JCSMPException {
        TextMessage message = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        message.setText(text);
        publishingSession.getMessageProducer(new SimpleJCSMPEventHandler())
                .send(message, JCSMPFactory.onlyInstance().createTopic(topicName));
    }

    /**
     * Spring stops the bindings of a context first. The binder depends on the session bean of its context, so Spring
     * destroys the binder before the configuration that made the session.
     */
    private static void closeTheWaySpringDoes(JCSMPSessionConfiguration context, SolaceMessageChannelBinder binder,
                                              Binding<?>... bindings) {
        for (Binding<?> binding : bindings) {
            binding.unbind();
        }
        binder.destroy();
        context.destroy();
    }

    private static JCSMPSessionEventHandler sessionEventsSeenBy(JCSMPSessionConfiguration context,
                                                                SolaceBinderHealthContributor health,
                                                                JCSMPProperties jcsmpProperties) {
        SolaceSessionEventHandler healthEventHandler =
                new SolaceHealthIndicatorsConfiguration().solaceSessionEventHandler(jcsmpProperties, null, health);
        return context.jcsmpSessionEventHandler(jcsmpProperties, Optional.of(health), Optional.of(healthEventHandler), Optional.empty());
    }

    private static XMLMessageConsumer receiverOn(JCSMPSession session, Topic topic) throws JCSMPException {
        XMLMessageConsumer consumer = session.getMessageConsumer((XMLMessageListener) null);
        session.addSubscription(topic);
        consumer.start();
        return consumer;
    }

    private static SessionEventArgs aSessionThatWentDown() {
        SessionEventArgs sessionWentDown = Mockito.mock(SessionEventArgs.class);
        Mockito.when(sessionWentDown.getEvent()).thenReturn(SessionEvent.DOWN_ERROR);
        return sessionWentDown;
    }

    private static Status statusOf(SolaceBinderHealthContributor health) {
        return health.getSolaceSessionHealthIndicator().health().getStatus();
    }

    /**
     * The integration infrastructure a binder binds against. Each binder gets its own, as in its own binder context.
     */
    @Configuration
    @EnableIntegration
    static class IntegrationConfig {
    }
}

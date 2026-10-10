package com.solace.spring.cloud.stream.binder.config;

import com.solace.spring.cloud.stream.binder.SolaceMessageChannelBinder;
import com.solace.spring.cloud.stream.binder.config.autoconfigure.JCSMPSessionConfiguration;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.handlers.SolaceSessionEventHandler;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.test.util.SimpleJCSMPEventHandler;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.SessionEvent;
import com.solacesystems.jcsmp.SessionEventArgs;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Spring context of a JVM that connects with the same properties shares one JCSMP session: the application
 * context and the context of its binder, and in a test suite every context Spring keeps cached. Each
 * {@link JCSMPSessionConfiguration} here plays one such context. A context that closes must not take the session
 * away from the others.
 * <p>
 * The session cache is static, so every test closes each context it opened.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(PubSubPlusExtension.class)
public class JCSMPSessionSharingIT {
    private static final int RECEIVE_TIMEOUT_MILLIS = 10_000;

    private final List<JCSMPSessionConfiguration> openContexts = new ArrayList<>();

    @AfterEach
    void closeEveryContext() {
        openContexts.forEach(JCSMPSessionConfiguration::destroy);
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

    private static SolaceMessageChannelBinder aBinderOn(JCSMPSession session) {
        return new SolaceMessageChannelBinder(session, new SolaceEndpointProvisioner(session, Optional.empty()),
                new DefaultListableBeanFactory(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * The binder depends on the session bean of its context, so Spring destroys the binder before the configuration
     * that made the session.
     */
    private static void closeTheWaySpringDoes(JCSMPSessionConfiguration context, SolaceMessageChannelBinder binder) {
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
}

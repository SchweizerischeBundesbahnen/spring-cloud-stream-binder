package com.solace.spring.cloud.stream.binder.health;

import com.solace.spring.cloud.stream.binder.config.SolaceHealthIndicatorsConfiguration;
import com.solace.spring.cloud.stream.binder.config.autoconfigure.JCSMPSessionConfiguration;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.handlers.SolaceSessionEventHandler;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension.JCSMPProperty;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension.JCSMPProxy;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension.ToxiproxyContext;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.action.model.ActionMsgVpnClientDisconnect;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.SessionEvent;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.health.contributor.Status;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pins what the connection health of the binder says while JCSMP loses its session: {@code DOWN} the moment JCSMP
 * starts to reconnect, with no grace period, and {@code UP} the moment it is back. A team sizes the tolerance of its
 * liveness probe on exactly this, and {@code API.md} and the health-indicator example promise it.
 * <p>
 * When JCSMP starts to reconnect depends on how the loss shows. A connection the broker closes, as on a restart or a
 * failover, is seen at once. A silent loss, where packets stop and no socket closes, is seen only when the keep-alive
 * gives up, after {@code KeepAliveIntervalInMillis × KeepAliveLimit}; until then the health stays {@code UP}.
 * <p>
 * The session is the one the binder's own {@link JCSMPSessionConfiguration} connects, against a real broker. The
 * broker closes the connection through SEMP; the silent loss is a Toxiproxy between the two. Nothing is mocked.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(PubSubPlusExtension.class)
class SessionHealthOnConnectionLossIT {
    private static final String RECONNECT_RETRIES = "client_channel_properties." + JCSMPChannelProperties.RECONNECT_RETRIES;
    private static final String KEEP_ALIVE_INTERVAL = "client_channel_properties." + JCSMPChannelProperties.KEEP_ALIVE_INTERVAL_IN_MILLIS;
    private static final String KEEP_ALIVE_LIMIT = "client_channel_properties." + JCSMPChannelProperties.KEEP_ALIVE_LIMIT;
    private static final String RECONNECT_FOREVER = "-1";
    private static final String KEEP_ALIVE_INTERVAL_MILLIS = "1000";
    private static final String KEEP_ALIVES_UNTIL_GIVING_UP = "10";
    private static final Duration KEEP_ALIVE_GIVES_UP_AFTER = Duration.ofMillis(
            Long.parseLong(KEEP_ALIVE_INTERVAL_MILLIS) * Long.parseLong(KEEP_ALIVES_UNTIL_GIVING_UP));
    private static final Duration TIMING_SLACK = Duration.ofSeconds(3);
    private static final Duration A_CLOSED_CONNECTION_IS_SEEN_WITHIN = Duration.ofSeconds(5);
    private static final Duration RECONNECT_ENDS_WITHIN = Duration.ofMinutes(1);

    private final JCSMPSessionConfiguration sessionContext = new JCSMPSessionConfiguration();
    private final SolaceBinderHealthContributor health = new SolaceHealthIndicatorsConfiguration().solaceBinderHealthContributor();
    private final List<SeenEvent> sessionEventsSeen = new CopyOnWriteArrayList<>();
    private ToxiproxyContext silencedNetwork;

    private record SeenEvent(SessionEvent event, Status connectionStatusRightAfterIt) {
    }

    @AfterEach
    void healTheNetworkAndCloseTheSession() throws IOException {
        if (silencedNetwork != null) {
            for (Toxic toxic : silencedNetwork.getProxy().toxics().getAll()) {
                toxic.remove();
            }
        }
        sessionContext.destroy();
    }

    @Test
    void aConnectionTheBrokerCloses_reportsTheConnectionDownTheMomentTheSessionReconnects(
            @JCSMPProperty(key = RECONNECT_RETRIES, value = RECONNECT_FOREVER) JCSMPProperties properties,
            SempV2Api semp) throws Exception {
        JCSMPSession session = connect(properties);

        disconnectByTheBroker(session, semp);

        await().atMost(A_CLOSED_CONNECTION_IS_SEEN_WITHIN).until(() -> seen(SessionEvent.RECONNECTING).isPresent());
        assertThat(seen(SessionEvent.RECONNECTING)).get()
                .extracting(SeenEvent::connectionStatusRightAfterIt).isEqualTo(Status.DOWN);
        assertThat(sessionEventsSeen).extracting(SeenEvent::event)
                .as("JCSMP reconnects forever here, so only its RECONNECTING may turn the health DOWN")
                .doesNotContain(SessionEvent.DOWN_ERROR);
    }

    @Test
    void aReconnect_afterTheBrokerClosedTheConnection_reportsTheConnectionUpTheMomentItSucceeds(
            @JCSMPProperty(key = RECONNECT_RETRIES, value = RECONNECT_FOREVER) JCSMPProperties properties,
            SempV2Api semp) throws Exception {
        JCSMPSession session = connect(properties);

        disconnectByTheBroker(session, semp);

        await().atMost(RECONNECT_ENDS_WITHIN).until(() -> seen(SessionEvent.RECONNECTED).isPresent());
        assertThat(seen(SessionEvent.RECONNECTED)).get()
                .extracting(SeenEvent::connectionStatusRightAfterIt).isEqualTo(Status.UP);
    }

    @Test
    void aSilentConnectionLoss_reportsTheConnectionDownOnlyOnceTheKeepAliveGivesUp(
            @JCSMPProxy
            @JCSMPProperty(key = RECONNECT_RETRIES, value = RECONNECT_FOREVER)
            @JCSMPProperty(key = KEEP_ALIVE_INTERVAL, value = KEEP_ALIVE_INTERVAL_MILLIS)
            @JCSMPProperty(key = KEEP_ALIVE_LIMIT, value = KEEP_ALIVES_UNTIL_GIVING_UP) JCSMPProperties properties,
            @JCSMPProxy ToxiproxyContext network) throws IOException {
        connect(properties);

        network.getProxy().toxics().timeout("nothing-comes-back", ToxicDirection.DOWNSTREAM, 0);
        silencedNetwork = network;

        await().during(KEEP_ALIVE_GIVES_UP_AFTER.minus(TIMING_SLACK))
                .atMost(KEEP_ALIVE_GIVES_UP_AFTER)
                .until(() -> connectionStatus() == Status.UP);
        await().atMost(KEEP_ALIVE_GIVES_UP_AFTER.plus(TIMING_SLACK)).until(() -> connectionStatus() == Status.DOWN);
    }

    /**
     * Connects the way a binder context does. The event recorder is added after the health's own handler, which the
     * session hands each event first, so a recorded status is the one the health shows right after that event.
     */
    private JCSMPSession connect(JCSMPProperties properties) {
        SolaceSessionEventHandler healthEvents =
                new SolaceHealthIndicatorsConfiguration().solaceSessionEventHandler(properties, null, health);
        JCSMPSessionEventHandler sessionEvents = sessionContext.jcsmpSessionEventHandler(properties, Optional.of(health),
                Optional.of(healthEvents), Optional.empty());
        sessionEvents.addSessionEventHandler(eventArgs ->
                sessionEventsSeen.add(new SeenEvent(eventArgs.getEvent(), connectionStatus())));
        JCSMPSession session = sessionContext.jcsmpSession(properties, Optional.of(health), Optional.of(healthEvents),
                Optional.empty());
        assertThat(connectionStatus()).isEqualTo(Status.UP);
        return session;
    }

    private static void disconnectByTheBroker(JCSMPSession session, SempV2Api semp) throws Exception {
        semp.action().doMsgVpnClientDisconnect(new ActionMsgVpnClientDisconnect(),
                (String) session.getProperty(JCSMPProperties.VPN_NAME),
                (String) session.getProperty(JCSMPProperties.CLIENT_NAME));
    }

    private Optional<SeenEvent> seen(SessionEvent event) {
        return sessionEventsSeen.stream().filter(seenEvent -> seenEvent.event() == event).findFirst();
    }

    private Status connectionStatus() {
        return health.getSolaceSessionHealthIndicator().health().getStatus();
    }
}

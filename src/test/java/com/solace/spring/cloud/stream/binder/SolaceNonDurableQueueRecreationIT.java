package com.solace.spring.cloud.stream.binder;

import com.solace.spring.cloud.stream.binder.event.SolaceNonDurableQueueRecreatedEvent;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.test.spring.SpringCloudStreamContext;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension.JCSMPProperty;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension.JCSMPProxy;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension.ToxiproxyContext;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.semp.v2.action.model.ActionMsgVpnClientDisconnect;
import com.solace.test.integration.semp.v2.monitor.model.MonitorMsgVpnQueue;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.MessageChannel;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pins issue #183: the broker drops the temporary queue of an anonymous consumer some time after its client
 * disconnected, JCSMP creates it anew, empty, when the flow rebinds, and the application must learn of it through a
 * {@link SolaceNonDurableQueueRecreatedEvent}, published off the JCSMP dispatcher thread. A queue that survives the
 * disconnect has lost nothing, so its reconnect must stay silent.
 * <p>
 * The connection runs through a proxy, so the test can keep the binder from reconnecting while it has the broker
 * disconnect the client and waits for the broker to drop the queue.
 */
@ExtendWith(PubSubPlusExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
class SolaceNonDurableQueueRecreationIT {
    private static final String RECONNECT_RETRIES = "client_channel_properties." + JCSMPChannelProperties.RECONNECT_RETRIES;
    private static final Duration QUEUE_DROPPED_WITHIN = Duration.ofMinutes(3);
    private static final Duration FLOW_BACK_WITHIN = Duration.ofMinutes(1);
    private static final Duration QUIET_AFTER_RECONNECT = Duration.ofSeconds(10);

    private final List<Recorded> events = new CopyOnWriteArrayList<>();
    private SpringCloudStreamContext context;
    private Binding<MessageChannel> binding;

    private record Recorded(SolaceNonDurableQueueRecreatedEvent event, String thread) {
    }

    @AfterEach
    void tearDown() {
        if (binding != null) {
            binding.unbind();
        }
        if (context != null) {
            context.cleanup();
            context.close();
        }
    }

    @Test
    void aQueueRecreatedAfterTheBrokerDroppedItIsAnnounced(
            @JCSMPProxy @JCSMPProperty(key = RECONNECT_RETRIES, value = "-1") JCSMPSession session,
            @JCSMPProxy ToxiproxyContext proxy,
            SempV2Api semp) throws Exception {
        String queueName = bindAnonymousConsumer(session, semp, "recreated-in-0");

        proxy.getProxy().setConnectionCut(true);
        disconnectClient(session, semp);
        await().atMost(QUEUE_DROPPED_WITHIN).pollInterval(Duration.ofSeconds(2))
                .until(() -> findQueue(session, semp, queueName).isEmpty());
        proxy.getProxy().setConnectionCut(false);

        await().atMost(FLOW_BACK_WITHIN).until(() -> !events.isEmpty());
        assertThat(events).singleElement().satisfies(recorded -> {
            assertThat(recorded.event()).isEqualTo(new SolaceNonDurableQueueRecreatedEvent("recreated-in-0", queueName));
            assertThat(recorded.thread()).doesNotContain("Dispatcher");
        });
    }

    @Test
    void aQueueThatSurvivedTheDisconnectIsNotAnnounced(
            @JCSMPProxy @JCSMPProperty(key = RECONNECT_RETRIES, value = "-1") JCSMPSession session,
            @JCSMPProxy ToxiproxyContext proxy,
            SempV2Api semp) throws Exception {
        String queueName = bindAnonymousConsumer(session, semp, "survived-in-0");

        proxy.getProxy().setConnectionCut(true);
        disconnectClient(session, semp);
        await().atMost(FLOW_BACK_WITHIN).until(() -> bindCount(session, semp, queueName) == 0);
        proxy.getProxy().setConnectionCut(false);

        await().atMost(FLOW_BACK_WITHIN).until(() -> bindCount(session, semp, queueName) == 1);
        await().during(QUIET_AFTER_RECONNECT).atMost(QUIET_AFTER_RECONNECT.plusSeconds(5))
                .until(events::isEmpty);
    }

    private String bindAnonymousConsumer(JCSMPSession session, SempV2Api semp, String bindingName) throws Exception {
        context = new SpringCloudStreamContext(session, semp);
        context.before();
        SolaceTestBinder binder = context.getBinder();
        binder.getApplicationContext().addApplicationListener((ApplicationListener<ApplicationEvent>) event -> {
            if (event instanceof PayloadApplicationEvent<?> payloadEvent
                    && payloadEvent.getPayload() instanceof SolaceNonDurableQueueRecreatedEvent recreated) {
                events.add(new Recorded(recreated, Thread.currentThread().getName()));
            }
        });

        DirectChannel input = context.createBindableChannel("input", new BindingProperties());
        ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties = context.createConsumerProperties();
        consumerProperties.populateBindingName(bindingName);
        binding = binder.bindConsumer(RandomStringUtils.randomAlphanumeric(10), null, input, consumerProperties);

        String queueName = binder.getConsumerQueueName(binding);
        await().atMost(FLOW_BACK_WITHIN).until(() -> bindCount(session, semp, queueName) == 1);
        return queueName;
    }

    private static void disconnectClient(JCSMPSession session, SempV2Api semp) throws Exception {
        semp.action().doMsgVpnClientDisconnect(new ActionMsgVpnClientDisconnect(), vpn(session),
                (String) session.getProperty(JCSMPProperties.CLIENT_NAME));
    }

    private static Optional<MonitorMsgVpnQueue> findQueue(JCSMPSession session, SempV2Api semp, String queueName)
            throws Exception {
        return semp.monitor().getMsgVpnQueues(vpn(session), 100, null, null, null).getData().stream()
                .filter(queue -> queue.getQueueName().equals(queueName))
                .findAny();
    }

    private static long bindCount(JCSMPSession session, SempV2Api semp, String queueName) throws Exception {
        if (findQueue(session, semp, queueName).isEmpty()) {
            return 0;
        }
        return semp.monitor().getMsgVpnQueueTxFlows(vpn(session), queueName, 100, null, null, null).getData().size();
    }

    private static String vpn(JCSMPSession session) {
        return (String) session.getProperty(JCSMPProperties.VPN_NAME);
    }
}

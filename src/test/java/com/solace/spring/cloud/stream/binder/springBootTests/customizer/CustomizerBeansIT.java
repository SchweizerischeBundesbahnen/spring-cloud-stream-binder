package com.solace.spring.cloud.stream.binder.springBootTests.customizer;

import com.solace.spring.cloud.stream.binder.extension.BinderIntegrationTest;
import com.solace.spring.cloud.stream.binder.inbound.queue.JCSMPInboundQueueMessageProducer;
import com.solace.spring.cloud.stream.binder.outbound.JCSMPOutboundMessageHandler;
import com.solace.spring.cloud.stream.binder.springBootTests.customizer.CustomizerApp.CustomizedEndpoint;
import com.solace.spring.cloud.stream.binder.springBootTests.customizer.CustomizerApp.CustomizedHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A customizer bean is declared in the application context, but the binder that has to apply it is
 * built in a child context of its own. Only a real application proves the bean crosses that
 * boundary, reaches the binder and is applied to a binding against a live broker — asserting that
 * the configuration calls the setter proves the line exists, nothing more.
 */
@Isolated
@BinderIntegrationTest
@SpringBootTest(classes = CustomizerApp.class)
@ActiveProfiles("customizer-beans")
@DirtiesContext
public class CustomizerBeansIT {

    @Autowired
    private StreamBridge streamBridge;

    @Test
    void aDeclaredConsumerEndpointCustomizer_isAppliedToTheConsumerBinding() {
        await().atMost(30, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(200))
                .until(() -> CustomizerApp.CUSTOMIZED_ENDPOINT.get() != null);

        CustomizedEndpoint customized = CustomizerApp.CUSTOMIZED_ENDPOINT.get();
        assertThat(customized.endpoint()).isInstanceOf(JCSMPInboundQueueMessageProducer.class);
        assertThat(customized.destination()).isEqualTo("customizer/endpoint/topic");
        assertThat(customized.group()).isEqualTo("customizer-group");
    }

    @Test
    void aDeclaredProducerMessageHandlerCustomizer_isAppliedToTheProducerBinding() {
        streamBridge.send("customizedProducer-out-0", "trigger the producer binding");

        await().atMost(30, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(200))
                .until(() -> CustomizerApp.CUSTOMIZED_HANDLER.get() != null);

        CustomizedHandler customized = CustomizerApp.CUSTOMIZED_HANDLER.get();
        assertThat(customized.handler()).isInstanceOf(JCSMPOutboundMessageHandler.class);
        assertThat(customized.destination()).isEqualTo("customizer/handler/topic");
    }
}

package com.solace.spring.cloud.stream.binder;

import com.solace.spring.cloud.stream.binder.outbound.JCSMPOutboundMessageHandler;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solacesystems.jcsmp.Context;
import com.solacesystems.jcsmp.JCSMPSession;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.messaging.MessageHandler;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A {@code ProducerMessageHandlerCustomizer} bean only reaches the outbound handler if the binder is
 * given the customizer. {@code SolaceMessageChannelBinderConfiguration} wires it; this asserts the
 * binder then really invokes it.
 */
class SolaceMessageChannelBinderCustomizerTest {

    /**
     * {@code customizeProducerMessageHandler} is protected on the Spring base class, so a subclass has
     * to open it up for the test.
     */
    private static class CustomizableBinder extends SolaceMessageChannelBinder {
        CustomizableBinder(JCSMPSession session, Context context, SolaceEndpointProvisioner provisioner, BeanFactory beanFactory) {
            super(session, context, provisioner, beanFactory, Optional.empty(), Optional.empty(), Optional.empty());
        }

        void customize(MessageHandler handler, String destinationName) {
            customizeProducerMessageHandler(handler, destinationName);
        }
    }

    @Test
    void producerMessageHandlerCustomizerIsAppliedToTheOutboundHandler() {
        CustomizableBinder binder = new CustomizableBinder(
                Mockito.mock(JCSMPSession.class),
                Mockito.mock(Context.class),
                Mockito.mock(SolaceEndpointProvisioner.class),
                Mockito.mock(BeanFactory.class));

        AtomicReference<JCSMPOutboundMessageHandler> customizedHandler = new AtomicReference<>();
        AtomicReference<String> customizedDestination = new AtomicReference<>();
        binder.setProducerMessageHandlerCustomizer(
                (JCSMPOutboundMessageHandler handler, String destinationName) -> {
                    customizedHandler.set(handler);
                    customizedDestination.set(destinationName);
                });

        JCSMPOutboundMessageHandler handler = Mockito.mock(JCSMPOutboundMessageHandler.class);
        binder.customize(handler, "example/topic");

        assertSame(handler, customizedHandler.get());
        assertEquals("example/topic", customizedDestination.get());
    }

    @Test
    void bindersWithoutACustomizerLeaveTheOutboundHandlerUntouched() {
        CustomizableBinder binder = new CustomizableBinder(
                Mockito.mock(JCSMPSession.class),
                Mockito.mock(Context.class),
                Mockito.mock(SolaceEndpointProvisioner.class),
                Mockito.mock(BeanFactory.class));

        JCSMPOutboundMessageHandler handler = Mockito.mock(JCSMPOutboundMessageHandler.class);
        binder.customize(handler, "example/topic");

        Mockito.verifyNoInteractions(handler);
    }
}

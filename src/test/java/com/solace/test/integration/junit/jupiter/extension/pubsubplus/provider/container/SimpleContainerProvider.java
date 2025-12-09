package com.solace.test.integration.junit.jupiter.extension.pubsubplus.provider.container;

import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.testcontainer.PubSubPlusContainer;
import com.solacesystems.jcsmp.JCSMPProperties;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import java.util.function.Supplier;

/**
 * A PubSubPlusContainer provider using the default settings.
 */
public class SimpleContainerProvider implements PubSubPlusExtension.ContainerProvider {
    public Supplier<PubSubPlusContainer> containerSupplier(ExtensionContext extensionContext) {
        return () -> {
            PubSubPlusContainer container = new PubSubPlusContainer();
            container.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger(PubSubPlusContainer.class)));
            return container;
        };
    }

    public void containerPostStart(ExtensionContext extensionContext, PubSubPlusContainer container) {
    }

    public JCSMPProperties createJcsmpProperties(ExtensionContext extensionContext, PubSubPlusContainer container) {
        JCSMPProperties jcsmpProperties = new JCSMPProperties();
        jcsmpProperties.setProperty(JCSMPProperties.HOST, container.getOrigin(PubSubPlusContainer.Port.SMF));
        jcsmpProperties.setProperty(JCSMPProperties.USERNAME, "default");
        jcsmpProperties.setProperty(JCSMPProperties.VPN_NAME, "default");
        jcsmpProperties.setIntegerProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE, 255);
        jcsmpProperties.setIntegerProperty(JCSMPProperties.PUB_ACK_WINDOW_SIZE, 255);
        jcsmpProperties.setIntegerProperty(JCSMPProperties.SUB_ACK_TIME, 1500);
        jcsmpProperties.setIntegerProperty(JCSMPProperties.PUB_ACK_TIME, 60000);
        return jcsmpProperties;
    }

    public SempV2Api createSempV2Api(ExtensionContext extensionContext, PubSubPlusContainer container) {
        return new SempV2Api(container.getOrigin(PubSubPlusContainer.Port.SEMP),
                container.getAdminUsername(),
                container.getAdminPassword());
    }
}

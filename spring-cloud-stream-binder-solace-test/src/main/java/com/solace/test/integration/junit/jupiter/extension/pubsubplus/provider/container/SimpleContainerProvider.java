package com.solace.test.integration.junit.jupiter.extension.pubsubplus.provider.container;

import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.testcontainer.PubSubPlusContainer;
import com.solacesystems.jcsmp.JCSMPProperties;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.function.Supplier;

/**
 * A PubSubPlusContainer provider using the default settings.
 */
public class SimpleContainerProvider implements PubSubPlusExtension.ContainerProvider {
	public Supplier<PubSubPlusContainer> containerSupplier(ExtensionContext extensionContext) {
		return PubSubPlusContainer::new;
	}

	public void containerPostStart(ExtensionContext extensionContext, PubSubPlusContainer container) {}

	public JCSMPProperties createJcsmpProperties(ExtensionContext extensionContext, PubSubPlusContainer container) {
		JCSMPProperties jcsmpProperties = new JCSMPProperties();
		jcsmpProperties.setProperty(JCSMPProperties.HOST, container.getOrigin(PubSubPlusContainer.Port.SMF));
		jcsmpProperties.setProperty(JCSMPProperties.USERNAME, "default");
		jcsmpProperties.setProperty(JCSMPProperties.VPN_NAME, "default");
		return jcsmpProperties;
	}

	public SempV2Api createSempV2Api(ExtensionContext extensionContext, PubSubPlusContainer container) {
		return new SempV2Api(container.getOrigin(PubSubPlusContainer.Port.SEMP),
				container.getAdminUsername(),
				container.getAdminPassword());
	}
}

package com.solace.test.integration.junit.jupiter.extension.pubsubplus.provider;

import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solacesystems.jcsmp.JCSMPProperties;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Add a {@value #PROPERTIES_FILENAME} resource file to point to a Solace PubSub+ broker:
 * <pre><code>
 * client.host=http://localhost:55555
 * client.username=default
 * client.password=default
 * client.vpn=default
 *
 * semp.host=http://localhost:8080
 * semp.username=admin
 * semp.password=admin
 *
 * enable=true
 * </code></pre>
 */
public class PubSubPlusFileProvider implements PubSubPlusExtension.ExternalProvider {
	private static final String PROPERTIES_FILENAME = "solace.properties";
	private static final Namespace NAMESPACE = Namespace.create(PubSubPlusFileProvider.class);

	@Override
	public boolean isValid(ExtensionContext extensionContext) {
		if (Optional.ofNullable(getProperties(extensionContext))
				.map(p -> p.getProperty("enable", "true"))
				.map(Boolean::parseBoolean)
				.orElse(false)) {
			return true;
		} else if (PubSubPlusFileProvider.class.getClassLoader().getResource(PROPERTIES_FILENAME) != null) {
			init(extensionContext);
			return Boolean.parseBoolean(getProperties(extensionContext).getProperty("enable", "true"));
		} else {
			return false;
		}
	}

	@Override
	public void init(ExtensionContext extensionContext) {
		extensionContext.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent(Properties.class, c -> {
			Properties properties = new Properties();
			try (InputStream stream = PubSubPlusFileProvider.class.getClassLoader()
					.getResourceAsStream(PROPERTIES_FILENAME)) {
				properties.load(stream);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			return properties;
		}, Properties.class);
	}

	@Override
	public JCSMPProperties createJCSMPProperties(ExtensionContext extensionContext) {
		Properties properties = getProperties(extensionContext);
		JCSMPProperties jcsmpProperties = new JCSMPProperties();
		jcsmpProperties.setProperty(JCSMPProperties.HOST, properties.getProperty("client.host"));
		jcsmpProperties.setProperty(JCSMPProperties.USERNAME, properties.getProperty("client.username"));
		jcsmpProperties.setProperty(JCSMPProperties.PASSWORD, properties.getProperty("client.password"));
		jcsmpProperties.setProperty(JCSMPProperties.VPN_NAME, properties.getProperty("client.vpn"));
		return jcsmpProperties;
	}

	@Override
	public SempV2Api createSempV2Api(ExtensionContext extensionContext) {
		Properties properties = getProperties(extensionContext);
		return new SempV2Api(properties.getProperty("semp.host"), properties.getProperty("semp.username"),
				properties.getProperty("semp.password"));
	}

	private Properties getProperties(ExtensionContext extensionContext) {
		return extensionContext.getRoot().getStore(NAMESPACE).get(Properties.class, Properties.class);
	}
}

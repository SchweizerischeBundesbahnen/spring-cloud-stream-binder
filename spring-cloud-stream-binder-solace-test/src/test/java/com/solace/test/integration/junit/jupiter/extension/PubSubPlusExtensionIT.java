package com.solace.test.integration.junit.jupiter.extension;

import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension.JCSMPProperty;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension.JCSMPProxy;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension.Store;
import com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension.ToxiproxyContext;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.Queue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(PubSubPlusExtension.class)
@ExtendWith(PubSubPlusExtensionIT.TestExtension.class)
public class PubSubPlusExtensionIT {
	@ParameterizedTest
	@ValueSource(strings = {JCSMPProperties.HOST, JCSMPProperties.VPN_NAME, JCSMPProperties.USERNAME,
			JCSMPProperties.PASSWORD})
	public void testJCSMPSessionProperty(String jcsmpProperty,
										 JCSMPProperties jcsmpProperties,
										 JCSMPSession jcsmpSession) {
		assertFalse(jcsmpSession.isClosed(), "Session is not connected");
		assertEquals(jcsmpProperties.getStringProperty(jcsmpProperty), jcsmpSession.getProperty(jcsmpProperty));
	}

	@Test
	public void testDuplicateParameterTypes(
			@JCSMPProperty(key = JCSMPProperties.PASSWORD, value = "testabc") JCSMPSession defaultJcsmpSession,
			Queue defaultQueue,
			SempV2Api defaultSempV2Api,
			@JCSMPProxy JCSMPSession defaultProxyJcsmpSession,
			@JCSMPProxy ToxiproxyContext defaultToxiproxyContext,
			@Store("other") JCSMPSession otherJcsmpSession,
			@Store("other") Queue otherQueue,
			@Store("other") SempV2Api otherSempV2Api,
			@Store("other") @JCSMPProxy JCSMPSession otherProxyJcsmpSession,
			@Store("other") @JCSMPProxy ToxiproxyContext otherToxiproxyContext) {
		assertNotEquals(defaultJcsmpSession, otherJcsmpSession);
		assertNotEquals(defaultQueue, otherQueue);
		assertNotEquals(defaultSempV2Api, otherSempV2Api);
		assertNotEquals(defaultProxyJcsmpSession, otherProxyJcsmpSession);
		assertNotEquals(defaultToxiproxyContext, otherToxiproxyContext);

		assertNotEquals(defaultJcsmpSession.getProperty(JCSMPProperties.PASSWORD),
				otherJcsmpSession.getProperty(JCSMPProperties.PASSWORD));

		assertTrue(((String) defaultProxyJcsmpSession.getProperty(JCSMPProperties.HOST))
				.contains(String.valueOf(defaultToxiproxyContext.getProxy().getProxyPort())));
		assertTrue(((String) otherProxyJcsmpSession.getProperty(JCSMPProperties.HOST))
				.contains(String.valueOf(otherToxiproxyContext.getProxy().getProxyPort())));
		assertNotEquals(defaultToxiproxyContext.getProxy().getProxyPort(),
				otherToxiproxyContext.getProxy().getProxyPort());
	}

	@Test
	public void testJCSMPProxy(JCSMPProperties jcsmpProperties,
							   @JCSMPProxy JCSMPSession jcsmpSession,
							   @JCSMPProxy ToxiproxyContext toxiproxyContext) {
		assertNotEquals(jcsmpProperties.getStringProperty(JCSMPProperties.HOST),
				jcsmpSession.getProperty(JCSMPProperties.HOST));
		assertEquals(jcsmpSession.getProperty(JCSMPProperties.HOST), String.format("%s://%s:%s",
				URI.create(jcsmpProperties.getStringProperty(JCSMPProperties.HOST)).getScheme(),
				toxiproxyContext.getProxy().getContainerIpAddress(),
				toxiproxyContext.getProxy().getProxyPort()));
	}

	@Test
	public void testExtensionIntegration(TestExtension.PubSubPlusContext pubSubPlusContext,
										 JCSMPProperties jcsmpProperties,
										 JCSMPSession jcsmpSession,
										 SempV2Api sempV2Api,
										 Queue queue,
										 @JCSMPProxy ToxiproxyContext toxiproxyContext) {
		assertEquals(jcsmpProperties.toProperties(), pubSubPlusContext.getJcsmpProperties().toProperties());
		assertEquals(jcsmpSession, pubSubPlusContext.getJcsmpSession());
		assertEquals(sempV2Api, pubSubPlusContext.getSempV2Api());
		assertEquals(queue, pubSubPlusContext.getQueue());
		assertEquals(toxiproxyContext, pubSubPlusContext.getToxiproxyContext());
	}

	@Test
	public void testOverrideOneJCSMPProperty(@JCSMPProperty(key = JCSMPProperties.PASSWORD, value = "testabc")
														 JCSMPProperties jcsmpProperties) {
		assertEquals("testabc", jcsmpProperties.getStringProperty(JCSMPProperties.PASSWORD));
	}

	@Test
	public void testOverrideJCSMPProperty(
			@JCSMPProperty(key = JCSMPProperties.PASSWORD, value = "testabc")
			@JCSMPProperty(key = "client_channel_properties." + JCSMPChannelProperties.CONNECT_RETRIES, value = "1")
			@JCSMPProperty(key = JCSMPProperties.SUB_ACK_WINDOW_SIZE, value = "1") JCSMPProperties jcsmpProperties) {
		assertEquals("testabc", jcsmpProperties.getStringProperty(JCSMPProperties.PASSWORD));
		assertEquals(1, jcsmpProperties.getIntegerProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE));
		assertEquals(1, ((JCSMPChannelProperties) jcsmpProperties
				.getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES)).getConnectRetries());
	}

	@Test
	public void testOverrideJCSMPSessionProperty(
			@JCSMPProperty(key = JCSMPProperties.PASSWORD, value = "testabc")
			@JCSMPProperty(key = "client_channel_properties." + JCSMPChannelProperties.CONNECT_RETRIES, value = "1")
			@JCSMPProperty(key = JCSMPProperties.SUB_ACK_WINDOW_SIZE, value = "1") JCSMPSession jcsmpSession) {
		assertEquals("testabc", jcsmpSession.getProperty(JCSMPProperties.PASSWORD));
		assertEquals(1, jcsmpSession.getProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE));
		assertEquals(1, ((JCSMPChannelProperties) jcsmpSession.getProperty(
				JCSMPProperties.CLIENT_CHANNEL_PROPERTIES)).getConnectRetries());
	}

	@Test
	public void testOverrideJCSMPProxyProperty(
			@JCSMPProperty(key = JCSMPProperties.PASSWORD, value = "testabc")
			@JCSMPProperty(key = "client_channel_properties." + JCSMPChannelProperties.CONNECT_RETRIES, value = "1")
			@JCSMPProperty(key = JCSMPProperties.SUB_ACK_WINDOW_SIZE, value = "1")
			@JCSMPProxy JCSMPProperties jcsmpProperties) {
		assertEquals("testabc", jcsmpProperties.getProperty(JCSMPProperties.PASSWORD));
		assertEquals(1, jcsmpProperties.getProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE));
		assertEquals(1, ((JCSMPChannelProperties) jcsmpProperties.getProperty(
				JCSMPProperties.CLIENT_CHANNEL_PROPERTIES)).getConnectRetries());
	}

	@Test
	public void testOverrideJCSMPProxySessionProperty(
			@JCSMPProperty(key = JCSMPProperties.PASSWORD, value = "testabc")
			@JCSMPProperty(key = "client_channel_properties." + JCSMPChannelProperties.CONNECT_RETRIES, value = "1")
			@JCSMPProperty(key = JCSMPProperties.SUB_ACK_WINDOW_SIZE, value = "1")
			@JCSMPProxy JCSMPSession jcsmpSession) {
		assertEquals("testabc", jcsmpSession.getProperty(JCSMPProperties.PASSWORD));
		assertEquals(1, jcsmpSession.getProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE));
		assertEquals(1, ((JCSMPChannelProperties) jcsmpSession.getProperty(
				JCSMPProperties.CLIENT_CHANNEL_PROPERTIES)).getConnectRetries());
	}

	static class TestExtension implements ParameterResolver {

		@Override
		public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
				throws ParameterResolutionException {
			return parameterContext.getParameter().getType().isAssignableFrom(PubSubPlusContext.class);
		}

		@Override
		public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
				throws ParameterResolutionException {
			return new PubSubPlusContext(
					PubSubPlusExtension.getJCSMPProperties(extensionContext),
					PubSubPlusExtension.getJCSMPSession(extensionContext),
					PubSubPlusExtension.getSempV2Api(extensionContext),
					PubSubPlusExtension.getQueue(extensionContext),
					PubSubPlusExtension.getToxiproxyContext(extensionContext, new JCSMPProxy() {
						@Override
						public Class<? extends Annotation> annotationType() {
							return JCSMPProxy.class;
						}
					}));
		}

		private static class PubSubPlusContext {
			private final JCSMPProperties jcsmpProperties;
			private final JCSMPSession jcsmpSession;
			private final SempV2Api sempV2Api;
			private final Queue queue;
			private final ToxiproxyContext toxiproxyContext;

			private PubSubPlusContext(JCSMPProperties jcsmpProperties, JCSMPSession jcsmpSession, SempV2Api sempV2Api,
									  Queue queue, ToxiproxyContext toxiproxyContext) {
				this.jcsmpProperties = jcsmpProperties;
				this.jcsmpSession = jcsmpSession;
				this.sempV2Api = sempV2Api;
				this.queue = queue;
				this.toxiproxyContext = toxiproxyContext;
			}

			public JCSMPProperties getJcsmpProperties() {
				return jcsmpProperties;
			}

			public JCSMPSession getJcsmpSession() {
				return jcsmpSession;
			}

			public SempV2Api getSempV2Api() {
				return sempV2Api;
			}

			public Queue getQueue() {
				return queue;
			}

			public ToxiproxyContext getToxiproxyContext() {
				return toxiproxyContext;
			}
		}
	}
}

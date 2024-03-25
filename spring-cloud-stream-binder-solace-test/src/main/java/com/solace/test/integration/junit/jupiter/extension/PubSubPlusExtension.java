package com.solace.test.integration.junit.jupiter.extension;

import com.solace.test.integration.junit.jupiter.extension.pubsubplus.provider.PubSubPlusFileProvider;
import com.solace.test.integration.junit.jupiter.extension.pubsubplus.provider.container.SimpleContainerProvider;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solace.test.integration.testcontainer.PubSubPlusContainer;
import com.solacesystems.jcsmp.EndpointProperties;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.Queue;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.ToxiproxyContainer.ContainerProxy;
import org.testcontainers.utility.DockerImageName;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>Junit 5 extension for using Solace PubSub+.</p>
 * <p>By default a Solace PubSub+ container will be auto-provisioned only if necessary.</p>
 *
 * <h1>PubSub+ Resource Lifecycle</h1>
 * <p>The lifecycle of resources (e.g. sessions and endpoints) created through this extension are bound to the earliest
 * JUnit context that they were defined in.</p>
 * <p>e.g.:</p>
 * <ul>
 *     <li>if a resource was only defined as a parameter of a {@code @Test} method,
 *     then that resource's lifecycle is bound to the test, and will be cleaned up after the test completes.</li>
 *     <li>If a resource was only defined as a parameter of a {@code @BeforeAll} method, then the resource's
 *     lifecycle is bound to the class, and will be cleaned up after the test class completes.</li>
 *     <li>If you had the same resource defined as parameters of both {@code @BeforeAll} and {@code @Test}, then
 *     both methods will use the same resource, and its lifecycle would be bound to the test class (i.e. the earliest
 *     definition of the resource).</li>
 * </ul>
 * <p>Note that the only exception to this is the PubSub+ broker container. Which, if created, is bound to JUnit's
 * root context. i.e. it will be cleaned up with the JVM.</p>
 *
 * <H1>Basic Usage:</h1>
 * <pre><code>
 * {@literal @}ExtendWith(PubSubPlusExtension.class)
 * public class Test {
 * 	// At least one of these arguments must be defined on the test function for the session and broker to be provisioned.
 * 	{@literal @}Test
 * 	public void testMethod(JCSMPSession session, SempV2Api sempV2Api, Queue queue, JCSMPProperties properties) {
 * 		// Test logic using JCSMP
 * 	}
 * }
 * </code></pre>
 * <p>{@code JCSMPProperties} & {@code JCSMPSession} parameters can be annotated with
 * {@link JCSMPProperty @JCSMPProperty} to override individual JCSMP properties.</p>
 * <p>If the test needs multiple parameters of the same type (e.g. 2 {@code JCSMPSession}s),
 * use the {@link Store @Store} annotation.</p>
 *
 * <h1>To use an External PubSub+ Broker:</h1>
 * <p>First, implement the {@link ExternalProvider} interface.</p>
 * <p>Then add the
 * {@code META-INF/services/com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension$ExternalProvider}
 * resource file to configure external PubSub+ providers:</p>
 * <pre><code>
 * com.test.OtherExternalProvider
 * com.solace.test.integration.junit.jupiter.extension.pubsubplus.provider.PubSubPlusFileProvider
 * </code></pre>
 * <p>Providers are resolved in order of top-to-bottom.</p>
 * <p>By default, {@link PubSubPlusFileProvider} is enabled as the only external provider.</p>
 *
 * <h1>Customize PubSub+ Docker Container:</h1>
 * <p>Either extend {@link SimpleContainerProvider} or implement {@link ContainerProvider}. Then add the
 * {@code META-INF/services/com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension$ContainerProvider}
 * resource file:</p>
 * <pre><code>
 * com.test.OtherContainerProvider
 * </code></pre>
 * <p>Only one container provider is supported. If multiple are detected, the first found provider will be used.</p>
 * <p>By default, {@link SimpleContainerProvider} is enabled as the container provider.</p>
 *
 * <h1>Toxiproxy Integration:</h1>
 * <p>To retrieve a proxied JCSMP session, annotate your {@code JCSMPSession} parameter with {@code @JCSMPProxy}.
 * To get the proxy itself, add the {@code ToxiproxyContext} parameter that's also annotated with {@code @JCSMPProxy}:
 * </p>
 *<pre><code>
 * {@literal @}ExtendWith(PubSubPlusExtension.class)
 * public class Test {
 * 	{@literal @}Test
 * 	public void testMethod({@literal @}JCSMPProxy JCSMPSession jcsmpSession, {@literal @}JCSMPProxy ToxiproxyContext jcsmpProxyContext) {
 * 		// add a toxic to the JCSMP proxy
 * 		Latency toxic = jcsmpProxyContext.getProxy().toxics()
 * 			.latency("lag", ToxicDirection.UPSTREAM, TimeUnit.SECONDS.toMillis(5));
 *
 * 		// get a host that a container within the docker network can use to access the proxy
 * 		String toxicJCSMPNetworkHost = String.format("tcp://%s:%s", jcsmpProxyContext.getDockerNetworkAlias(),
 * 			jcsmpProxyContext.getProxy().getOriginalProxyPort())
 *
 * 		// Test logic using toxic JCSMP session.
 * 		// Is already preconfigured to use the proxy since the parameter is annotated by {@literal @}JCSMPProxy.
 * 	}
 * }
 * </code></pre>
 */
public class PubSubPlusExtension implements ParameterResolver {
	private static final Logger LOG = LoggerFactory.getLogger(PubSubPlusExtension.class);
	private static final Namespace EXT_NAMESPACE = Namespace.create(PubSubPlusExtension.class);
	private static final String TOXIPROXY_NAMESPACE_POSTFIX = "toxiproxy";
	private static final String TOXIPROXY_NETWORK_ALIAS = "toxiproxy";
	private static final ContainerProvider CONTAINER_PROVIDER;
	private static final List<ExternalProvider> EXTERNAL_PROVIDERS;

	static {
		CONTAINER_PROVIDER = Optional.of(ServiceLoader.load(ContainerProvider.class).iterator())
				.filter(Iterator::hasNext)
				.map(Iterator::next)
				.orElseGet(SimpleContainerProvider::new);

		List<ExternalProvider> externalProvidersList = new ArrayList<>();
		ServiceLoader.load(ExternalProvider.class).iterator().forEachRemaining(externalProvidersList::add);
		EXTERNAL_PROVIDERS = !externalProvidersList.isEmpty() ?
				Collections.unmodifiableList(externalProvidersList) :
				Collections.singletonList(new PubSubPlusFileProvider());
	}

	@Override
	public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
			throws ParameterResolutionException {
		return supportsParameter(parameterContext.getParameter().getType(),
				parameterContext.getParameter().getAnnotations());
	}

	private static boolean supportsParameter(Class<?> paramType, Annotation... annotations)
			throws ParameterResolutionException {
		return JCSMPProperties.class.isAssignableFrom(paramType) ||
				JCSMPSession.class.isAssignableFrom(paramType) ||
				Queue.class.isAssignableFrom(paramType) ||
				SempV2Api.class.isAssignableFrom(paramType) ||
				(ToxiproxyContext.class.isAssignableFrom(paramType) &&
						Arrays.stream(annotations).anyMatch(a -> a instanceof JCSMPProxy));
	}

	@Override
	public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
			throws ParameterResolutionException {
		return resolveParameter(extensionContext,
				parameterContext.getParameter().getType(),
				parameterContext.getParameter().getAnnotations());
	}

	private static Object resolveParameter(ExtensionContext extensionContext, Class<?> paramType, Annotation... annotations)
			throws ParameterResolutionException {
		PubSubPlusContainer container;
		if (getValidResolver(extensionContext) != null) {
			extensionContext.getRoot().getStore(EXT_NAMESPACE).getOrComputeIfAbsent(ExternalProvider.class, key -> {
				ExternalProvider externalProvider = getValidResolver(extensionContext);
				LOG.info("Initializing external PubSub+ provider {}", externalProvider.getClass().getSimpleName());
				externalProvider.init(extensionContext);
				return externalProvider;
			});
			container = null;
		} else {
			// Store container in root store so that it's only created once for all test classes.
			container = extensionContext.getRoot().getStore(EXT_NAMESPACE).getOrComputeIfAbsent(
					PubSubPlusContainerResource.class,
					key -> {
						LOG.info("Creating PubSub+ container");
						PubSubPlusContainer newContainer = CONTAINER_PROVIDER.containerSupplier(extensionContext).get();
						if (newContainer.getNetwork() == null) {
							newContainer.withNetwork(Network.newNetwork());
						}
						if (!newContainer.isCreated()) {
							newContainer.start();
						}
						CONTAINER_PROVIDER.containerPostStart(extensionContext, newContainer);
						return new PubSubPlusContainerResource(newContainer);
					}, PubSubPlusContainerResource.class).getContainer();
		}

		Namespace parameterNamespace = EXT_NAMESPACE.append(Arrays.stream(annotations)
				.filter(a -> a instanceof Store)
				.map(a -> ((Store) a).value())
				.findFirst()
				.orElse(Store.DEFAULT));
		Namespace toxiproxyNamespace = parameterNamespace.append(TOXIPROXY_NAMESPACE_POSTFIX);

		if (Queue.class.isAssignableFrom(paramType) ||
				JCSMPSession.class.isAssignableFrom(paramType) ||
				JCSMPProperties.class.isAssignableFrom(paramType) ||
				ToxiproxyContext.class.isAssignableFrom(paramType)) {
			JCSMPProperties jcsmpProperties = Optional.ofNullable(container)
					.map(c -> CONTAINER_PROVIDER.createJcsmpProperties(extensionContext, c))
					.orElseGet(() -> Optional.ofNullable(getValidResolver(extensionContext))
							.map(p -> p.createJCSMPProperties(extensionContext))
							.orElseGet(PubSubPlusExtension::createDefaultJcsmpProperties));

			if (ToxiproxyContext.class.isAssignableFrom(paramType) &&
					Arrays.stream(annotations).anyMatch(a -> a instanceof JCSMPProxy)) {
				ToxiproxyContainer toxiproxyContainer = createToxiproxyContainer(extensionContext, toxiproxyNamespace,
						container);
				return createJcsmpProxy(extensionContext, toxiproxyNamespace, toxiproxyContainer, jcsmpProperties,
						container);
			}

			if (JCSMPProperties.class.isAssignableFrom(paramType)) {
				applyJCSMPPropertiesOverride(jcsmpProperties, annotations);
				if (Arrays.stream(annotations).anyMatch(a -> a instanceof JCSMPProxy)) {
					ToxiproxyContainer toxiproxyContainer = createToxiproxyContainer(extensionContext,
							toxiproxyNamespace, container);
					ToxiproxyContext jcsmpProxy = createJcsmpProxy(extensionContext, toxiproxyNamespace,
							toxiproxyContainer, jcsmpProperties, container);
					return createToxicJCSMPProperties(jcsmpProxy.getProxy(), jcsmpProperties);
				} else {
					return jcsmpProperties;
				}
			}

			boolean createToxicSession = JCSMPSession.class.isAssignableFrom(paramType) &&
					Arrays.stream(annotations).anyMatch(a -> a instanceof JCSMPProxy);

			JCSMPSession session = extensionContext.getStore(createToxicSession ? toxiproxyNamespace : parameterNamespace)
					.getOrComputeIfAbsent(PubSubPlusSessionResource.class, key -> {
				JCSMPProperties props;
				if (createToxicSession) {
					ToxiproxyContainer toxiproxyContainer = createToxiproxyContainer(extensionContext,
							toxiproxyNamespace, container);
					props = createToxicJCSMPProperties(createJcsmpProxy(extensionContext, toxiproxyNamespace,
									toxiproxyContainer, jcsmpProperties, container).getProxy(), jcsmpProperties);
				} else {
					props = jcsmpProperties;
				}
				try {
					LOG.info("Creating JCSMP session");
					applyJCSMPPropertiesOverride(props, annotations);
					JCSMPSession jcsmpSession = JCSMPFactory.onlyInstance().createSession(props);
					jcsmpSession.connect();
					return new PubSubPlusSessionResource(jcsmpSession);
				} catch (JCSMPException e) {
					throw new ParameterResolutionException("Failed to create JCSMP session", e);
				}
			}, PubSubPlusSessionResource.class).getSession();

			if (JCSMPSession.class.isAssignableFrom(paramType)) {
				return session;
			}

			return extensionContext.getStore(parameterNamespace).getOrComputeIfAbsent(PubSubPlusQueueResource.class,
					key -> {
				Queue queue = JCSMPFactory.onlyInstance().createQueue(RandomStringUtils.randomAlphanumeric(20));
				try {
					LOG.info("Provisioning queue {}", queue.getName());
					session.provision(queue, new EndpointProperties(), JCSMPSession.WAIT_FOR_CONFIRM);
				} catch (JCSMPException e) {
					throw new ParameterResolutionException("Could not create queue", e);
				}
				return new PubSubPlusQueueResource(queue, session);
			}, PubSubPlusQueueResource.class).getQueue();
		} else if (SempV2Api.class.isAssignableFrom(paramType)) {
			return extensionContext.getStore(parameterNamespace).getOrComputeIfAbsent(SempV2Api.class, key ->
					Optional.ofNullable(container)
							.map(c -> CONTAINER_PROVIDER.createSempV2Api(extensionContext, c))
							.orElseGet(() -> Optional.ofNullable(getValidResolver(extensionContext))
									.map(p -> p.createSempV2Api(extensionContext))
									.orElseGet(PubSubPlusExtension::createDefaultSempV2Api)
							), SempV2Api.class);
		} else {
			throw new IllegalArgumentException(String.format("Parameter type %s is not supported", paramType));
		}
	}

	private static void applyJCSMPPropertiesOverride(JCSMPProperties jcsmpProperties, Annotation... annotations) {
		Set<String> overriddenJcsmpKeys = new HashSet<>();

		Map<String, Object> jcsmpPropertyOverrides = JCSMPProperties.fromProperties(Arrays.stream(annotations)
				.filter(a -> a instanceof JCSMPProps || a instanceof JCSMPProperty)
				.flatMap(a -> a instanceof JCSMPProps ? Arrays.stream(((JCSMPProps) a).value()) :
						Stream.of((JCSMPProperty) a))
				.peek(a -> {
					if (a.key().startsWith("client_channel_properties.")) {
						overriddenJcsmpKeys.add(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES);
						overriddenJcsmpKeys.add(String.join(".", JCSMPProperties.CLIENT_CHANNEL_PROPERTIES,
										a.key().split("\\.", 2)[1]));
					} else {
						overriddenJcsmpKeys.add(a.key());
					}
				})
				.collect(Properties::new, (p, a) -> p.setProperty("jcsmp." + a.key(), a.value()), Properties::putAll))
				.getProperties();

		for (Map.Entry<String, Object> overriddenJcsmpProperty : jcsmpPropertyOverrides.entrySet()) {
			if (!overriddenJcsmpKeys.contains(overriddenJcsmpProperty.getKey())) continue;

			if (overriddenJcsmpProperty.getValue() instanceof JCSMPChannelProperties) {
				JCSMPChannelProperties channelProperties = (JCSMPChannelProperties) jcsmpProperties.getProperty(
						overriddenJcsmpProperty.getKey());
				overriddenJcsmpKeys.stream()
						.filter(k -> k.startsWith(overriddenJcsmpProperty.getKey() + "."))
						.map(k -> k.split("\\.", 2)[1])
						.forEach(k -> {
							Object newValue = ((JCSMPChannelProperties) overriddenJcsmpProperty.getValue())
									.getProperty(k);
							LOG.trace("Overriding property {}.{} with {}", overriddenJcsmpProperty.getKey(), k,
									newValue);
							channelProperties.setProperty(k, newValue);
						});
			} else {
				LOG.trace("Overriding property {} with {}", overriddenJcsmpProperty.getKey(), overriddenJcsmpProperty.getValue());
				jcsmpProperties.setProperty(overriddenJcsmpProperty.getKey(), overriddenJcsmpProperty.getValue());
			}
		}
	}

	private static JCSMPProperties createDefaultJcsmpProperties() {
		return new JCSMPProperties();
	}

	private static SempV2Api createDefaultSempV2Api() {
		return new SempV2Api("http://localhost:8080", "admin", "admin");
	}

	private static ExternalProvider getValidResolver(ExtensionContext extensionContext) {
		return EXTERNAL_PROVIDERS.stream()
				.filter(p -> p.isValid(extensionContext))
				.findFirst()
				.orElse(null);
	}

	private static ToxiproxyContainer createToxiproxyContainer(ExtensionContext extensionContext,
															   Namespace toxiproxyNamespace,
															   PubSubPlusContainer pubSubPlusContainer) {
		return extensionContext.getStore(toxiproxyNamespace).getOrComputeIfAbsent(ToxiproxyContainerResource.class, key -> {
			LOG.info("Creating Toxiproxy container");
			ToxiproxyContainer container = new ToxiproxyContainer(DockerImageName.parse("shopify/toxiproxy:2.1.0"));
			if (pubSubPlusContainer != null) {
				if (pubSubPlusContainer.getNetwork() != null) {
					container.withNetwork(pubSubPlusContainer.getNetwork()).withNetworkAliases(TOXIPROXY_NETWORK_ALIAS);
				} else {
					throw new IllegalStateException(String.format("%s container %s has no network",
							pubSubPlusContainer.getDockerImageName(), pubSubPlusContainer.getContainerName()));
				}
			}
			if (!container.isCreated()) {
				container.start();
			}
			return new ToxiproxyContainerResource(container);
			}, ToxiproxyContainerResource.class).getContainer();
	}

	private static ToxiproxyContext createJcsmpProxy(ExtensionContext extensionContext,
													 Namespace toxiproxyNamespace,
													 ToxiproxyContainer toxiproxyContainer,
													 JCSMPProperties jcsmpProperties,
													 PubSubPlusContainer pubSubPlusContainer) {
		URI clientHost = URI.create(jcsmpProperties.getStringProperty(JCSMPProperties.HOST));
		return extensionContext.getStore(toxiproxyNamespace)
				.getOrComputeIfAbsent(ToxiproxyContext.class, key -> {
					LOG.info("Provisioning Toxiproxy context for container {}", toxiproxyContainer.getContainerName());
					ContainerProxy proxy = pubSubPlusContainer != null ?
							toxiproxyContainer.getProxy(pubSubPlusContainer, PubSubPlusContainer.Port.SMF.getInternalPort()) :
							toxiproxyContainer.getProxy(clientHost.getHost(), clientHost.getPort());
					return new ToxiproxyContext(proxy, TOXIPROXY_NETWORK_ALIAS);
					}, ToxiproxyContext.class);
	}

	private static JCSMPProperties createToxicJCSMPProperties(ContainerProxy jcsmpProxy, JCSMPProperties jcsmpProperties) {
		URI clientHost = URI.create(jcsmpProperties.getStringProperty(JCSMPProperties.HOST));
		JCSMPProperties newJcsmpProperties = (JCSMPProperties) jcsmpProperties.clone();
		newJcsmpProperties.setProperty(JCSMPProperties.HOST, String.format("%s://%s:%s",
				clientHost.getScheme(), jcsmpProxy.getContainerIpAddress(), jcsmpProxy.getProxyPort()));
		return newJcsmpProperties;
	}

	@SuppressWarnings("unchecked")
	private static <T> T getParameterForExternalUse(ExtensionContext extensionContext, Class<T> paramType,
											 Annotation... annotations) {
		if (supportsParameter(JCSMPProperties.class, annotations)) {
			return (T) resolveParameter(extensionContext, paramType, annotations);
		} else {
			throw new IllegalArgumentException(String.format(
					"Parameter is not supported. (type: %s, annotations: [%s])",
					paramType.getSimpleName(),
					Stream.of(annotations).map(Annotation::toString).collect(Collectors.joining(", "))));
		}
	}

	/**
	 * Should only be used by other JUnit 5 extensions. Use JUnit test parameters to get the JCSMP properties
	 * within tests.
	 * @param extensionContext extension context
	 * @param annotations annotations to apply
	 * @return the existing or new JCSMPProperties
	 */
	public static JCSMPProperties getJCSMPProperties(ExtensionContext extensionContext, Annotation... annotations) {
		return getParameterForExternalUse(extensionContext, JCSMPProperties.class, annotations);
	}

	/**
	 * Should only be used by other JUnit 5 extensions. Use JUnit test parameters to get the session within tests.
	 * @param extensionContext extension context
	 * @param annotations annotations to apply
	 * @return the existing or new JCSMP session
	 */
	public static JCSMPSession getJCSMPSession(ExtensionContext extensionContext, Annotation... annotations) {
		return getParameterForExternalUse(extensionContext, JCSMPSession.class, annotations);
	}

	/**
	 * Should only be used by other JUnit 5 extensions. Use JUnit test parameters to get the queue within tests.
	 * @param extensionContext extension context
	 * @param annotations annotations to apply
	 * @return the existing or new queue
	 */
	public static Queue getQueue(ExtensionContext extensionContext, Annotation... annotations) {
		return getParameterForExternalUse(extensionContext, Queue.class, annotations);
	}

	/**
	 * Should only be used by other JUnit 5 extensions. Use JUnit test parameters to get the SempV2Api within tests.
	 * @param extensionContext extension context
	 * @param annotations annotations to apply
	 * @return The existing or new SempV2Api
	 */
	public static SempV2Api getSempV2Api(ExtensionContext extensionContext, Annotation... annotations) {
		return getParameterForExternalUse(extensionContext, SempV2Api.class, annotations);
	}

	/**
	 * Should only be used by other JUnit 5 extensions. Use JUnit test parameters to get the ToxiproxyContext
	 * within tests.
	 * @param extensionContext extension context
	 * @param annotations annotations to apply
	 * @return The existing or new Toxiproxy context
	 */
	public static ToxiproxyContext getToxiproxyContext(ExtensionContext extensionContext, Annotation... annotations) {
		return getParameterForExternalUse(extensionContext, ToxiproxyContext.class, annotations);
	}

	public static class ToxiproxyContext {
		private final ContainerProxy proxy;
		private final String dockerNetworkHost;

		public ToxiproxyContext(ContainerProxy proxy, String dockerNetworkHost) {
			this.proxy = proxy;
			this.dockerNetworkHost = dockerNetworkHost;
		}

		public ContainerProxy getProxy() {
			return proxy;
		}

		/**
		 * The host which this proxy container can be reached from within the same docker network.
		 * @return Toxiproxy docker network alias
		 */
		public String getDockerNetworkAlias() {
			return dockerNetworkHost;
		}
	}

	/**
	 * A provider for an external PubSub+ broker.
	 */
	public interface ExternalProvider {
		/**
		 * Indicates to the PubSub+ extension whether it can use the external provider to get its test broker.
		 * @param extensionContext extension context
		 * @return true, if the provider is usable.
		 */
		boolean isValid(ExtensionContext extensionContext);

		/**
		 * Initialize the external provider. Is only invoked once.
		 * <p><b>TIP:</b> In most cases, any data that needs to be persisted in the {@code init()} should be stored
		 * in a store on the root context.</p>
		 * @param extensionContext extension context
		 */
		void init(ExtensionContext extensionContext);

		/**
		 * Create a new JCSMPProperties object for this provider's broker.
		 * @param extensionContext extension context
		 * @return a new JCSMPProperties instance
		 */
		JCSMPProperties createJCSMPProperties(ExtensionContext extensionContext);

		/**
		 * Create a new SempV2API object for this provider's broker.
		 * @param extensionContext extension context
		 * @return a new SempV2Api instance
		 */
		SempV2Api createSempV2Api(ExtensionContext extensionContext);
	}

	/**
	 * A {@link PubSubPlusContainer} provider for a PubSub+ broker.
	 */
	public interface ContainerProvider {
		/**
		 * The supplier which returns a new {@link PubSubPlusContainer}. This supplier must not start the container,
		 * but only configure it.
		 * @param extensionContext extension context
		 * @return a supplier to create a new PubSub+ container
		 */
		Supplier<PubSubPlusContainer> containerSupplier(ExtensionContext extensionContext);

		/**
		 * Action to run after starting the PubSub+ container.
		 * @param extensionContext extension context
		 * @param container running PubSub+ container
		 */
		void containerPostStart(ExtensionContext extensionContext, PubSubPlusContainer container);

		/**
		 * Create a new {@link JCSMPProperties} object for the given PubSub+ container.
		 * @param extensionContext extension context
		 * @param container running PubSub+ container
		 * @return new JCSMPProperties
		 */
		JCSMPProperties createJcsmpProperties(ExtensionContext extensionContext, PubSubPlusContainer container);

		/**
		 * Create a new {@link SempV2Api} client for the given PubSub+ container.
		 * @param extensionContext extension context
		 * @param container running PubSub+ container
		 * @return new SempV2Api
		 */
		SempV2Api createSempV2Api(ExtensionContext extensionContext, PubSubPlusContainer container);
	}

	/**
	 * <p>The store which the parameter's resources belongs to. Use this if you're using multiple parameters of the
	 * same type. e.g. 2 {@code JCSMPSession}s.</p>
	 * <p>If one resource is needed to create another (e.g. {@code JCSMPSession} is needed to create a {@code Queue}),
	 * then this extension will retrieve that resource's required resource from the same store.</p>
	 * <p>e.g. in the following code snippet, {@code defaultQueue} will be created using {@code defaultSession},
	 * while {@code otherQueue} will be created using {@code otherSession}:</p>
	 * <pre><code>
	 * {@literal @}ExtendWith(PubSubPlusExtension.class)
	 * public class Test {
	 * 	{@literal @}Test
	 * 	public void testMethod(JCSMPSession defaultSession, @Store("other") otherSession,
	 * 		Queue defaultQueue, @Store("other") Queue otherQueue) {
	 * 	}
	 * }
	 * </code></pre>
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.PARAMETER)
	public @interface Store {
		String DEFAULT = "default";

		/**
		 * The store name. Default value is {@value #DEFAULT}.
		 * @return the store name.
		 */
		String value() default DEFAULT;
	}

	/**
	 * A proxy for a toxic JCSMP client.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.PARAMETER)
	public @interface JCSMPProxy {}

	/**
	 * Collection of JCSMP properties.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.PARAMETER)
	public @interface JCSMPProps {
		JCSMPProperty[] value() default {};
	}

	/**
	 * A single JCSMP property. Keys and values must satisfy {@link JCSMPProperties#fromProperties(Properties)}
	 * requirements.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.PARAMETER)
	@Repeatable(JCSMPProps.class)
	public @interface JCSMPProperty {
		/**
		 * JCSMP property key whose format adheres to the requirements of
		 * {@link JCSMPProperties#fromProperties(Properties)}.
		 * Does not need to be prepended with the 'jcsmp.' prefix.
		 * @return JCSMP property key.
		 * @see JCSMPProperties
		 */
		String key();

		/**
		 * Textual representation of the JCSMP property's value which meets the requirements of
		 * {@link JCSMPProperties#fromProperties(Properties)}.
		 * @return JCSMP property textual value.
		 */
		String value();
	}

	private static class PubSubPlusContainerResource extends ContainerResource<PubSubPlusContainer> {
		private PubSubPlusContainerResource(PubSubPlusContainer container) {
			super(container);
		}
	}

	private static class ToxiproxyContainerResource extends ContainerResource<ToxiproxyContainer> {
		private ToxiproxyContainerResource(ToxiproxyContainer container) {
			super(container);
		}
	}

	private static class ContainerResource<T extends GenericContainer<T>>
			implements ExtensionContext.Store.CloseableResource {
		private static final Logger LOG = LoggerFactory.getLogger(ContainerResource.class);
		private final T container;

		private ContainerResource(T container) {
			this.container = container;
		}

		public T getContainer() {
			return container;
		}

		@Override
		public void close() {
			LOG.info("Closing {} container {}", container.getDockerImageName(), container.getContainerName());
			container.close();
		}
	}

	private static class PubSubPlusSessionResource implements ExtensionContext.Store.CloseableResource {
		private static final Logger LOG = LoggerFactory.getLogger(PubSubPlusSessionResource.class);
		private final JCSMPSession session;

		private PubSubPlusSessionResource(JCSMPSession session) {
			this.session = session;
		}

		public JCSMPSession getSession() {
			return session;
		}

		@Override
		public void close() {
			LOG.info("Closing session {}", session.getProperty(JCSMPProperties.CLIENT_NAME));
			session.closeSession();
		}
	}

	private static class PubSubPlusQueueResource implements ExtensionContext.Store.CloseableResource {
		private static final Logger LOG = LoggerFactory.getLogger(PubSubPlusQueueResource.class);
		private final Queue queue;
		private final JCSMPSession session;

		private PubSubPlusQueueResource(Queue queue, JCSMPSession session) {
			this.queue = queue;
			this.session = session;
		}

		public Queue getQueue() {
			return queue;
		}

		@Override
		public void close() throws Throwable {
			LOG.info("Deprovisioning queue {}", queue.getName());
			session.deprovision(queue, JCSMPSession.FLAG_IGNORE_DOES_NOT_EXIST);
		}
	}
}

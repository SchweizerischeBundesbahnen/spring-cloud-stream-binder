package com.solace.spring.cloud.stream.binder.extension;

import com.github.dockerjava.api.model.Ulimit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DynamicPropertiesTestContextCustomizerFactory creates a customizer to initialize dynamic properties
 * for integration tests. This replaces the dynamic property initialization that was
 * previously handled in the SpringBootIntegrationTestExtension.
 * <p>
 * The factory creates dynamic properties for:
 * - Solace messaging configuration
 */
@Slf4j
public class DynamicPropertiesTestContextCustomizerFactory implements ContextCustomizerFactory {
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin";
    private static final long DEFAULT_SHM_SIZE = (long) Math.pow(1024, 3); // 1 GB

    public static final SolaceContainer solaceContainer = new SolaceContainer("solace/solace-pubsub-standard:latest").withExposedPorts(8080, 55555, 55003, 55443)
            .withSharedMemorySize(DEFAULT_SHM_SIZE)
            .withCredentials("testuserSolace", "testpassSolace")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withUlimits(new Ulimit[]{new Ulimit("nofile", 1048576L, 1048576L)}))
            .waitingFor(Wait.forHttp("/SEMP/v2/monitor/msgVpns/default")
                    .forPort(8080)
                    .withBasicCredentials(DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD)
                    .forStatusCode(200)
                    .forResponsePredicate(s -> s.contains("\"state\":\"up\""))
                    .withStartupTimeout(Duration.ofMinutes(5)));


    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        // Only apply to tests that use BinderIntegrationTest annotation
        BinderIntegrationTest annotation = testClass.getAnnotation(BinderIntegrationTest.class);
        if (annotation != null) {
            return new DynamicPropertiesContextCustomizer(
                    annotation.disableSolaceContainer(),
                    annotation.multiBinderEnabled());
        }
        return null;
    }

    public static class DynamicPropertiesApplicationContextCustomizer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        boolean disableSolaceContainer;
        boolean multiBinderEnabled;

        public DynamicPropertiesApplicationContextCustomizer(boolean disableSolaceContainer, boolean multiBinderEnabled) {
            this.disableSolaceContainer = disableSolaceContainer;
            this.multiBinderEnabled = multiBinderEnabled;
        }

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            PropertiesGenerator.generatePropertiesForEnvironment(applicationContext.getEnvironment(), disableSolaceContainer, multiBinderEnabled);
        }
    }

    public static class PropertiesGenerator {
        public static void generatePropertiesForEnvironment(ConfigurableEnvironment environment, boolean disableSolaceContainer, boolean multiBinderEnabled) {
            MutablePropertySources propertySources = environment.getPropertySources();

            // Create dynamic properties using the same containers from SpringBootIntegrationTestExtension
            Map<String, Object> dynamicProperties = createDynamicProperties(disableSolaceContainer, multiBinderEnabled);
            MapPropertySource dynamicPropertySource = new MapPropertySource("dynamicTestProperties", dynamicProperties);

            // Add it with high priority (before application properties)
            propertySources.addFirst(dynamicPropertySource);

            log.debug("Added {} dynamic properties to test context", dynamicProperties.size());
        }

        /**
         * Create the map of dynamic properties using the shared containers
         * from SpringBootIntegrationTestExtension
         */
        private static Map<String, Object> createDynamicProperties(boolean disableSolaceContainer, boolean multiBinderEnabled) {
            Map<String, Object> properties = new HashMap<>();

            synchronized (solaceContainer) {
                if (!disableSolaceContainer) {
                    if (!solaceContainer.isRunning()) {
                        solaceContainer.start();

                        solaceContainer.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger(DynamicPropertiesTestContextCustomizerFactory.class)));
                    }
                }
            }

            if (!disableSolaceContainer) {
                log.info("Solace container running: {}", solaceContainer.isRunning());
                log.info("Solace SMF port: {}", solaceContainer.getMappedPort(55555));
                log.info("Solace Secure SMF port: {}", solaceContainer.getMappedPort(55443));
                log.info("Solace SEMP port: {}", solaceContainer.getMappedPort(8080));
            }

            // Configure Solace properties
            if (!disableSolaceContainer) {
                if (multiBinderEnabled) {
                    properties.put("spring.cloud.stream.binders.solace1.environment.solace.java.host", solaceContainer.getOrigin(Service.SMF));
                    properties.put("spring.cloud.stream.binders.solace1.environment.solace.java.msgVpn", solaceContainer.getVpn());
                    properties.put("spring.cloud.stream.binders.solace1.environment.solace.java.clientName", UUID.randomUUID()
                            .toString());
                    properties.put("spring.cloud.stream.binders.solace1.environment.solace.java.client-username", solaceContainer.getUsername());
                    properties.put("spring.cloud.stream.binders.solace1.environment.solace.java.client-password", solaceContainer.getPassword());

                    properties.put("spring.cloud.stream.binders.solace2.environment.solace.java.host", solaceContainer.getOrigin(Service.SMF));
                    properties.put("spring.cloud.stream.binders.solace2.environment.solace.java.msgVpn", solaceContainer.getVpn());
                    properties.put("spring.cloud.stream.binders.solace2.environment.solace.java.clientName", UUID.randomUUID()
                            .toString());
                    properties.put("spring.cloud.stream.binders.solace2.environment.solace.java.client-username", solaceContainer.getUsername());
                    properties.put("spring.cloud.stream.binders.solace2.environment.solace.java.client-password", solaceContainer.getPassword());
                } else {
                    properties.put("solace.java.host", solaceContainer.getOrigin(Service.SMF));
                    properties.put("solace.java.msgVpn", solaceContainer.getVpn());
                    properties.put("solace.java.clientName", UUID.randomUUID()
                            .toString());
                    properties.put("solace.java.client-username", solaceContainer.getUsername());
                    properties.put("solace.java.client-password", solaceContainer.getPassword());
                }
            }

            log.debug("Created {} dynamic properties", properties.size());

            return properties;
        }
    }

    /**
     * ContextCustomizer that adds dynamic properties to the Spring test context
     */
    public static class DynamicPropertiesContextCustomizer implements ContextCustomizer {

        private final boolean disableSolaceContainer;
        private final boolean multiBinderEnabled;

        public DynamicPropertiesContextCustomizer(boolean disableSolaceContainer, boolean multiBinderEnabled) {
            this.disableSolaceContainer = disableSolaceContainer;
            this.multiBinderEnabled = multiBinderEnabled;
        }

        @Override
        public void customizeContext(org.springframework.context.ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            ConfigurableEnvironment environment = context.getEnvironment();
            PropertiesGenerator.generatePropertiesForEnvironment(environment, disableSolaceContainer, multiBinderEnabled);
        }


        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DynamicPropertiesContextCustomizer that)) {
                return false;
            }
            return disableSolaceContainer == that.disableSolaceContainer
                    && multiBinderEnabled == that.multiBinderEnabled;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(disableSolaceContainer, multiBinderEnabled);
        }
    }
}

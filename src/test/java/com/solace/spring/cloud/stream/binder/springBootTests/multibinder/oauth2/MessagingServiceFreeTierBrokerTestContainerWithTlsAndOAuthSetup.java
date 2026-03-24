package com.solace.spring.cloud.stream.binder.springBootTests.multibinder.oauth2;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public interface MessagingServiceFreeTierBrokerTestContainerWithTlsAndOAuthSetup {

    String DOCKER_COMPOSE_FILENAME = "free-tier-broker-with-tls-and-oauth-docker-compose.yml";
    String DOCKER_COMPOSE_SOURCE_PATH = "src/test/resources/oauth2";
    String DOCKER_EXTERNALIZED_PATH = "localdev/docker-externalized";
    String PUBSUB_BROKER_SERVICE_NAME = "solbroker";
    String NGINX_RPROXY_SERVICE_NAME = "solaceoauth";
    String KEYCLOAK_OAUTH_SERVICE_NAME = "keycloak";
    String DOCKER_COMPOSE_BUILD_PATH = "target/docker-compose-build";

    Logger LOGGER = LoggerFactory.getLogger(
            MessagingServiceFreeTierBrokerTestContainerWithTlsAndOAuthSetup.class);

    ComposeContainer COMPOSE_CONTAINER = prepareContainer();

    static ComposeContainer prepareContainer() {
        prepareDockerComposeBuildPath(DOCKER_COMPOSE_SOURCE_PATH, DOCKER_EXTERNALIZED_PATH, DOCKER_COMPOSE_BUILD_PATH);
        return new ComposeContainer( // using constructor with DockerImageName allows using a remote docker-compose by env DOCKER_HOST -> default localCompose=false
                DockerImageName.parse("docker"),
                "solbinder-compose-" + new Random().nextInt(100000),
                new File(DOCKER_COMPOSE_BUILD_PATH + "/" + DOCKER_COMPOSE_FILENAME))
                .withPull(true)

                .withExposedService(PUBSUB_BROKER_SERVICE_NAME, 8080)
                .withExposedService(PUBSUB_BROKER_SERVICE_NAME, 55443)
                .withExposedService(PUBSUB_BROKER_SERVICE_NAME, 55555)

                .withExposedService(NGINX_RPROXY_SERVICE_NAME, 10443)
                .withExposedService(NGINX_RPROXY_SERVICE_NAME, 1080)

                .withExposedService(KEYCLOAK_OAUTH_SERVICE_NAME, 8080)

                .waitingFor(PUBSUB_BROKER_SERVICE_NAME,
                        Wait.forHttp("/").forPort(8080).withStartupTimeout(Duration.ofSeconds(220)))
                .waitingFor(NGINX_RPROXY_SERVICE_NAME,
                        Wait.forHttp("/").forPort(10443).allowInsecure().usingTls()
                                .withStartupTimeout(Duration.ofSeconds(220)))
                .waitingFor(KEYCLOAK_OAUTH_SERVICE_NAME,
                        Wait.forHttp("/auth/").forPort(8080).allowInsecure()
                                .withStartupTimeout(Duration.ofSeconds(280)));
    }

    static void prepareDockerComposeBuildPath(String sourcePath, String dockerExternalizedPath, String buildPath) {
        try {
            FileUtils.copyDirectory(new File(sourcePath), new File(buildPath));
            if (new File(dockerExternalizedPath).isDirectory()) {
                Files.copy(Path.of(dockerExternalizedPath + "/broker/solbroker.pem"), Path.of(buildPath + "/certs/broker/solbroker.pem"), StandardCopyOption.REPLACE_EXISTING);
                FileUtils.cleanDirectory(new File(buildPath + "/certs/keycloak"));
                FileUtils.copyDirectory(new File(dockerExternalizedPath + "/keycloak"), new File(buildPath + "/certs/keycloak"));
            }
        } catch (Exception e) {
            throw new RuntimeException("We can not recover the build if docker compose path preparation fails.", e);
        }
    }

    @BeforeAll
    static void startContainer() {
        String trustStoreLocationFallback = "src/test/resources/oauth2/certs";
        if (new File(DOCKER_EXTERNALIZED_PATH).isDirectory()) {
            System.setProperty("javax.net.ssl.trustStore",
                    new File(DOCKER_EXTERNALIZED_PATH + "/client/client-truststore.p12").getAbsolutePath());
        } else {
            System.setProperty("javax.net.ssl.trustStore",
                    new File(trustStoreLocationFallback + "/client/client-truststore.p12").getAbsolutePath());
        }
        System.setProperty("javax.net.ssl.trustStorePassword", "changeMe123");
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        //System.setProperty("javax.net.debug", "all");

        COMPOSE_CONTAINER.start();
    }

    @BeforeAll
    static void checkContainer() {
        String solaceBroker = COMPOSE_CONTAINER.getServiceHost(PUBSUB_BROKER_SERVICE_NAME, 8080);
        assertNotNull(solaceBroker, "solace broker host expected to be not null");

        String nginxProxy = COMPOSE_CONTAINER.getServiceHost(NGINX_RPROXY_SERVICE_NAME, 10443);
        assertNotNull(nginxProxy, "nginx proxy host expected to be not null");

        String keycloak = COMPOSE_CONTAINER.getServiceHost(KEYCLOAK_OAUTH_SERVICE_NAME, 8080);
        assertNotNull(keycloak, "keycloak host expected to be not null");
    }

    @AfterAll
    static void afterAll() {
        final SolaceBroker broker = SolaceBroker.getInstance();
        broker.backupFinalBrokerLogs(); //Backup container logs before it's destroyed
        COMPOSE_CONTAINER.stop();  //Destroy the container
    }

    class SolaceBroker {

        private static final class LazyHolder {

            static final SolaceBroker INSTANCE = new SolaceBroker();
        }


        public static SolaceBroker getInstance() {
            return LazyHolder.INSTANCE;
        }

        private final ComposeContainer container;

        private SolaceBroker(ComposeContainer container) {
            this.container = container;
        }

        public SolaceBroker() {
            this(COMPOSE_CONTAINER);
        }

        /**
         * backs up final log from a broker
         */
        void backupFinalBrokerLogs() {
            final Consumer<ContainerState> copyToBrokerJob = containerState -> {
                if (containerState.isRunning()) {
                    try {
                        containerState.copyFileFromContainer("/usr/sw/jail/logs/debug.log",
                                "oauth_test_final_debug.log");
                        containerState.copyFileFromContainer("/usr/sw/jail/logs/event.log",
                                "oauth_test_final_event.log");
                    } catch (Exception e) {
                        LOGGER.error("Failed to backup final log from a broker", e);
                    }
                }
            };
            // run actual job on a container
            container.getContainerByServiceName(PUBSUB_BROKER_SERVICE_NAME + "_1")
                    .ifPresent(copyToBrokerJob);
        }
    }
}
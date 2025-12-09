package com.solace.test.integration.testcontainer;

import com.github.dockerjava.api.model.Ulimit;
import lombok.Getter;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Arrays;

@Getter
public class PubSubPlusContainer extends GenericContainer<PubSubPlusContainer> {
    private String adminUsername;
    private String adminPassword;

    public static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("solace/solace-pubsub-standard");
    public static final String DEFAULT_IMAGE_TAG = "10.25.0";
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin";
    private static final String DEFAULT_MAX_CONNECTION_COUNT = "100";
    private static final long DEFAULT_SHM_SIZE = (long) Math.pow(1024, 3); // 1 GB

    public PubSubPlusContainer() {
        this(DEFAULT_IMAGE_NAME.withTag(DEFAULT_IMAGE_TAG));
    }

    public PubSubPlusContainer(String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    public PubSubPlusContainer(DockerImageName dockerImageName) {
        super(dockerImageName);

        withExposedPorts(Arrays.stream(Port.values()).map(Port::getInternalPort).toArray(Integer[]::new))
                .withAdminUsername(DEFAULT_ADMIN_USERNAME)
                .withAdminPassword(DEFAULT_ADMIN_PASSWORD)
                .withMaxConnectionCount(DEFAULT_MAX_CONNECTION_COUNT)
                .withSharedMemorySize(DEFAULT_SHM_SIZE)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withUlimits(new Ulimit[]{new Ulimit("nofile", 1048576L, 1048576L)}))
                .waitingFor(Wait.forHttp("/SEMP/v2/monitor/msgVpns/default")
                        .forPort(8080)
                        .withBasicCredentials(DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD)
                        .forStatusCode(200)
                        .forResponsePredicate(s -> s.contains("\"state\":\"up\""))
                        .withStartupTimeout(Duration.ofMinutes(5)));

        this.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger(PubSubPlusContainer.class)));
    }

    public String getOrigin(Port port) {
        if (port.getProtocol() == null) {
            throw new IllegalArgumentException(String.format("Getting origin of port %s is not supported", port.name()));
        }

        return String.format("%s://%s:%s", port.getProtocol(), getHost(),
                getMappedPort(port.getInternalPort()));
    }

    public Integer getSshPort() {
        return getMappedPort(Port.SSH.getInternalPort());
    }

    public PubSubPlusContainer withAdminUsername(String username) {
        adminUsername = username;
        return withEnv("username_admin_globalaccesslevel", adminUsername);
    }

    public PubSubPlusContainer withAdminPassword(String password) {
        adminPassword = password;
        return withEnv("username_admin_password", password);
    }

    public PubSubPlusContainer withMaxConnectionCount(String maxConnectionCount) {
        return withEnv("system_scaling_maxconnectioncount", maxConnectionCount);
    }

    public enum Port {
        AMQP(5672, "amqp"),
        MQTT(1883, "tcp"),
        MQTT_WEB(8000, "ws"),
        REST(9000, "http"),
        SEMP(8080, "http"),
        SMF(55555, "tcp"),
        SMF_WEB(8008, "ws"),
        SSH(2222, null);

        private final int containerPort;
        private final String protocol;

        Port(int containerPort, String protocol) {
            this.containerPort = containerPort;
            this.protocol = protocol;
        }

        public int getInternalPort() {
            return containerPort;
        }

        private String getProtocol() {
            return protocol;
        }
    }
}

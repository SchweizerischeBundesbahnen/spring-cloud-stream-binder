package ch.sbb.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class MultiBinderIT {

    @Container
    static SolaceContainer solace1 = new SolaceContainer("solace/solace-pubsub-standard:latest")
            .withExposedPorts(8080, 55555);
            
    @Container
    static SolaceContainer solace2 = new SolaceContainer("solace/solace-pubsub-standard:latest")
            .withExposedPorts(8080, 55555);

    @DynamicPropertySource
    static void solaceProps(DynamicPropertyRegistry r) {
        // Binder 1 properties
        r.add("spring.cloud.stream.binders.solace-broker-1.environment.solace.java.host", () -> solace1.getOrigin(Service.SMF));
        r.add("spring.cloud.stream.binders.solace-broker-1.environment.solace.java.msgVpn", solace1::getVpn);
        r.add("spring.cloud.stream.binders.solace-broker-1.environment.solace.java.client-username", solace1::getUsername);
        r.add("spring.cloud.stream.binders.solace-broker-1.environment.solace.java.client-password", solace1::getPassword);
        
        // Binder 2 properties
        r.add("spring.cloud.stream.binders.solace-broker-2.environment.solace.java.host", () -> solace2.getOrigin(Service.SMF));
        r.add("spring.cloud.stream.binders.solace-broker-2.environment.solace.java.msgVpn", solace2::getVpn);
        r.add("spring.cloud.stream.binders.solace-broker-2.environment.solace.java.client-username", solace2::getUsername);
        r.add("spring.cloud.stream.binders.solace-broker-2.environment.solace.java.client-password", solace2::getPassword);
    }

    @Test
    void messageIsReceivedOnBroker1ButNotBroker2() throws InterruptedException {
        String msg1 = MultiBinderApp.RECEIVED_1.poll(30, TimeUnit.SECONDS);
        String msg2 = MultiBinderApp.RECEIVED_2.poll(2, TimeUnit.SECONDS);
        
        assertThat(msg1).isNotNull().isEqualTo("msg-to-broker1");
        assertThat(msg2).isNull(); // Broker 2 shouldn't receive this
    }
}

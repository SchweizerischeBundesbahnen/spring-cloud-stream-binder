package ch.sbb.example;

import no.nav.security.mock.oauth2.MockOAuth2Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solace.Service;
import org.testcontainers.solace.SolaceContainer;
import okhttp3.*;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class OAuth2IT {
    private static final Logger log = LoggerFactory.getLogger(OAuth2IT.class);
    private static MockOAuth2Server mockOAuth2Server;

    @Container
    static SolaceContainer solace = new SolaceContainer("solace/solace-pubsub-standard:latest")
            .withExposedPorts(8080, 55555);

    @BeforeAll
    static void setUpMockOauthServerAndBroker() throws Exception {
        // Start Mock OAuth2 Server
        mockOAuth2Server = new MockOAuth2Server();
        mockOAuth2Server.start();

        // Needs the port of the host machine from the inner docker container's perspective
        org.testcontainers.Testcontainers.exposeHostPorts(mockOAuth2Server.port());
        String hostIp = "host.testcontainers.internal";
        String jwksUri = "http://" + hostIp + ":" + mockOAuth2Server.port() + "/solace/jwks";

        // We use Semps to configure the Solace broker to trust the Mock OAuth2 server and enable OAuth2
        SempsHelper.enableOAuth(solace, jwksUri, "solace");
    }

    @AfterAll
    static void teardown() throws Exception {
        if (mockOAuth2Server != null) {
            mockOAuth2Server.shutdown();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("solace.java.host", () -> solace.getOrigin(Service.SMF));
        r.add("solace.java.msgVpn", solace::getVpn);

        // Tell Spring Security where the token URI is
        String tokenUri = mockOAuth2Server.tokenEndpointUrl("solace").toString();
        r.add("spring.security.oauth2.client.provider.mock-oauth.token-uri", () -> tokenUri);
        
        // Define authentication scheme
        r.add("solace.java.authenticationScheme", () -> "OAUTH2");
        
        // We do *not* set the client-username or client-password because they will be fetched via OAuth2.
        log.info("Using token URI: {}", tokenUri);
    }

    @Test
    void canAuthenticateAndExchangeMessages() throws InterruptedException {
        String msg = OAuth2App.RECEIVED.poll(30, TimeUnit.SECONDS);
        assertThat(msg).isNotNull().isEqualTo("oauth-secured-msg");
    }

    // A helper class to issue Semps calls since testcontainers-solace lacks direct OAuth APIs
    static class SempsHelper {
        private static final OkHttpClient client = new OkHttpClient();

        static void enableOAuth(SolaceContainer solace, String jwksUri, String issuer) throws Exception {
            String sempsBase = "http://" + solace.getHost() + ":" + solace.getMappedPort(8080) + "/SEMP/v2/config";
            String credentials = Credentials.basic(solace.getAdminUsername(), solace.getAdminPassword());

            // 1. Create OAuth profile for the VPN
            String profileBody = String.format("{ \\"oauthProfileName\\": \\"spring-test\\", \\"minimumResolution\\": 0," +
                            " \\"clientRequiredType\\": \\"default\\", \\"defaultRequiredType\\": \\"default\\"," +
                            " \\"clientIdClaimName\\": \\"client_id\\", \\"clientValidateType\\": \\"default\\"," +
                            " \\"issuer\\": \\"%s\\", \\"jwksUri\\": \\"%s\\", \\"mqttUsernameValidate\\": false," +
                            " \\"enabled\\": true }",
                    issuer, jwksUri);

            post(sempsBase + "/msgVpns/default/oauthProfiles", profileBody, credentials);

            // 2. Enable OAuth Profile for the VPN via clientProfile
            String clientProfileBody = "{\"authenticationOAuthProfileName\":\"spring-test\", \"authenticationClientCertEnabled\": false, \"authenticationOauthEnabled\": true}";
            patch(sempsBase + "/msgVpns/default/clientProfiles/default", clientProfileBody, credentials);
            
            // Allow default permissions
            // patch(sempsBase + "/msgVpns/default/aclProfiles/default", "{\\"clientConnectDefaultAction\\": \\"allow\\"}", credentials);

            // 3. Inform the VPN to accept OAuth2
            String vpnBody = "{ \"authenticationOauthEnabled\": true, \"authenticationBasicEnabled\": false }";
            patch(sempsBase + "/msgVpns/default", vpnBody, credentials);
        }

        private static void post(String url, String json, String credentials) throws Exception {
            Request request = new Request.Builder().url(url)
                    .addHeader("Authorization", credentials)
                    .post(RequestBody.create(json, MediaType.parse("application/json"))).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("SEMP post failed: " + response.body().string());
                }
            }
        }

        private static void patch(String url, String json, String credentials) throws Exception {
            Request request = new Request.Builder().url(url)
                    .addHeader("Authorization", credentials)
                    .patch(RequestBody.create(json, MediaType.parse("application/json"))).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("SEMP patch failed: " + response.body().string());
                }
            }
        }
    }
}

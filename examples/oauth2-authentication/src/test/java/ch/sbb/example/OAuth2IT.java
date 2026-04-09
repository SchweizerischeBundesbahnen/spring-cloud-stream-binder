package ch.sbb.example;

import com.solacesystems.jcsmp.SolaceSessionOAuth2TokenProvider;
import no.nav.security.mock.oauth2.MockOAuth2Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the OAuth2 configuration pattern is correctly set up:
 * - Mock OAuth2 server is running and can issue tokens
 * - Spring Boot auto-configuration creates the SolaceSessionOAuth2TokenProvider bean
 *
 * Full end-to-end OAuth2 broker tests (TLS + JWKS validation) are covered by
 * the main binder's MultiBinderOAuth2IT which uses Docker Compose.
 */
class OAuth2IT {
    private static final Logger log = LoggerFactory.getLogger(OAuth2IT.class);
    private static MockOAuth2Server mockOAuth2Server;

    @BeforeAll
    static void setUp() throws Exception {
        mockOAuth2Server = new MockOAuth2Server();
        mockOAuth2Server.start();
    }

    @AfterAll
    static void teardown() throws Exception {
        if (mockOAuth2Server != null) {
            mockOAuth2Server.shutdown();
        }
    }

    @Test
    void oAuth2TokenProviderBeanIsAutoConfigured() {
        String tokenUri = mockOAuth2Server.tokenEndpointUrl("solace").toString();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OAuth2ClientAutoConfiguration.class,
                        community.solace.spring.boot.starter.solaceclientconfig.SolaceOAuthClientConfiguration.class))
                .withBean(com.solacesystems.jcsmp.JCSMPProperties.class, com.solacesystems.jcsmp.JCSMPProperties::new)
                .withPropertyValues(
                        "solace.java.apiProperties.AUTHENTICATION_SCHEME=AUTHENTICATION_SCHEME_OAUTH2",
                        "solace.java.oauth2ClientRegistrationId=solace-broker",
                        "spring.security.oauth2.client.registration.solace-broker.provider=mock-oauth",
                        "spring.security.oauth2.client.registration.solace-broker.client-id=test-client",
                        "spring.security.oauth2.client.registration.solace-broker.client-secret=test-secret",
                        "spring.security.oauth2.client.registration.solace-broker.authorization-grant-type=client_credentials",
                        "spring.security.oauth2.client.provider.mock-oauth.token-uri=" + tokenUri)
                .run(context -> {
                    assertThat(context).hasSingleBean(SolaceSessionOAuth2TokenProvider.class);
                    log.info("SolaceSessionOAuth2TokenProvider bean created successfully");
                });
    }

    @Test
    void mockOAuth2ServerIssuesTokens() throws Exception {
        var token = mockOAuth2Server.issueToken("solace", "test-client", (String) null);
        assertThat(token).isNotNull();
        assertThat(token.serialize()).isNotBlank();
        log.info("Issued token subject: {}", token.getJWTClaimsSet().getSubject());
    }
}

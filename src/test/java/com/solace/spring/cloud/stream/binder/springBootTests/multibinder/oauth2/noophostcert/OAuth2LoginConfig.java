package com.solace.spring.cloud.stream.binder.springBootTests.multibinder.oauth2.noophostcert;

import com.solacesystems.jcsmp.JCSMPProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@TestConfiguration
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class OAuth2LoginConfig {
    private final JCSMPProperties jcsmpProperties;

    OAuth2LoginConfig(JCSMPProperties jcsmpProperties) {
        this.jcsmpProperties = jcsmpProperties;
    }

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(OAuth2ClientProperties props) {
        return new InMemoryClientRegistrationRepository(new OAuth2ClientPropertiesMapper(props).asClientRegistrations());
    }

    /*
    private ClientRegistration solaceClientRegistration() {
        final String clientUserName = Objects.toString(
                jcsmpProperties.getStringProperty(USERNAME), "spring-default-client-username");

        return ClientRegistration.withRegistrationId("solace")
                .clientId(clientUserName)
                .clientSecret("google-client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email", "address", "phone")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://www.googleapis.com/oauth2/v4/token")
                .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .clientName("Google")
                .build();
    }*/
}
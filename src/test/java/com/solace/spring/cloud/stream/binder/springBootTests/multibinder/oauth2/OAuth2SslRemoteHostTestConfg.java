package com.solace.spring.cloud.stream.binder.springBootTests.multibinder.oauth2;

import com.solacesystems.jcsmp.DefaultSolaceSessionOAuth2TokenProvider;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.SolaceSessionOAuth2TokenProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.util.Objects;


@TestConfiguration
public class OAuth2SslRemoteHostTestConfg {
    private final JCSMPProperties jcsmpProperties;
    private ClientRegistrationRepository clientRegistrationRepository;

    OAuth2SslRemoteHostTestConfg(JCSMPProperties jcsmpProperties,
                                 ClientRegistrationRepository clientRegistrationRepository) {
        Objects.requireNonNull(jcsmpProperties);
        Objects.requireNonNull(clientRegistrationRepository);
        this.jcsmpProperties = jcsmpProperties;
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @Bean(name = "solaceSessionOAuth2TokenProvider")
    @Primary
    public SolaceSessionOAuth2TokenProvider solaceSessionOAuth2TokenProvider() {
        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .authorizationCode()
                        .refreshToken()
                        .clientCredentials()
                        .build();
        OAuth2AuthorizedClientService authorizedClientService = new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);

        AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

        DefaultSolaceSessionOAuth2TokenProvider provider = new NoHostVerifyingSolaceSessionOAuth2TokenProvider(this.jcsmpProperties,
                authorizedClientManager);
        return provider;/*
        DefaultSolaceSessionOAuth2TokenProvider provider = () -> {
            try {
                final String clientUserName = Objects.toString(
                        jcsmpProperties.getStringProperty(USERNAME), "spring-default-client-username");
                final String oauth2ClientRegistrationId = jcsmpProperties
                        .getStringProperty(SolaceJavaProperties.SPRING_OAUTH2_CLIENT_REGISTRATION_ID);

                if (logger.isInfoEnabled()) {
                    logger.info(String.format("Fetching OAuth2 access token using client registration ID: %s",
                            oauth2ClientRegistrationId));
                }

                final OAuth2AuthorizeRequest authorizeRequest =
                        OAuth2AuthorizeRequest.withClientRegistrationId(oauth2ClientRegistrationId)
                                .principal(clientUserName)
                                .build();

                //Perform the actual authorization request using the authorized client service and authorized
                //client manager. This is where the JWT is retrieved from the OAuth/OIDC servers.


                final OAuth2AuthorizedClient oAuth2AuthorizedClient =
                        authorizedClientManager.authorize(authorizeRequest);

                //Get the token from the authorized client object
                final OAuth2AccessToken accessToken = Objects.requireNonNull(oAuth2AuthorizedClient)
                        .getAccessToken();

                return accessToken.getTokenValue();
            } catch (Throwable t) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Exception while fetching OAuth2 access token.", t);
                }
                throw t;
            }
        };
        return provider;*/
    }
}

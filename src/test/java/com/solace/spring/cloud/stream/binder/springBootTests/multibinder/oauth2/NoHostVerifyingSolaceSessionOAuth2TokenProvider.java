package com.solace.spring.cloud.stream.binder.springBootTests.multibinder.oauth2;

import com.solacesystems.jcsmp.DefaultSolaceSessionOAuth2TokenProvider;
import com.solacesystems.jcsmp.JCSMPProperties;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;

public class NoHostVerifyingSolaceSessionOAuth2TokenProvider extends DefaultSolaceSessionOAuth2TokenProvider {

    /**
     * Constructs a new DefaultSolaceSessionOAuth2TokenProvider with the provided JCSMP properties and
     * OAuth2 authorized client manager.
     *
     * @param jcsmpProperties                              The JCSMP properties.
     * @param solaceOAuthAuthorizedClientServiceAndManager The OAuth2 authorized client manager.
     */
    public NoHostVerifyingSolaceSessionOAuth2TokenProvider(JCSMPProperties jcsmpProperties, AuthorizedClientServiceOAuth2AuthorizedClientManager solaceOAuthAuthorizedClientServiceAndManager) {
        super(jcsmpProperties, solaceOAuthAuthorizedClientServiceAndManager);
    }
}

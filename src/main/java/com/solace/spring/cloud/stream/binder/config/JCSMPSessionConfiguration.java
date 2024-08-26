package com.solace.spring.cloud.stream.binder.config;

import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.handlers.SolaceSessionEventHandler;
import com.solace.spring.cloud.stream.binder.health.indicators.SessionHealthIndicator;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.impl.JCSMPBasicSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayOutputStream;
import java.util.*;

import static com.solacesystems.jcsmp.XMLMessage.Outcome.*;

@Slf4j
@RequiredArgsConstructor
@Configuration
@Import({SolaceHealthIndicatorsConfiguration.class, OAuth2ClientAutoConfiguration.class})
public class JCSMPSessionConfiguration {
    private final static Map<String, SessionCacheEntry> SESSION_CACHE = new HashMap<>();
    private final JCSMPProperties jcsmpProperties;
    private final Optional<SolaceBinderHealthContributor> sessionHealthIndicator;
    private final Optional<SolaceSessionEventHandler> solaceSessionEventHandler;
    private final Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider;

    @Bean
    JCSMPSessionEventHandler jcsmpSessionEventHandler() {
        return ensureSessionCache().jcsmpSessionEventHandler();
    }

    @Bean
    JCSMPSession jcsmpSession() {
        return ensureSessionCache().jcsmpSession();
    }

    @Bean
    Context jcsmpContext() {
        return ensureSessionCache().context();
    }

    @Bean
    SolaceEndpointProvisioner jcsmpProvisioningProvider() {
        return ensureSessionCache().solaceEndpointProvisioner();
    }

    private SessionCacheEntry ensureSessionCache() {
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            Properties properties = jcsmpProperties.toProperties();
            properties.setProperty("jcsmp.CLIENT_NAME", "ignored"); // dont create a new connection if only the clientname changed
            properties.storeToXML(os, "cached");
            os.close();
            String configAsString = os.toString();
            return SESSION_CACHE.computeIfAbsent(configAsString, (key) -> createSession(jcsmpProperties, sessionHealthIndicator, solaceSessionEventHandler, solaceSessionOAuth2TokenProvider));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static SessionCacheEntry createSession(JCSMPProperties jcsmpProperties,
                                                   Optional<SolaceBinderHealthContributor> sessionHealthIndicator,
                                                   Optional<SolaceSessionEventHandler> solaceSessionEventHandler,
                                                   Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
        JCSMPProperties properties = (JCSMPProperties) jcsmpProperties.clone();
        properties.setProperty(JCSMPProperties.CLIENT_INFO_PROVIDER, new SolaceBinderClientInfoProvider());
        properties.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);

        final JCSMPSessionEventHandler jcsmpSessionEventHandler = new JCSMPSessionEventHandler();
        JCSMPSession jcsmpSession;
        Context context = null;
        try {
            SpringJCSMPFactory springJCSMPFactory = new SpringJCSMPFactory(properties, solaceSessionOAuth2TokenProvider.orElse(null));
            context = springJCSMPFactory.createContext(new ContextProperties());
            jcsmpSession = springJCSMPFactory.createSession(context, jcsmpSessionEventHandler);
            log.info(String.format("Connecting JCSMP session %s", jcsmpSession.getSessionName()));
            jcsmpSession.connect();
            // after setting the session health indicator status to UP,
            // we should not be worried about setting its status to DOWN,
            // as the call closing JCSMP session also delete the context
            // and terminates the application
            sessionHealthIndicator.map(SolaceBinderHealthContributor::getSolaceSessionHealthIndicator).ifPresent(SessionHealthIndicator::up);
            solaceSessionEventHandler.ifPresent(jcsmpSessionEventHandler::addSessionEventHandler);
            if (jcsmpSession instanceof JCSMPBasicSession session && !session.isRequiredSettlementCapable(Set.of(ACCEPTED, FAILED, REJECTED))) {
                log.warn("The connected Solace PubSub+ Broker is not compatible. It doesn't support message NACK capability. Consumer bindings will fail to start.");
            }
        } catch (Exception e) {
            if (context != null) {
                context.destroy();
            }
            throw new RuntimeException(e);
        }
        SolaceEndpointProvisioner solaceEndpointProvisioner = new SolaceEndpointProvisioner(jcsmpSession, jcsmpSessionEventHandler);
        return new SessionCacheEntry(properties, jcsmpSessionEventHandler, jcsmpSession, context, solaceEndpointProvisioner);
    }

    private record SessionCacheEntry(JCSMPProperties jcsmpProperties, JCSMPSessionEventHandler jcsmpSessionEventHandler, JCSMPSession jcsmpSession, Context context, SolaceEndpointProvisioner solaceEndpointProvisioner) {
    }
}

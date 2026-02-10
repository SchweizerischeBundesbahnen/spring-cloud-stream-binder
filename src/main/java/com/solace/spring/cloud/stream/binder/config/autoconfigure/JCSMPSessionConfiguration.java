package com.solace.spring.cloud.stream.binder.config.autoconfigure;

import com.solace.spring.cloud.stream.binder.config.SolaceBinderClientInfoProvider;
import com.solace.spring.cloud.stream.binder.config.SolaceHealthIndicatorsConfiguration;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.handlers.SolaceSessionEventHandler;
import com.solace.spring.cloud.stream.binder.health.indicators.SessionHealthIndicator;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.impl.JCSMPBasicSession;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

import java.io.ByteArrayOutputStream;
import java.util.*;

import static com.solacesystems.jcsmp.XMLMessage.Outcome.*;

@Slf4j
@RequiredArgsConstructor
@Configuration
@Import(SolaceHealthIndicatorsConfiguration.class)
public class JCSMPSessionConfiguration {
    private final static Map<String, SessionCacheEntry> SESSION_CACHE = new HashMap<>();

    @PreDestroy
    public void destroy() {
        SESSION_CACHE.clear();
    }

    @Bean
    @Lazy
    public JCSMPSessionEventHandler jcsmpSessionEventHandler(JCSMPProperties jcsmpProperties,
                                                             Optional<SolaceBinderHealthContributor> binderHealthContributor,
                                                             Optional<SolaceSessionEventHandler> solaceSessionEventHandler,
                                                             Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
        return ensureSessionCache(jcsmpProperties, binderHealthContributor, solaceSessionEventHandler, solaceSessionOAuth2TokenProvider).jcsmpSessionEventHandler();
    }

    @Bean
    @Lazy
    public JCSMPSession jcsmpSession(JCSMPProperties jcsmpProperties,
                                     Optional<SolaceBinderHealthContributor> binderHealthContributor,
                                     Optional<SolaceSessionEventHandler> solaceSessionEventHandler,
                                     Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
        return ensureSessionCache(jcsmpProperties, binderHealthContributor, solaceSessionEventHandler, solaceSessionOAuth2TokenProvider).jcsmpSession();
    }

    @Bean
    @Lazy
    public Context jcsmpContext(JCSMPProperties jcsmpProperties,
                                Optional<SolaceBinderHealthContributor> binderHealthContributor,
                                Optional<SolaceSessionEventHandler> solaceSessionEventHandler,
                                Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
        return ensureSessionCache(jcsmpProperties, binderHealthContributor, solaceSessionEventHandler, solaceSessionOAuth2TokenProvider).context();
    }

    @Bean
    @Lazy
    public SolaceEndpointProvisioner jcsmpProvisioningProvider(JCSMPProperties jcsmpProperties,
                                                               Optional<SolaceBinderHealthContributor> binderHealthContributor,
                                                               Optional<SolaceSessionEventHandler> solaceSessionEventHandler,
                                                               Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
        return ensureSessionCache(jcsmpProperties, binderHealthContributor, solaceSessionEventHandler, solaceSessionOAuth2TokenProvider).solaceEndpointProvisioner();
    }

    private SessionCacheEntry ensureSessionCache(JCSMPProperties jcsmpProperties,
                                                 Optional<SolaceBinderHealthContributor> binderHealthContributor,
                                                 Optional<SolaceSessionEventHandler> solaceSessionEventHandler,
                                                 Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
        log.info("Connect to host {}", jcsmpProperties.getProperty(JCSMPProperties.HOST));
        if (StringUtils.isEmpty((String) jcsmpProperties.getProperty(JCSMPProperties.HOST))) {
            log.warn("Host was empty, skipping session caching");
            return new SessionCacheEntry(jcsmpProperties, null, null, null, null, null);
        }
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            Properties properties = jcsmpProperties.toProperties();
            properties.setProperty("jcsmp.CLIENT_NAME", "ignored"); // dont create a new connection if only the clientname changed
            properties.storeToXML(os, "cached");
            os.close();
            String configAsString = os.toString();
            return SESSION_CACHE.computeIfAbsent(configAsString, (key) -> createSession(jcsmpProperties, binderHealthContributor, solaceSessionEventHandler, solaceSessionOAuth2TokenProvider));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static SessionCacheEntry createSession(JCSMPProperties jcsmpProperties,
                                                   Optional<SolaceBinderHealthContributor> binderHealthContributor,
                                                   Optional<SolaceSessionEventHandler> solaceSessionEventHandler,
                                                   Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
        JCSMPProperties solaceJcsmpProperties = (JCSMPProperties) jcsmpProperties.clone();
        solaceJcsmpProperties.setProperty(JCSMPProperties.CLIENT_INFO_PROVIDER, new SolaceBinderClientInfoProvider());
        solaceJcsmpProperties.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);

        final JCSMPSessionEventHandler jcsmpSessionEventHandler = new JCSMPSessionEventHandler();
        final SolaceSessionOAuth2TokenProvider solaceSessionOAuth2TokenProviderValue = solaceSessionOAuth2TokenProvider.orElse(null);
        JCSMPSession jcsmpSession;
        Context context = null;
        try {
            SpringJCSMPFactory springJCSMPFactory = new SpringJCSMPFactory(solaceJcsmpProperties, solaceSessionOAuth2TokenProviderValue);

            context = springJCSMPFactory.createContext(new ContextProperties());
            jcsmpSession = springJCSMPFactory.createSession(context, jcsmpSessionEventHandler);
            log.info("Connecting JCSMP session {}", jcsmpSession.getSessionName());
            jcsmpSession.connect();
            // after setting the session health indicator status to UP,
            // we should not be worried about setting its status to DOWN,
            // as the call closing JCSMP session also delete the context
            // and terminates the application
            binderHealthContributor.map(SolaceBinderHealthContributor::getSolaceSessionHealthIndicator).ifPresent(SessionHealthIndicator::up);
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
        SolaceEndpointProvisioner solaceEndpointProvisioner = new SolaceEndpointProvisioner(jcsmpSession, binderHealthContributor);
        return new SessionCacheEntry(solaceJcsmpProperties, jcsmpSessionEventHandler, jcsmpSession, context, solaceEndpointProvisioner, solaceSessionOAuth2TokenProviderValue);
    }

    private record SessionCacheEntry(JCSMPProperties jcsmpProperties, JCSMPSessionEventHandler jcsmpSessionEventHandler, JCSMPSession jcsmpSession, Context context, SolaceEndpointProvisioner solaceEndpointProvisioner, SolaceSessionOAuth2TokenProvider solaceSessionOAuth2TokenProvider) {
    }
}

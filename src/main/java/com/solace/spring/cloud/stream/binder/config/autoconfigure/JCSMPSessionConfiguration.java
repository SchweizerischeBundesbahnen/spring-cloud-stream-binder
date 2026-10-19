package com.solace.spring.cloud.stream.binder.config.autoconfigure;

import com.solace.spring.cloud.stream.binder.config.SolaceBinderClientInfoProvider;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.handlers.SolaceSessionEventHandler;
import com.solace.spring.cloud.stream.binder.health.indicators.SessionHealthIndicator;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.impl.JCSMPBasicSession;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.ByteArrayOutputStream;
import java.util.*;

import static com.solacesystems.jcsmp.XMLMessage.Outcome.*;

/**
 * Connects the JCSMP session of a Spring context. Every context of the JVM that connects with the same properties
 * shares one session, and the session closes when the last of these contexts closes.
 */
@Slf4j
@RequiredArgsConstructor
@Configuration
public class JCSMPSessionConfiguration {
    private final static Map<String, SharedSession> SESSION_CACHE = new HashMap<>();

    /**
     * Gives back this context's share of every session it took, and closes a session nobody else holds.
     */
    @PreDestroy
    public void destroy() {
        synchronized (SESSION_CACHE) {
            Iterator<SharedSession> sharedSessions = SESSION_CACHE.values().iterator();
            while (sharedSessions.hasNext()) {
                SharedSession sharedSession = sharedSessions.next();
                sharedSession.release(this);
                if (sharedSession.isUnheld()) {
                    sharedSession.close();
                    sharedSessions.remove();
                }
            }
        }
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
            synchronized (SESSION_CACHE) {
                SharedSession sharedSession = SESSION_CACHE.computeIfAbsent(configAsString, (key) -> new SharedSession(createSession(jcsmpProperties, binderHealthContributor, solaceSessionOAuth2TokenProvider)));
                // A context that adopts a cached session needs the events too: whichever context connects first
                // owns the session, and without this the others only ever learn that it was up once.
                sharedSession.hold(this, solaceSessionEventHandler);
                binderHealthContributor.map(SolaceBinderHealthContributor::getSolaceSessionHealthIndicator)
                        .filter(SessionHealthIndicator::hasNotSeenASessionYet)
                        .ifPresent(SessionHealthIndicator::up);
                return sharedSession.getEntry();
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static SessionCacheEntry createSession(JCSMPProperties jcsmpProperties,
                                                   Optional<SolaceBinderHealthContributor> binderHealthContributor,
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
            binderHealthContributor.map(SolaceBinderHealthContributor::getSolaceSessionHealthIndicator).ifPresent(SessionHealthIndicator::up);
            if (jcsmpSession instanceof JCSMPBasicSession session && !session.isRequiredSettlementCapable(Set.of(ACCEPTED, FAILED, REJECTED))) {
                log.warn("The connected Solace PubSub+ Broker is not compatible. It doesn't support message NACK capability. Consumer bindings will fail to start.");
            }
        } catch (Exception e) {
            binderHealthContributor.map(SolaceBinderHealthContributor::getSolaceSessionHealthIndicator)
                    .ifPresent(sessionHealthIndicator -> sessionHealthIndicator.connectFailed(e));
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

    /**
     * A cached session together with the contexts that hold it, and the session event handlers each of them
     * attached. A context that closes takes its handlers along, so the session no longer reports to a context
     * that is gone.
     */
    @RequiredArgsConstructor
    private static final class SharedSession {
        @Getter
        private final SessionCacheEntry entry;
        private final Map<JCSMPSessionConfiguration, Set<SessionEventHandler>> handlersByHolder = new HashMap<>();

        private void hold(JCSMPSessionConfiguration holder, Optional<SolaceSessionEventHandler> solaceSessionEventHandler) {
            Set<SessionEventHandler> handlersOfTheHolder = handlersByHolder.computeIfAbsent(holder, (key) -> new HashSet<>());
            solaceSessionEventHandler.ifPresent(handler -> {
                entry.jcsmpSessionEventHandler().addSessionEventHandler(handler);
                handlersOfTheHolder.add(handler);
            });
        }

        private void release(JCSMPSessionConfiguration holder) {
            Set<SessionEventHandler> handlersOfTheHolder = handlersByHolder.remove(holder);
            if (handlersOfTheHolder != null) {
                handlersOfTheHolder.forEach(entry.jcsmpSessionEventHandler()::removeSessionEventHandler);
            }
        }

        private boolean isUnheld() {
            return handlersByHolder.isEmpty();
        }

        private void close() {
            log.info("Closing JCSMP session {}", entry.jcsmpSession().getSessionName());
            entry.jcsmpSession().closeSession();
            entry.context().destroy();
        }
    }
}

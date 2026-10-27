package com.solace.spring.cloud.stream.binder.config.autoconfigure;

import com.solace.spring.cloud.stream.binder.config.SolaceBinderClientInfoProvider;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.handlers.SolaceSessionEventHandler;
import com.solace.spring.cloud.stream.binder.health.indicators.SessionHealthIndicator;
import com.solace.spring.cloud.stream.binder.inbound.topic.JCSMPInboundTopicMessageMultiplexer;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionProducerManager;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.impl.JCSMPBasicSession;
import jakarta.annotation.PreDestroy;
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

    @PreDestroy
    public void destroy() {
        List<SharedSession> unheldSessions = new ArrayList<>();
        synchronized (SESSION_CACHE) {
            Iterator<SharedSession> sharedSessions = SESSION_CACHE.values().iterator();
            while (sharedSessions.hasNext()) {
                SharedSession sharedSession = sharedSessions.next();
                sharedSession.release(this);
                if (sharedSession.isUnheld()) {
                    sharedSessions.remove();
                    unheldSessions.add(sharedSession);
                }
            }
        }
        unheldSessions.forEach(SharedSession::close);
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

    @Bean
    @Lazy
    public JCSMPSessionProducerManager jcsmpSessionProducerManager(JCSMPProperties jcsmpProperties,
                                                                   Optional<SolaceBinderHealthContributor> binderHealthContributor,
                                                                   Optional<SolaceSessionEventHandler> solaceSessionEventHandler,
                                                                   Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
        return ensureSessionCache(jcsmpProperties, binderHealthContributor, solaceSessionEventHandler, solaceSessionOAuth2TokenProvider).jcsmpSessionProducerManager();
    }

    @Bean
    @Lazy
    public JCSMPInboundTopicMessageMultiplexer jcsmpInboundTopicMessageMultiplexer(JCSMPProperties jcsmpProperties,
                                                                                   Optional<SolaceBinderHealthContributor> binderHealthContributor,
                                                                                   Optional<SolaceSessionEventHandler> solaceSessionEventHandler,
                                                                                   Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
        return ensureSessionCache(jcsmpProperties, binderHealthContributor, solaceSessionEventHandler, solaceSessionOAuth2TokenProvider).jcsmpInboundTopicMessageMultiplexer();
    }

    private SessionCacheEntry ensureSessionCache(JCSMPProperties jcsmpProperties,
                                                 Optional<SolaceBinderHealthContributor> binderHealthContributor,
                                                 Optional<SolaceSessionEventHandler> solaceSessionEventHandler,
                                                 Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
        log.info("Connect to host {}", jcsmpProperties.getProperty(JCSMPProperties.HOST));
        if (StringUtils.isEmpty((String) jcsmpProperties.getProperty(JCSMPProperties.HOST))) {
            log.warn("Host was empty, skipping session caching");
            return new SessionCacheEntry(jcsmpProperties, null, null, null, null, null, null, null);
        }
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            Properties properties = jcsmpProperties.toProperties();
            properties.setProperty("jcsmp.CLIENT_NAME", "ignored"); // dont create a new connection if only the clientname changed
            properties.storeToXML(os, "cached");
            os.close();
            String configAsString = os.toString();
            SharedSession sharedSession = holdSharedSession(configAsString);
            SessionCacheEntry sessionCacheEntry = connectOrGiveBack(configAsString, sharedSession, jcsmpProperties, binderHealthContributor, solaceSessionOAuth2TokenProvider);
            synchronized (SESSION_CACHE) {
                // A context that adopts a cached session needs the events too: whichever context connects first
                // owns the session, and without this the others only ever learn that it was up once.
                sharedSession.addSessionEventHandler(this, solaceSessionEventHandler);
            }
            binderHealthContributor.map(SolaceBinderHealthContributor::getSolaceSessionHealthIndicator)
                    .filter(SessionHealthIndicator::hasNotSeenASessionYet)
                    .ifPresent(SessionHealthIndicator::up);
            return sessionCacheEntry;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private SharedSession holdSharedSession(String configAsString) {
        synchronized (SESSION_CACHE) {
            SharedSession sharedSession = SESSION_CACHE.computeIfAbsent(configAsString, (key) -> new SharedSession());
            sharedSession.hold(this);
            return sharedSession;
        }
    }

    private SessionCacheEntry connectOrGiveBack(String configAsString,
                                                SharedSession sharedSession,
                                                JCSMPProperties jcsmpProperties,
                                                Optional<SolaceBinderHealthContributor> binderHealthContributor,
                                                Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
        try {
            return sharedSession.connect(jcsmpProperties, binderHealthContributor, solaceSessionOAuth2TokenProvider);
        } catch (RuntimeException connectFailed) {
            synchronized (SESSION_CACHE) {
                sharedSession.release(this);
                if (sharedSession.isUnheld()) {
                    SESSION_CACHE.remove(configAsString, sharedSession);
                }
            }
            throw connectFailed;
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
        JCSMPSessionProducerManager jcsmpSessionProducerManager = new JCSMPSessionProducerManager(jcsmpSession);
        JCSMPInboundTopicMessageMultiplexer jcsmpInboundTopicMessageMultiplexer = new JCSMPInboundTopicMessageMultiplexer(jcsmpSession);
        return new SessionCacheEntry(solaceJcsmpProperties, jcsmpSessionEventHandler, jcsmpSession, context, solaceEndpointProvisioner, jcsmpSessionProducerManager, jcsmpInboundTopicMessageMultiplexer, solaceSessionOAuth2TokenProviderValue);
    }

    private record SessionCacheEntry(JCSMPProperties jcsmpProperties, JCSMPSessionEventHandler jcsmpSessionEventHandler, JCSMPSession jcsmpSession, Context context, SolaceEndpointProvisioner solaceEndpointProvisioner, JCSMPSessionProducerManager jcsmpSessionProducerManager, JCSMPInboundTopicMessageMultiplexer jcsmpInboundTopicMessageMultiplexer, SolaceSessionOAuth2TokenProvider solaceSessionOAuth2TokenProvider) {
    }

    /**
     * A cached session together with the contexts that hold it, and the session event handlers each of them
     * attached. A context that closes takes its handlers along, so the session stops reporting to a context that is
     * gone.
     * <p>
     * The holders and their handlers are guarded by the session cache. The connect locks only this session, so a
     * context waits for the connect of its own properties and never for that of others, and a closing context never
     * waits for a connect at all.
     */
    private static final class SharedSession {
        private final Map<JCSMPSessionConfiguration, Set<SessionEventHandler>> handlersByHolder = new HashMap<>();
        private volatile SessionCacheEntry entry;

        private synchronized SessionCacheEntry connect(JCSMPProperties jcsmpProperties,
                                                       Optional<SolaceBinderHealthContributor> binderHealthContributor,
                                                       Optional<SolaceSessionOAuth2TokenProvider> solaceSessionOAuth2TokenProvider) {
            if (entry == null) {
                entry = createSession(jcsmpProperties, binderHealthContributor, solaceSessionOAuth2TokenProvider);
            }
            return entry;
        }

        private void hold(JCSMPSessionConfiguration holder) {
            handlersByHolder.computeIfAbsent(holder, (key) -> new HashSet<>());
        }

        private void addSessionEventHandler(JCSMPSessionConfiguration holder, Optional<SolaceSessionEventHandler> solaceSessionEventHandler) {
            solaceSessionEventHandler.ifPresent(handler -> {
                entry.jcsmpSessionEventHandler().addSessionEventHandler(handler);
                handlersByHolder.get(holder).add(handler);
            });
        }

        private void release(JCSMPSessionConfiguration holder) {
            Set<SessionEventHandler> handlersOfTheHolder = handlersByHolder.remove(holder);
            if (handlersOfTheHolder == null) {
                return;
            }
            for (SessionEventHandler handler : handlersOfTheHolder) {
                entry.jcsmpSessionEventHandler().removeSessionEventHandler(handler);
            }
        }

        private boolean isUnheld() {
            return handlersByHolder.isEmpty();
        }

        private void close() {
            if (entry == null) {
                return;
            }
            log.info("Closing JCSMP session {}", entry.jcsmpSession().getSessionName());
            entry.jcsmpSession().closeSession();
            entry.context().destroy();
        }
    }
}

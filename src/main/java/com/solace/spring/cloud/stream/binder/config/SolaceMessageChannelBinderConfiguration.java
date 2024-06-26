package com.solace.spring.cloud.stream.binder.config;

import com.solace.spring.cloud.stream.binder.SolaceMessageChannelBinder;
import com.solace.spring.cloud.stream.binder.health.SolaceBinderHealthAccessor;
import com.solace.spring.cloud.stream.binder.health.contributors.SolaceBinderHealthContributor;
import com.solace.spring.cloud.stream.binder.health.handlers.SolaceSessionEventHandler;
import com.solace.spring.cloud.stream.binder.health.indicators.SessionHealthIndicator;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceExtendedBindingProperties;
import com.solace.spring.cloud.stream.binder.provisioning.SolaceEndpointProvisioner;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.impl.JCSMPBasicSession;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.lang.Nullable;

import java.util.Optional;
import java.util.Set;

import static com.solacesystems.jcsmp.XMLMessage.Outcome.*;

@RequiredArgsConstructor
@Configuration
@Import(SolaceHealthIndicatorsConfiguration.class)
@EnableConfigurationProperties({SolaceExtendedBindingProperties.class})
public class SolaceMessageChannelBinderConfiguration {
    private final JCSMPSessionEventHandler jcsmpSessionEventHandler = new JCSMPSessionEventHandler();
    private final JCSMPProperties jcsmpProperties;
    private final SolaceExtendedBindingProperties solaceExtendedBindingProperties;
    private final Optional<SolaceBinderHealthContributor> sessionHealthIndicator;
    private final Optional<SolaceSessionEventHandler> solaceSessionEventHandler;

    private JCSMPSession jcsmpSession;
    private Context context;

    private static final Log logger = LogFactory.getLog(SolaceMessageChannelBinderConfiguration.class);

    @PostConstruct
    private void initSession() throws JCSMPException {
        JCSMPProperties jcsmpProperties = (JCSMPProperties) this.jcsmpProperties.clone();
        jcsmpProperties.setProperty(JCSMPProperties.CLIENT_INFO_PROVIDER, new SolaceBinderClientInfoProvider());
        jcsmpProperties.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);
        try {
            this.context = JCSMPFactory.onlyInstance().createContext(new ContextProperties());
            this.jcsmpSession = JCSMPFactory.onlyInstance().createSession(jcsmpProperties, context, jcsmpSessionEventHandler);
            logger.info(String.format("Connecting JCSMP session %s", jcsmpSession.getSessionName()));
            jcsmpSession.connect();
            // after setting the session health indicator status to UP,
            // we should not be worried about setting its status to DOWN,
            // as the call closing JCSMP session also delete the context
            // and terminates the application
            sessionHealthIndicator.map(SolaceBinderHealthContributor::getSolaceSessionHealthIndicator).ifPresent(SessionHealthIndicator::up);
            solaceSessionEventHandler.ifPresent(jcsmpSessionEventHandler::addSessionEventHandler);
            if (jcsmpSession instanceof JCSMPBasicSession session && !session.isRequiredSettlementCapable(Set.of(ACCEPTED, FAILED, REJECTED))) {
                logger.warn("The connected Solace PubSub+ Broker is not compatible. It doesn't support message NACK capability. Consumer bindings will fail to start.");
            }
        } catch (Exception e) {
            if (context != null) {
                context.destroy();
            }
            throw e;
        }
    }

    @Bean
    JCSMPSessionEventHandler jcsmpSessionEventHandler() {
        return jcsmpSessionEventHandler;
    }

    @Bean
    SolaceMessageChannelBinder solaceMessageChannelBinder(SolaceEndpointProvisioner solaceEndpointProvisioner, @Nullable SolaceBinderHealthAccessor solaceBinderHealthAccessor, @Nullable SolaceMeterAccessor solaceMeterAccessor) {
        SolaceMessageChannelBinder binder = new SolaceMessageChannelBinder(jcsmpSession, context, solaceEndpointProvisioner);
        binder.setExtendedBindingProperties(solaceExtendedBindingProperties);
        binder.setSolaceMeterAccessor(solaceMeterAccessor);
        if (solaceBinderHealthAccessor != null) {
            binder.setSolaceBinderHealthAccessor(solaceBinderHealthAccessor);
        }
        return binder;
    }

    @Bean
    SolaceEndpointProvisioner provisioningProvider() {
        return new SolaceEndpointProvisioner(jcsmpSession, jcsmpSessionEventHandler);
    }

}

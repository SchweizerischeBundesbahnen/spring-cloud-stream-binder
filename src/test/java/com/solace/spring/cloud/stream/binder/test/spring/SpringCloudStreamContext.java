package com.solace.spring.cloud.stream.binder.test.spring;

import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import com.solace.spring.cloud.stream.binder.test.util.SolaceTestBinder;
import com.solace.spring.cloud.stream.binder.util.JCSMPSessionEventHandler;
import com.solace.test.integration.semp.v2.SempV2Api;
import com.solacesystems.jcsmp.ContextProperties;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPSession;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.cloud.stream.binder.*;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.integration.channel.AbstractSubscribableChannel;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.MessageHandler;

import java.util.Objects;

/**
 * <p>Spring Cloud Stream Context.</p>
 * <p>Note: Parent class {@link PartitionCapableBinderTests} also inherits some unit tests. In general, do not
 * subclass this class directly but instead use
 * {@link com.solace.spring.cloud.stream.binder.test.junit.extension.SpringCloudStreamExtension
 * SpringCloudStreamExtension} to inject this
 * context into tests.</p>
 *
 * @see com.solace.spring.cloud.stream.binder.test.junit.extension.SpringCloudStreamExtension SpringCloudStreamExtension
 */
@Slf4j
public class SpringCloudStreamContext extends PartitionCapableBinderTests<SolaceTestBinder,
        ExtendedConsumerProperties<SolaceConsumerProperties>, ExtendedProducerProperties<SolaceProducerProperties>>
        implements ExtensionContext.Store.CloseableResource {
    /**
     * -- SETTER --
     *  Should only be used by subclasses.
     */
    @Getter
    @Setter
    private JCSMPSession jcsmpSession;
    private final JCSMPSessionEventHandler jcsmpSessionEventHandler = new JCSMPSessionEventHandler();
    /**
     * -- SETTER --
     *  Should only be used by subclasses.
     */
    @Setter
    private SempV2Api sempV2Api;

    public SpringCloudStreamContext(JCSMPSession jcsmpSession, SempV2Api sempV2Api) {
        this.jcsmpSession = Objects.requireNonNull(jcsmpSession);
        this.sempV2Api = sempV2Api;
    }

    /**
     * Should only be used by subclasses.
     */
    protected SpringCloudStreamContext() {
        this.jcsmpSession = null;
        this.sempV2Api = null;
    }

    @Override
    protected boolean usesExplicitRouting() {
        return true;
    }

    @Override
    protected String getClassUnderTestName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public SolaceTestBinder getBinder() {
        if (testBinder == null) {
            if (jcsmpSession == null || jcsmpSession.isClosed()) {
                throw new IllegalStateException("JCSMPSession cannot be null or closed");
            }
            log.info("Creating new test binder");
            GenericApplicationContext context = new GenericApplicationContext();
            BeanFactory beanFactory = context.getBeanFactory();
            testBinder = new SolaceTestBinder(jcsmpSession, JCSMPFactory.onlyInstance().createContext(new ContextProperties()), sempV2Api, beanFactory);
        }
        return testBinder;
    }

    @Override
    public ExtendedConsumerProperties<SolaceConsumerProperties> createConsumerProperties() {
        return createConsumerProperties(true);
    }

    public ExtendedConsumerProperties<SolaceConsumerProperties> createConsumerProperties(boolean useDefaultOverrides) {
        return new ExtendedConsumerProperties<>(
                new SolaceConsumerProperties());
    }

    @Override
    public ExtendedProducerProperties<SolaceProducerProperties> createProducerProperties(TestInfo testInfo) {
        return new ExtendedProducerProperties<>(new SolaceProducerProperties());
    }

    @Override
    public Spy spyOn(String name) {
        return null;
    }

    @Override
    public void binderBindUnbindLatency() throws InterruptedException {
        super.binderBindUnbindLatency();
    }

    @Override
    public DirectChannel createBindableChannel(String channelName, BindingProperties bindingProperties) throws Exception {
        return super.createBindableChannel(channelName, bindingProperties);
    }

    @Override
    public DirectChannel createBindableChannel(String channelName, BindingProperties bindingProperties, boolean inputChannel) throws Exception {
        return super.createBindableChannel(channelName, bindingProperties, inputChannel);
    }

    @Override
    public DefaultPollableMessageSource createBindableMessageSource(String bindingName, BindingProperties bindingProperties) throws Exception {
        throw new UnsupportedOperationException("PollableMessageSource is not supported");
    }

    @Override
    public String getDestinationNameDelimiter() {
        return super.getDestinationNameDelimiter();
    }

    @Override
    public void close() {
        if (testBinder != null) {
            log.info("Destroying binder");
            testBinder.getBinder().destroy();
            testBinder = null;
        }
    }

    public <T extends AbstractSubscribableChannel> T createChannel(String channelName, Class<T> type,
                                                                   MessageHandler messageHandler)
            throws IllegalAccessException, InstantiationException {
        T channel;
        if (testBinder.getApplicationContext().containsBean(channelName)) {
            channel = testBinder.getApplicationContext().getBean(channelName, type);
        } else {
            channel = type.newInstance();
            channel.setComponentName(channelName);
            testBinder.getApplicationContext().registerBean(channelName, type, () -> channel);
        }
        channel.subscribe(messageHandler);
        return channel;
    }

    public <T> ConsumerInfrastructureUtil<T> createConsumerInfrastructureUtil(Class<T> type) {
        return new ConsumerInfrastructureUtil<>(this, type);
    }
}

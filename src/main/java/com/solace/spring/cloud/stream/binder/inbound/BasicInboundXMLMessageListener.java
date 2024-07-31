package com.solace.spring.cloud.stream.binder.inbound;

import com.solace.spring.cloud.stream.binder.inbound.acknowledge.JCSMPAcknowledgementCallbackFactory;
import com.solace.spring.cloud.stream.binder.inbound.acknowledge.SolaceAckUtil;
import com.solace.spring.cloud.stream.binder.meter.SolaceMeterAccessor;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.util.FlowReceiverContainer;
import com.solace.spring.cloud.stream.binder.util.SolaceAcknowledgmentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.core.AttributeAccessor;
import org.springframework.integration.acks.AckUtils;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public class BasicInboundXMLMessageListener extends InboundXMLMessageListener {
    private final BiFunction<Message<?>, RuntimeException, Boolean> errorHandlerFunction;

    BasicInboundXMLMessageListener(FlowReceiverContainer flowReceiverContainer,
                                   ConsumerDestination consumerDestination,
                                   ExtendedConsumerProperties<SolaceConsumerProperties> consumerProperties,
                                   Consumer<Message<?>> messageConsumer,
                                   JCSMPAcknowledgementCallbackFactory ackCallbackFactory,
                                   BiFunction<Message<?>, RuntimeException, Boolean> errorHandlerFunction,
                                   @Nullable SolaceMeterAccessor solaceMeterAccessor,
                                   @Nullable AtomicBoolean remoteStopFlag,
                                   ThreadLocal<AttributeAccessor> attributesHolder,
                                   boolean needHolderAndAttributes) {
        super(flowReceiverContainer,
                consumerDestination,
                consumerProperties,
                messageConsumer,
                ackCallbackFactory,
                solaceMeterAccessor,
                remoteStopFlag,
                attributesHolder,
                needHolderAndAttributes,
                needHolderAndAttributes);
        this.errorHandlerFunction = errorHandlerFunction;
    }

    @Override
    void handleMessage(Supplier<Message<?>> messageSupplier, Consumer<Message<?>> sendToConsumerHandler,
                       AcknowledgmentCallback acknowledgmentCallback)
            throws SolaceAcknowledgmentException {
        Message<?> message;
        try {
            message = messageSupplier.get();
        } catch (RuntimeException e) {
            boolean processedByErrorHandler = errorHandlerFunction != null && errorHandlerFunction.apply(null, e);
            if (processedByErrorHandler) {
                AckUtils.autoAck(acknowledgmentCallback);
            } else {
                log.warn("Failed to map %s to a Spring Message and no error channel " +
                        "was configured. Message will be rejected. an XMLMessage", e);
                if (!SolaceAckUtil.republishToErrorQueue(acknowledgmentCallback)) {
                    AckUtils.requeue(acknowledgmentCallback);
                }
            }
            return;
        }

        sendToConsumerHandler.accept(message);
        AckUtils.autoAck(acknowledgmentCallback);
    }
}

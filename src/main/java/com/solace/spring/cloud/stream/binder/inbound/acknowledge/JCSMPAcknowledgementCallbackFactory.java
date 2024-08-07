package com.solace.spring.cloud.stream.binder.inbound.acknowledge;

import com.solace.spring.cloud.stream.binder.util.ErrorQueueInfrastructure;
import com.solace.spring.cloud.stream.binder.util.FlowReceiverContainer;
import com.solace.spring.cloud.stream.binder.util.MessageContainer;
import com.solacesystems.jcsmp.transaction.TransactedSession;
import lombok.Setter;
import org.springframework.integration.acks.AcknowledgmentCallback;

import java.util.List;

public class JCSMPAcknowledgementCallbackFactory {
    private final FlowReceiverContainer flowReceiverContainer;
    @Setter
    private ErrorQueueInfrastructure errorQueueInfrastructure;

    public JCSMPAcknowledgementCallbackFactory(FlowReceiverContainer flowReceiverContainer) {
        this.flowReceiverContainer = flowReceiverContainer;
    }

    public AcknowledgmentCallback createCallback(MessageContainer messageContainer) {
        return createJCSMPCallback(messageContainer);
    }

    public AcknowledgmentCallback createBatchCallback(List<MessageContainer> messageContainers) {
        return new JCSMPBatchAcknowledgementCallback(messageContainers.stream()
                .map(this::createJCSMPCallback).toList());
    }

    public AcknowledgmentCallback createTransactedBatchCallback(List<MessageContainer> messageContainers,
                                                                TransactedSession transactedSession) {
        return new TransactedJCSMPAcknowledgementCallback(transactedSession, errorQueueInfrastructure);
    }

    private JCSMPAcknowledgementCallback createJCSMPCallback(MessageContainer messageContainer) {
        return new JCSMPAcknowledgementCallback(messageContainer, flowReceiverContainer,
                errorQueueInfrastructure);
    }

}

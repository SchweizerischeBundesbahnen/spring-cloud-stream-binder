package com.solace.spring.cloud.stream.binder.inbound.acknowledge;

import com.solace.spring.cloud.stream.binder.util.*;
import org.springframework.integration.acks.AcknowledgmentCallback;

import java.util.List;
import java.util.stream.Collectors;

public class JCSMPAcknowledgementCallbackFactory {
	private final Receiver receiver;
	private final boolean hasTemporaryQueue;
	private final RetryableTaskService taskService;
	private ErrorQueueInfrastructure errorQueueInfrastructure;

	public JCSMPAcknowledgementCallbackFactory(Receiver receiver, boolean hasTemporaryQueue,
                                               RetryableTaskService taskService) {
		this.receiver = receiver;
		this.hasTemporaryQueue = hasTemporaryQueue;
		this.taskService = taskService;
	}

	public void setErrorQueueInfrastructure(ErrorQueueInfrastructure errorQueueInfrastructure) {
		this.errorQueueInfrastructure = errorQueueInfrastructure;
	}

	public AcknowledgmentCallback createCallback(MessageContainer messageContainer) {
		return createJCSMPCallback(messageContainer);
	}

	public AcknowledgmentCallback createBatchCallback(List<MessageContainer> messageContainers) {
		return new JCSMPBatchAcknowledgementCallback(messageContainers.stream()
				.map(this::createJCSMPCallback)
				.collect(Collectors.toList()), receiver, taskService);
	}

	private JCSMPAcknowledgementCallback createJCSMPCallback(MessageContainer messageContainer) {
		return new JCSMPAcknowledgementCallback(messageContainer, receiver, hasTemporaryQueue,
				taskService, errorQueueInfrastructure);
	}

}

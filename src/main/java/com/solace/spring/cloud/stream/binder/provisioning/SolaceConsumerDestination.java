package com.solace.spring.cloud.stream.binder.provisioning;

import lombok.Getter;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;

import java.util.Set;
import java.util.StringJoiner;

@Getter
public class SolaceConsumerDestination implements ConsumerDestination {
	private final String bindingDestinationName;
	private final String physicalGroupName;
	private final String queueName;
	private final boolean isTemporary;
	private final String errorQueueName;
	private final Set<String> additionalSubscriptions;

	SolaceConsumerDestination(String queueName, String bindingDestinationName, String physicalGroupName,
							  boolean isTemporary, String errorQueueName, Set<String> additionalSubscriptions) {
		this.bindingDestinationName = bindingDestinationName;
		this.physicalGroupName = physicalGroupName;
		this.queueName = queueName;
		this.isTemporary = isTemporary;
		this.errorQueueName = errorQueueName;
		this.additionalSubscriptions = additionalSubscriptions;
	}

	@Override
	public String getName() {
		return queueName;
	}

	@Override
	public String toString() {
		return new StringJoiner(", ", SolaceConsumerDestination.class.getSimpleName() + "[", "]")
				.add("bindingDestinationName='" + bindingDestinationName + "'")
				.add("physicalGroupName='" + physicalGroupName + "'")
				.add("queueName='" + queueName + "'")
				.add("isTemporary=" + isTemporary)
				.add("errorQueueName='" + errorQueueName + "'")
				.toString();
	}
}

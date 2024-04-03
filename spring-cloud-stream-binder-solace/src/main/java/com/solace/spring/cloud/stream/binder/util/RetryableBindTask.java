package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.JCSMPException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Objects;
import java.util.StringJoiner;

public class RetryableBindTask implements RetryableTaskService.RetryableTask {
	private final Receiver receiver;

	private static final Log logger = LogFactory.getLog(RetryableBindTask.class);

	public RetryableBindTask(Receiver receiver) {
		this.receiver = receiver;
	}

	@Override
	public boolean run(int attempt) {
		try {
            receiver.bind();
			return true;
		} catch (JCSMPException e) {
			logger.warn(String.format("failed to bind queue %s. Will retry", receiver.getEndpointName()), e);
			return false;
		}
	}

	@Override
	public String toString() {
		return new StringJoiner(", ", RetryableBindTask.class.getSimpleName() + "[", "]")
				.add("flowReceiverContainer=" + receiver.getId())
				.toString();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		RetryableBindTask that = (RetryableBindTask) o;
		return Objects.equals(receiver.getId(), that.receiver.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hash(receiver);
	}
}

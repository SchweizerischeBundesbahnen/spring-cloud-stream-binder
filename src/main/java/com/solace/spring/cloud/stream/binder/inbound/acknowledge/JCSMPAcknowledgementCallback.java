package com.solace.spring.cloud.stream.binder.inbound.acknowledge;

import com.solace.spring.cloud.stream.binder.util.*;
import com.solacesystems.jcsmp.XMLMessage;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.lang.Nullable;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class JCSMPAcknowledgementCallback implements AcknowledgmentCallback {
	private final MessageContainer messageContainer;
	private final Receiver receiver;
	private final boolean hasTemporaryQueue;
	private final ErrorQueueInfrastructure errorQueueInfrastructure;
	private final RetryableTaskService taskService;
	private boolean acknowledged = false;
	private boolean autoAckEnabled = true;
	private boolean asyncRebind = false;
	private Future<UUID> rebindFuture;

	private static final Log logger = LogFactory.getLog(JCSMPAcknowledgementCallback.class);

	JCSMPAcknowledgementCallback(MessageContainer messageContainer, Receiver receiver,
								 boolean hasTemporaryQueue,
								 RetryableTaskService taskService,
								 @Nullable ErrorQueueInfrastructure errorQueueInfrastructure) {
		this.messageContainer = messageContainer;
		this.receiver = receiver;
		this.hasTemporaryQueue = hasTemporaryQueue;
		this.taskService = taskService;
		this.errorQueueInfrastructure = errorQueueInfrastructure;
	}

	@Override
	public void acknowledge(Status status) {
		// messageContainer.isAcknowledged() might be async set which is why we also need a local ack variable
		if (acknowledged || messageContainer.isAcknowledged()) {
			if (logger.isDebugEnabled()) {
				logger.debug(String.format("%s %s is already acknowledged", XMLMessage.class.getSimpleName(),
						messageContainer.getMessage().getMessageId()));
			}
			return;
		}

		try {
			switch (status) {
				case ACCEPT:
                    receiver.acknowledge(messageContainer);
					break;
				case REJECT:
					if (republishToErrorQueue()) {
						break;
					} else if (!hasTemporaryQueue) {
						acknowledge(Status.REQUEUE);
					} else {
						if (logger.isDebugEnabled()) {
							logger.debug(String.format(
									"Cannot %s %s %s since this flow is bound to a temporary queue, failed message " +
											"will be discarded",
									Status.REQUEUE, XMLMessage.class.getSimpleName(),
									messageContainer.getMessage().getMessageId()));
						}
                        receiver.acknowledge(messageContainer);
					}
					break;
				case REQUEUE:
					if (hasTemporaryQueue) {
						throw new UnsupportedOperationException(String.format(
								"Cannot %s XMLMessage %s, this operation is not supported with temporary queues",
								status, messageContainer.getMessage().getMessageId()));
					} else if (messageContainer.isStale()) {
						throw new SolaceStaleMessageException(String.format(
								"Message container %s (XMLMessage %s) is stale",
								messageContainer.getId(), messageContainer.getMessage().getMessageId()));
					} else {
						if (logger.isDebugEnabled()) {
							logger.debug(String.format("%s %s: Will be re-queued onto queue %s",
									XMLMessage.class.getSimpleName(), messageContainer.getMessage().getMessageId(),
                                    receiver.getEndpointName()));
						}
						if (!asyncRebind) {
							RetryableAckRebindTask rebindTask = new RetryableAckRebindTask(receiver,
									messageContainer, taskService);
							if (!rebindTask.run(0)) {
								taskService.submit(rebindTask);
							}
						} else {
							rebindFuture = receiver.futureAcknowledgeRebind(messageContainer);
						}
					}
			}
		} catch (SolaceAcknowledgmentException e) {
			throw e;
		} catch (Exception e) {
			throw new SolaceAcknowledgmentException(String.format("Failed to acknowledge XMLMessage %s",
					messageContainer.getMessage().getMessageId()), e);
		}

		acknowledged = true;
	}

	/**
	 * Send the message to the error queue and acknowledge the message.
	 *
	 * @return {@code true} if successful, {@code false} if {@code errorQueueInfrastructure} is not defined.
	 */
	private boolean republishToErrorQueue() throws SolaceStaleMessageException {
		if (errorQueueInfrastructure == null) {
			return false;
		}

		if (logger.isDebugEnabled()) {
			logger.debug(String.format("%s %s: Will be republished onto error queue %s",
					XMLMessage.class.getSimpleName(), messageContainer.getMessage().getMessageId(),
					errorQueueInfrastructure.getErrorQueueName()));
		}

		if (messageContainer.isStale()) {
			throw new SolaceStaleMessageException(String.format("Cannot republish failed message container %s " +
							"(XMLMessage %s) to error queue %s. Message is stale and will be redelivered.",
					messageContainer.getId(), messageContainer.getMessage().getMessageId(),
					errorQueueInfrastructure.getErrorQueueName()));
		}

		errorQueueInfrastructure.createCorrelationKey(messageContainer, receiver, hasTemporaryQueue)
				.handleError(false);
		return true;
	}

	@Override
	public boolean isAcknowledged() {
		return acknowledged || messageContainer.isAcknowledged();
	}

	@Override
	public void noAutoAck() {
		autoAckEnabled = false;
	}

	@Override
	public boolean isAutoAck() {
		return autoAckEnabled;
	}

	MessageContainer getMessageContainer() {
		return messageContainer;
	}

	/**
	 * Directs this acknowledgment callback to do an asynchronous rebind if rebinding occurs during acknowledgment.
	 */
	void doAsyncRebindIfNecessary() {
		asyncRebind = true;
	}

	/**
	 * If async-rebind was enabled and rebind was performed on this acknowledgment callback,
	 * wait for rebind to finish.
	 * @param timeout Timeout value, wait forever if timeout < 0
	 * @param unit Timeout units
	 * @throws ExecutionException rebind exception
	 * @throws InterruptedException wait was interrupted
	 * @throws TimeoutException timeout elapsed
	 */
	void awaitRebindIfNecessary(long timeout, TimeUnit unit)
			throws ExecutionException, InterruptedException, TimeoutException {
		if (rebindFuture != null) {
			if (timeout >= 0) {
				rebindFuture.get(timeout, unit);
			} else {
				rebindFuture.get();
			}
		}
	}


}

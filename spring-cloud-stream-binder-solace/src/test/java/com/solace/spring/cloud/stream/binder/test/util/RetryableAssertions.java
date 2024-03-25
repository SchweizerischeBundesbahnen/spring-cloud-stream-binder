package com.solace.spring.cloud.stream.binder.test.util;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.SoftAssertionsProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class RetryableAssertions {
	public static Duration RETRY_INTERVAL = Duration.ofMillis(500);

	public static void retryAssert(SoftAssertionsProvider.ThrowingRunnable assertRun) throws InterruptedException {
		retryAssert(30, TimeUnit.SECONDS, assertRun);
	}
	public static void retryAssert(SoftAssertionsProvider.ThrowingRunnable assertRun, long timeout, TimeUnit unit)
			throws InterruptedException {
		retryAssert(timeout,unit,assertRun);
	}
	@SuppressWarnings("BusyWait")
	public static void retryAssert(long timeout, TimeUnit unit, SoftAssertionsProvider.ThrowingRunnable assertRun)
			throws InterruptedException {
		final long expiry = System.currentTimeMillis() + unit.toMillis(timeout);
		SoftAssertions softAssertions;
		do {
			softAssertions = new SoftAssertions();
			softAssertions.check(assertRun);
			if (!softAssertions.wasSuccess()) {
				Thread.sleep(RETRY_INTERVAL.toMillis());
			}
		} while (!softAssertions.wasSuccess() && System.currentTimeMillis() < expiry);
		softAssertions.assertAll();
	}
}

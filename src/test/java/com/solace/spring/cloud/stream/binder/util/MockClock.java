package com.solace.spring.cloud.stream.binder.util;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public class MockClock implements Clock {
    private long timeNanos = TimeUnit.MILLISECONDS.toNanos(1);

    public long add(long amount, TimeUnit unit) {
        timeNanos += unit.toNanos(amount);
        return timeNanos;
    }

    public long add(Duration duration) {
        return add(duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    public long addSeconds(long amount) {
        return add(amount, TimeUnit.SECONDS);
    }

    @Override
    public Instant now() {
        return Instant.ofEpochMilli(TimeUnit.NANOSECONDS.toMillis(timeNanos));
    }

    @Override
    public long monotonicTime() {
        return 0;
    }

    public void setCurrentTime(Instant ts) {
        timeNanos = TimeUnit.MILLISECONDS.toNanos(ts.toEpochMilli());
    }
}

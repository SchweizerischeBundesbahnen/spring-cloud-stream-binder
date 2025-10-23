package com.solace.spring.cloud.stream.binder.util;

import java.time.Instant;


public interface Clock {
    Clock SYSTEM = new Clock() {
        @Override
        public Instant now() {
            return Instant.now();
        }

        @Override
        public long monotonicTime() {
            return System.nanoTime();
        }
    };

    /**
     * Current wall time in milliseconds since the epoch. Typically equivalent to
     * System.currentTimeMillis.
     */
    Instant now();

    /**
     * Current time from a monotonic clock source. The value is only meaningful when
     * compared with another snapshot to determine the elapsed time for an operation. The
     * difference between two samples will have a unit of nanoseconds. The returned value
     * is typically equivalent to System.nanoTime.
     * @return Monotonic time in nanoseconds
     */
    long monotonicTime();
}

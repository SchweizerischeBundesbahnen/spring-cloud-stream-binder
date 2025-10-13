package com.solace.spring.cloud.stream.binder.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class WatchdogLogger {
    private long latestWarning = System.currentTimeMillis();

    public void warnIfNecessary(Integer timeBetweenWarningsS, Integer urgentWarningMultiplier, Integer threadCount, Integer messageQueueSize) {
        long now = System.currentTimeMillis();
        long timeSinceLastWarning = now - latestWarning;
        if (timeSinceLastWarning > TimeUnit.SECONDS.toMillis(timeBetweenWarningsS) && messageQueueSize > threadCount) {
            if (messageQueueSize > threadCount * urgentWarningMultiplier) {
                logUrgent(threadCount, messageQueueSize, urgentWarningMultiplier);
            } else {
                logRelaxed(threadCount, messageQueueSize);
            }
            latestWarning = now;
        }
    }

    public void logUrgent(Integer threadCount, Integer messageQueueSize, Integer urgentWarningMultiplier) {
        log.warn("Too many messages in queue! {} times more messages than threads, check what is causing the congestion: messages={}, threads={}", urgentWarningMultiplier, messageQueueSize, threadCount);
    }

    public void logRelaxed(Integer threadCount, Integer messageQueueSize) {
        log.warn("More messages in queue than threads: messages={}, threads={}.", messageQueueSize, threadCount);
    }

    public void setLatestWarning(Long latestWarning) {
        this.latestWarning = latestWarning;
    }
}

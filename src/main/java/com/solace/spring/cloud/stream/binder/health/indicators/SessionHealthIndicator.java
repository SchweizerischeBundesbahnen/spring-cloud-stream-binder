package com.solace.spring.cloud.stream.binder.health.indicators;

import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import com.solacesystems.jcsmp.SessionEventArgs;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@NoArgsConstructor
public class SessionHealthIndicator extends SolaceHealthIndicator {
    private final ReentrantLock writeLock = new ReentrantLock();

    public void up() {
        writeLock.lock();
        try {
            super.healthUp();
        } finally {
            writeLock.unlock();
        }
    }

    public void reconnecting(@Nullable SessionEventArgs eventArgs) {
        writeLock.lock();
        try {
            if (log.isDebugEnabled()) {
                log.debug("Solace connection is reconnecting, immediately changing state to down");
            }
            super.healthDown(eventArgs);
        } finally {
            writeLock.unlock();
        }
    }

    public void down(@Nullable SessionEventArgs eventArgs) {
        writeLock.lock();
        try {
            super.healthDown(eventArgs);
        } finally {
            writeLock.unlock();
        }
    }
}
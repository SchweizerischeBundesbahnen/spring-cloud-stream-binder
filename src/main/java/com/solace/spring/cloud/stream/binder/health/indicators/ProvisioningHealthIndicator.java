package com.solace.spring.cloud.stream.binder.health.indicators;

import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import com.solacesystems.jcsmp.FlowEventArgs;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@NoArgsConstructor
public class ProvisioningHealthIndicator extends SolaceHealthIndicator {
    private final ReentrantLock writeLock = new ReentrantLock();

    public void down(@Nullable FlowEventArgs flowEventArgs) {
        writeLock.lock();
        try {
            super.healthDown(flowEventArgs);
        } finally {
            writeLock.unlock();
        }
    }
}

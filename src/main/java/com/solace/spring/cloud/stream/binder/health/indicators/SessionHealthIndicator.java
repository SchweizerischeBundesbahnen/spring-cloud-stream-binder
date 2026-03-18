package com.solace.spring.cloud.stream.binder.health.indicators;

import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import com.solacesystems.jcsmp.SessionEventArgs;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

@Slf4j
@NoArgsConstructor
public class SessionHealthIndicator extends SolaceHealthIndicator {

    public void up() {
        super.healthUp();
    }

    public void reconnecting(@Nullable SessionEventArgs eventArgs) {
        if (log.isDebugEnabled()) {
            log.debug("Solace connection is reconnecting, immediately changing state to down");
        }
        super.healthDown(eventArgs);
    }

    public void down(@Nullable SessionEventArgs eventArgs) {
        super.healthDown(eventArgs);
    }
}

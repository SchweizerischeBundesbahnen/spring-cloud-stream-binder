package com.solace.spring.cloud.stream.binder.health.indicators;

import com.solace.spring.cloud.stream.binder.health.base.SolaceHealthIndicator;
import com.solacesystems.jcsmp.SessionEventArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.lang.Nullable;

@Slf4j
public class SessionHealthIndicator extends SolaceHealthIndicator {
    private static final String NO_SESSION_YET = "no session connected yet";

    public SessionHealthIndicator() {
        super(Health.unknown().withDetail(INFO, NO_SESSION_YET).build());
    }

    public boolean hasNotSeenASessionYet() {
        return Status.UNKNOWN.equals(health().getStatus());
    }

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

    public void connectFailed(Throwable cause) {
        setHealth(Health.down(cause).build());
    }
}

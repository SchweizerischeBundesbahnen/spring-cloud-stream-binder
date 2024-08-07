package com.solace.spring.cloud.stream.binder.health.handlers;

import com.solace.spring.cloud.stream.binder.health.indicators.SessionHealthIndicator;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class SolaceSessionEventHandler implements SessionEventHandler {
    private final SessionHealthIndicator sessionHealthIndicator;

    @Override
    public void handleEvent(SessionEventArgs eventArgs) {
        if (log.isDebugEnabled()) {
            log.debug(String.format("Received Solace JCSMP Session event [%s]", eventArgs));
        }
        switch (eventArgs.getEvent()) {
            case RECONNECTED -> this.sessionHealthIndicator.up();
            case DOWN_ERROR -> this.sessionHealthIndicator.down(eventArgs);
            case RECONNECTING -> this.sessionHealthIndicator.reconnecting(eventArgs);
        }
    }

    public void setSessionHealthUp() {
        this.sessionHealthIndicator.up();
    }
}

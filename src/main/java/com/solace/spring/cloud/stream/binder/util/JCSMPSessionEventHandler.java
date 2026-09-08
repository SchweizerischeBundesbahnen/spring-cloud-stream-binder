package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class JCSMPSessionEventHandler implements SessionEventHandler, SolaceOAuth2SessionEventHandler {
    private final List<SessionEventHandler> sessionEventHandlers = new ArrayList<>();
    private final List<Runnable> afterReconnectTasks = new ArrayList<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final AtomicReference<JCSMPSession> jcsmpSession = new AtomicReference<>();

    public void addSessionEventHandler(SessionEventHandler sessionEventHandler) {
        synchronized (sessionEventHandlers) {
            if (sessionEventHandlers.contains(sessionEventHandler)) {
                return;
            }
            sessionEventHandlers.add(sessionEventHandler);
            if (jcsmpSession.get() != null && sessionEventHandler instanceof SolaceOAuth2SessionEventHandler sessionEventHandlerOAuth2) {
                sessionEventHandlerOAuth2.setJcsmpSession(jcsmpSession.get());
            }
        }
    }

    public void removeSessionEventHandler(SessionEventHandler sessionEventHandler) {
        synchronized (sessionEventHandlers) {
            sessionEventHandlers.remove(sessionEventHandler);
        }
    }

    public void addAfterReconnectTask(Runnable afterReconnectTask) {
        synchronized (afterReconnectTasks) {
            afterReconnectTasks.add(afterReconnectTask);
        }
    }

    public void removeAfterReconnectTask(Runnable afterReconnectTask) {
        synchronized (afterReconnectTasks) {
            afterReconnectTasks.remove(afterReconnectTask);
        }
    }

    @Override
    public void handleEvent(SessionEventArgs sessionEventArgs) {
        synchronized (sessionEventHandlers) {
            sessionEventHandlers.forEach(sessionEventHandler -> sessionEventHandler.handleEvent(sessionEventArgs));
        }
        if (SessionEvent.RECONNECTED.equals(sessionEventArgs.getEvent())) {
            synchronized (afterReconnectTasks) {
                afterReconnectTasks.forEach(executorService::submit);
            }
        }
    }

    @Override
    public void setJcsmpSession(JCSMPSession jcsmpSession) {
        this.jcsmpSession.set(jcsmpSession);
        synchronized (sessionEventHandlers) {
            for (var sessionEventHandler : sessionEventHandlers) {
                if (sessionEventHandler instanceof SolaceOAuth2SessionEventHandler sessionEventHandlerOAuth2) {
                    sessionEventHandlerOAuth2.setJcsmpSession(jcsmpSession);
                }
            }
        }
    }
}

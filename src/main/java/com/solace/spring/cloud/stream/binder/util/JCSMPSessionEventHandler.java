package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.SessionEvent;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class JCSMPSessionEventHandler implements SessionEventHandler {
    private final List<SessionEventHandler> sessionEventHandlers = new ArrayList<>();
    private final List<Runnable> afterReconnectTasks = new ArrayList<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public void addSessionEventHandler(SessionEventHandler sessionEventHandler) {
        synchronized (sessionEventHandlers) {
            sessionEventHandlers.add(sessionEventHandler);
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
}

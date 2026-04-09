package com.solace.spring.cloud.stream.binder.util;

import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;

@Slf4j
abstract class SharedResourceManager<T> {
    private final String type;
    T sharedResource;
    private final Set<String> registeredIds = new HashSet<>();
    private final Object lock = new Object();

    SharedResourceManager(String type) {
        this.type = type;
    }

    abstract T create() throws Exception;

    abstract void close();

    /**
     * Register {@code key} to the shared resource.
     * <p>If this is the first key to claim this shared resource, {@link #create()} the resource.
     *
     * @param key the registration key of the caller that wants to use this resource
     * @return the shared resource
     * @throws Exception whatever exception that may be thrown by {@link #create()}
     */
    public T get(String key) throws Exception {
        synchronized (lock) {
            if (registeredIds.isEmpty()) {
                log.info("No {} exists, a new one will be created", type);
                sharedResource = create();
            } else {
                log.trace("A message {} already exists, reusing it", type);
            }

            registeredIds.add(key);
        }

        return sharedResource;
    }

    /**
     * De-register {@code key} from the shared resource.
     * <p>If this is the last {@code key} associated to the shared resource, {@link #close()} the resource.
     *
     * @param key the registration key of the caller that is using the resource
     */
    public void release(String key) {
        synchronized (lock) {
            if (!registeredIds.contains(key)) return;

            if (registeredIds.size() <= 1) {
                log.info("{} is the last user, closing {}...", key, type);
                close();
                sharedResource = null;
            } else {
                log.info("{} is not the last user, persisting {}...", key, type);
            }
            registeredIds.remove(key);
        }
    }
}

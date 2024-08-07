package com.solace.test.integration.junit.jupiter.extension;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>Junit 5 extension to auto-create and delete executor services.</p>
 * <p>Can be accessed with test parameters:</p>
 * <pre><code>
 *    {@literal @}ExtendWith(ExecutorServiceExtension.class)
 * 	public class Test {
 *        {@literal @}Test
 * 		public void testMethod({@literal @}ExecSvc ExecutorService executorService) { // param type can be any subclass of ExecutorService
 * 			// Test logic using executor service
 *    }
 *  }
 * </code></pre>
 */
@Slf4j
public class ExecutorServiceExtension implements ParameterResolver {
    private static final Namespace NAMESPACE = Namespace.create(ExecutorServiceExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return ExecutorService.class.isAssignableFrom(parameterContext.getParameter().getType()) &&
                parameterContext.isAnnotated(ExecSvc.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        ExecSvc config = parameterContext.findAnnotation(ExecSvc.class).orElseThrow(() ->
                new ParameterResolutionException(String.format("parameter %s is not annotated with %s",
                        parameterContext.getParameter().getName(), ExecSvc.class)));

        return extensionContext.getStore(NAMESPACE).getOrComputeIfAbsent(ExecutorServiceResource.class,
                c -> {
                    ExecutorService executorService;
                    int poolSize = config.poolSize();
                    if (config.scheduled()) {
                        if (poolSize < 1) {
                            throw new ParameterResolutionException(
                                    "Pool size must be > 1 for scheduled executor services");
                        }
                        log.info("Creating scheduled thread pool with core pool size {}", poolSize);
                        executorService = Executors.newScheduledThreadPool(poolSize);
                    } else if (poolSize < 1) {
                        log.info("Creating cached thread pool");
                        executorService = Executors.newCachedThreadPool();
                    } else {
                        log.info("Creating fixed thread pool of size {}", poolSize);
                        executorService = Executors.newFixedThreadPool(poolSize);
                    }
                    return new ExecutorServiceResource(executorService);
                }, ExecutorServiceResource.class).getExecutorService();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface ExecSvc {
        int poolSize() default 0;

        boolean scheduled() default false;
    }

    @Slf4j
    private static final class ExecutorServiceResource implements ExtensionContext.Store.CloseableResource {
        private final ExecutorService executorService;

        private ExecutorServiceResource(ExecutorService executorService) {
            this.executorService = executorService;
        }

        public ExecutorService getExecutorService() {
            return executorService;
        }

        @Override
        public void close() throws Throwable {
            log.info("Shutting down executor service");
            executorService.shutdownNow();
            if (!executorService.awaitTermination(1, TimeUnit.MINUTES)) {
                log.error("Could not shutdown executor");
            }
        }
    }
}

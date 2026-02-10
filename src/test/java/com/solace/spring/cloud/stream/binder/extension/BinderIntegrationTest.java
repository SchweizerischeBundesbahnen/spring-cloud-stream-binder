package com.solace.spring.cloud.stream.binder.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom annotation to mark a test class as a Binder integration test.
 * This annotation is designed to work with the Spring Boot Integration Test
 * extension infrastructure and provides enhanced lifecycle management,
 * parameter injection, and utility features for integration testing.
 * <p>
 * Key features of {@code @BinderIntegrationTest} include:
 * - Integration with the binder-based testing infrastructure for seamless testing of
 *   Spring Boot applications.
 * - Automatic setup and teardown of test-specific resources.
 * see {@link DynamicPropertiesTestContextCustomizerFactory} for more information
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface BinderIntegrationTest {
    boolean disableSolaceContainer() default false;

    boolean multiBinderEnabled() default false;
}

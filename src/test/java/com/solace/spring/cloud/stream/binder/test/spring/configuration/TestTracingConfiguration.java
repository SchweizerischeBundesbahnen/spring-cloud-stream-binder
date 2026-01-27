package com.solace.spring.cloud.stream.binder.test.spring.configuration;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestTracingConfiguration {
    @Bean
    public Resource resource() {
        return Resource.getDefault();
    }

    @Bean
    public OpenTelemetry openTelemetry() {
        ContextPropagators propagators = ContextPropagators.create(
                TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance()
                )
        );
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .setMeterProvider(SdkMeterProvider.builder().build())
                .setLoggerProvider(SdkLoggerProvider.builder().build())
                .setPropagators(propagators)
                .build();
    }

}

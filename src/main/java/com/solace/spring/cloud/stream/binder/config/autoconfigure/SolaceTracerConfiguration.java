package com.solace.spring.cloud.stream.binder.config.autoconfigure;

import com.solace.spring.cloud.stream.binder.tracing.TracingImpl;
import com.solace.spring.cloud.stream.binder.tracing.TracingProxy;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@ConditionalOnBean({Tracer.class, Propagator.class})
@Configuration
@AutoConfiguration(after = MicrometerTracingAutoConfiguration.class)
public class SolaceTracerConfiguration {

    @Bean
    public TracingImpl tracingImpl(@Autowired Tracer tracer, @Autowired Propagator propagator) {
        return new TracingImpl(tracer, propagator);
    }

    @Bean
    public TracingProxy tracingProxy(TracingImpl tracing) {
        return new TracingProxy(tracing);
    }
}

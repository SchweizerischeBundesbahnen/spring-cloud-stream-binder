package com.solace.spring.cloud.stream.binder.springBootTests.healthindicator.programmaticstart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.spring.cloud.stream.binder.extension.DynamicPropertiesTestContextCustomizerFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.stream.binding.BindingsLifecycleController;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

@Disabled // STTRS-2869 re-enable when health-page contributors are reliable
@Isolated
public class SolaceBinderHealthProgrammaticStartIT {


    private static final Class<?> APP = ProgrammaticStartApp.class;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static ConfigurableApplicationContext startApp(String... extraProps) {
        List<String> baseProps = new ArrayList<>();
        baseProps.add("server.port=0");
        baseProps.add("spring.main.banner-mode=off");
        baseProps.add("management.metrics.export.wavefront.enabled=false");
        baseProps.add("management.wavefront.enabled=false"); // if wavefront starter is present

        // allow callers to add/override
        baseProps.addAll(Arrays.asList(extraProps));

        return new SpringApplicationBuilder(APP)
                .initializers((ApplicationContextInitializer<GenericApplicationContext>) gac -> {
                    // Example: override/register a bean for tests
                    gac.registerBean(ProgrammaticStartConsumer.class, ProgrammaticStartConsumer::new, bd -> bd.setPrimary(true));
                    gac.registerBean(DynamicPropertiesTestContextCustomizerFactory.DynamicPropertiesContextCustomizer.class, () -> new DynamicPropertiesTestContextCustomizerFactory.DynamicPropertiesContextCustomizer(false, false));
                }, new DynamicPropertiesTestContextCustomizerFactory.DynamicPropertiesApplicationContextCustomizer(false, false))
                .profiles("programmatic-start") // set your test profile(s)
                .properties(baseProps.toArray(String[]::new))                        // per-run overrides
                .run();
    }


    @Test
    void applyBrokenConfig_healthShouldBeDownWhenStartedProgrammatically() throws Exception {
        ConfigurableApplicationContext context = startApp();

        Environment environment = context.getEnvironment();
        BindingsLifecycleController lifecycleController = context.getBean(BindingsLifecycleController.class);

        int port = Integer.parseInt(environment.getProperty("local.server.port"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:%s/actuator/health".formatted(port)))
                .headers("Content-Type", "application/json;charset=UTF-8")
                .GET()
                .build();

        lifecycleController.start("receiveProgrammaticStart-in-0");

        //give application time to reach down state
        await().pollInterval(Duration.ofSeconds(1))
                .atMost(2, TimeUnit.MINUTES).until(() -> {
                    String healthResponseBody = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
                    return objectMapper.readTree(healthResponseBody).get("status").asText().equals("DOWN");
                });
    }
}

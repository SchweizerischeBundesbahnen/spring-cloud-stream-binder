package ch.sbb.example;

import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.binding.BindingsLifecycleController;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

@SpringBootApplication
@RestController
public class ProgrammaticBindingControlApp {
    static final String BINDING_NAME = "controlledConsumer-in-0";

    private static final Logger log = LoggerFactory.getLogger(ProgrammaticBindingControlApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();

    private final StreamBridge streamBridge;
    private final BindingsLifecycleController bindingsLifecycleController;

    public ProgrammaticBindingControlApp(StreamBridge streamBridge,
                                         BindingsLifecycleController bindingsLifecycleController) {
        this.streamBridge = streamBridge;
        this.bindingsLifecycleController = bindingsLifecycleController;
    }

    public static void main(String[] args) {
        SpringApplication.run(ProgrammaticBindingControlApp.class, args);
    }

    @PostMapping("/send")
    public String publish(@RequestBody String payload) {
        streamBridge.send("controlledPublisher-out-0", MessageBuilder.withPayload(payload)
                .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                .build());
        log.info("Published to controlled topic: {}", payload);
        return "Sent " + payload;
    }

    @PostMapping("/bindings/start")
    public String startBinding() {
        bindingsLifecycleController.start(BINDING_NAME);
        log.info("Started binding {}", BINDING_NAME);
        return "Started " + BINDING_NAME;
    }

    @PostMapping("/bindings/stop")
    public String stopBinding() {
        bindingsLifecycleController.stop(BINDING_NAME);
        log.info("Stopped binding {}", BINDING_NAME);
        return "Stopped " + BINDING_NAME;
    }

    @Bean
    public Consumer<String> controlledConsumer() {
        return payload -> {
            log.info("Received from controlled consumer: {}", payload);
            RECEIVED.offer(payload);
        };
    }
}
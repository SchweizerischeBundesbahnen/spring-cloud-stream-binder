# Micrometer Tracing

## Overview
Automatically pushes OpenTelemetry headers directly inline with Solace SMF envelopes. It tracks traces across producer down to consumer seamlessly.

## Key Properties (`pom.xml`)
- Adds `spring-boot-micrometer-tracing-opentelemetry` to inject parent/child span IDs automatically onto outgoing headers and unwrap them on incoming callbacks.

## Running Locally
In the `examples/micrometer-tracing` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
Logs and tracking platforms natively surface combined span trees passing directly through the Solace broker via intrinsic header insertions.

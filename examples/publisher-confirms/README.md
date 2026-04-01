# Publisher Confirmations

## Overview
Intercept Solace JCSMP native producer Correlation IDs to track internally whether explicitly sent messages successfully arrived onto the Solace Broker instance inherently natively.

## Key Properties (`application.yml`)
- Set `ErrorChannelSendingCorrelationKey` or utilize `SolaceBinderHeaders.CONFIRM_CORRELATION` alongside `CorrelationData`. 
- Observe internal `correlationData.getFuture().get()` to block sequentially for exact completion acknowledgments synchronously from the server-side Broker.

## Running Locally
In the `examples/publisher-confirms` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
Explicit assertions successfully confirming delivery track the inner Future objects native to the Solace APIs asynchronously! Any publisher timeouts are transparently handled.

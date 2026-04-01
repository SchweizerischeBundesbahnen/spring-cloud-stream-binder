# Queue Provisioning Options

## Overview
Displays advanced Solace natively mapped queue tuning via direct YAML metadata properties including Error properties or explicit physical Queue creation properties directly natively mapped.

## Key Properties (`application.yml`)
- Advanced toggles like `provisionDurableQueue` bypass runtime Endpoint allocation allowing usage against Pre-Provisioned physical mappings!
- Options such as `queueNameExpression` let you dynamically orchestrate exact queue syntax rules cleanly utilizing inline variables!

## Running Locally
In the `examples/queue-provisioning-options` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
During startup, physical Endpoints mapped successfully apply properties like extra subscriptions implicitly, natively mapping accurately directly internally over SEMP via internal provisioning loops accurately.

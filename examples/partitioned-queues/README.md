# Partitioned Queues

## Overview
Ensure strict ordering across concurrent consumers using natively enforced Solace Partitioned Queues matched strictly to your payload's embedded partition key.

## Key Properties (`application.yml`)
- Ensure the Publisher inserts the specific Partition Key (`SolaceBinderHeaders.PARTITION_KEY`).
- Rely upon Solace Brokers natively routing similarly-keyed payloads strictly down the exact same physical flow context internally to prevent interleaving.

## Running Locally
In the `examples/partitioned-queues` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
Even if scaling out linearly, consumers will consistently receive identical partition keys routed precisely towards exactly the same consumer instance ensuring ordering guarantees native to Solace.

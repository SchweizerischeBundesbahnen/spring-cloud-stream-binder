# OAuth2 Authentication

## Overview
Provides detailed code implementations replacing static basic-auth credentials with rotating OAuth2 tokens utilizing `mock-oauth2-server`. It authenticates the Solace JCSMP session using OAuth2 client credentials natively.

## Key Properties (`application.yml`)
- Configure standard Spring Security metrics linking your Broker to your explicit Authorization Server via JWKS bindings natively.
- Use TLS (`tcps://`) implicitly while specifying OAuth configuration under `spring.security.oauth2.client.provider`.

## Running Locally
In the `examples/oauth2-authentication` directory run:

```bash
docker-compose up -d
mvn spring-boot:run
```

## Expected Behavior
The internal Session seamlessly authenticates using JWTs signed by your native Auth Server without relying on traditional passwords, reconnecting gracefully if the tokens rotate or expire physically.

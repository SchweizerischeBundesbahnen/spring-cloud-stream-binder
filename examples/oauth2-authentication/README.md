# OAuth2 Authentication

Demonstrates how to replace static username/password credentials with rotating OAuth2 tokens for authenticating the Solace JCSMP session. The binder uses Spring Security's OAuth2 client credentials flow to obtain and automatically refresh JWT tokens.

## Features Demonstrated

- Configuring Spring Security OAuth2 client credentials for Solace authentication
- Using `mock-oauth2-server` as a lightweight identity provider for testing
- How the binder's `SolaceSessionOAuth2TokenProvider` integrates with the JCSMP session
- Automatic token refresh on expiry

## Prerequisites

- Java 17+
- Docker (for the Solace broker and mock OAuth2 server)

## How to Run

**Option A — Automated test:**

```bash
mvn verify
```

The integration test starts both a Solace broker and a `mock-oauth2-server` via Testcontainers, configures the broker's OAuth profile via SEMP, and verifies authenticated message consumption.

**Option B — Interactive with Docker Compose:**

```bash
docker-compose up -d
mvn spring-boot:run
```

## Configuration Explained

```yaml
solace:
  java:
    host: tcps://localhost:55443                    # (1)
    msgVpn: default
    apiProperties:
      ssl_validate_certificate: false              # (2)

spring:
  security:
    oauth2:
      client:
        registration:
          solace-broker:                           # (3)
            provider: mock-oauth
            client-id: test-client
            client-secret: test-secret
            authorization-grant-type: client_credentials
        provider:
          mock-oauth:
            token-uri: http://localhost:8081/solace/token  # (4)

  cloud:
    stream:
      bindings:
        oauthConsumer-in-0:
          destination: example/oauth/topic
          group: oauth-group
```

1. **`tcps://`** — TLS connection. OAuth2 authentication typically requires TLS to protect the JWT tokens in transit.
2. **`ssl_validate_certificate: false`** — Disables certificate validation for testing. In production, configure a proper trust store.
3. **`solace-broker` registration** — The OAuth2 client credentials registration. The binder detects this and uses it to request tokens from the OAuth2 provider.
4. **`token-uri`** — The OAuth2 token endpoint. For testing, this points to `mock-oauth2-server`. In production, this would be your organization's identity provider (Keycloak, Azure AD, Okta, etc.).

### Broker-Side Setup

The Solace broker must be configured to accept OAuth2 tokens. This is typically done via SEMP:

1. Enable OAuth authentication on the Message VPN.
2. Create an OAuth profile pointing at the identity provider's JWKS endpoint.
3. The broker validates incoming JWT tokens against the JWKS keys.

In the integration test, this setup is performed automatically in `@BeforeAll`.

## Code Walkthrough

```java
@SpringBootApplication
@EnableScheduling
public class OAuth2App {
    private static final Logger log = LoggerFactory.getLogger(OAuth2App.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private final StreamBridge streamBridge;

    public OAuth2App(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(OAuth2App.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        streamBridge.send("oauthPublisher-out-0", "oauth-secured-msg");
        log.info("Published authenticated message");
    }
}
```

The application code is identical to a standard publisher/consumer. The OAuth2 authentication is handled entirely by the binder and Spring Security — no authentication code is needed in the application beans.

**How the authentication flow works:**

1. On startup, the binder detects the `spring.security.oauth2.client.registration.solace-broker` configuration.
2. The binder's `SolaceSessionOAuth2TokenProvider` requests a JWT token from the token endpoint using the client credentials grant.
3. The binder creates the JCSMP session using the JWT token instead of username/password.
4. When the token expires, the binder automatically requests a new token and reconnects.

## What to Observe

```
INFO  Received authenticated message: oauth-secured-msg
```

The consumer successfully receives messages over an OAuth2-authenticated session. No username/password is used.

**Token refresh:** The binder monitors token expiry and automatically obtains a new token before the current one expires, ensuring uninterrupted connectivity.

## Why `mock-oauth2-server`?

| Feature | `mock-oauth2-server` | Keycloak |
|---|---|---|
| Image size | ~50 MB | ~400 MB |
| Startup time | 2–3 seconds | 15–30 seconds |
| Configuration | Zero (auto-creates issuers by URL path) | Requires realm import |
| ARM64 support | Native | Requires emulation |
| TLS proxy needed | No | Often yes |

## When to Use This Pattern

- Enterprise environments requiring OAuth2/OIDC authentication
- Replacing static credentials with rotating tokens
- Integration with corporate identity providers (Azure AD, Okta, Keycloak)
- Zero-trust security architectures

## Related API Documentation

- [Solace Session Properties](../../API.md#solace-session-properties) — `solace.java.apiProperties` for TLS configuration
- [Creating a Simple Solace Binding](../../API.md#creating-a-simple-solace-binding) — Basic session configuration

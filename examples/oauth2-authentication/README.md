# OAuth2 Authentication

Demonstrates how to replace static username/password credentials with rotating OAuth2 tokens for authenticating the Solace JCSMP session. The binder uses Spring Security's OAuth2 client credentials flow to obtain and automatically refresh JWT tokens.

## Features Demonstrated

- Configuring Spring Security OAuth2 client credentials for Solace authentication
- Using `mock-oauth2-server` as a lightweight identity provider for configuration testing
- How the binder's `SolaceSessionOAuth2TokenProvider` integrates with the JCSMP session
- A runnable application shape for an OAuth-enabled Solace broker

## Prerequisites

- Java 17+
- For an interactive run: an OAuth-enabled Solace broker, TLS configuration, and an OAuth2 token issuer

## How to Run

**Option A — Automated test:**

```bash
mvn verify
```

The included automated test is intentionally lightweight. It starts `mock-oauth2-server`, verifies token issuance, and asserts that Spring Boot auto-configures `SolaceSessionOAuth2TokenProvider` correctly. Full end-to-end OAuth2 broker coverage lives in the main binder integration suite.

**Option B — Interactive with your own OAuth-enabled broker:**

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--solace.java.host=tcps://broker.example.com:55443 --solace.java.msgVpn=default --solace.java.oauth2ClientRegistrationId=solace-broker --solace.java.apiProperties.AUTHENTICATION_SCHEME=AUTHENTICATION_SCHEME_OAUTH2 --solace.java.apiProperties.ssl_validate_certificate=false --spring.security.oauth2.client.registration.solace-broker.provider=my-idp --spring.security.oauth2.client.registration.solace-broker.client-id=my-client-id --spring.security.oauth2.client.registration.solace-broker.client-secret=my-client-secret --spring.security.oauth2.client.registration.solace-broker.authorization-grant-type=client_credentials --spring.security.oauth2.client.provider.my-idp.token-uri=https://idp.example.com/oauth2/token"
```

Replace the broker host, client credentials, and token URI with values from your environment. In production, keep certificate validation enabled and configure a trust store instead of `ssl_validate_certificate=false`.

## Configuration Explained

```yaml
solace:
  java:
    host: tcps://localhost:55443                    # (1)
    msgVpn: default
    oauth2ClientRegistrationId: solace-broker      # (2)
    apiProperties:
      AUTHENTICATION_SCHEME: AUTHENTICATION_SCHEME_OAUTH2  # (3)
      ssl_validate_certificate: false              # (4)

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
            token-uri: http://localhost:8081/solace/token  # (5)

  cloud:
    stream:
      bindings:
        oauthConsumer-in-0:
          destination: example/oauth/topic
          group: oauth-group
```

1. **`tcps://`** — TLS connection. OAuth2 authentication typically requires TLS to protect the JWT tokens in transit.
2. **`oauth2ClientRegistrationId`** — Tells the binder which Spring Security OAuth2 client registration to use.
3. **`AUTHENTICATION_SCHEME_OAUTH2`** — Switches the Solace JCSMP session from username/password authentication to OAuth2 bearer tokens.
4. **`ssl_validate_certificate: false`** — Disables certificate validation for testing. In production, configure a proper trust store.
5. **`token-uri`** — The OAuth2 token endpoint. In tests this is overridden dynamically to the `mock-oauth2-server` instance. In production, this would be your organization's identity provider (Keycloak, Azure AD, Okta, etc.).

### Broker-Side Setup

The Solace broker must be configured to accept OAuth2 tokens. This is typically done via SEMP:

1. Enable OAuth authentication on the Message VPN.
2. Create an OAuth profile pointing at the identity provider's JWKS endpoint.
3. The broker validates incoming JWT tokens against the JWKS keys.

This repository's lightweight example test does **not** provision that broker-side OAuth2 setup. Use the interactive run only against a broker that is already configured for OAuth2, or refer to the main binder integration tests for full end-to-end coverage.

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
      streamBridge.send("oauthPublisher-out-0", MessageBuilder.withPayload("oauth-secured-msg")
          .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
          .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
          .build());
        log.info("Published authenticated message");
    }
}
```

  The application code is still effectively identical to a standard publisher/consumer, apart from the standard 30 second TTL and `solace_dmqEligible=true` headers on outbound messages. OAuth2 authentication is handled entirely by the binder and Spring Security — no authentication code is needed in the application beans.

**How the authentication flow works:**

1. On startup, the binder detects the `spring.security.oauth2.client.registration.solace-broker` configuration.
2. The binder's `SolaceSessionOAuth2TokenProvider` requests a JWT token from the token endpoint using the client credentials grant.
3. The binder creates the JCSMP session using the JWT token instead of username/password.
4. When the token expires, the binder automatically requests a new token and reconnects.

## What to Observe

During `mvn verify`, look for log lines showing that a token was issued by `mock-oauth2-server` and that `SolaceSessionOAuth2TokenProvider` was auto-configured successfully.

When you run the application against a real OAuth-enabled broker, you should then see the normal publish/consume log flow:

```
INFO  Published authenticated message
INFO  Received authenticated message: oauth-secured-msg
```

At runtime the binder monitors token expiry and requests a fresh token before the current one expires.

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

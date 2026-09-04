# Application-owned OAuth resource-server pattern

Soklet 4.0.0 provides the point at which an application admits and identifies
each MCP request. The application remains the OAuth resource server: it chooses
an authorization server, verifies access tokens, maps claims to scopes, serves
protected resource metadata, and owns revocation and provider policy.

Soklet does **not** implement an OAuth authorization server, token endpoint,
JWT/JWK client, introspection client, dynamic client registration, consent
flow, protected resource metadata hosting, or identity-provider-specific
policy. The example below supplies those application seams without pretending
they are framework features.

## Boundary and flow

For every structurally valid MCP message:

1. Soklet invokes `McpAdmissionController` before rate limiting, framework
   discovery/catalog rendering, interception, typed conversion, or a handler.
2. A trusted edge rejects duplicate physical `Authorization` fields. The
   application then extracts exactly one materialized Bearer credential value
   and asks its token verifier to authenticate it.
3. The application checks the scope for the validated MCP method and selected
   operation.
4. A rejection returns a safe HTTP status, JSON-RPC error, and optional opaque
   `WWW-Authenticate` challenge. A notification still carries the HTTP status
   and headers but has no JSON-RPC response body.
5. An acceptance carries stable opaque partition keys and an application
   principal into `McpRequestContext.getAdmissionIdentity()`.

Connection reuse does not reuse identity. Every request and notification is
admitted independently.

## Token-verification seam

This interface is intentionally application code. Its implementation might use
a locally verified JWT, RFC 7662 introspection, a sidecar, or a gateway-attested
credential, according to the deployment's trust model.

```java
package example.security;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface AccessTokenVerifier {
  Optional<TokenClaims> verify(String encodedToken);

  record TokenClaims(
      String issuer,
      String subject,
      String tenant,
      Set<String> audiences,
      Set<String> scopes,
      Instant expiresAt) {
    public boolean hasScope(String scope) {
      return scopes.contains(scope);
    }
  }
}
```

Before returning claims, a production implementation must at least validate the
token's integrity/authenticity, allowed issuer, resource audience, expiry and
not-before constraints, algorithm/key policy, and deployment-specific
revocation semantics. Do not decode a JWT and treat its unverified payload as
claims. Do not log or place the encoded token in an exception message.

Partition keys should also be application-derived, stable, bounded, and opaque:

```java
package example.security;

@FunctionalInterface
public interface PartitionKeyDeriver {
  // For example: versioned HMAC over issuer + tenant + subject.
  String derive(AccessTokenVerifier.TokenClaims claims);
}
```

Do not use a raw access token as either partition key.

## Admission and authorization controller

The following controller is complete apart from the two application seams
above. Its fixed challenge vocabulary comes only from application-defined
scope constants and a fixed registration-name-to-scope map; it contains no raw
request value, token, subject, tenant, or provider error text.

```java
package example.security;

import com.soklet.McpAdmissionContext;
import com.soklet.McpAdmissionController;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpAdmissionRejection;
import com.soklet.McpJsonRpcError;
import com.soklet.Request;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OAuthAdmissionController
    implements McpAdmissionController {
  private static final String RESOURCE_METADATA =
      "https://api.example.com/.well-known/"
          + "oauth-protected-resource/catalog/mcp";
  private static final Map<String, String> TOOL_SCOPES = Map.of(
      "catalog.search", "mcp:tools:call:catalog.search");

  private final AccessTokenVerifier tokenVerifier;
  private final PartitionKeyDeriver partitionKeyDeriver;

  public OAuthAdmissionController(
      AccessTokenVerifier tokenVerifier,
      PartitionKeyDeriver partitionKeyDeriver) {
    this.tokenVerifier = tokenVerifier;
    this.partitionKeyDeriver = partitionKeyDeriver;
  }

  @Override
  public McpAdmissionDecision admit(McpAdmissionContext context) {
    Optional<String> requiredScope = requiredScope(context);
    if (requiredScope.isEmpty()) {
      return unmappedOperation();
    }
    String scope = requiredScope.orElseThrow();
    Optional<String> credential = bearerCredential(context.getRequest());
    if (credential.isEmpty()) {
      return unauthorized(scope, false);
    }

    Optional<AccessTokenVerifier.TokenClaims> verified =
        tokenVerifier.verify(credential.orElseThrow());
    if (verified.isEmpty()) {
      return unauthorized(scope, true);
    }

    AccessTokenVerifier.TokenClaims claims = verified.orElseThrow();
    if (!claims.hasScope(scope)) {
      return forbidden(scope);
    }

    String partitionKey = partitionKeyDeriver.derive(claims);
    McpAdmissionIdentity identity =
        McpAdmissionIdentity.withRateLimitPartitionKey(partitionKey)
            .authorizationPartitionKey(partitionKey)
            .principal(claims)
            .build();
    return McpAdmissionDecision.accepted(identity);
  }

  private static Optional<String> requiredScope(
      McpAdmissionContext context) {
    return switch (context.getJsonRpcMethod()) {
      case "server/discover", "tools/list", "prompts/list",
          "resources/list", "resources/templates/list" ->
          Optional.of("mcp:discover");
      case "tools/call" -> context.getOperationName()
          .flatMap(name -> Optional.ofNullable(TOOL_SCOPES.get(name)));
      case "prompts/get" -> Optional.of("mcp:prompts:get");
      case "resources/read" -> Optional.of("mcp:resources:read");
      case "subscriptions/listen" ->
          Optional.of("mcp:subscriptions:listen");
      default -> Optional.empty();
    };
  }

  private static Optional<String> bearerCredential(Request request) {
    List<String> values = request.getHeaders().entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase("Authorization"))
        .flatMap(entry -> entry.getValue().stream())
        .toList();
    if (values.size() != 1) {
      return Optional.empty();
    }

    String value = values.get(0);
    if (value.length() <= 7
        || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return Optional.empty();
    }
    String credential = value.substring(7);
    if (credential.chars().anyMatch(Character::isWhitespace)) {
      return Optional.empty();
    }
    return Optional.of(credential);
  }

  private static McpAdmissionDecision unauthorized(
      String scope, boolean invalidToken) {
    String error = invalidToken ? "error=\"invalid_token\", " : "";
    String challenge = "Bearer " + error
        + "resource_metadata=\"" + RESOURCE_METADATA + "\", "
        + "scope=\"" + scope + "\"";
    return McpAdmissionDecision.rejected(
        McpAdmissionRejection.withStatusCodeAndError(
                401,
                McpJsonRpcError.fromApplication(
                    -31901, "Authentication required"))
            .addHeader("WWW-Authenticate", challenge)
            .build());
  }

  private static McpAdmissionDecision forbidden(String scope) {
    String challenge = "Bearer error=\"insufficient_scope\", "
        + "resource_metadata=\"" + RESOURCE_METADATA + "\", "
        + "scope=\"" + scope + "\"";
    return McpAdmissionDecision.rejected(
        McpAdmissionRejection.withStatusCodeAndError(
                403,
                McpJsonRpcError.fromApplication(
                    -31903, "Operation not permitted"))
            .addHeader("WWW-Authenticate", challenge)
            .build());
  }

  private static McpAdmissionDecision unmappedOperation() {
    return McpAdmissionDecision.rejected(
        McpAdmissionRejection.withStatusCodeAndError(
                403,
                McpJsonRpcError.fromApplication(
                    -31903, "Operation not permitted"))
            .build());
  }
}
```

Soklet's request model stores header values in sets after case-insensitive
normalization. It can reject multiple distinct materialized authorization
values, but it cannot prove that a proxy did not send two identical physical
fields that collapsed to one value. Configure and test the trusted edge to
reject duplicate physical `Authorization` fields before forwarding.

`McpAdmissionController` can be invoked concurrently; the verifier and
partition-key derivation must therefore be thread-safe. Bound their network,
cache, and cryptographic work so admission cannot become an unbounded resource
sink. If the controller throws or returns malformed data, Soklet fails closed.

The scope mapping is an example policy, not a Soklet-defined vocabulary. In
particular, URI-template resources and tenant-specific records usually need
authorization beyond the coarse `resources/read` scope. Perform that semantic
check in the selected handler against the accepted principal, and return one
fixed no-data error for unknown, missing, and unauthorized protected values.

## Install the controller

Use the controller in place of the quickstart's anonymous admission policy:

```java
AccessTokenVerifier verifier = applicationTokenVerifier();
PartitionKeyDeriver partitionKeys = applicationPartitionKeyDeriver();

McpServer mcpServer = McpServer.withPort(
        8081,
        McpEndpointRegistry.fromClasses(CatalogMcpEndpoint.class),
        new OAuthAdmissionController(verifier, partitionKeys))
    .host("127.0.0.1")
    .toolRateLimiter(McpRateLimiter.fromInMemoryDefaults())
    .corsAuthorizer(CorsAuthorizer.rejectAllInstance())
    .allowedHosts(Set.of("127.0.0.1"))
    .build();
```

The in-memory limiter remains only a one-node example. A fleet should provide a
limiter whose partitioning and failure mode match the deployment.

## Protected resource metadata

Serve metadata from application-owned ordinary HTTP infrastructure or a
gateway, not from the dedicated MCP listener. For example, an HTTPS response at
`https://api.example.com/.well-known/oauth-protected-resource/catalog/mcp`
might be:

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: public, max-age=300

{
  "resource": "https://api.example.com/catalog/mcp",
  "authorization_servers": ["https://id.example.com"],
  "scopes_supported": [
    "mcp:discover",
    "mcp:tools:call:catalog.search",
    "mcp:prompts:get",
    "mcp:resources:read",
    "mcp:subscriptions:listen"
  ]
}
```

The application or gateway owns this response, its standards compliance, and
its synchronization with the actual audience, issuer, and scope policy. The
MCP listener itself is intentionally `POST`-only and does not become a general
OAuth metadata server.

## Expected failures

| Condition | HTTP | JSON-RPC body | Required behavior |
| --- | ---: | --- | --- |
| Missing or malformed Bearer credential | 401 | Fixed application error for requests; empty for notifications | Include a fixed Bearer challenge and metadata URI; do not reflect input. |
| Token fails application verification | 401 | Same fixed request error | `invalid_token` may be named in the challenge; do not include provider details. |
| Authenticated identity lacks the operation scope | 403 | Fixed no-data authorization error for requests | The handler and downstream services must not run. |
| Admission verifier throws/times out | Framework failure response | Safe framework error | Fail closed; observe the internal failure without exposing credentials. |
| Origin or Host is rejected | 403 or transport rejection | No application admission result | CORS/Host checks remain separate from OAuth. |
| Handler-level protected object is unknown or unauthorized | Application-defined safe error | Same response shape for both | Do not reveal existence, owner, path, or policy details. |

Soklet validates application-supplied response headers and owns JSON-RPC
serialization. It treats the Bearer challenge as opaque text; creating it does
not prove RFC conformance. For browser clients, explicitly allow the intended
Origin and expose `WWW-Authenticate` only as required. For every remote
deployment, terminate TLS at a trusted boundary and ensure proxies do not
accept or synthesize identity headers outside that boundary.

## Minimum security tests

- missing, multiple distinct materialized values, wrong-scheme, mixed-case
  valid Bearer scheme, extra-whitespace, blank, malformed,
  expired, wrong-issuer, wrong-audience, and bad-signature credentials all
  behave as intended and otherwise fail closed;
- the trusted edge rejects duplicate physical `Authorization` fields,
  including identical values and mixed-case header-name duplicates;
- no response, log, metric, exception, trace, or partition key contains the
  encoded token or sensitive claim;
- discovery/list authorization is intentional rather than assumed public;
- operation-specific scopes cannot authorize a different tool or tenant, and
  every registered tool missing from the explicit scope map is rejected;
- notifications receive no JSON-RPC response body while preserving the safe
  HTTP status and challenge behavior;
- rejected Origin and Host values do not reach the token verifier;
- verifier timeout/circuit-breaker/cache behavior is bounded; and
- token/key rotation and revocation behavior is tested according to the chosen
  provider and deployment.

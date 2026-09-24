# Soklet 4.0.0 public API signatures — current working tree

Generated directly from `src/main/java` on 2026-09-24. This is the current working tree, including uncommitted parameter-name edits. It is a source signature inventory, not a release freeze. Method bodies, private/package members, Javadoc, and imports are omitted. Declared public members are shown under their declaring type; inherited members are not repeated. Annotations and parameter names are copied from the source AST. Implicit enum compiler methods are omitted.

Scope: public `com.soklet.Mcp*` types, public `com.soklet.annotation.Mcp*` annotations, and public application/lifecycle/shutdown companion types. Each heading links to its source declaration.

## `com.soklet.LifecycleObserver`

Source: [`LifecycleObserver.java`](../../src/main/java/com/soklet/LifecycleObserver.java#L47)

```java
@ThreadSafe
public interface LifecycleObserver {
    default void willStartSoklet(@NonNull Soklet soklet);
    default void didStartSoklet(@NonNull Soklet soklet);
    default void didFailToStartSoklet(@NonNull Soklet soklet, @NonNull Throwable throwable);
    default void willStopSoklet(@NonNull Soklet soklet);
    default void didStopSoklet(@NonNull Soklet soklet, @NonNull ShutdownResult shutdownResult);
    default void willStartHttpServer(@NonNull HttpServer httpServer);
    default void didStartHttpServer(@NonNull HttpServer httpServer);
    default void didFailToStartHttpServer(@NonNull HttpServer httpServer, @NonNull Throwable throwable);
    default void willStopHttpServer(@NonNull HttpServer httpServer);
    default void didStopHttpServer(@NonNull HttpServer httpServer, @NonNull ShutdownComponentResult shutdownComponentResult);
    default void willAcceptConnection(@NonNull ServerType serverType, @Nullable InetSocketAddress remoteAddress);
    default void didAcceptConnection(@NonNull ServerType serverType, @Nullable InetSocketAddress remoteAddress);
    default void didFailToAcceptConnection(@NonNull ServerType serverType, @Nullable InetSocketAddress remoteAddress, @NonNull ConnectionRejectionReason reason, @Nullable Throwable throwable);
    default void willAcceptRequest(@NonNull ServerType serverType, @Nullable InetSocketAddress remoteAddress, @Nullable String requestTarget);
    default void didAcceptRequest(@NonNull ServerType serverType, @Nullable InetSocketAddress remoteAddress, @Nullable String requestTarget);
    default void didFailToAcceptRequest(@NonNull ServerType serverType, @Nullable InetSocketAddress remoteAddress, @Nullable String requestTarget, @NonNull RequestRejectionReason reason, @Nullable Throwable throwable);
    default void willReadRequest(@NonNull ServerType serverType, @Nullable InetSocketAddress remoteAddress, @Nullable String requestTarget);
    default void didReadRequest(@NonNull ServerType serverType, @Nullable InetSocketAddress remoteAddress, @Nullable String requestTarget);
    default void didFailToReadRequest(@NonNull ServerType serverType, @Nullable InetSocketAddress remoteAddress, @Nullable String requestTarget, @NonNull RequestReadFailureReason reason, @Nullable Throwable throwable);
    default void didRejectUnparsedRequest(@NonNull UnparsedRequest request);
    default void didStartRequestHandling(@NonNull ServerType serverType, @NonNull Request request, @Nullable ResourceMethod resourceMethod);
    default void didFinishRequestHandling(@NonNull ServerType serverType, @NonNull Request request, @Nullable ResourceMethod resourceMethod, @NonNull MarshaledResponse marshaledResponse, @NonNull Duration duration, @NonNull List<@NonNull Throwable> throwables);
    default void willWriteResponse(@NonNull ServerType serverType, @NonNull Request request, @Nullable ResourceMethod resourceMethod, @NonNull MarshaledResponse marshaledResponse);
    default void didWriteResponse(@NonNull ServerType serverType, @NonNull Request request, @Nullable ResourceMethod resourceMethod, @NonNull MarshaledResponse marshaledResponse, @NonNull Duration responseWriteDuration);
    default void didFailToWriteResponse(@NonNull ServerType serverType, @NonNull Request request, @Nullable ResourceMethod resourceMethod, @NonNull MarshaledResponse marshaledResponse, @NonNull Duration responseWriteDuration, @NonNull Throwable throwable);
    default void willTerminateResponseStream(@NonNull StreamingResponseHandle streamingResponseHandle, @NonNull StreamTermination streamTermination);
    default void didTerminateResponseStream(@NonNull StreamingResponseHandle streamingResponseHandle, @NonNull StreamTermination streamTermination);
    default void willStartSseServer(@NonNull SseServer sseServer);
    default void didStartSseServer(@NonNull SseServer sseServer);
    default void didFailToStartSseServer(@NonNull SseServer sseServer, @NonNull Throwable throwable);
    default void willStopSseServer(@NonNull SseServer sseServer);
    default void didStopSseServer(@NonNull SseServer sseServer, @NonNull ShutdownComponentResult shutdownComponentResult);
    default void willStartMcpServer(@NonNull McpServer mcpServer);
    default void didStartMcpServer(@NonNull McpServer mcpServer);
    default void didFailToStartMcpServer(@NonNull McpServer mcpServer, @NonNull Throwable throwable);
    default void willStopMcpServer(@NonNull McpServer mcpServer);
    default void didStopMcpServer(@NonNull McpServer mcpServer, @NonNull ShutdownComponentResult shutdownComponentResult);
    default void didStartMcpRequestHandling(@NonNull McpRequestContext requestContext);
    default void didFinishMcpRequestHandling(@NonNull McpRequestContext requestContext, @NonNull McpRequestOutcome requestOutcome, @Nullable McpJsonRpcError jsonRpcError, @NonNull Duration requestDuration, @NonNull List<@NonNull Throwable> throwables);
    default void willEstablishSseConnection(@NonNull Request request, @Nullable ResourceMethod resourceMethod);
    default void didEstablishSseConnection(@NonNull SseConnection sseConnection);
    default void didFailToEstablishSseConnection(@NonNull Request request, @Nullable ResourceMethod resourceMethod, SseConnection.@NonNull HandshakeFailureReason connectionHandshakeFailureReason, @Nullable Throwable throwable);
    default void willTerminateSseConnection(@NonNull SseConnection sseConnection, @NonNull StreamTermination streamTermination);
    default void didTerminateSseConnection(@NonNull SseConnection sseConnection, @NonNull StreamTermination streamTermination);
    default void willWriteSseEvent(@NonNull SseConnection sseConnection, @NonNull SseEvent sseEvent);
    default void didWriteSseEvent(@NonNull SseConnection sseConnection, @NonNull SseEvent sseEvent, @NonNull Duration writeDuration);
    default void didFailToWriteSseEvent(@NonNull SseConnection sseConnection, @NonNull SseEvent sseEvent, @NonNull Duration writeDuration, @NonNull Throwable throwable);
    default void willWriteSseComment(@NonNull SseConnection sseConnection, @NonNull SseComment sseComment);
    default void didWriteSseComment(@NonNull SseConnection sseConnection, @NonNull SseComment sseComment, @NonNull Duration writeDuration);
    default void didFailToWriteSseComment(@NonNull SseConnection sseConnection, @NonNull SseComment sseComment, @NonNull Duration writeDuration, @NonNull Throwable throwable);
    default void didReceiveLogEvent(@NonNull LogEvent logEvent);
    @NonNull
    static LifecycleObserver defaultInstance();
}
```

## `com.soklet.LifecyclePolicy`

Source: [`LifecyclePolicy.java`](../../src/main/java/com/soklet/LifecyclePolicy.java#L32)

```java
@ThreadSafe
public final class LifecyclePolicy {
    @NonNull
    public static LifecyclePolicy defaultInstance();
    @NonNull
    public static Builder builder();
    @NonNull
    public Duration getStartupTimeout();
    @NonNull
    public Duration getStartupCancelationTimeout();
    @NonNull
    public Duration getGracefulShutdownTimeout();
    @NonNull
    public Duration getForcedShutdownTimeout();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder startupTimeout(@Nullable Duration startupTimeout);
        @NonNull
        public Builder startupCancelationTimeout(@Nullable Duration startupCancelationTimeout);
        @NonNull
        public Builder gracefulShutdownTimeout(@Nullable Duration gracefulShutdownTimeout);
        @NonNull
        public Builder forcedShutdownTimeout(@Nullable Duration forcedShutdownTimeout);
        @NonNull
        public LifecyclePolicy build();
    }
}
```

## `com.soklet.McpAbsentOriginPolicy`

Source: [`McpAbsentOriginPolicy.java`](../../src/main/java/com/soklet/McpAbsentOriginPolicy.java#L27)

```java
public enum McpAbsentOriginPolicy {
    ALLOW, REQUIRE_ORIGIN;
}
```

## `com.soklet.McpAdmissionContext`

Source: [`McpAdmissionContext.java`](../../src/main/java/com/soklet/McpAdmissionContext.java#L32)

```java
@ThreadSafe
public interface McpAdmissionContext {
    @NonNull
    Request getRequest();
    @NonNull
    McpEndpoint getEndpoint();
    @NonNull
    Map<@NonNull String, @NonNull String> getEndpointPathParameters();
    @NonNull
    String getJsonRpcMethod();
    @NonNull
    default McpOperationType getOperationType();
    @NonNull
    Boolean isNotification();
    @NonNull
    Optional<@NonNull McpRequestId> getRequestId();
    @NonNull
    String getProtocolVersion();
    @NonNull
    Optional<@NonNull String> getOperationName();
    @NonNull
    Optional<@NonNull McpImplementation> getClientInfo();
    @NonNull
    Optional<@NonNull McpClientCapabilities> getClientCapabilities();
    @NonNull
    List<@NonNull URI> getRequestedResourceSubscriptionUris();
    @NonNull
    Optional<@NonNull TraceContext> getTraceContext();
}
```

## `com.soklet.McpAdmissionController`

Source: [`McpAdmissionController.java`](../../src/main/java/com/soklet/McpAdmissionController.java#L30)

```java
@ThreadSafe
@FunctionalInterface
public interface McpAdmissionController {
    @NonNull
    McpAdmissionDecision admit(@NonNull McpAdmissionContext admissionContext) throws Exception;
    @NonNull
    static McpAdmissionController acceptAllInstance();
}
```

## `com.soklet.McpAdmissionDecision`

Source: [`McpAdmissionDecision.java`](../../src/main/java/com/soklet/McpAdmissionDecision.java#L33)

```java
@ThreadSafe
public sealed interface McpAdmissionDecision permits McpAdmissionDecision.Accepted, McpAdmissionDecision.Rejected {
    @NonNull
    static Accepted accepted(@NonNull McpAdmissionIdentity identity);
    @NonNull
    static Accepted accepted();
    @NonNull
    static Rejected rejected(@NonNull McpAdmissionRejection rejection);
    @ThreadSafe
    public final class Accepted implements McpAdmissionDecision {
        @NonNull
        public McpAdmissionIdentity getIdentity();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class Rejected implements McpAdmissionDecision {
        @NonNull
        public McpAdmissionRejection getRejection();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
}
```

## `com.soklet.McpAdmissionIdentity`

Source: [`McpAdmissionIdentity.java`](../../src/main/java/com/soklet/McpAdmissionIdentity.java#L46)

```java
@ThreadSafe
public final class McpAdmissionIdentity {
    public static final int MAXIMUM_PARTITION_KEY_SIZE_IN_UTF_8_BYTES;
    @NonNull
    public static McpAdmissionIdentity anonymousInstance();
    @NonNull
    public static Builder withRateLimitPartitionKey(@NonNull String rateLimitPartitionKey);
    @NonNull
    public Boolean isAuthenticated();
    @NonNull
    public Optional<@NonNull Object> getPrincipal();
    @NonNull
    public Optional<@NonNull Object> getApplicationContext();
    @NonNull
    public String getRateLimitPartitionKey();
    @NonNull
    public Optional<@NonNull String> getAuthorizationPartitionKey();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder authorizationPartitionKey(@NonNull String authorizationPartitionKey);
        @NonNull
        public Builder principal(@NonNull Object principal);
        @NonNull
        public Builder applicationContext(@NonNull Object applicationContext);
        @NonNull
        public McpAdmissionIdentity build();
    }
}
```

## `com.soklet.McpAdmissionRejection`

Source: [`McpAdmissionRejection.java`](../../src/main/java/com/soklet/McpAdmissionRejection.java#L45)

```java
@ThreadSafe
public final class McpAdmissionRejection {
    @NonNull
    public static Builder withStatusCodeAndError(@NonNull Integer statusCode, @NonNull McpJsonRpcError jsonRpcError);
    @NonNull
    public Integer getStatusCode();
    @NonNull
    public McpJsonRpcError getJsonRpcError();
    @NonNull
    public Map<@NonNull String, @NonNull Set<@NonNull String>> getHeaders();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder statusCode(@NonNull Integer statusCode);
        @NonNull
        public Builder jsonRpcError(@NonNull McpJsonRpcError jsonRpcError);
        @NonNull
        public Builder headers(@NonNull Map<@NonNull String, ? extends @NonNull Set<@NonNull String>> headers);
        @NonNull
        public Builder addHeader(@NonNull String name, @NonNull String value);
        @NonNull
        public McpAdmissionRejection build();
    }
}
```

## `com.soklet.McpAppResourceMetadata`

Source: [`McpAppResourceMetadata.java`](../../src/main/java/com/soklet/McpAppResourceMetadata.java#L42)

```java
@ThreadSafe
public final class McpAppResourceMetadata {
    @NonNull
    public static Builder builder();
    @NonNull
    public Optional<@NonNull ContentSecurityPolicy> getContentSecurityPolicy();
    @NonNull
    public Set<@NonNull Permission> getPermissions();
    @NonNull
    public Optional<@NonNull String> getDomain();
    @NonNull
    public Optional<@NonNull Boolean> getPrefersBorder();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
    public enum Permission {
        CAMERA, MICROPHONE, GEOLOCATION, CLIPBOARD_WRITE;
    }
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder contentSecurityPolicy(@NonNull ContentSecurityPolicy contentSecurityPolicy);
        @NonNull
        public Builder permissions(@NonNull Set<@NonNull Permission> permissions);
        @NonNull
        public Builder domain(@NonNull String domain);
        @NonNull
        public Builder prefersBorder(@NonNull Boolean prefersBorder);
        @NonNull
        public McpAppResourceMetadata build();
    }
    @ThreadSafe
    public static final class ContentSecurityPolicy {
        @NonNull
        public static ContentSecurityPolicy defaultInstance();
        @NonNull
        public static Builder builder();
        @NonNull
        public Set<@NonNull String> getConnectDomains();
        @NonNull
        public Set<@NonNull String> getResourceDomains();
        @NonNull
        public Set<@NonNull String> getFrameDomains();
        @NonNull
        public Set<@NonNull String> getBaseUriDomains();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
        @NotThreadSafe
        public static final class Builder {
            @NonNull
            public Builder connectDomains(@NonNull Set<@NonNull String> connectDomains);
            @NonNull
            public Builder resourceDomains(@NonNull Set<@NonNull String> resourceDomains);
            @NonNull
            public Builder frameDomains(@NonNull Set<@NonNull String> frameDomains);
            @NonNull
            public Builder baseUriDomains(@NonNull Set<@NonNull String> baseUriDomains);
            @NonNull
            public ContentSecurityPolicy build();
        }
    }
}
```

## `com.soklet.McpAppToolMetadata`

Source: [`McpAppToolMetadata.java`](../../src/main/java/com/soklet/McpAppToolMetadata.java#L43)

```java
@ThreadSafe
public final class McpAppToolMetadata {
    @NonNull
    public static Builder builder();
    @NonNull
    public Optional<@NonNull URI> getResourceUri();
    @NonNull
    public Set<@NonNull Visibility> getVisibility();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
    public enum Visibility {
        MODEL, APP;
    }
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder resourceUri(@NonNull URI resourceUri);
        @NonNull
        public Builder visibility(@NonNull Set<@NonNull Visibility> visibility);
        @NonNull
        public McpAppToolMetadata build();
    }
}
```

## `com.soklet.McpArgumentCompletionResult`

Source: [`McpArgumentCompletionResult.java`](../../src/main/java/com/soklet/McpArgumentCompletionResult.java#L39)

```java
@ThreadSafe
public final class McpArgumentCompletionResult implements McpOperationResult {
    @NonNull
    public static McpArgumentCompletionResult fromValues(@NonNull List<@NonNull String> values);
    @NonNull
    public static Builder withValues(@NonNull List<@NonNull String> values);
    @NonNull
    public List<@NonNull String> getValues();
    @NonNull
    public Optional<@NonNull Long> getTotal();
    @NonNull
    public Optional<@NonNull Boolean> getHasMore();
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder total(@NonNull Long total);
        @NonNull
        public Builder hasMore(@NonNull Boolean hasMore);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpArgumentCompletionResult build();
    }
}
```

## `com.soklet.McpAudioContent`

Source: [`McpAudioContent.java`](../../src/main/java/com/soklet/McpAudioContent.java#L37)

```java
@ThreadSafe
public final class McpAudioContent implements McpContentBlock {
    @NonNull
    public static Builder withDataAndMimeType(byte @NonNull [] data, @NonNull String mimeType);
    public byte @NonNull [] getData();
    @NonNull
    public String getMimeType();
    @Override
    @NonNull
    public Optional<@NonNull McpContentAnnotations> getAnnotations();
    @Override
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder annotations(@NonNull McpContentAnnotations annotations);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpAudioContent build();
    }
}
```

## `com.soklet.McpBlobResourceContents`

Source: [`McpBlobResourceContents.java`](../../src/main/java/com/soklet/McpBlobResourceContents.java#L36)

```java
@ThreadSafe
public final class McpBlobResourceContents implements McpResourceContents {
    @NonNull
    public static Builder withUriAndData(@NonNull URI uri, byte @NonNull [] data);
    @Override
    @NonNull
    public URI getUri();
    public byte @NonNull [] getData();
    @Override
    @NonNull
    public Optional<@NonNull String> getMimeType();
    @Override
    @NonNull
    public Optional<@NonNull McpAppResourceMetadata> getAppResourceMetadata();
    @Override
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder mimeType(@NonNull String mimeType);
        @NonNull
        public Builder appResourceMetadata(@NonNull McpAppResourceMetadata appResourceMetadata);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpBlobResourceContents build();
    }
}
```

## `com.soklet.McpCachePolicy`

Source: [`McpCachePolicy.java`](../../src/main/java/com/soklet/McpCachePolicy.java#L40)

```java
@ThreadSafe
public final class McpCachePolicy {
    @NonNull
    public static McpCachePolicy privateNoCacheInstance();
    @NonNull
    public static McpCachePolicy fromPrivateTimeToLive(@NonNull Duration timeToLive);
    @NonNull
    public static McpCachePolicy fromPublicTimeToLive(@NonNull Duration timeToLive);
    @NonNull
    public Duration getTimeToLive();
    @NonNull
    public McpCacheScope getScope();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
}
```

## `com.soklet.McpCacheScope`

Source: [`McpCacheScope.java`](../../src/main/java/com/soklet/McpCacheScope.java#L24)

```java
public enum McpCacheScope {
    PUBLIC, PRIVATE;
}
```

## `com.soklet.McpCatalogAccessPolicy`

Source: [`McpCatalogAccessPolicy.java`](../../src/main/java/com/soklet/McpCatalogAccessPolicy.java#L38)

```java
@ThreadSafe
public final class McpCatalogAccessPolicy {
    @NonNull
    public static McpCatalogAccessPolicy fromEvaluators(@NonNull ToolAccessEvaluator toolAccessEvaluator, @NonNull PromptAccessEvaluator promptAccessEvaluator);
    @NonNull
    public static McpCatalogAccessPolicy allowAllInstance();
    @Override
    @NonNull
    public String toString();
    @ThreadSafe
    @FunctionalInterface
    public interface ToolAccessEvaluator {
        @NonNull
        Boolean isToolAccessible(@NonNull McpRequestContext requestContext, @NonNull McpToolRegistration<?> toolRegistration, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
    }
    @ThreadSafe
    @FunctionalInterface
    public interface PromptAccessEvaluator {
        @NonNull
        Boolean isPromptAccessible(@NonNull McpRequestContext requestContext, @NonNull McpPromptRegistration promptRegistration, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
    }
}
```

## `com.soklet.McpClientCapabilities`

Source: [`McpClientCapabilities.java`](../../src/main/java/com/soklet/McpClientCapabilities.java#L37)

```java
@ThreadSafe
public final class McpClientCapabilities {
    @NonNull
    public static McpClientCapabilities fromJson(@NonNull McpJsonObject json);
    @NonNull
    public Boolean supports(@NonNull McpClientCapability capability);
    @NonNull
    public Boolean supportsAppMimeType(@NonNull String mimeType);
    @NonNull
    public Optional<@NonNull McpJsonObject> findExtension(@NonNull String extensionIdentifier);
    @NonNull
    public Map<@NonNull String, @NonNull McpJsonObject> getExtensions();
    @NonNull
    public McpJsonObject toJson();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
}
```

## `com.soklet.McpClientCapability`

Source: [`McpClientCapability.java`](../../src/main/java/com/soklet/McpClientCapability.java#L24)

```java
public enum McpClientCapability {
    ELICITATION_FORM, ELICITATION_URL;
}
```

## `com.soklet.McpCompletePayload`

Source: [`McpCompletePayload.java`](../../src/main/java/com/soklet/McpCompletePayload.java#L26)

```java
@ThreadSafe
public sealed interface McpCompletePayload permits McpPromptOutput, McpResourceOutput, McpToolOutput {
}
```

## `com.soklet.McpCompleteResult`

Source: [`McpCompleteResult.java`](../../src/main/java/com/soklet/McpCompleteResult.java#L35)

```java
@ThreadSafe
public final class McpCompleteResult implements McpOperationResult {
    @NonNull
    public static McpCompleteResult fromToolText(@NonNull String text);
    @NonNull
    public static McpCompleteResult fromToolStructuredContent(@NonNull McpJsonValue structuredContent);
    @NonNull
    public static McpCompleteResult fromToolErrorText(@NonNull String text);
    @NonNull
    public static McpCompleteResult fromToolOutput(@NonNull McpToolOutput toolOutput);
    @NonNull
    public static McpCompleteResult fromPromptOutput(@NonNull McpPromptOutput promptOutput);
    @NonNull
    public static McpCompleteResult fromResourceOutput(@NonNull McpResourceOutput resourceOutput);
    @NonNull
    public static Builder withToolOutput(@NonNull McpToolOutput toolOutput);
    @NonNull
    public static Builder withPromptOutput(@NonNull McpPromptOutput promptOutput);
    @NonNull
    public static Builder withResourceOutput(@NonNull McpResourceOutput resourceOutput);
    @NonNull
    public Builder toBuilder();
    @NonNull
    public McpCompletePayload getPayload();
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder payload(@NonNull McpCompletePayload payload);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpCompleteResult build();
    }
}
```

## `com.soklet.McpCompleteToolHandler`

Source: [`McpCompleteToolHandler.java`](../../src/main/java/com/soklet/McpCompleteToolHandler.java#L48)

```java
@ThreadSafe
@FunctionalInterface
public interface McpCompleteToolHandler<A, R> {
    @NonNull
    R handle(@NonNull McpRequestContext requestContext, @NonNull McpToolArguments<@NonNull A> toolArguments, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
```

## `com.soklet.McpCompletionContext`

Source: [`McpCompletionContext.java`](../../src/main/java/com/soklet/McpCompletionContext.java#L33)

```java
@ThreadSafe
public interface McpCompletionContext {
    @NonNull
    String getArgumentName();
    @NonNull
    String getArgumentValue();
    @NonNull
    Map<@NonNull String, @NonNull String> getContextArguments();
    @ThreadSafe
    interface Prompt extends McpCompletionContext {
        @NonNull
        McpPromptRegistration getPromptRegistration();
    }
    @ThreadSafe
    interface Resource extends McpCompletionContext {
        @NonNull
        McpResourceRegistration getResourceRegistration();
    }
}
```

## `com.soklet.McpCompletionHandler`

Source: [`McpCompletionHandler.java`](../../src/main/java/com/soklet/McpCompletionHandler.java#L32)

```java
@ThreadSafe
@FunctionalInterface
public interface McpCompletionHandler {
    @NonNull
    McpArgumentCompletionResult handle(@NonNull McpRequestContext requestContext, @NonNull McpCompletionContext completionContext, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
```

## `com.soklet.McpContentAnnotations`

Source: [`McpContentAnnotations.java`](../../src/main/java/com/soklet/McpContentAnnotations.java#L43)

```java
@ThreadSafe
public final class McpContentAnnotations {
    @NonNull
    public static Builder builder();
    @NonNull
    public Set<@NonNull McpRole> getAudience();
    @NonNull
    public Optional<@NonNull Double> getPriority();
    @NonNull
    public Optional<@NonNull Instant> getLastModified();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder audience(@NonNull McpRole @NonNull... audience);
        @NonNull
        public Builder priority(@NonNull Double priority);
        @NonNull
        public Builder lastModified(@NonNull Instant lastModified);
        @NonNull
        public McpContentAnnotations build();
    }
}
```

## `com.soklet.McpContentBlock`

Source: [`McpContentBlock.java`](../../src/main/java/com/soklet/McpContentBlock.java#L29)

```java
@ThreadSafe
public sealed interface McpContentBlock permits McpAudioContent, McpEmbeddedResource, McpImageContent, McpResourceLink, McpTextContent {
    @NonNull
    Optional<@NonNull McpContentAnnotations> getAnnotations();
    @NonNull
    McpJsonObject getMetadata();
}
```

## `com.soklet.McpEmbeddedResource`

Source: [`McpEmbeddedResource.java`](../../src/main/java/com/soklet/McpEmbeddedResource.java#L38)

```java
@ThreadSafe
public final class McpEmbeddedResource implements McpContentBlock {
    @NonNull
    public static Builder withResource(@NonNull McpResourceContents resourceContents);
    @NonNull
    public McpResourceContents getResource();
    @Override
    @NonNull
    public Optional<@NonNull McpContentAnnotations> getAnnotations();
    @Override
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder annotations(@NonNull McpContentAnnotations annotations);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpEmbeddedResource build();
    }
}
```

## `com.soklet.McpEndpoint`

Source: [`McpEndpoint.java`](../../src/main/java/com/soklet/McpEndpoint.java#L45)

```java
@ThreadSafe
public final class McpEndpoint {
    @NonNull
    public static Builder withPath(@NonNull String path, @NonNull McpImplementation implementation);
    @NonNull
    public String getPath();
    @NonNull
    public McpImplementation getServerInfo();
    @NonNull
    public Boolean isServerInfoIncluded();
    @NonNull
    public Optional<@NonNull String> getInstructions();
    @NonNull
    public List<@NonNull McpToolRegistration<?>> getToolRegistrations();
    @NonNull
    public List<@NonNull McpPromptRegistration> getPromptRegistrations();
    @NonNull
    public List<@NonNull McpResourceRegistration> getResourceRegistrations();
    @NonNull
    public List<@NonNull McpSkillRegistration> getSkillRegistrations();
    @NonNull
    public List<@NonNull McpSkillGroup> getSkillGroups();
    @NonNull
    public Optional<@NonNull McpSkillListHandler> getSkillListHandler();
    @NonNull
    public McpCachePolicy getSkillListCachePolicy();
    @NonNull
    public Optional<@NonNull McpResourceListHandler> getResourceListHandler();
    @NonNull
    public McpCachePolicy getResourceListCachePolicy();
    @NonNull
    public McpCachePolicy getResourceTemplateListCachePolicy();
    @NonNull
    public Optional<@NonNull String> getToolRateLimiterName();
    @NonNull
    public Optional<@NonNull McpRateLimiter> getToolRateLimiter();
    @NonNull
    public Optional<@NonNull McpSubscriptionConfig> getSubscriptionConfig();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder serverInfo(@NonNull McpImplementation implementation);
        @NonNull
        public Builder serverInfoIncluded(@Nullable Boolean serverInfoIncluded);
        @NonNull
        public Builder instructions(@Nullable String instructions);
        @NonNull
        public Builder toolRegistrations(@Nullable List<@NonNull McpToolRegistration<?>> toolRegistrations);
        @NonNull
        public Builder promptRegistrations(@Nullable List<@NonNull McpPromptRegistration> promptRegistrations);
        @NonNull
        public Builder resourceRegistrations(@Nullable List<@NonNull McpResourceRegistration> resourceRegistrations);
        @NonNull
        public Builder skillRegistrations(@Nullable List<@NonNull McpSkillRegistration> skillRegistrations);
        @NonNull
        public Builder skillGroups(@Nullable List<@NonNull McpSkillGroup> skillGroups);
        @NonNull
        public Builder skillListHandler(@Nullable McpSkillListHandler skillListHandler);
        @NonNull
        public Builder skillListCachePolicy(@Nullable McpCachePolicy skillListCachePolicy);
        @NonNull
        public Builder resourceListHandler(@Nullable McpResourceListHandler resourceListHandler);
        @NonNull
        public Builder resourceListCachePolicy(@Nullable McpCachePolicy resourceListCachePolicy);
        @NonNull
        public Builder resourceTemplateListCachePolicy(@Nullable McpCachePolicy resourceTemplateListCachePolicy);
        @NonNull
        public Builder toolRateLimiterName(@Nullable String toolRateLimiterName);
        @NonNull
        public Builder toolRateLimiter(@Nullable McpRateLimiter toolRateLimiter);
        @NonNull
        public Builder subscriptionConfig(@Nullable McpSubscriptionConfig subscriptionConfig);
        @NonNull
        public McpEndpoint build();
    }
}
```

## `com.soklet.McpEndpointRegistry`

Source: [`McpEndpointRegistry.java`](../../src/main/java/com/soklet/McpEndpointRegistry.java#L50)

```java
@ThreadSafe
public final class McpEndpointRegistry {
    @NonNull
    public List<@NonNull McpEndpoint> getEndpoints();
    @NonNull
    public McpEndpointRegistry withEndpoint(@NonNull McpEndpoint endpoint);
    @NonNull
    public McpEndpointRegistry withSubscriptionConfig(@NonNull Class<?> annotatedEndpointClass, @NonNull McpSubscriptionConfig subscriptionConfig);
    @NonNull
    public McpEndpointRegistry withSkillRegistrations(@NonNull Class<?> annotatedEndpointClass, @NonNull List<@NonNull McpSkillRegistration> skillRegistrations);
    @NonNull
    public McpEndpointRegistry withSkillGroups(@NonNull Class<?> annotatedEndpointClass, @NonNull List<@NonNull McpSkillGroup> skillGroups);
    @NonNull
    public static McpEndpointRegistry fromClasspathIntrospection();
    @NonNull
    public static McpEndpointRegistry fromClasses(@NonNull Class<?> @NonNull... endpointClasses);
    @NonNull
    public static McpEndpointRegistry fromEndpoints(@NonNull Collection<@NonNull McpEndpoint> endpoints);
}
```

## `com.soklet.McpHandlerContinuation`

Source: [`McpHandlerContinuation.java`](../../src/main/java/com/soklet/McpHandlerContinuation.java#L34)

```java
@NotThreadSafe
@FunctionalInterface
public interface McpHandlerContinuation {
    @NonNull
    McpOperationResult proceed() throws Exception;
}
```

## `com.soklet.McpHandlerInterceptor`

Source: [`McpHandlerInterceptor.java`](../../src/main/java/com/soklet/McpHandlerInterceptor.java#L66)

```java
@ThreadSafe
@FunctionalInterface
public interface McpHandlerInterceptor {
    @NonNull
    McpOperationResult interceptHandler(@NonNull McpRequestContext requestContext, @NonNull McpInvocationFeatures invocationFeatures, @NonNull McpHandlerContinuation continuation) throws Exception;
    @NonNull
    static McpHandlerInterceptor passThroughInstance();
}
```

## `com.soklet.McpIcon`

Source: [`McpIcon.java`](../../src/main/java/com/soklet/McpIcon.java#L36)

```java
@ThreadSafe
public final class McpIcon {
    @NonNull
    public static Builder withSource(@NonNull URI source);
    @NonNull
    public URI getSource();
    @NonNull
    public Optional<@NonNull String> getMimeType();
    @NonNull
    public List<@NonNull String> getSizes();
    @NonNull
    public Optional<@NonNull McpIconTheme> getTheme();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder mimeType(@NonNull String mimeType);
        @NonNull
        public Builder sizes(@Nullable List<@NonNull String> sizes);
        @NonNull
        public Builder theme(@NonNull McpIconTheme theme);
        @NonNull
        public McpIcon build();
    }
}
```

## `com.soklet.McpIconTheme`

Source: [`McpIconTheme.java`](../../src/main/java/com/soklet/McpIconTheme.java#L24)

```java
public enum McpIconTheme {
    LIGHT, DARK;
}
```

## `com.soklet.McpImageContent`

Source: [`McpImageContent.java`](../../src/main/java/com/soklet/McpImageContent.java#L37)

```java
@ThreadSafe
public final class McpImageContent implements McpContentBlock {
    @NonNull
    public static Builder withDataAndMimeType(byte @NonNull [] data, @NonNull String mimeType);
    public byte @NonNull [] getData();
    @NonNull
    public String getMimeType();
    @Override
    @NonNull
    public Optional<@NonNull McpContentAnnotations> getAnnotations();
    @Override
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder annotations(@NonNull McpContentAnnotations annotations);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpImageContent build();
    }
}
```

## `com.soklet.McpImplementation`

Source: [`McpImplementation.java`](../../src/main/java/com/soklet/McpImplementation.java#L39)

```java
@ThreadSafe
public final class McpImplementation {
    @NonNull
    public static Builder withNameAndVersion(@NonNull String name, @NonNull String version);
    @NonNull
    public String getName();
    @NonNull
    public String getVersion();
    @NonNull
    public Optional<@NonNull String> getTitle();
    @NonNull
    public Optional<@NonNull String> getDescription();
    @NonNull
    public Optional<@NonNull URI> getWebsiteUrl();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder title(@NonNull String title);
        @NonNull
        public Builder description(@NonNull String description);
        @NonNull
        public Builder websiteUrl(@NonNull URI websiteUrl);
        @NonNull
        public McpImplementation build();
    }
}
```

## `com.soklet.McpInMemoryTaskManager`

Source: [`McpInMemoryTaskManager.java`](../../src/main/java/com/soklet/McpInMemoryTaskManager.java#L63)

```java
@ThreadSafe
public final class McpInMemoryTaskManager implements McpTaskManager {
    @NonNull
    public static Builder builder();
    @NonNull
    public Integer getMaximumRetainedTasks();
    @NonNull
    public Duration getTaskTimeToLive();
    @NonNull
    public Duration getPollInterval();
    @Override
    @NonNull
    public Optional<@NonNull McpTaskEventPublisher> getTaskEventPublisher();
    @NonNull
    public McpTask createTask(@NonNull McpTaskCreationContext taskCreationContext);
    @NonNull
    public Optional<@NonNull McpTask> findTask(@NonNull String taskId);
    @NonNull
    public McpTask markTaskWorking(@NonNull String taskId, @Nullable String taskStatusMessage) throws McpTaskNotFoundException;
    @NonNull
    public McpTask requestTaskInput(@NonNull String taskId, @NonNull Map<@NonNull String, ? extends @NonNull McpInputRequest> inputRequests, @Nullable String taskStatusMessage) throws McpTaskNotFoundException;
    @NonNull
    public McpTask completeTask(@NonNull String taskId, @NonNull McpCompleteResult completeResult, @Nullable String taskStatusMessage) throws McpTaskNotFoundException;
    @NonNull
    public McpTask failTask(@NonNull String taskId, @NonNull McpJsonRpcError failure, @Nullable String taskStatusMessage) throws McpTaskNotFoundException;
    @NonNull
    public McpTask cancelTask(@NonNull String taskId, @Nullable String taskStatusMessage) throws McpTaskNotFoundException;
    @NonNull
    public McpInputResponses takeTaskInputResponses(@NonNull String taskId) throws McpTaskNotFoundException;
    @NonNull
    public Boolean isTaskCancelationRequested(@NonNull String taskId) throws McpTaskNotFoundException;
    @Override
    @NonNull
    public Optional<@NonNull McpTask> findTask(@NonNull McpTaskRequestContext taskRequestContext);
    @Override
    public void updateTask(@NonNull McpTaskUpdateContext taskUpdateContext) throws McpTaskNotFoundException;
    @Override
    public void requestTaskCancelation(@NonNull McpTaskRequestContext taskRequestContext) throws McpTaskNotFoundException;
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder maximumRetainedTasks(@Nullable Integer maximumRetainedTasks);
        @NonNull
        public Builder taskTimeToLive(@Nullable Duration taskTimeToLive);
        @NonNull
        public Builder pollInterval(@Nullable Duration pollInterval);
        @NonNull
        public McpInMemoryTaskManager build();
    }
}
```

## `com.soklet.McpInputRequest`

Source: [`McpInputRequest.java`](../../src/main/java/com/soklet/McpInputRequest.java#L42)

```java
@ThreadSafe
public final class McpInputRequest {
    @NonNull
    public static McpInputRequest fromDeclaration(@NonNull McpInputRequestDeclaration declaration, @NonNull McpJsonObject params);
    @NonNull
    public McpInputRequestDeclaration getDeclaration();
    @NonNull
    public McpJsonObject getParams();
    @NonNull
    public String getJsonRpcMethod();
    @NonNull
    public Boolean matchesInputResponse(@NonNull McpJsonValue inputResponse);
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public final String toString();
}
```

## `com.soklet.McpInputRequestDeclaration`

Source: [`McpInputRequestDeclaration.java`](../../src/main/java/com/soklet/McpInputRequestDeclaration.java#L40)

```java
@ThreadSafe
public final class McpInputRequestDeclaration {
    @NonNull
    public static McpInputRequestDeclaration fromElicitationForm(@NonNull McpInputRequirement requirement);
    @NonNull
    public static McpInputRequestDeclaration fromElicitationUrl(@NonNull McpInputRequirement requirement);
    @NonNull
    public McpInputRequestType getInputRequestType();
    @NonNull
    public String getJsonRpcMethod();
    @NonNull
    public Set<@NonNull McpClientCapability> getCapabilities();
    @NonNull
    public McpInputRequirement getRequirement();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpInputRequestType`

Source: [`McpInputRequestType.java`](../../src/main/java/com/soklet/McpInputRequestType.java#L28)

```java
public enum McpInputRequestType {
    ELICITATION_FORM, ELICITATION_URL;
}
```

## `com.soklet.McpInputRequiredResult`

Source: [`McpInputRequiredResult.java`](../../src/main/java/com/soklet/McpInputRequiredResult.java#L49)

```java
@ThreadSafe
public final class McpInputRequiredResult implements McpOperationResult {
    @NonNull
    public static Builder withInputRequest(@NonNull String key, @NonNull McpInputRequest inputRequest);
    @NonNull
    public static Builder withFrameworkRequestState(@NonNull McpJsonValue frameworkRequestState);
    @NonNull
    public static Builder withApplicationRequestState(@NonNull String applicationRequestState);
    @NonNull
    public Map<@NonNull String, @NonNull McpInputRequest> getInputRequests();
    @NonNull
    public Optional<@NonNull McpJsonValue> getFrameworkRequestState();
    @NonNull
    public Optional<@NonNull String> getApplicationRequestState();
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder addInputRequest(@NonNull String key, @NonNull McpInputRequest inputRequest);
        @NonNull
        public Builder frameworkRequestState(@NonNull McpJsonValue frameworkRequestState);
        @NonNull
        public Builder applicationRequestState(@NonNull String applicationRequestState);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpInputRequiredResult build();
    }
}
```

## `com.soklet.McpInputRequirement`

Source: [`McpInputRequirement.java`](../../src/main/java/com/soklet/McpInputRequirement.java#L25)

```java
public enum McpInputRequirement {
    REQUIRED, CONDITIONAL;
}
```

## `com.soklet.McpInputResponses`

Source: [`McpInputResponses.java`](../../src/main/java/com/soklet/McpInputResponses.java#L58)

```java
@ThreadSafe
public final class McpInputResponses {
    @NonNull
    public static McpInputResponses emptyInstance();
    @NonNull
    public static McpInputResponses fromResponses(@NonNull Map<@NonNull String, ? extends @NonNull McpJsonValue> responses);
    @NonNull
    public static Builder builder();
    @NonNull
    public Optional<@NonNull McpJsonValue> find(@NonNull String key);
    @NonNull
    public <T> Optional<@NonNull T> find(@NonNull String key, @NonNull Class<@NonNull T> type);
    @NonNull
    public <T> Optional<@NonNull T> find(@NonNull String key, @NonNull TypeReference<@NonNull T> type);
    @NonNull
    public Map<@NonNull String, @NonNull McpJsonValue> asMap();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder addResponse(@NonNull String key, @NonNull McpJsonValue inputResponse);
        @NonNull
        public Builder addResponses(@NonNull Map<@NonNull String, ? extends @NonNull McpJsonValue> responses);
        @NonNull
        public McpInputResponses build();
    }
}
```

## `com.soklet.McpInvocationFeatures`

Source: [`McpInvocationFeatures.java`](../../src/main/java/com/soklet/McpInvocationFeatures.java#L48)

```java
@ThreadSafe
public interface McpInvocationFeatures {
    @NonNull
    static McpInvocationFeatures fromFeatures(@NonNull Map<@NonNull Class<?>, @NonNull Object> featuresByType);
    @NonNull
    <T> Optional<@NonNull T> find(@NonNull Class<@NonNull T> featureType);
    @NonNull
    default CancelationToken getCancelationToken();
    @NonNull
    default Optional<@NonNull McpProgressReporter> getProgressReporter();
    @NonNull
    default Optional<@NonNull McpTaskCreationContext> getTaskCreationContext();
    @NonNull
    default <T> T require(@NonNull Class<@NonNull T> featureType);
}
```

## `com.soklet.McpJsonArray`

Source: [`McpJsonArray.java`](../../src/main/java/com/soklet/McpJsonArray.java#L36)

```java
@ThreadSafe
public final class McpJsonArray implements McpJsonValue {
    @NonNull
    public static McpJsonArray emptyInstance();
    @NonNull
    public static Builder builder();
    @NonNull
    public static McpJsonArray fromElements(@NonNull Collection<? extends @NonNull McpJsonValue> elements);
    @NonNull
    public List<@NonNull McpJsonValue> getElements();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder add(@NonNull McpJsonValue value);
        @NonNull
        public Builder add(@NonNull String value);
        @NonNull
        public Builder add(@NonNull BigDecimal value);
        @NonNull
        public Builder add(@NonNull Integer value);
        @NonNull
        public Builder add(@NonNull Long value);
        @NonNull
        public Builder add(@NonNull Double value);
        @NonNull
        public Builder add(@NonNull Boolean value);
        @NonNull
        public Builder addNull();
        @NonNull
        public McpJsonArray build();
    }
}
```

## `com.soklet.McpJsonBoolean`

Source: [`McpJsonBoolean.java`](../../src/main/java/com/soklet/McpJsonBoolean.java#L31)

```java
@ThreadSafe
public final class McpJsonBoolean implements McpJsonValue {
    @NonNull
    public static McpJsonBoolean fromValue(@NonNull Boolean value);
    @NonNull
    public Boolean getValue();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpJsonNull`

Source: [`McpJsonNull.java`](../../src/main/java/com/soklet/McpJsonNull.java#L24)

```java
public enum McpJsonNull implements McpJsonValue {
    INSTANCE;
}
```

## `com.soklet.McpJsonNumber`

Source: [`McpJsonNumber.java`](../../src/main/java/com/soklet/McpJsonNumber.java#L32)

```java
@ThreadSafe
public final class McpJsonNumber implements McpJsonValue {
    @NonNull
    public static McpJsonNumber fromValue(@NonNull BigDecimal value);
    @NonNull
    public BigDecimal getValue();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpJsonObject`

Source: [`McpJsonObject.java`](../../src/main/java/com/soklet/McpJsonObject.java#L37)

```java
@ThreadSafe
public final class McpJsonObject implements McpJsonValue {
    @NonNull
    public static McpJsonObject emptyInstance();
    @NonNull
    public static Builder builder();
    @NonNull
    public static McpJsonObject fromMembers(@NonNull Map<@NonNull String, ? extends @NonNull McpJsonValue> members);
    @NonNull
    public Map<@NonNull String, @NonNull McpJsonValue> getMembers();
    @NonNull
    public Optional<@NonNull McpJsonValue> find(@NonNull String name);
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder put(@NonNull String name, @NonNull McpJsonValue value);
        @NonNull
        public Builder put(@NonNull String name, @NonNull String value);
        @NonNull
        public Builder put(@NonNull String name, @NonNull BigDecimal value);
        @NonNull
        public Builder put(@NonNull String name, @NonNull Integer value);
        @NonNull
        public Builder put(@NonNull String name, @NonNull Long value);
        @NonNull
        public Builder put(@NonNull String name, @NonNull Double value);
        @NonNull
        public Builder put(@NonNull String name, @NonNull Boolean value);
        @NonNull
        public Builder putNull(@NonNull String name);
        @NonNull
        public McpJsonObject build();
    }
}
```

## `com.soklet.McpJsonRpcError`

Source: [`McpJsonRpcError.java`](../../src/main/java/com/soklet/McpJsonRpcError.java#L34)

```java
@ThreadSafe
public final class McpJsonRpcError {
    public static final int SOKLET_RATE_LIMIT_ERROR_CODE;
    public static final int SOKLET_STRICT_UNKNOWN_MIRRORED_HEADER_ERROR_CODE;
    @NonNull
    public static McpJsonRpcError fromApplication(@NonNull Integer code, @NonNull String message);
    @NonNull
    public static McpJsonRpcError fromApplication(@NonNull Integer code, @NonNull String message, @NonNull McpJsonValue data);
    @NonNull
    public static McpJsonRpcError fromInvalidParameters(@NonNull String message);
    @NonNull
    public static McpJsonRpcError fromInvalidParameters(@NonNull String message, @NonNull McpJsonValue data);
    @NonNull
    public Integer getCode();
    @NonNull
    public String getMessage();
    @NonNull
    public Optional<@NonNull McpJsonValue> getData();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
}
```

## `com.soklet.McpJsonRpcException`

Source: [`McpJsonRpcException.java`](../../src/main/java/com/soklet/McpJsonRpcException.java#L35)

```java
@NotThreadSafe
public final class McpJsonRpcException extends RuntimeException {
    public McpJsonRpcException(@NonNull McpJsonRpcError jsonRpcError) ;
    @NonNull
    public McpJsonRpcError getError();
}
```

## `com.soklet.McpJsonString`

Source: [`McpJsonString.java`](../../src/main/java/com/soklet/McpJsonString.java#L31)

```java
@ThreadSafe
public final class McpJsonString implements McpJsonValue {
    @NonNull
    public static McpJsonString fromValue(@NonNull String value);
    @NonNull
    public String getValue();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpJsonValue`

Source: [`McpJsonValue.java`](../../src/main/java/com/soklet/McpJsonValue.java#L26)

```java
@ThreadSafe
public sealed interface McpJsonValue permits McpJsonArray, McpJsonBoolean, McpJsonNull, McpJsonNumber, McpJsonObject, McpJsonString {
}
```

## `com.soklet.McpLocalizableText`

Source: [`McpLocalizableText.java`](../../src/main/java/com/soklet/McpLocalizableText.java#L32)

```java
@ThreadSafe
public final class McpLocalizableText {
    @NonNull
    public static McpLocalizableText fromCoordinateAndDefaultText(@NonNull McpTextCoordinate coordinate, @NonNull String defaultText);
    @NonNull
    public McpTextCoordinate getCoordinate();
    @NonNull
    public String getDefaultText();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpLocalizationCatalog`

Source: [`McpLocalizationCatalog.java`](../../src/main/java/com/soklet/McpLocalizationCatalog.java#L33)

```java
@ThreadSafe
public final class McpLocalizationCatalog {
    @NonNull
    public static McpLocalizationCatalog fromEndpointRegistry(@NonNull McpEndpointRegistry endpointRegistry);
    @NonNull
    public List<@NonNull McpLocalizableText> getTexts();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
}
```

## `com.soklet.McpLocalizationCatalogInvalidator`

Source: [`McpLocalizationCatalogInvalidator.java`](../../src/main/java/com/soklet/McpLocalizationCatalogInvalidator.java#L33)

```java
@ThreadSafe
public interface McpLocalizationCatalogInvalidator {
    @NonNull
    Boolean isEnabled();
    void invalidateCatalogs();
}
```

## `com.soklet.McpLocalizationContext`

Source: [`McpLocalizationContext.java`](../../src/main/java/com/soklet/McpLocalizationContext.java#L55)

```java
@ThreadSafe
public final class McpLocalizationContext {
    @NonNull
    public static Builder withLocale(@NonNull Locale locale, @NonNull McpLocalizationLookup localizationLookup);
    @NonNull
    public Locale getLocale();
    @NonNull
    public Optional<@NonNull McpLocalizationRevision> getRevision();
    @NonNull
    public McpLocalizationResult localize(@NonNull McpLocalizableText text);
    @Override
    @NonNull
    public String toString();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder revision(@Nullable McpLocalizationRevision revision);
        @NonNull
        public McpLocalizationContext build();
    }
}
```

## `com.soklet.McpLocalizationContextProvider`

Source: [`McpLocalizationContextProvider.java`](../../src/main/java/com/soklet/McpLocalizationContextProvider.java#L41)

```java
@ThreadSafe
@FunctionalInterface
public interface McpLocalizationContextProvider {
    @NonNull
    McpLocalizationContext provideContext(@NonNull McpLocalizationRequest localizationRequest) throws Exception;
}
```

## `com.soklet.McpLocalizationFailurePolicy`

Source: [`McpLocalizationFailurePolicy.java`](../../src/main/java/com/soklet/McpLocalizationFailurePolicy.java#L30)

```java
public enum McpLocalizationFailurePolicy {
    USE_DEFAULT_TEXT, FAIL_REQUEST;
}
```

## `com.soklet.McpLocalizationLookup`

Source: [`McpLocalizationLookup.java`](../../src/main/java/com/soklet/McpLocalizationLookup.java#L33)

```java
@ThreadSafe
@FunctionalInterface
public interface McpLocalizationLookup {
    @NonNull
    McpLocalizationResult localize(@NonNull McpLocalizableText text);
}
```

## `com.soklet.McpLocalizationRequest`

Source: [`McpLocalizationRequest.java`](../../src/main/java/com/soklet/McpLocalizationRequest.java#L39)

```java
@ThreadSafe
public interface McpLocalizationRequest {
    @NonNull
    McpRequestContext getRequestContext();
    @NonNull
    List<Locale.@NonNull LanguageRange> getLanguageRanges();
    @NonNull
    Optional<@NonNull Locale> getContinuationLocale();
    @NonNull
    Optional<@NonNull String> getResourceListCursor();
    @NonNull
    Optional<@NonNull String> getSkillListCursor();
    @NonNull
    Locale getFallbackLocale();
}
```

## `com.soklet.McpLocalizationResult`

Source: [`McpLocalizationResult.java`](../../src/main/java/com/soklet/McpLocalizationResult.java#L31)

```java
@ThreadSafe
public sealed interface McpLocalizationResult permits McpLocalizationResult.Localized, McpLocalizationResult.UseDefaultText, McpLocalizationResult.Failure {
    @NonNull
    static Localized localized(@NonNull String text);
    @NonNull
    static UseDefaultText useDefaultText();
    @NonNull
    static Failure failure();
    @ThreadSafe
    public final class Localized implements McpLocalizationResult {
        @NonNull
        public String getText();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public final String toString();
    }
    @ThreadSafe
    public final class UseDefaultText implements McpLocalizationResult {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class Failure implements McpLocalizationResult {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
}
```

## `com.soklet.McpLocalizationRevision`

Source: [`McpLocalizationRevision.java`](../../src/main/java/com/soklet/McpLocalizationRevision.java#L36)

```java
@ThreadSafe
public final class McpLocalizationRevision {
    @NonNull
    public static McpLocalizationRevision fromValue(@NonNull String value);
    @NonNull
    public String getValue();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpLocalizer`

Source: [`McpLocalizer.java`](../../src/main/java/com/soklet/McpLocalizer.java#L40)

```java
@ThreadSafe
public final class McpLocalizer {
    @NonNull
    public static Builder withFallbackLocale(@NonNull Locale fallbackLocale, @NonNull McpLocalizationContextProvider localizationContextProvider);
    @NonNull
    public Locale getFallbackLocale();
    @NonNull
    public McpLocalizationContextProvider getContextProvider();
    @NonNull
    public McpLocalizationFailurePolicy getFailurePolicy();
    @NonNull
    public Integer getMaximumLocalizableTextCountPerResponse();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder failurePolicy(@Nullable McpLocalizationFailurePolicy failurePolicy);
        @NonNull
        public Builder maximumLocalizableTextCountPerResponse(@Nullable Integer maximumLocalizableTextCountPerResponse);
        @NonNull
        public McpLocalizer build();
    }
}
```

## `com.soklet.McpMetricsEvent`

Source: [`McpMetricsEvent.java`](../../src/main/java/com/soklet/McpMetricsEvent.java#L43)

```java
@ThreadSafe
public sealed interface McpMetricsEvent permits McpMetricsEvent.ServerStarted, McpMetricsEvent.ConnectionAccepted, McpMetricsEvent.ConnectionRejected, McpMetricsEvent.RequestAccepted, McpMetricsEvent.RequestRejected, McpMetricsEvent.RequestStarted, McpMetricsEvent.RequestFinished, McpMetricsEvent.RequestStreamOpened, McpMetricsEvent.RequestStreamClosed, McpMetricsEvent.SubscriptionOpened, McpMetricsEvent.SubscriptionClosed, McpMetricsEvent.SubscriptionMaintenance, McpMetricsEvent.CancelationSignaled, McpMetricsEvent.ProgressEmitted, McpMetricsEvent.KeepAliveEmitted, McpMetricsEvent.ProtocolError, McpMetricsEvent.UnknownMirroredHeader, McpMetricsEvent.HandlerExecutionStarted, McpMetricsEvent.HandlerExecutionFinished, McpMetricsEvent.HandlerQueued, McpMetricsEvent.HandlerDequeued, McpMetricsEvent.HandlerCapacityRejected, McpMetricsEvent.TransportFailure, McpMetricsEvent.ServerStopped {
    @NonNull
    String UNRECOGNIZED_JSON_RPC_METHOD;
    @NonNull
    static ServerStarted serverStarted();
    @NonNull
    static ConnectionAccepted connectionAccepted();
    @NonNull
    static ConnectionRejected connectionRejected();
    @NonNull
    static RequestAccepted requestAccepted();
    @NonNull
    static RequestRejected requestRejected();
    @NonNull
    static RequestStarted requestStarted(@NonNull String endpointPath, @NonNull String jsonRpcMethod);
    @NonNull
    static RequestFinished requestFinished(@NonNull String endpointPath, @NonNull String jsonRpcMethod, @NonNull McpRequestOutcome requestOutcome, @NonNull Duration requestDuration);
    @NonNull
    static RequestStreamOpened requestStreamOpened(@NonNull String endpointPath, @NonNull String jsonRpcMethod);
    @NonNull
    static RequestStreamClosed requestStreamClosed(@NonNull String endpointPath, @NonNull String jsonRpcMethod, @NonNull McpStreamTerminationReason streamTerminationReason, @NonNull Duration streamDuration);
    @NonNull
    static SubscriptionOpened subscriptionOpened(@NonNull String endpointPath);
    @NonNull
    static SubscriptionClosed subscriptionClosed(@NonNull String endpointPath, @NonNull McpStreamTerminationReason streamTerminationReason, @NonNull Duration subscriptionDuration);
    @NonNull
    static SubscriptionMaintenance subscriptionMaintenance(@NonNull String endpointPath, SubscriptionMaintenance.@NonNull Work maintenanceWork, SubscriptionMaintenance.@NonNull Outcome maintenanceOutcome);
    @NonNull
    static CancelationSignaled cancelationSignaled(@NonNull String endpointPath, @NonNull String jsonRpcMethod);
    @NonNull
    static ProgressEmitted progressEmitted(@NonNull String endpointPath, @NonNull String jsonRpcMethod);
    @NonNull
    static KeepAliveEmitted keepAliveEmitted();
    @NonNull
    static ProtocolError protocolError(@NonNull Integer code);
    @NonNull
    static UnknownMirroredHeader unknownMirroredHeader(@NonNull String endpointPath, @NonNull String jsonRpcMethod);
    @NonNull
    static HandlerExecutionStarted handlerExecutionStarted();
    @NonNull
    static HandlerExecutionFinished handlerExecutionFinished();
    @NonNull
    static HandlerQueued handlerQueued();
    @NonNull
    static HandlerDequeued handlerDequeued();
    @NonNull
    static HandlerCapacityRejected handlerCapacityRejected();
    @NonNull
    static TransportFailure transportFailure(MetricsCollector.@NonNull TransportFailureReason reason);
    @NonNull
    static ServerStopped serverStopped(@NonNull ShutdownComponentDisposition shutdownComponentDisposition);
    @ThreadSafe
    public final class ServerStarted implements McpMetricsEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class ConnectionAccepted implements McpMetricsEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class ConnectionRejected implements McpMetricsEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class RequestAccepted implements McpMetricsEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class RequestRejected implements McpMetricsEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class RequestStarted implements McpMetricsEvent {
        @NonNull
        public String getEndpointPath();
        @NonNull
        public String getJsonRpcMethod();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class RequestFinished implements McpMetricsEvent {
        @NonNull
        public String getEndpointPath();
        @NonNull
        public String getJsonRpcMethod();
        @NonNull
        public McpRequestOutcome getOutcome();
        @NonNull
        public Duration getDuration();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class RequestStreamOpened implements McpMetricsEvent {
        @NonNull
        public String getEndpointPath();
        @NonNull
        public String getJsonRpcMethod();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class RequestStreamClosed implements McpMetricsEvent {
        @NonNull
        public String getEndpointPath();
        @NonNull
        public String getJsonRpcMethod();
        @NonNull
        public McpStreamTerminationReason getReason();
        @NonNull
        public Duration getDuration();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class SubscriptionOpened implements McpMetricsEvent {
        @NonNull
        public String getEndpointPath();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class SubscriptionClosed implements McpMetricsEvent {
        @NonNull
        public String getEndpointPath();
        @NonNull
        public McpStreamTerminationReason getReason();
        @NonNull
        public Duration getDuration();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class SubscriptionMaintenance implements McpMetricsEvent {
        public enum Work {
            AUTHORIZATION, CATALOG_PROJECTION, RECONCILIATION;
        }
        public enum Outcome {
            SUCCEEDED, DENIED, TIMED_OUT, CAPACITY_REJECTED, FAILED, COALESCED, STALE_RESULT_DISCARDED;
        }
        @NonNull
        public String getEndpointPath();
        @NonNull
        public Work getWork();
        @NonNull
        public Outcome getOutcome();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class CancelationSignaled implements McpMetricsEvent {
        @NonNull
        public String getEndpointPath();
        @NonNull
        public String getJsonRpcMethod();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class ProgressEmitted implements McpMetricsEvent {
        @NonNull
        public String getEndpointPath();
        @NonNull
        public String getJsonRpcMethod();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class KeepAliveEmitted implements McpMetricsEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class ProtocolError implements McpMetricsEvent {
        @NonNull
        public Integer getCode();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class UnknownMirroredHeader implements McpMetricsEvent {
        @NonNull
        public String getEndpointPath();
        @NonNull
        public String getJsonRpcMethod();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class HandlerExecutionStarted implements McpMetricsEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class HandlerExecutionFinished implements McpMetricsEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class HandlerQueued implements McpMetricsEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class HandlerDequeued implements McpMetricsEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class HandlerCapacityRejected implements McpMetricsEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class TransportFailure implements McpMetricsEvent {
        public MetricsCollector.@NonNull TransportFailureReason getReason();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class ServerStopped implements McpMetricsEvent {
        @NonNull
        public ShutdownComponentDisposition getShutdownComponentDisposition();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
}
```

## `com.soklet.McpMetricsSnapshot`

Source: [`McpMetricsSnapshot.java`](../../src/main/java/com/soklet/McpMetricsSnapshot.java#L44)

```java
@ThreadSafe
public final class McpMetricsSnapshot {
    @NonNull
    public static McpMetricsSnapshot emptyInstance();
    @NonNull
    public static Builder builder();
    @NonNull
    public Long getActiveHandlerExecutions();
    @NonNull
    public Long getHandlerQueueDepth();
    @NonNull
    public Long getHandlerCapacityRejections();
    @NonNull
    public Map<@NonNull ShutdownComponentDisposition, @NonNull Long> getServerStops();
    @NonNull
    public Long getConnectionsAccepted();
    @NonNull
    public Long getConnectionsRejected();
    @NonNull
    public Map<MetricsCollector.@NonNull TransportFailureReason, @NonNull Long> getTransportFailures();
    @NonNull
    public Long getServerStarts();
    @NonNull
    public Long getRequestsAccepted();
    @NonNull
    public Long getRequestsRejected();
    @NonNull
    public Long getActiveRequests();
    @NonNull
    public Map<@NonNull RequestOutcomeKey, @NonNull Long> getRequests();
    @NonNull
    public Map<@NonNull RequestOutcomeKey, MetricsCollector.@NonNull HistogramSnapshot> getRequestDurations();
    @NonNull
    public Long getActiveRequestStreams();
    @NonNull
    public Map<@NonNull RequestStreamTerminationKey, MetricsCollector.@NonNull HistogramSnapshot> getRequestStreamDurations();
    @NonNull
    public Long getActiveSubscriptions();
    @NonNull
    public Map<@NonNull SubscriptionTerminationKey, MetricsCollector.@NonNull HistogramSnapshot> getSubscriptionDurations();
    @NonNull
    public Map<@NonNull EndpointMethodKey, @NonNull Long> getCancelationsSignaled();
    @NonNull
    public Map<@NonNull EndpointMethodKey, @NonNull Long> getProgressEmitted();
    @NonNull
    public Long getKeepAlivesEmitted();
    @NonNull
    public Map<@NonNull Integer, @NonNull Long> getProtocolErrors();
    @NonNull
    public Map<@NonNull EndpointMethodKey, @NonNull Long> getUnknownMirroredHeaders();
    @ThreadSafe
    public static final class EndpointMethodKey {
        @NonNull
        public static EndpointMethodKey fromDimensions(@NonNull String endpointPath, @NonNull String jsonRpcMethod);
        @NonNull
        public String getEndpointPath();
        @NonNull
        public String getJsonRpcMethod();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public static final class RequestOutcomeKey {
        @NonNull
        public static RequestOutcomeKey fromDimensions(@NonNull String endpointPath, @NonNull String jsonRpcMethod, @NonNull McpRequestOutcome requestOutcome);
        @NonNull
        public String getEndpointPath();
        @NonNull
        public String getJsonRpcMethod();
        @NonNull
        public McpRequestOutcome getOutcome();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public static final class RequestStreamTerminationKey {
        @NonNull
        public static RequestStreamTerminationKey fromDimensions(@NonNull String endpointPath, @NonNull String jsonRpcMethod, @NonNull McpStreamTerminationReason streamTerminationReason);
        @NonNull
        public String getEndpointPath();
        @NonNull
        public String getJsonRpcMethod();
        @NonNull
        public McpStreamTerminationReason getReason();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public static final class SubscriptionTerminationKey {
        @NonNull
        public static SubscriptionTerminationKey fromDimensions(@NonNull String endpointPath, @NonNull McpStreamTerminationReason streamTerminationReason);
        @NonNull
        public String getEndpointPath();
        @NonNull
        public McpStreamTerminationReason getReason();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder activeHandlerExecutions(@NonNull Long activeHandlerExecutions);
        @NonNull
        public Builder handlerQueueDepth(@NonNull Long handlerQueueDepth);
        @NonNull
        public Builder handlerCapacityRejections(@NonNull Long handlerCapacityRejections);
        @NonNull
        public Builder serverStops(@Nullable Map<@NonNull ShutdownComponentDisposition, @NonNull Long> serverStops);
        @NonNull
        public Builder connectionsAccepted(@NonNull Long connectionsAccepted);
        @NonNull
        public Builder connectionsRejected(@NonNull Long connectionsRejected);
        @NonNull
        public Builder transportFailures(@Nullable Map<MetricsCollector.@NonNull TransportFailureReason, @NonNull Long> transportFailures);
        @NonNull
        public Builder serverStarts(@NonNull Long serverStarts);
        @NonNull
        public Builder requestsAccepted(@NonNull Long requestsAccepted);
        @NonNull
        public Builder requestsRejected(@NonNull Long requestsRejected);
        @NonNull
        public Builder activeRequests(@NonNull Long activeRequests);
        @NonNull
        public Builder requests(@Nullable Map<@NonNull RequestOutcomeKey, @NonNull Long> requests);
        @NonNull
        public Builder requestDurations(@Nullable Map<@NonNull RequestOutcomeKey, MetricsCollector.@NonNull HistogramSnapshot> requestDurations);
        @NonNull
        public Builder activeRequestStreams(@NonNull Long activeRequestStreams);
        @NonNull
        public Builder requestStreamDurations(@Nullable Map<@NonNull RequestStreamTerminationKey, MetricsCollector.@NonNull HistogramSnapshot> requestStreamDurations);
        @NonNull
        public Builder activeSubscriptions(@NonNull Long activeSubscriptions);
        @NonNull
        public Builder subscriptionDurations(@Nullable Map<@NonNull SubscriptionTerminationKey, MetricsCollector.@NonNull HistogramSnapshot> subscriptionDurations);
        @NonNull
        public Builder cancelationsSignaled(@Nullable Map<@NonNull EndpointMethodKey, @NonNull Long> cancelationsSignaled);
        @NonNull
        public Builder progressEmitted(@Nullable Map<@NonNull EndpointMethodKey, @NonNull Long> progressEmitted);
        @NonNull
        public Builder keepAlivesEmitted(@NonNull Long keepAlivesEmitted);
        @NonNull
        public Builder protocolErrors(@Nullable Map<@NonNull Integer, @NonNull Long> protocolErrors);
        @NonNull
        public Builder unknownMirroredHeaders(@Nullable Map<@NonNull EndpointMethodKey, @NonNull Long> unknownMirroredHeaders);
        @NonNull
        public McpMetricsSnapshot build();
    }
}
```

## `com.soklet.McpOperationResult`

Source: [`McpOperationResult.java`](../../src/main/java/com/soklet/McpOperationResult.java#L33)

```java
@ThreadSafe
public sealed interface McpOperationResult permits McpCompleteResult, McpInputRequiredResult, McpTaskCreatedResult, McpResourcePage, McpArgumentCompletionResult, McpSkillPage {
}
```

## `com.soklet.McpOperationType`

Source: [`McpOperationType.java`](../../src/main/java/com/soklet/McpOperationType.java#L37)

```java
public enum McpOperationType {
    SERVER_DISCOVER, TOOLS_LIST, TOOLS_CALL, PROMPTS_LIST, PROMPTS_GET, RESOURCES_LIST, RESOURCES_TEMPLATES_LIST, RESOURCES_READ, SKILLS_LIST, SKILLS_GET, COMPLETION_COMPLETE, SUBSCRIPTIONS_LISTEN, TASKS_GET, TASKS_UPDATE, TASKS_CANCEL, NOTIFICATIONS_CANCELED, OTHER;
}
```

## `com.soklet.McpProgressReporter`

Source: [`McpProgressReporter.java`](../../src/main/java/com/soklet/McpProgressReporter.java#L35)

```java
@ThreadSafe
@FunctionalInterface
public interface McpProgressReporter {
    void report(@NonNull McpProgressUpdate update);
}
```

## `com.soklet.McpProgressUpdate`

Source: [`McpProgressUpdate.java`](../../src/main/java/com/soklet/McpProgressUpdate.java#L38)

```java
@ThreadSafe
public final class McpProgressUpdate {
    @NonNull
    public static Builder withProgress(@NonNull Double progress);
    @NonNull
    public Double getProgress();
    @NonNull
    public Optional<@NonNull Double> getTotal();
    @NonNull
    public Optional<@NonNull String> getMessage();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder total(@NonNull Double total);
        @NonNull
        public Builder message(@NonNull String message);
        @NonNull
        public McpProgressUpdate build();
    }
}
```

## `com.soklet.McpPromptArgumentDeclaration`

Source: [`McpPromptArgumentDeclaration.java`](../../src/main/java/com/soklet/McpPromptArgumentDeclaration.java#L38)

```java
@ThreadSafe
public final class McpPromptArgumentDeclaration {
    @NonNull
    public static Builder withName(@NonNull String name);
    @NonNull
    public String getName();
    @NonNull
    public Optional<@NonNull String> getTitle();
    @NonNull
    public Optional<@NonNull String> getDescription();
    @NonNull
    public Boolean isRequired();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder title(@NonNull String title);
        @NonNull
        public Builder description(@NonNull String description);
        @NonNull
        public Builder required(@NonNull Boolean required);
        @NonNull
        public McpPromptArgumentDeclaration build();
    }
}
```

## `com.soklet.McpPromptGetContext`

Source: [`McpPromptGetContext.java`](../../src/main/java/com/soklet/McpPromptGetContext.java#L38)

```java
@ThreadSafe
public interface McpPromptGetContext {
    @NonNull
    Map<@NonNull String, @NonNull String> getArguments();
    @NonNull
    Optional<@NonNull String> findArgument(@NonNull String name);
}
```

## `com.soklet.McpPromptHandler`

Source: [`McpPromptHandler.java`](../../src/main/java/com/soklet/McpPromptHandler.java#L42)

```java
@ThreadSafe
@FunctionalInterface
public interface McpPromptHandler {
    @NonNull
    McpOperationResult handle(@NonNull McpRequestContext requestContext, @NonNull McpPromptGetContext promptGetContext, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
```

## `com.soklet.McpPromptMessage`

Source: [`McpPromptMessage.java`](../../src/main/java/com/soklet/McpPromptMessage.java#L32)

```java
@ThreadSafe
public final class McpPromptMessage {
    @NonNull
    public static McpPromptMessage fromUserText(@NonNull String text);
    @NonNull
    public static McpPromptMessage fromUserContent(@NonNull McpContentBlock content);
    @NonNull
    public static McpPromptMessage fromAssistantText(@NonNull String text);
    @NonNull
    public static McpPromptMessage fromAssistantContent(@NonNull McpContentBlock content);
    @NonNull
    public McpRole getRole();
    @NonNull
    public McpContentBlock getContent();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpPromptOutput`

Source: [`McpPromptOutput.java`](../../src/main/java/com/soklet/McpPromptOutput.java#L35)

```java
@ThreadSafe
public final class McpPromptOutput implements McpCompletePayload {
    @NonNull
    public static Builder builder();
    @NonNull
    public static McpPromptOutput fromMessages(@NonNull McpPromptMessage @NonNull... messages);
    @NonNull
    public Optional<@NonNull String> getDescription();
    @NonNull
    public List<@NonNull McpPromptMessage> getMessages();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder description(@NonNull String description);
        @NonNull
        public Builder messages(@Nullable List<@NonNull McpPromptMessage> messages);
        @NonNull
        public McpPromptOutput build();
    }
}
```

## `com.soklet.McpPromptRegistration`

Source: [`McpPromptRegistration.java`](../../src/main/java/com/soklet/McpPromptRegistration.java#L43)

```java
@ThreadSafe
public final class McpPromptRegistration {
    @NonNull
    public static HandlerStage withName(@NonNull String name);
    @NonNull
    public String getName();
    @NonNull
    public Optional<@NonNull String> getTitle();
    @NonNull
    public Optional<@NonNull String> getDescription();
    @NonNull
    public List<@NonNull McpIcon> getIcons();
    @NonNull
    public List<@NonNull McpPromptArgumentDeclaration> getArguments();
    @NonNull
    public List<@NonNull McpInputRequestDeclaration> getInputRequestDeclarations();
    @NonNull
    public McpRequestStateMode getRequestStateMode();
    @NonNull
    public McpJsonObject getMetadata();
    @NonNull
    public McpPromptHandler getHandler();
    @NonNull
    public Optional<@NonNull McpCompletionHandler> getCompletionHandler();
    @NotThreadSafe
    public static final class HandlerStage {
        @NonNull
        public Builder handler(@NonNull McpPromptHandler handler);
    }
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder title(@NonNull String title);
        @NonNull
        public Builder description(@NonNull String description);
        @NonNull
        public Builder icons(@Nullable List<@NonNull McpIcon> icons);
        @NonNull
        public Builder arguments(@Nullable List<@NonNull McpPromptArgumentDeclaration> argumentDeclarations);
        @NonNull
        public Builder inputRequestDeclarations(@Nullable List<@NonNull McpInputRequestDeclaration> inputRequestDeclarations);
        @NonNull
        public Builder requestStateMode(@NonNull McpRequestStateMode requestStateMode);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public Builder completionHandler(@NonNull McpCompletionHandler completionHandler);
        @NonNull
        public McpPromptRegistration build();
    }
}
```

## `com.soklet.McpProtectionConfig`

Source: [`McpProtectionConfig.java`](../../src/main/java/com/soklet/McpProtectionConfig.java#L41)

```java
@ThreadSafe
public final class McpProtectionConfig {
    @NonNull
    public static Builder withKeyring(@NonNull McpProtectionKeyring keyring);
    @NonNull
    public static Builder withRequestStateProtector(@NonNull McpRequestStateProtector requestStateProtector);
    @NonNull
    public static Builder withDevelopmentEphemeralProtection();
    @NonNull
    public McpProtectionMode getProtectionMode();
    @NonNull
    public Optional<@NonNull McpProtectionKeyring> getInitialKeyring();
    @NonNull
    public Optional<@NonNull McpRequestStateProtector> getRequestStateProtector();
    @NonNull
    public Integer getMaximumEncodedRequestStateSizeInBytes();
    @NonNull
    public Integer getMaximumDecodedRequestStateSizeInBytes();
    @NonNull
    public Duration getMaximumRequestStateLifetime();
    @NonNull
    public Integer getMaximumRequestStateRounds();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder maximumEncodedRequestStateSizeInBytes(@Nullable Integer maximumEncodedRequestStateSizeInBytes);
        @NonNull
        public Builder maximumDecodedRequestStateSizeInBytes(@Nullable Integer maximumDecodedRequestStateSizeInBytes);
        @NonNull
        public Builder maximumRequestStateLifetime(@Nullable Duration maximumRequestStateLifetime);
        @NonNull
        public Builder maximumRequestStateRounds(@Nullable Integer maximumRequestStateRounds);
        @NonNull
        public McpProtectionConfig build();
    }
}
```

## `com.soklet.McpProtectionKey`

Source: [`McpProtectionKey.java`](../../src/main/java/com/soklet/McpProtectionKey.java#L42)

```java
@ThreadSafe
public final class McpProtectionKey {
    @NonNull
    public static McpProtectionKey fromIdAndBytes(@NonNull String keyId, byte @NonNull [] keyMaterial);
    @NonNull
    public String getKeyId();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpProtectionKeyInUseException`

Source: [`McpProtectionKeyInUseException.java`](../../src/main/java/com/soklet/McpProtectionKeyInUseException.java#L31)

```java
@NotThreadSafe
public final class McpProtectionKeyInUseException extends RuntimeException {
}
```

## `com.soklet.McpProtectionKeyring`

Source: [`McpProtectionKeyring.java`](../../src/main/java/com/soklet/McpProtectionKeyring.java#L41)

```java
@ThreadSafe
public final class McpProtectionKeyring {
    @NonNull
    public static Builder withActiveKey(@NonNull McpProtectionKey activeKey);
    @NonNull
    public String getActiveKeyId();
    @NonNull
    public Set<@NonNull String> getVerificationKeyIds();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder addVerificationKey(@NonNull McpProtectionKey verificationKey);
        @NonNull
        public Builder addVerificationKeys(@NonNull Collection<@NonNull McpProtectionKey> verificationKeys);
        @NonNull
        public McpProtectionKeyring build();
    }
}
```

## `com.soklet.McpProtectionKeyringFingerprint`

Source: [`McpProtectionKeyringFingerprint.java`](../../src/main/java/com/soklet/McpProtectionKeyringFingerprint.java#L36)

```java
@ThreadSafe
public final class McpProtectionKeyringFingerprint {
    @NonNull
    public static final String VERSION;
    @NonNull
    public static final String PROFILE;
    @NonNull
    public String getVersion();
    @NonNull
    public String getProfile();
    @NonNull
    public String getValue();
    @Override
    @NonNull
    public String toString();
    @Override
    public boolean equals(@Nullable Object object);
    @Override
    public int hashCode();
}
```

## `com.soklet.McpProtectionKeyringManager`

Source: [`McpProtectionKeyringManager.java`](../../src/main/java/com/soklet/McpProtectionKeyringManager.java#L45)

```java
@ThreadSafe
public interface McpProtectionKeyringManager {
    @NonNull
    McpProtectionMode getProtectionMode();
    @NonNull
    Optional<@NonNull McpProtectionKeyringSnapshot> getKeyringSnapshot();
    void stageVerificationKey(@NonNull McpProtectionKey verificationKey);
    void activateStagedKey(@NonNull String keyId);
    void rotateActiveKey(@NonNull McpProtectionKey activeKey);
    @NonNull
    Boolean removeVerificationKey(@NonNull String keyId);
}
```

## `com.soklet.McpProtectionKeyringSnapshot`

Source: [`McpProtectionKeyringSnapshot.java`](../../src/main/java/com/soklet/McpProtectionKeyringSnapshot.java#L32)

```java
@ThreadSafe
public final class McpProtectionKeyringSnapshot {
    @NonNull
    public String getActiveKeyId();
    @NonNull
    public Set<@NonNull String> getVerificationKeyIds();
    @NonNull
    public McpProtectionKeyringFingerprint getFingerprint();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
}
```

## `com.soklet.McpProtectionMode`

Source: [`McpProtectionMode.java`](../../src/main/java/com/soklet/McpProtectionMode.java#L24)

```java
public enum McpProtectionMode {
    NONE, CUSTOM_PROTECTOR, PRODUCTION_KEYRING, DEVELOPMENT_EPHEMERAL;
}
```

## `com.soklet.McpRateLimitContext`

Source: [`McpRateLimitContext.java`](../../src/main/java/com/soklet/McpRateLimitContext.java#L34)

```java
@ThreadSafe
public interface McpRateLimitContext {
    @NonNull
    Request getRequest();
    @NonNull
    McpEndpoint getEndpoint();
    @NonNull
    McpAdmissionIdentity getAdmissionIdentity();
    @NonNull
    McpRateLimitTarget getTarget();
    @NonNull
    String getJsonRpcMethod();
    @NonNull
    default McpOperationType getOperationType();
    @NonNull
    Optional<@NonNull String> getOperationName();
}
```

## `com.soklet.McpRateLimitDecision`

Source: [`McpRateLimitDecision.java`](../../src/main/java/com/soklet/McpRateLimitDecision.java#L32)

```java
@ThreadSafe
public sealed interface McpRateLimitDecision permits McpRateLimitDecision.Allowed, McpRateLimitDecision.Denied {
    @NonNull
    static Allowed allowed();
    @NonNull
    static Denied denied(@NonNull Duration retryAfter);
    @ThreadSafe
    public final class Allowed implements McpRateLimitDecision {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class Denied implements McpRateLimitDecision {
        @NonNull
        public Duration getRetryAfter();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
}
```

## `com.soklet.McpRateLimitTarget`

Source: [`McpRateLimitTarget.java`](../../src/main/java/com/soklet/McpRateLimitTarget.java#L24)

```java
public enum McpRateLimitTarget {
    REQUEST, TOOL;
}
```

## `com.soklet.McpRateLimiter`

Source: [`McpRateLimiter.java`](../../src/main/java/com/soklet/McpRateLimiter.java#L46)

```java
@ThreadSafe
@FunctionalInterface
public interface McpRateLimiter {
    @NonNull
    McpRateLimitDecision acquire(@NonNull McpRateLimitContext rateLimitContext) throws Exception;
    @NonNull
    static McpRateLimiter fromInMemoryDefaults();
    @NonNull
    static McpRateLimiter fromInMemoryTokenBucket(@NonNull McpTokenBucketConfig tokenBucketConfig);
}
```

## `com.soklet.McpRateLimiterRegistry`

Source: [`McpRateLimiterRegistry.java`](../../src/main/java/com/soklet/McpRateLimiterRegistry.java#L35)

```java
@ThreadSafe
public final class McpRateLimiterRegistry {
    @NonNull
    public static McpRateLimiterRegistry emptyInstance();
    @NonNull
    public static Builder builder();
    @NonNull
    public Map<@NonNull String, @NonNull McpRateLimiter> getRateLimiters();
    @NonNull
    public Optional<@NonNull McpRateLimiter> find(@NonNull String name);
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder addRateLimiter(@NonNull String name, @NonNull McpRateLimiter rateLimiter);
        @NonNull
        public McpRateLimiterRegistry build();
    }
}
```

## `com.soklet.McpRequestContext`

Source: [`McpRequestContext.java`](../../src/main/java/com/soklet/McpRequestContext.java#L46)

```java
@ThreadSafe
public interface McpRequestContext {
    @NonNull
    Request getRequest();
    @NonNull
    McpEndpoint getEndpoint();
    @NonNull
    Map<@NonNull String, @NonNull String> getEndpointPathParameters();
    @NonNull
    String getJsonRpcMethod();
    @NonNull
    default McpOperationType getOperationType();
    @NonNull
    Optional<@NonNull McpRequestId> getRequestId();
    @NonNull
    String getProtocolVersion();
    @NonNull
    Optional<@NonNull String> getOperationName();
    @NonNull
    Optional<@NonNull McpImplementation> getClientInfo();
    @NonNull
    McpClientCapabilities getClientCapabilities();
    @NonNull
    McpJsonObject getRequestMetadata();
    @NonNull
    McpInputResponses getInputResponses();
    @NonNull
    Optional<@NonNull McpJsonValue> getFrameworkRequestState();
    @NonNull
    Optional<@NonNull String> getApplicationRequestState();
    @NonNull
    Optional<@NonNull TraceContext> getTraceContext();
    @NonNull
    Map<@NonNull String, @NonNull String> getBaggage();
    @NonNull
    McpAdmissionIdentity getAdmissionIdentity();
}
```

## `com.soklet.McpRequestId`

Source: [`McpRequestId.java`](../../src/main/java/com/soklet/McpRequestId.java#L34)

```java
@ThreadSafe
public final class McpRequestId {
    @NonNull
    public static McpRequestId fromString(@NonNull String value);
    @NonNull
    public static McpRequestId fromInteger(@NonNull BigInteger value);
    @NonNull
    public Optional<@NonNull String> asString();
    @NonNull
    public Optional<@NonNull BigInteger> asInteger();
    @Override
    public boolean equals(@Nullable Object object);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpRequestOutcome`

Source: [`McpRequestOutcome.java`](../../src/main/java/com/soklet/McpRequestOutcome.java#L24)

```java
public enum McpRequestOutcome {
    COMPLETE, INPUT_REQUIRED, REJECTED, APPLICATION_ERROR, PROTOCOL_ERROR, INTERNAL_ERROR, CANCELED, DEADLINE_EXCEEDED, CLIENT_DISCONNECTED, WRITE_FAILED;
}
```

## `com.soklet.McpRequestStateMode`

Source: [`McpRequestStateMode.java`](../../src/main/java/com/soklet/McpRequestStateMode.java#L27)

```java
public enum McpRequestStateMode {
    NONE, FRAMEWORK_PROTECTED, APPLICATION_PROTECTED;
}
```

## `com.soklet.McpRequestStateProtectionContext`

Source: [`McpRequestStateProtectionContext.java`](../../src/main/java/com/soklet/McpRequestStateProtectionContext.java#L38)

```java
@ThreadSafe
public final class McpRequestStateProtectionContext {
    @NonNull
    public static McpRequestStateProtectionContext fromComponents(@NonNull String endpointPath, @NonNull String protocolVersion, @NonNull String jsonRpcMethod, byte @NonNull [] associatedData);
    @NonNull
    public String getEndpointPath();
    @NonNull
    public String getProtocolVersion();
    @NonNull
    public String getJsonRpcMethod();
    public byte @NonNull [] getAssociatedData();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
}
```

## `com.soklet.McpRequestStateProtectionException`

Source: [`McpRequestStateProtectionException.java`](../../src/main/java/com/soklet/McpRequestStateProtectionException.java#L30)

```java
@NotThreadSafe
public final class McpRequestStateProtectionException extends Exception {
    public enum Reason {
        INVALID_STATE, PROTECTOR_UNAVAILABLE;
    }
    @NonNull
    public static McpRequestStateProtectionException fromInvalidState();
    @NonNull
    public static McpRequestStateProtectionException fromProtectorUnavailable();
    @NonNull
    public Reason getReason();
}
```

## `com.soklet.McpRequestStateProtector`

Source: [`McpRequestStateProtector.java`](../../src/main/java/com/soklet/McpRequestStateProtector.java#L35)

```java
@ThreadSafe
public interface McpRequestStateProtector {
    @NonNull
    String seal(@NonNull McpRequestStateProtectionContext requestStateProtectionContext, byte @NonNull [] plaintext) throws McpRequestStateProtectionException;
    byte @NonNull [] open(@NonNull McpRequestStateProtectionContext requestStateProtectionContext, @NonNull String protectedState) throws McpRequestStateProtectionException;
}
```

## `com.soklet.McpResourceAddressType`

Source: [`McpResourceAddressType.java`](../../src/main/java/com/soklet/McpResourceAddressType.java#L24)

```java
public enum McpResourceAddressType {
    URI, URI_TEMPLATE;
}
```

## `com.soklet.McpResourceContents`

Source: [`McpResourceContents.java`](../../src/main/java/com/soklet/McpResourceContents.java#L30)

```java
@ThreadSafe
public sealed interface McpResourceContents permits McpBlobResourceContents, McpTextResourceContents {
    @NonNull
    URI getUri();
    @NonNull
    Optional<@NonNull String> getMimeType();
    @NonNull
    Optional<@NonNull McpAppResourceMetadata> getAppResourceMetadata();
    @NonNull
    McpJsonObject getMetadata();
}
```

## `com.soklet.McpResourceDescriptor`

Source: [`McpResourceDescriptor.java`](../../src/main/java/com/soklet/McpResourceDescriptor.java#L37)

```java
@ThreadSafe
public final class McpResourceDescriptor {
    @NonNull
    public static Builder withUriAndName(@NonNull URI uri, @NonNull String name);
    @NonNull
    public URI getUri();
    @NonNull
    public String getName();
    @NonNull
    public Optional<@NonNull String> getTitle();
    @NonNull
    public Optional<@NonNull String> getDescription();
    @NonNull
    public Optional<@NonNull String> getMimeType();
    @NonNull
    public List<@NonNull McpIcon> getIcons();
    @NonNull
    public Optional<@NonNull McpContentAnnotations> getAnnotations();
    @NonNull
    public Optional<@NonNull Long> getSizeInBytes();
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder title(@NonNull String title);
        @NonNull
        public Builder description(@NonNull String description);
        @NonNull
        public Builder mimeType(@NonNull String mimeType);
        @NonNull
        public Builder addIcon(@NonNull McpIcon icon);
        @NonNull
        public Builder annotations(@NonNull McpContentAnnotations annotations);
        @NonNull
        public Builder sizeInBytes(@NonNull Long sizeInBytes);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpResourceDescriptor build();
    }
}
```

## `com.soklet.McpResourceLink`

Source: [`McpResourceLink.java`](../../src/main/java/com/soklet/McpResourceLink.java#L42)

```java
@ThreadSafe
public final class McpResourceLink implements McpContentBlock {
    @NonNull
    public static Builder withUriAndName(@NonNull URI uri, @NonNull String name);
    @NonNull
    public static McpResourceLink fromResourceDescriptor(@NonNull McpResourceDescriptor resourceDescriptor);
    @NonNull
    public URI getUri();
    @NonNull
    public String getName();
    @NonNull
    public Optional<@NonNull String> getTitle();
    @NonNull
    public Optional<@NonNull String> getDescription();
    @NonNull
    public Optional<@NonNull String> getMimeType();
    @NonNull
    public List<@NonNull McpIcon> getIcons();
    @Override
    @NonNull
    public Optional<@NonNull McpContentAnnotations> getAnnotations();
    @NonNull
    public Optional<@NonNull Long> getSizeInBytes();
    @Override
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder title(@NonNull String title);
        @NonNull
        public Builder description(@NonNull String description);
        @NonNull
        public Builder mimeType(@NonNull String mimeType);
        @NonNull
        public Builder addIcon(@NonNull McpIcon icon);
        @NonNull
        public Builder annotations(@NonNull McpContentAnnotations annotations);
        @NonNull
        public Builder sizeInBytes(@NonNull Long sizeInBytes);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpResourceLink build();
    }
}
```

## `com.soklet.McpResourceListContext`

Source: [`McpResourceListContext.java`](../../src/main/java/com/soklet/McpResourceListContext.java#L36)

```java
@ThreadSafe
public interface McpResourceListContext {
    @NonNull
    Optional<@NonNull String> getCursor();
    @NonNull
    List<@NonNull McpResourceDescriptor> getRegisteredResourceDescriptors();
}
```

## `com.soklet.McpResourceListHandler`

Source: [`McpResourceListHandler.java`](../../src/main/java/com/soklet/McpResourceListHandler.java#L46)

```java
@ThreadSafe
@FunctionalInterface
public interface McpResourceListHandler {
    @NonNull
    McpResourcePage handle(@NonNull McpRequestContext requestContext, @NonNull McpResourceListContext resourceListContext, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
```

## `com.soklet.McpResourceOutput`

Source: [`McpResourceOutput.java`](../../src/main/java/com/soklet/McpResourceOutput.java#L43)

```java
@ThreadSafe
public final class McpResourceOutput implements McpCompletePayload {
    @NonNull
    public static Builder withContent(@NonNull McpResourceContents resourceContents);
    @NonNull
    public static McpResourceOutput fromContent(@NonNull McpResourceContents resourceContents);
    @NonNull
    public static Builder withContents(@NonNull List<? extends @NonNull McpResourceContents> resourceContents);
    @NonNull
    public static McpResourceOutput fromContents(@NonNull List<? extends @NonNull McpResourceContents> resourceContents);
    @NonNull
    public List<@NonNull McpResourceContents> getContents();
    @NonNull
    public Optional<@NonNull Duration> getCacheTimeToLiveOverride();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder contents(@NonNull List<? extends @NonNull McpResourceContents> resourceContents);
        @NonNull
        public Builder cacheTimeToLiveOverride(@NonNull Duration cacheTimeToLiveOverride);
        @NonNull
        public McpResourceOutput build();
    }
}
```

## `com.soklet.McpResourcePage`

Source: [`McpResourcePage.java`](../../src/main/java/com/soklet/McpResourcePage.java#L42)

```java
@ThreadSafe
public final class McpResourcePage implements McpOperationResult {
    @NonNull
    public static Builder builder();
    @NonNull
    public List<@NonNull McpResourceDescriptor> getResourceDescriptors();
    @NonNull
    public McpJsonObject getMetadata();
    @NonNull
    public Optional<@NonNull String> getNextCursor();
    @NonNull
    public Optional<@NonNull Duration> getCacheTimeToLiveOverride();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder resourceDescriptors(@Nullable List<@NonNull McpResourceDescriptor> resourceDescriptors);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public Builder nextCursor(@NonNull String nextCursor);
        @NonNull
        public Builder cacheTimeToLiveOverride(@NonNull Duration cacheTimeToLiveOverride);
        @NonNull
        public McpResourcePage build();
    }
}
```

## `com.soklet.McpResourceReadContext`

Source: [`McpResourceReadContext.java`](../../src/main/java/com/soklet/McpResourceReadContext.java#L36)

```java
@ThreadSafe
public interface McpResourceReadContext {
    @NonNull
    URI getUri();
    @NonNull
    Map<@NonNull String, @NonNull String> getUriTemplateVariables();
}
```

## `com.soklet.McpResourceReadHandler`

Source: [`McpResourceReadHandler.java`](../../src/main/java/com/soklet/McpResourceReadHandler.java#L46)

```java
@ThreadSafe
@FunctionalInterface
public interface McpResourceReadHandler {
    @NonNull
    McpOperationResult handle(@NonNull McpRequestContext requestContext, @NonNull McpResourceReadContext resourceReadContext, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
```

## `com.soklet.McpResourceRegistration`

Source: [`McpResourceRegistration.java`](../../src/main/java/com/soklet/McpResourceRegistration.java#L50)

```java
@ThreadSafe
public final class McpResourceRegistration {
    @NonNull
    public static ExactHandlerStage withUriAndName(@NonNull URI uri, @NonNull String name);
    @NonNull
    public static TemplateHandlerStage withUriTemplateAndName(@NonNull String uriTemplate, @NonNull String name);
    @NonNull
    public McpResourceAddressType getAddressType();
    @NonNull
    public Optional<@NonNull URI> getUri();
    @NonNull
    public Optional<@NonNull String> getUriTemplate();
    @NonNull
    public String getName();
    @NonNull
    public Optional<@NonNull String> getTitle();
    @NonNull
    public Optional<@NonNull String> getDescription();
    @NonNull
    public Optional<@NonNull String> getMimeType();
    @NonNull
    public List<@NonNull McpIcon> getIcons();
    @NonNull
    public Optional<@NonNull McpContentAnnotations> getAnnotations();
    @NonNull
    public Optional<@NonNull Long> getSizeInBytes();
    @NonNull
    public McpCachePolicy getCachePolicy();
    @NonNull
    public List<@NonNull McpInputRequestDeclaration> getInputRequestDeclarations();
    @NonNull
    public McpRequestStateMode getRequestStateMode();
    @NonNull
    public McpJsonObject getMetadata();
    @NonNull
    public McpResourceReadHandler getHandler();
    @NonNull
    public Optional<@NonNull McpCompletionHandler> getCompletionHandler();
    @NotThreadSafe
    public static final class ExactHandlerStage {
        @NonNull
        public ExactBuilder handler(@NonNull McpResourceReadHandler handler);
    }
    @NotThreadSafe
    public static final class TemplateHandlerStage {
        @NonNull
        public TemplateBuilder handler(@NonNull McpResourceReadHandler handler);
    }
    @NotThreadSafe
    public static final class ExactBuilder {
        @NonNull
        public ExactBuilder title(@NonNull String title);
        @NonNull
        public ExactBuilder description(@NonNull String description);
        @NonNull
        public ExactBuilder mimeType(@NonNull String mimeType);
        @NonNull
        public ExactBuilder icons(@Nullable List<@NonNull McpIcon> icons);
        @NonNull
        public ExactBuilder annotations(@NonNull McpContentAnnotations annotations);
        @NonNull
        public ExactBuilder sizeInBytes(@NonNull Long sizeInBytes);
        @NonNull
        public ExactBuilder cachePolicy(@NonNull McpCachePolicy cachePolicy);
        @NonNull
        public ExactBuilder inputRequestDeclarations(@Nullable List<@NonNull McpInputRequestDeclaration> inputRequestDeclarations);
        @NonNull
        public ExactBuilder requestStateMode(@NonNull McpRequestStateMode requestStateMode);
        @NonNull
        public ExactBuilder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpResourceRegistration build();
    }
    @NotThreadSafe
    public static final class TemplateBuilder {
        @NonNull
        public TemplateBuilder title(@NonNull String title);
        @NonNull
        public TemplateBuilder description(@NonNull String description);
        @NonNull
        public TemplateBuilder mimeType(@NonNull String mimeType);
        @NonNull
        public TemplateBuilder icons(@Nullable List<@NonNull McpIcon> icons);
        @NonNull
        public TemplateBuilder annotations(@NonNull McpContentAnnotations annotations);
        @NonNull
        public TemplateBuilder cachePolicy(@NonNull McpCachePolicy cachePolicy);
        @NonNull
        public TemplateBuilder inputRequestDeclarations(@Nullable List<@NonNull McpInputRequestDeclaration> inputRequestDeclarations);
        @NonNull
        public TemplateBuilder requestStateMode(@NonNull McpRequestStateMode requestStateMode);
        @NonNull
        public TemplateBuilder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public TemplateBuilder completionHandler(@NonNull McpCompletionHandler completionHandler);
        @NonNull
        public McpResourceRegistration build();
    }
}
```

## `com.soklet.McpRole`

Source: [`McpRole.java`](../../src/main/java/com/soklet/McpRole.java#L24)

```java
public enum McpRole {
    USER, ASSISTANT;
}
```

## `com.soklet.McpServer`

Source: [`McpServer.java`](../../src/main/java/com/soklet/McpServer.java#L44)

```java
@ThreadSafe
public sealed interface McpServer permits DefaultMcpServer {
    @NonNull
    McpEndpointRegistry getEndpointRegistry();
    @NonNull
    McpCatalogAccessPolicy getCatalogAccessPolicy();
    @NonNull
    McpSkillAccessPolicy getSkillAccessPolicy();
    @NonNull
    Optional<@NonNull McpSkillVariantSelector> getSkillVariantSelector();
    @NonNull
    McpSubscriptionAuthorizer getSubscriptionAuthorizer();
    @NonNull
    McpAdmissionController getAdmissionController();
    @NonNull
    McpHandlerInterceptor getHandlerInterceptor();
    @NonNull
    McpToolResultSanitizer getToolResultSanitizer();
    @NonNull
    Optional<@NonNull McpTaskManager> getTaskManager();
    @NonNull
    Optional<@NonNull McpRateLimiter> getRequestRateLimiter();
    @NonNull
    Optional<@NonNull McpRateLimiter> getToolRateLimiter();
    @NonNull
    McpRateLimiterRegistry getRateLimiterRegistry();
    @NonNull
    CorsAuthorizer getCorsAuthorizer();
    @NonNull
    Integer getMaximumCursorSizeInBytes();
    @NonNull
    McpProtectionKeyringManager getProtectionKeyringManager();
    @NonNull
    McpTraceCorrelationKeyManager getTraceCorrelationKeyManager();
    @NonNull
    McpLocalizationCatalogInvalidator getLocalizationCatalogInvalidator();
    @NonNull
    McpSubscriptionReconciler getSubscriptionReconciler();
    @NonNull
    McpServerDiagnostics getDiagnostics();
    @NonNull
    static Builder withPort(@NonNull Integer port);
    @NotThreadSafe
    final class Builder {
        @NonNull
        public Builder port(@NonNull Integer port);
        @NonNull
        public Builder host(@Nullable String host);
        @NonNull
        public Builder requestHeaderTimeout(@Nullable Duration requestHeaderTimeout);
        @NonNull
        public Builder requestBodyTimeout(@Nullable Duration requestBodyTimeout);
        @NonNull
        public Builder maximumRequestSizeInBytes(@Nullable Integer maximumRequestSizeInBytes);
        @NonNull
        public Builder maximumHeaderCount(@Nullable Integer maximumHeaderCount);
        @NonNull
        public Builder maximumHeadersSizeInBytes(@Nullable Integer maximumHeadersSizeInBytes);
        @NonNull
        public Builder maximumRequestTargetLengthInBytes(@Nullable Integer maximumRequestTargetLengthInBytes);
        @NonNull
        public Builder requestReadBufferSizeInBytes(@Nullable Integer requestReadBufferSizeInBytes);
        @NonNull
        public Builder concurrentConnectionLimit(@Nullable Integer concurrentConnectionLimit);
        @NonNull
        public Builder connectionQueueCapacity(@Nullable Integer connectionQueueCapacity);
        @NonNull
        public Builder maximumCursorSizeInBytes(@Nullable Integer maximumCursorSizeInBytes);
        @NonNull
        public Builder maximumSubscriptionsPerPartition(@Nullable Integer maximumSubscriptionsPerPartition);
        @NonNull
        public Builder maximumSubscriptionDuration(@Nullable Duration maximumSubscriptionDuration);
        @NonNull
        public Builder subscriptionCatalogProjectionTimeout(@Nullable Duration subscriptionCatalogProjectionTimeout);
        @NonNull
        public Builder subscriptionAuthorizationTimeout(@Nullable Duration subscriptionAuthorizationTimeout);
        @NonNull
        public Builder maximumSubscriptionAuthorizationDuration(@Nullable Duration maximumSubscriptionAuthorizationDuration);
        @NonNull
        public Builder requestTimeout(@Nullable Duration requestTimeout);
        @NonNull
        public Builder requestHandlerConcurrency(@Nullable Integer requestHandlerConcurrency);
        @NonNull
        public Builder requestHandlerQueueCapacity(@Nullable Integer requestHandlerQueueCapacity);
        @NonNull
        public Builder requestHandlerExecutorServiceSupplier(@Nullable Supplier<@NonNull ExecutorService> requestHandlerExecutorServiceSupplier);
        @NonNull
        public Builder streamQueueCapacity(@Nullable Integer streamQueueCapacity);
        @NonNull
        public Builder writeTimeout(@Nullable Duration writeTimeout);
        @NonNull
        public Builder keepAliveInterval(@Nullable Duration keepAliveInterval);
        @NonNull
        public Builder endpointRegistry(@Nullable McpEndpointRegistry endpointRegistry);
        @NonNull
        public Builder localizer(@Nullable McpLocalizer localizer);
        @NonNull
        public Builder admissionController(@Nullable McpAdmissionController admissionController);
        @NonNull
        public Builder catalogAccessPolicy(@Nullable McpCatalogAccessPolicy catalogAccessPolicy);
        @NonNull
        public Builder skillAccessPolicy(@Nullable McpSkillAccessPolicy skillAccessPolicy);
        @NonNull
        public Builder skillVariantSelector(@Nullable McpSkillVariantSelector skillVariantSelector);
        @NonNull
        public Builder subscriptionAuthorizer(@Nullable McpSubscriptionAuthorizer subscriptionAuthorizer);
        @NonNull
        public Builder handlerInterceptor(@Nullable McpHandlerInterceptor handlerInterceptor);
        @NonNull
        public Builder toolResultSanitizer(@Nullable McpToolResultSanitizer toolResultSanitizer);
        @NonNull
        public Builder taskManager(@Nullable McpTaskManager taskManager);
        @NonNull
        public Builder requestRateLimiter(@Nullable McpRateLimiter requestRateLimiter);
        @NonNull
        public Builder toolRateLimiter(@Nullable McpRateLimiter toolRateLimiter);
        @NonNull
        public Builder rateLimiterRegistry(@Nullable McpRateLimiterRegistry rateLimiterRegistry);
        @NonNull
        public Builder corsAuthorizer(@Nullable CorsAuthorizer corsAuthorizer);
        @NonNull
        public Builder absentOriginPolicy(@Nullable McpAbsentOriginPolicy absentOriginPolicy);
        @NonNull
        public Builder unknownMirroredHeaderPolicy(@Nullable McpUnknownMirroredHeaderPolicy unknownMirroredHeaderPolicy);
        @NonNull
        public Builder unknownMirroredHeaderNameDiagnostics(@Nullable Boolean unknownMirroredHeaderNameDiagnostics);
        @NonNull
        public Builder traceCorrelationKey(@Nullable McpTraceCorrelationKey traceCorrelationKey);
        @NonNull
        public Builder logRawValidatedTraceIds(@Nullable Boolean logRawValidatedTraceIds);
        @NonNull
        public Builder protectionConfig(@Nullable McpProtectionConfig protectionConfig);
        @NonNull
        public Builder allowedHosts(@Nullable Set<@NonNull String> allowedHosts);
        @NonNull
        public McpServer build();
    }
}
```

## `com.soklet.McpServerDiagnostics`

Source: [`McpServerDiagnostics.java`](../../src/main/java/com/soklet/McpServerDiagnostics.java#L38)

```java
@ThreadSafe
public interface McpServerDiagnostics {
    @NonNull
    McpServerStatus getStatus();
    @NonNull
    Optional<@NonNull InetSocketAddress> getBoundAddress();
    @NonNull
    Integer getRequestHandlerConcurrency();
    @NonNull
    Integer getRequestHandlerQueueCapacity();
    @NonNull
    Integer getActiveHandlerExecutions();
    @NonNull
    Integer getRequestHandlerQueueDepth();
    @NonNull
    Integer getActiveRequestStreams();
    @NonNull
    Integer getActiveSubscriptions();
    @NonNull
    McpProtectionMode getProtectionMode();
    @NonNull
    Boolean isApplicationRequestStateProtectorConfigured();
    @NonNull
    Optional<@NonNull McpProtectionKeyringFingerprint> getProtectionKeyringFingerprint();
    @NonNull
    Optional<@NonNull McpTraceCorrelationFingerprint> getTraceCorrelationFingerprint();
}
```

## `com.soklet.McpServerStatus`

Source: [`McpServerStatus.java`](../../src/main/java/com/soklet/McpServerStatus.java#L24)

```java
public enum McpServerStatus {
    NOT_STARTED, STARTING, RUNNING, SHUTTING_DOWN, TERMINATED, RESIDUAL_ACTIVITY, TERMINATION_UNKNOWN;
}
```

## `com.soklet.McpSimulation`

Source: [`McpSimulation.java`](../../src/main/java/com/soklet/McpSimulation.java#L30)

```java
@ThreadSafe
public interface McpSimulation extends AutoCloseable {
    @NonNull
    Optional<@NonNull McpSimulationResponse> awaitResponse(@NonNull Duration timeout) throws InterruptedException;
    @NonNull
    Optional<@NonNull McpSimulationStreamItem> awaitStreamItem(@NonNull Duration timeout) throws InterruptedException;
    @NonNull
    Optional<@NonNull McpSimulationCompletion> awaitCompletion(@NonNull Duration timeout) throws InterruptedException;
    @NonNull
    Boolean isComplete();
    @Override
    void close();
}
```

## `com.soklet.McpSimulationBodyType`

Source: [`McpSimulationBodyType.java`](../../src/main/java/com/soklet/McpSimulationBodyType.java#L24)

```java
public enum McpSimulationBodyType {
    EMPTY, JSON, SSE;
}
```

## `com.soklet.McpSimulationCompletion`

Source: [`McpSimulationCompletion.java`](../../src/main/java/com/soklet/McpSimulationCompletion.java#L30)

```java
@ThreadSafe
public interface McpSimulationCompletion {
    @NonNull
    McpStreamTerminationReason getReason();
    @NonNull
    Optional<@NonNull McpJsonValue> getTerminalMessage();
    @NonNull
    List<@NonNull Throwable> getThrowables();
}
```

## `com.soklet.McpSimulationOptions`

Source: [`McpSimulationOptions.java`](../../src/main/java/com/soklet/McpSimulationOptions.java#L30)

```java
@ThreadSafe
public final class McpSimulationOptions {
    @NonNull
    public static McpSimulationOptions defaultInstance();
    @NonNull
    public static Builder builder();
    @NonNull
    public Integer getStreamItemQueueCapacity();
    @NonNull
    public Integer getMaximumCapturedSizeInBytes();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder streamItemQueueCapacity(@Nullable Integer streamItemQueueCapacity);
        @NonNull
        public Builder maximumCapturedSizeInBytes(@Nullable Integer maximumCapturedSizeInBytes);
        @NonNull
        public McpSimulationOptions build();
    }
}
```

## `com.soklet.McpSimulationResponse`

Source: [`McpSimulationResponse.java`](../../src/main/java/com/soklet/McpSimulationResponse.java#L31)

```java
@ThreadSafe
public interface McpSimulationResponse {
    @NonNull
    Integer getStatusCode();
    @NonNull
    Map<@NonNull String, @NonNull Set<@NonNull String>> getHeaders();
    @NonNull
    McpSimulationBodyType getBodyType();
    @NonNull
    Optional<byte @NonNull []> getBody();
}
```

## `com.soklet.McpSimulationStreamItem`

Source: [`McpSimulationStreamItem.java`](../../src/main/java/com/soklet/McpSimulationStreamItem.java#L29)

```java
@ThreadSafe
public interface McpSimulationStreamItem {
    @NonNull
    McpSimulationStreamItemType getType();
    @NonNull
    Optional<@NonNull McpJsonValue> getMessage();
    @NonNull
    Optional<@NonNull String> getComment();
    byte @NonNull [] getEncodedBytes();
}
```

## `com.soklet.McpSimulationStreamItemType`

Source: [`McpSimulationStreamItemType.java`](../../src/main/java/com/soklet/McpSimulationStreamItemType.java#L24)

```java
public enum McpSimulationStreamItemType {
    JSON_MESSAGE, KEEP_ALIVE_COMMENT;
}
```

## `com.soklet.McpSkillAccessPolicy`

Source: [`McpSkillAccessPolicy.java`](../../src/main/java/com/soklet/McpSkillAccessPolicy.java#L42)

```java
@ThreadSafe
public final class McpSkillAccessPolicy {
    @NonNull
    public static McpSkillAccessPolicy fromEvaluators(@NonNull AccessEvaluator accessEvaluator, @NonNull DiscoveryEvaluator discoveryEvaluator);
    @NonNull
    public static McpSkillAccessPolicy allowAllInstance();
    @Override
    @NonNull
    public String toString();
    @ThreadSafe
    @FunctionalInterface
    public interface AccessEvaluator {
        @NonNull
        Boolean isSkillAccessible(@NonNull McpRequestContext requestContext, @NonNull McpSkillRegistration skillRegistration, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
    }
    @ThreadSafe
    @FunctionalInterface
    public interface DiscoveryEvaluator {
        @NonNull
        Boolean isSkillDiscoverable(@NonNull McpRequestContext requestContext, @NonNull McpSkillRegistration skillRegistration, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
    }
}
```

## `com.soklet.McpSkillBundle`

Source: [`McpSkillBundle.java`](../../src/main/java/com/soklet/McpSkillBundle.java#L38)

```java
@ThreadSafe
public final class McpSkillBundle {
    @NonNull
    public static McpSkillBundle fromFiles(@NonNull Map<@NonNull String, byte @NonNull []> fileContentsByLogicalPath);
    @NonNull
    public String getName();
    @NonNull
    public String getDescription();
    @NonNull
    public McpJsonObject getDocumentMetadata();
    @NonNull
    public Set<@NonNull String> getFilePaths();
    @NonNull
    public Optional<byte @NonNull []> findFileBytes(@NonNull String logicalFilePath);
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpSkillGroup`

Source: [`McpSkillGroup.java`](../../src/main/java/com/soklet/McpSkillGroup.java#L43)

```java
@ThreadSafe
public final class McpSkillGroup {
    @NonNull
    public static McpSkillGroup fromKeyAndSkillRegistrations(@NonNull String key, @NonNull List<@NonNull McpSkillRegistration> skillRegistrations);
    @NonNull
    public String getKey();
    @NonNull
    public List<@NonNull McpSkillRegistration> getSkillRegistrations();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpSkillListContext`

Source: [`McpSkillListContext.java`](../../src/main/java/com/soklet/McpSkillListContext.java#L33)

```java
@ThreadSafe
public final class McpSkillListContext {
    @NonNull
    public Optional<@NonNull String> getCursor();
    @NonNull
    public Optional<@NonNull List<@NonNull McpSkillRegistration>> getInitialSkillRegistrations();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpSkillListHandler`

Source: [`McpSkillListHandler.java`](../../src/main/java/com/soklet/McpSkillListHandler.java#L39)

```java
@ThreadSafe
@FunctionalInterface
public interface McpSkillListHandler {
    @NonNull
    McpSkillPage handle(@NonNull McpRequestContext requestContext, @NonNull McpSkillListContext skillListContext, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
```

## `com.soklet.McpSkillPage`

Source: [`McpSkillPage.java`](../../src/main/java/com/soklet/McpSkillPage.java#L45)

```java
@ThreadSafe
public final class McpSkillPage implements McpOperationResult {
    @NonNull
    public static Builder builder();
    @NonNull
    public List<@NonNull McpSkillRegistration> getSkillRegistrations();
    @NonNull
    public McpJsonObject getMetadata();
    @NonNull
    public Optional<@NonNull String> getNextCursor();
    @NonNull
    public Optional<@NonNull Duration> getCacheTimeToLiveOverride();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder skillRegistrations(@Nullable List<@NonNull McpSkillRegistration> skillRegistrations);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public Builder nextCursor(@NonNull String nextCursor);
        @NonNull
        public Builder cacheTimeToLiveOverride(@NonNull Duration cacheTimeToLiveOverride);
        @NonNull
        public McpSkillPage build();
    }
}
```

## `com.soklet.McpSkillRegistration`

Source: [`McpSkillRegistration.java`](../../src/main/java/com/soklet/McpSkillRegistration.java#L41)

```java
@ThreadSafe
public final class McpSkillRegistration {
    @NonNull
    public static Builder withUriAndSkillBundle(@NonNull URI uri, @NonNull McpSkillBundle skillBundle);
    @NonNull
    public URI getUri();
    @NonNull
    public McpSkillBundle getSkillBundle();
    @NonNull
    public Optional<@NonNull Locale> getLocale();
    @NonNull
    public McpCachePolicy getCachePolicy();
    @NonNull
    public List<McpSkillRegistration.@NonNull Resource> getResources();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
    @ThreadSafe
    public static final class Resource {
        @NonNull
        public URI getUri();
        @NonNull
        public String getDigest();
        @NonNull
        public Long getSizeInBytes();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder locale(@NonNull Locale locale);
        @NonNull
        public Builder cachePolicy(@NonNull McpCachePolicy cachePolicy);
        @NonNull
        public McpSkillRegistration build();
    }
}
```

## `com.soklet.McpSkillVariantSelectionContext`

Source: [`McpSkillVariantSelectionContext.java`](../../src/main/java/com/soklet/McpSkillVariantSelectionContext.java#L37)

```java
@ThreadSafe
public final class McpSkillVariantSelectionContext {
    @NonNull
    public String getSkillGroupKey();
    @NonNull
    public List<@NonNull McpSkillRegistration> getSkillRegistrations();
    @NonNull
    public List<Locale.@NonNull LanguageRange> getLanguageRanges();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpSkillVariantSelector`

Source: [`McpSkillVariantSelector.java`](../../src/main/java/com/soklet/McpSkillVariantSelector.java#L43)

```java
@ThreadSafe
@FunctionalInterface
public interface McpSkillVariantSelector {
    @NonNull
    Optional<@NonNull McpSkillRegistration> select(@NonNull McpRequestContext requestContext, @NonNull McpSkillVariantSelectionContext skillVariantSelectionContext, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
```

## `com.soklet.McpStreamTerminationReason`

Source: [`McpStreamTerminationReason.java`](../../src/main/java/com/soklet/McpStreamTerminationReason.java#L24)

```java
public enum McpStreamTerminationReason {
    COMPLETED, CLIENT_DISCONNECTED, REQUEST_CANCELED, DEADLINE_EXCEEDED, WRITE_FAILED, BACKPRESSURE, SERVER_STOPPING, SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED, SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED, SUBSCRIPTION_AUTHORIZATION_DENIED, SUBSCRIPTION_AUTHORIZATION_EXPIRED, SUBSCRIPTION_AUTHORIZATION_CHECK_FAILED, SUBSCRIPTION_RECONCILIATION_FAILED, INTERNAL_ERROR;
}
```

## `com.soklet.McpSubscriptionAuthorization`

Source: [`McpSubscriptionAuthorization.java`](../../src/main/java/com/soklet/McpSubscriptionAuthorization.java#L38)

```java
@ThreadSafe
public sealed interface McpSubscriptionAuthorization permits McpSubscriptionAuthorization.Allowed, McpSubscriptionAuthorization.Denied {
    @NonNull
    static Denied deniedInstance();
    @ThreadSafe
    public final class Allowed implements McpSubscriptionAuthorization {
        @NonNull
        public static Allowed fromValidUntil(@NonNull Instant validUntil);
        @NonNull
        public static Builder withValidUntil(@NonNull Instant validUntil);
        @NonNull
        public Instant getValidUntil();
        @NonNull
        public Optional<@NonNull Object> getApplicationContext();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
        @NotThreadSafe
        public static final class Builder {
            @NonNull
            public Builder applicationContext(@Nullable Object applicationContext);
            @NonNull
            public Allowed build();
        }
    }
    @ThreadSafe
    public final class Denied implements McpSubscriptionAuthorization {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
}
```

## `com.soklet.McpSubscriptionAuthorizationContext`

Source: [`McpSubscriptionAuthorizationContext.java`](../../src/main/java/com/soklet/McpSubscriptionAuthorizationContext.java#L39)

```java
@ThreadSafe
public interface McpSubscriptionAuthorizationContext {
    @NonNull
    McpRequestContext getInitialRequestContext();
    @NonNull
    Optional<@NonNull Object> getApplicationContext();
    @NonNull
    Optional<@NonNull Instant> getPreviousValidUntil();
    @NonNull
    Instant getDeadline();
    @NonNull
    Boolean isToolsListChangedIncluded();
    @NonNull
    Boolean isPromptsListChangedIncluded();
    @NonNull
    Boolean isResourcesListChangedIncluded();
    @NonNull
    Set<@NonNull URI> getResourceSubscriptionUris();
    @NonNull
    Set<@NonNull String> getTaskIds();
}
```

## `com.soklet.McpSubscriptionAuthorizer`

Source: [`McpSubscriptionAuthorizer.java`](../../src/main/java/com/soklet/McpSubscriptionAuthorizer.java#L41)

```java
@ThreadSafe
@FunctionalInterface
public interface McpSubscriptionAuthorizer {
    @NonNull
    McpSubscriptionAuthorization authorize(@NonNull McpSubscriptionAuthorizationContext subscriptionAuthorizationContext, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
    @NonNull
    static McpSubscriptionAuthorizer denyAllInstance();
}
```

## `com.soklet.McpSubscriptionConfig`

Source: [`McpSubscriptionConfig.java`](../../src/main/java/com/soklet/McpSubscriptionConfig.java#L44)

```java
@ThreadSafe
public final class McpSubscriptionConfig {
    @NonNull
    public static Builder withEventPublisherAndNotificationTypes(@NonNull McpSubscriptionEventPublisher subscriptionEventPublisher, @NonNull Set<@NonNull McpSubscriptionNotificationType> subscriptionNotificationTypes);
    @NonNull
    public McpSubscriptionEventPublisher getEventPublisher();
    @NonNull
    public Set<@NonNull McpSubscriptionNotificationType> getNotificationTypes();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder notificationTypes(@NonNull Set<@NonNull McpSubscriptionNotificationType> subscriptionNotificationTypes);
        @NonNull
        public McpSubscriptionConfig build();
    }
}
```

## `com.soklet.McpSubscriptionEvent`

Source: [`McpSubscriptionEvent.java`](../../src/main/java/com/soklet/McpSubscriptionEvent.java#L37)

```java
@ThreadSafe
public sealed interface McpSubscriptionEvent permits McpSubscriptionEvent.ResourcesListChanged, McpSubscriptionEvent.ResourceUpdated, McpSubscriptionEvent.ToolsListChanged, McpSubscriptionEvent.PromptsListChanged {
    @NonNull
    static ResourcesListChanged resourcesListChanged();
    @NonNull
    static ResourceUpdated resourceUpdated(@NonNull URI resourceUri);
    @NonNull
    static ToolsListChanged toolsListChanged();
    @NonNull
    static PromptsListChanged promptsListChanged();
    @ThreadSafe
    public final class ResourcesListChanged implements McpSubscriptionEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class ResourceUpdated implements McpSubscriptionEvent {
        @NonNull
        public URI getResourceUri();
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public final String toString();
    }
    @ThreadSafe
    public final class ToolsListChanged implements McpSubscriptionEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
    @ThreadSafe
    public final class PromptsListChanged implements McpSubscriptionEvent {
        @Override
        public boolean equals(@Nullable Object other);
        @Override
        public int hashCode();
        @Override
        @NonNull
        public String toString();
    }
}
```

## `com.soklet.McpSubscriptionEventListener`

Source: [`McpSubscriptionEventListener.java`](../../src/main/java/com/soklet/McpSubscriptionEventListener.java#L32)

```java
@ThreadSafe
@FunctionalInterface
public interface McpSubscriptionEventListener {
    void onEvent(@NonNull McpSubscriptionEvent subscriptionEvent);
}
```

## `com.soklet.McpSubscriptionEventPublisher`

Source: [`McpSubscriptionEventPublisher.java`](../../src/main/java/com/soklet/McpSubscriptionEventPublisher.java#L48)

```java
@ThreadSafe
public interface McpSubscriptionEventPublisher {
    @NonNull
    static McpSubscriptionEventPublisher fromInMemoryDefaults();
    @NonNull
    McpSubscriptionEventRegistration subscribe(@NonNull McpSubscriptionEventListener listener);
    void publish(@NonNull McpSubscriptionEvent subscriptionEvent);
    default void publishResourcesListChanged();
    default void publishResourceUpdated(@NonNull URI resourceUri);
    default void publishToolsListChanged();
    default void publishPromptsListChanged();
}
```

## `com.soklet.McpSubscriptionEventRegistration`

Source: [`McpSubscriptionEventRegistration.java`](../../src/main/java/com/soklet/McpSubscriptionEventRegistration.java#L39)

```java
@ThreadSafe
public interface McpSubscriptionEventRegistration extends AutoCloseable {
    @Override
    void close();
}
```

## `com.soklet.McpSubscriptionNotificationType`

Source: [`McpSubscriptionNotificationType.java`](../../src/main/java/com/soklet/McpSubscriptionNotificationType.java#L25)

```java
public enum McpSubscriptionNotificationType {
    RESOURCES_LIST_CHANGED, RESOURCE_UPDATED, TOOLS_LIST_CHANGED, PROMPTS_LIST_CHANGED;
}
```

## `com.soklet.McpSubscriptionReconciler`

Source: [`McpSubscriptionReconciler.java`](../../src/main/java/com/soklet/McpSubscriptionReconciler.java#L33)

```java
@ThreadSafe
public interface McpSubscriptionReconciler {
    void reconcileSubscriptions();
}
```

## `com.soklet.McpTask`

Source: [`McpTask.java`](../../src/main/java/com/soklet/McpTask.java#L54)

```java
@ThreadSafe
public final class McpTask {
    @NonNull
    public static Builder withTaskId(@NonNull String taskId, @NonNull McpTaskOrigin taskOrigin, @NonNull McpTaskStatus taskStatus, @NonNull Instant createdAt, @NonNull Instant lastUpdatedAt);
    @NonNull
    public String getTaskId();
    @NonNull
    public McpTaskOrigin getTaskOrigin();
    @NonNull
    public McpTaskStatus getTaskStatus();
    @NonNull
    public Optional<@NonNull String> getTaskStatusMessage();
    @NonNull
    public Instant getCreatedAt();
    @NonNull
    public Instant getLastUpdatedAt();
    @NonNull
    public Optional<@NonNull Duration> getTimeToLive();
    @NonNull
    public Optional<@NonNull Duration> getPollInterval();
    @NonNull
    public Map<@NonNull String, @NonNull McpInputRequest> getInputRequests();
    @NonNull
    public Optional<@NonNull McpCompleteResult> getCompletedResult();
    @NonNull
    public Optional<@NonNull McpJsonRpcError> getFailure();
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder taskStatusMessage(@Nullable String taskStatusMessage);
        @NonNull
        public Builder timeToLive(@Nullable Duration timeToLive);
        @NonNull
        public Builder pollInterval(@Nullable Duration pollInterval);
        @NonNull
        public Builder addInputRequest(@NonNull String key, @NonNull McpInputRequest inputRequest);
        @NonNull
        public Builder addInputRequests(@NonNull Map<@NonNull String, ? extends @NonNull McpInputRequest> inputRequests);
        @NonNull
        public Builder completedResult(@NonNull McpCompleteResult completedResult);
        @NonNull
        public Builder failure(@NonNull McpJsonRpcError failure);
        @NonNull
        public Builder metadata(@Nullable McpJsonObject metadata);
        @NonNull
        public McpTask build();
    }
}
```

## `com.soklet.McpTaskCreatedResult`

Source: [`McpTaskCreatedResult.java`](../../src/main/java/com/soklet/McpTaskCreatedResult.java#L40)

```java
@ThreadSafe
public final class McpTaskCreatedResult<R> implements McpOperationResult {
    @NonNull
    public static <R> McpTaskCreatedResult<@NonNull R> fromTaskId(@NonNull String taskId);
    @NonNull
    public String getTaskId();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpTaskCreationContext`

Source: [`McpTaskCreationContext.java`](../../src/main/java/com/soklet/McpTaskCreationContext.java#L40)

```java
@ThreadSafe
public interface McpTaskCreationContext {
    @NonNull
    McpRequestContext getRequestContext();
    @NonNull
    McpTaskOrigin getTaskOrigin();
}
```

## `com.soklet.McpTaskEventListener`

Source: [`McpTaskEventListener.java`](../../src/main/java/com/soklet/McpTaskEventListener.java#L35)

```java
@ThreadSafe
@FunctionalInterface
public interface McpTaskEventListener {
    void onTaskChanged(@NonNull String taskId);
}
```

## `com.soklet.McpTaskEventPublisher`

Source: [`McpTaskEventPublisher.java`](../../src/main/java/com/soklet/McpTaskEventPublisher.java#L48)

```java
@ThreadSafe
public interface McpTaskEventPublisher {
    @NonNull
    static McpTaskEventPublisher fromInMemoryDefaults();
    @NonNull
    McpSubscriptionEventRegistration subscribe(@NonNull McpTaskEventListener listener);
    void publishTaskChanged(@NonNull String taskId);
}
```

## `com.soklet.McpTaskManager`

Source: [`McpTaskManager.java`](../../src/main/java/com/soklet/McpTaskManager.java#L53)

```java
@ThreadSafe
public interface McpTaskManager {
    @NonNull
    static McpInMemoryTaskManager fromInMemoryDefaults();
    @NonNull
    default Optional<@NonNull McpTaskEventPublisher> getTaskEventPublisher();
    @NonNull
    Optional<@NonNull McpTask> findTask(@NonNull McpTaskRequestContext taskRequestContext) throws Exception;
    void updateTask(@NonNull McpTaskUpdateContext taskUpdateContext) throws Exception;
    void requestTaskCancelation(@NonNull McpTaskRequestContext taskRequestContext) throws Exception;
}
```

## `com.soklet.McpTaskNotFoundException`

Source: [`McpTaskNotFoundException.java`](../../src/main/java/com/soklet/McpTaskNotFoundException.java#L31)

```java
@NotThreadSafe
public final class McpTaskNotFoundException extends Exception {
    public McpTaskNotFoundException() ;
}
```

## `com.soklet.McpTaskOrigin`

Source: [`McpTaskOrigin.java`](../../src/main/java/com/soklet/McpTaskOrigin.java#L60)

```java
@ThreadSafe
public final class McpTaskOrigin {
    @NonNull
    public static McpTaskOrigin fromPersistedState(@NonNull McpJsonObject persistedState);
    @NonNull
    public static McpTaskOrigin fromPersistedString(@NonNull String persistedString);
    @NonNull
    public McpJsonObject getPersistedState();
    @NonNull
    public String toPersistedString();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpTaskRequestContext`

Source: [`McpTaskRequestContext.java`](../../src/main/java/com/soklet/McpTaskRequestContext.java#L40)

```java
@ThreadSafe
public final class McpTaskRequestContext {
    @NonNull
    public static McpTaskRequestContext fromComponents(@NonNull McpRequestContext requestContext, @NonNull String taskId);
    @NonNull
    public McpRequestContext getRequestContext();
    @NonNull
    public String getTaskId();
}
```

## `com.soklet.McpTaskStatus`

Source: [`McpTaskStatus.java`](../../src/main/java/com/soklet/McpTaskStatus.java#L28)

```java
public enum McpTaskStatus {
    WORKING, INPUT_REQUIRED, COMPLETED, FAILED, CANCELED;
}
```

## `com.soklet.McpTaskUpdateContext`

Source: [`McpTaskUpdateContext.java`](../../src/main/java/com/soklet/McpTaskUpdateContext.java#L44)

```java
@ThreadSafe
public final class McpTaskUpdateContext {
    @NonNull
    public static McpTaskUpdateContext fromComponents(@NonNull McpRequestContext requestContext, @NonNull String taskId, @NonNull McpInputResponses inputResponses);
    @NonNull
    public McpRequestContext getRequestContext();
    @NonNull
    public String getTaskId();
    @NonNull
    public McpInputResponses getInputResponses();
}
```

## `com.soklet.McpTextContent`

Source: [`McpTextContent.java`](../../src/main/java/com/soklet/McpTextContent.java#L36)

```java
@ThreadSafe
public final class McpTextContent implements McpContentBlock {
    @NonNull
    public static McpTextContent fromText(@NonNull String text);
    @NonNull
    public static Builder withText(@NonNull String text);
    @NonNull
    public String getText();
    @Override
    @NonNull
    public Optional<@NonNull McpContentAnnotations> getAnnotations();
    @Override
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder annotations(@NonNull McpContentAnnotations annotations);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpTextContent build();
    }
}
```

## `com.soklet.McpTextCoordinate`

Source: [`McpTextCoordinate.java`](../../src/main/java/com/soklet/McpTextCoordinate.java#L41)

```java
@ThreadSafe
public final class McpTextCoordinate {
    @NonNull
    public static McpTextCoordinate fromComponents(@NonNull String endpointPath, @NonNull McpTextOwnerType ownerType, @NonNull String subjectId, @NonNull String memberPath);
    @NonNull
    public String getEndpointPath();
    @NonNull
    public McpTextOwnerType getOwnerType();
    @NonNull
    public String getSubjectId();
    @NonNull
    public String getMemberPath();
    @NonNull
    public String toExternalKey();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpTextOwnerType`

Source: [`McpTextOwnerType.java`](../../src/main/java/com/soklet/McpTextOwnerType.java#L28)

```java
public enum McpTextOwnerType {
    SERVER_INFORMATION, ENDPOINT, TOOL, PROMPT, RESOURCE, RESOURCE_TEMPLATE;
}
```

## `com.soklet.McpTextResourceContents`

Source: [`McpTextResourceContents.java`](../../src/main/java/com/soklet/McpTextResourceContents.java#L36)

```java
@ThreadSafe
public final class McpTextResourceContents implements McpResourceContents {
    @NonNull
    public static Builder withUriAndText(@NonNull URI uri, @NonNull String text);
    @Override
    @NonNull
    public URI getUri();
    @NonNull
    public String getText();
    @Override
    @NonNull
    public Optional<@NonNull String> getMimeType();
    @Override
    @NonNull
    public Optional<@NonNull McpAppResourceMetadata> getAppResourceMetadata();
    @Override
    @NonNull
    public McpJsonObject getMetadata();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder mimeType(@NonNull String mimeType);
        @NonNull
        public Builder appResourceMetadata(@NonNull McpAppResourceMetadata appResourceMetadata);
        @NonNull
        public Builder metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpTextResourceContents build();
    }
}
```

## `com.soklet.McpTokenBucketConfig`

Source: [`McpTokenBucketConfig.java`](../../src/main/java/com/soklet/McpTokenBucketConfig.java#L34)

```java
@ThreadSafe
public final class McpTokenBucketConfig {
    @NonNull
    public static McpTokenBucketConfig fromDefaults();
    @NonNull
    public static Builder withCapacity(@NonNull Long capacity);
    @NonNull
    public Long getCapacity();
    @NonNull
    public Long getRefillTokens();
    @NonNull
    public Duration getRefillInterval();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder capacity(@NonNull Long capacity);
        @NonNull
        public Builder refillTokens(@Nullable Long refillTokens);
        @NonNull
        public Builder refillInterval(@Nullable Duration refillInterval);
        @NonNull
        public McpTokenBucketConfig build();
    }
}
```

## `com.soklet.McpToolAnnotations`

Source: [`McpToolAnnotations.java`](../../src/main/java/com/soklet/McpToolAnnotations.java#L38)

```java
@ThreadSafe
public final class McpToolAnnotations {
    @NonNull
    public static Builder builder();
    @NonNull
    public Optional<@NonNull String> getTitle();
    @NonNull
    public Optional<@NonNull Boolean> getReadOnlyHint();
    @NonNull
    public Optional<@NonNull Boolean> getDestructiveHint();
    @NonNull
    public Optional<@NonNull Boolean> getIdempotentHint();
    @NonNull
    public Optional<@NonNull Boolean> getOpenWorldHint();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder title(@NonNull String title);
        @NonNull
        public Builder readOnlyHint(@NonNull Boolean readOnlyHint);
        @NonNull
        public Builder destructiveHint(@NonNull Boolean destructiveHint);
        @NonNull
        public Builder idempotentHint(@NonNull Boolean idempotentHint);
        @NonNull
        public Builder openWorldHint(@NonNull Boolean openWorldHint);
        @NonNull
        public McpToolAnnotations build();
    }
}
```

## `com.soklet.McpToolArguments`

Source: [`McpToolArguments.java`](../../src/main/java/com/soklet/McpToolArguments.java#L34)

```java
@ThreadSafe
public interface McpToolArguments<A> {
    @NonNull
    A getConvertedArguments();
    @NonNull
    McpJsonObject getRawArguments();
}
```

## `com.soklet.McpToolHandler`

Source: [`McpToolHandler.java`](../../src/main/java/com/soklet/McpToolHandler.java#L38)

```java
@ThreadSafe
@FunctionalInterface
public interface McpToolHandler<A> {
    @NonNull
    McpOperationResult handle(@NonNull McpRequestContext requestContext, @NonNull McpToolArguments<@NonNull A> toolArguments, @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
```

## `com.soklet.McpToolOutput`

Source: [`McpToolOutput.java`](../../src/main/java/com/soklet/McpToolOutput.java#L35)

```java
@ThreadSafe
public final class McpToolOutput implements McpCompletePayload {
    @NonNull
    public static Builder builder();
    @NonNull
    public static McpToolOutput fromText(@NonNull String text);
    @NonNull
    public static McpToolOutput fromStructuredContent(@NonNull McpJsonValue structuredContent);
    @NonNull
    public static McpToolOutput fromErrorText(@NonNull String text);
    @NonNull
    public List<@NonNull McpContentBlock> getContent();
    @NonNull
    public Optional<@NonNull McpJsonValue> getStructuredContent();
    @NonNull
    public Boolean isError();
    @NonNull
    public Builder toBuilder();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder content(@Nullable List<? extends @NonNull McpContentBlock> content);
        @NonNull
        public Builder structuredContent(@NonNull McpJsonValue structuredContent);
        @NonNull
        public Builder error(@NonNull Boolean error);
        @NonNull
        public McpToolOutput build();
    }
}
```

## `com.soklet.McpToolRegistration`

Source: [`McpToolRegistration.java`](../../src/main/java/com/soklet/McpToolRegistration.java#L49)

```java
@ThreadSafe
public final class McpToolRegistration<A> {
    @NonNull
    public static ArgumentTypeStage withName(@NonNull String name);
    @NonNull
    public String getName();
    @NonNull
    public Optional<@NonNull String> getTitle();
    @NonNull
    public Optional<@NonNull String> getDescription();
    @NonNull
    public List<@NonNull McpIcon> getIcons();
    @NonNull
    public Type getArgumentType();
    @NonNull
    public McpToolSchema getInputSchema();
    @NonNull
    public Optional<@NonNull McpToolSchema> getOutputSchema();
    @NonNull
    public Optional<@NonNull Type> getOutputType();
    @NonNull
    public Optional<@NonNull McpToolAnnotations> getToolAnnotations();
    @NonNull
    public Optional<@NonNull McpAppToolMetadata> getAppToolMetadata();
    @NonNull
    public Optional<@NonNull String> getRateLimiterName();
    @NonNull
    public Optional<@NonNull McpRateLimiter> getRateLimiter();
    @NonNull
    public Boolean isStructuredContentMirroredAsText();
    @NonNull
    public List<@NonNull McpInputRequestDeclaration> getInputRequestDeclarations();
    @NonNull
    public McpRequestStateMode getRequestStateMode();
    @NonNull
    public McpJsonObject getMetadata();
    @NonNull
    public McpToolHandler<@NonNull A> getHandler();
    @NotThreadSafe
    public static final class ArgumentTypeStage {
        @NonNull
        public <T, R> CompleteHandlerStage<@NonNull T, @NonNull R> argumentAndOutputTypes(@NonNull Class<@NonNull T> argumentType, @NonNull Class<@NonNull R> outputType);
        @NonNull
        public <T, R> CompleteHandlerStage<@NonNull T, @NonNull R> argumentAndOutputTypes(@NonNull Class<@NonNull T> argumentType, @NonNull TypeReference<@NonNull R> outputType);
        @NonNull
        public <T, R> CompleteHandlerStage<@NonNull T, @NonNull R> argumentAndOutputTypes(@NonNull TypeReference<@NonNull T> argumentType, @NonNull Class<@NonNull R> outputType);
        @NonNull
        public <T, R> CompleteHandlerStage<@NonNull T, @NonNull R> argumentAndOutputTypes(@NonNull TypeReference<@NonNull T> argumentType, @NonNull TypeReference<@NonNull R> outputType);
        @NonNull
        public <T> OperationHandlerStage<@NonNull T> argumentType(@NonNull Class<@NonNull T> argumentType);
        @NonNull
        public <T> OperationHandlerStage<@NonNull T> argumentType(@NonNull TypeReference<@NonNull T> argumentType);
        @NonNull
        public OperationHandlerStage<@NonNull McpJsonObject> jsonObjectArguments();
        @NonNull
        public OperationHandlerStage<@NonNull McpJsonObject> inputSchema(@NonNull McpJsonObject inputSchema);
    }
    @NotThreadSafe
    public static final class CompleteHandlerStage<A, R> {
        @NonNull
        public CompleteBuilder<@NonNull A> handler(@NonNull McpCompleteToolHandler<@NonNull A, @NonNull R> handler);
        @NonNull
        public OperationBuilder<@NonNull A> inlineOperationHandler(@NonNull McpToolHandler<@NonNull A> handler);
        @NonNull
        public OperationBuilder<@NonNull A> operationHandler(@NonNull McpToolHandler<@NonNull A> handler);
    }
    @NotThreadSafe
    public static final class OperationHandlerStage<A> {
        @NonNull
        public OperationBuilder<@NonNull A> handler(@NonNull McpToolHandler<@NonNull A> handler);
    }
    @NotThreadSafe
    public static final class OperationBuilder<A> {
        @NonNull
        public OperationBuilder<@NonNull A> title(@NonNull String title);
        @NonNull
        public OperationBuilder<@NonNull A> description(@NonNull String description);
        @NonNull
        public OperationBuilder<@NonNull A> icons(@Nullable List<@NonNull McpIcon> icons);
        @NonNull
        public OperationBuilder<@NonNull A> toolAnnotations(@NonNull McpToolAnnotations toolAnnotations);
        @NonNull
        public OperationBuilder<@NonNull A> appToolMetadata(@NonNull McpAppToolMetadata appToolMetadata);
        @NonNull
        public OperationBuilder<@NonNull A> rateLimiterName(@NonNull String rateLimiterName);
        @NonNull
        public OperationBuilder<@NonNull A> rateLimiter(@NonNull McpRateLimiter rateLimiter);
        @NonNull
        public OperationBuilder<@NonNull A> structuredContentMirroredAsText(@NonNull Boolean structuredContentMirroredAsText);
        @NonNull
        public OperationBuilder<@NonNull A> inputRequestDeclarations(@Nullable List<@NonNull McpInputRequestDeclaration> inputRequestDeclarations);
        @NonNull
        public OperationBuilder<@NonNull A> requestStateMode(@NonNull McpRequestStateMode requestStateMode);
        @NonNull
        public OperationBuilder<@NonNull A> metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpToolRegistration<@NonNull A> build();
    }
    @NotThreadSafe
    public static final class CompleteBuilder<A> {
        @NonNull
        public CompleteBuilder<@NonNull A> title(@NonNull String title);
        @NonNull
        public CompleteBuilder<@NonNull A> description(@NonNull String description);
        @NonNull
        public CompleteBuilder<@NonNull A> icons(@Nullable List<@NonNull McpIcon> icons);
        @NonNull
        public CompleteBuilder<@NonNull A> toolAnnotations(@NonNull McpToolAnnotations toolAnnotations);
        @NonNull
        public CompleteBuilder<@NonNull A> appToolMetadata(@NonNull McpAppToolMetadata appToolMetadata);
        @NonNull
        public CompleteBuilder<@NonNull A> rateLimiterName(@NonNull String rateLimiterName);
        @NonNull
        public CompleteBuilder<@NonNull A> rateLimiter(@NonNull McpRateLimiter rateLimiter);
        @NonNull
        public CompleteBuilder<@NonNull A> structuredContentMirroredAsText(@NonNull Boolean structuredContentMirroredAsText);
        @NonNull
        public CompleteBuilder<@NonNull A> metadata(@NonNull McpJsonObject metadata);
        @NonNull
        public McpToolRegistration<@NonNull A> build();
    }
}
```

## `com.soklet.McpToolResultSanitizer`

Source: [`McpToolResultSanitizer.java`](../../src/main/java/com/soklet/McpToolResultSanitizer.java#L55)

```java
@ThreadSafe
@FunctionalInterface
public interface McpToolResultSanitizer {
    @NonNull
    McpCompleteResult sanitize(@NonNull McpRequestContext requestContext, @NonNull String toolName, @NonNull McpJsonObject rawArguments, @NonNull McpCompleteResult completeResult) throws Exception;
    @NonNull
    static McpToolResultSanitizer nonSanitizingInstance();
}
```

## `com.soklet.McpToolSchema`

Source: [`McpToolSchema.java`](../../src/main/java/com/soklet/McpToolSchema.java#L40)

```java
@ThreadSafe
public final class McpToolSchema {
    @NonNull
    public McpJsonObject getDocument();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
}
```

## `com.soklet.McpTraceCorrelationFingerprint`

Source: [`McpTraceCorrelationFingerprint.java`](../../src/main/java/com/soklet/McpTraceCorrelationFingerprint.java#L35)

```java
@ThreadSafe
public final class McpTraceCorrelationFingerprint {
    @NonNull
    public String getValue();
    @Override
    public boolean equals(@Nullable Object other);
    @Override
    public int hashCode();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpTraceCorrelationKey`

Source: [`McpTraceCorrelationKey.java`](../../src/main/java/com/soklet/McpTraceCorrelationKey.java#L36)

```java
@ThreadSafe
public final class McpTraceCorrelationKey {
    @NonNull
    public static McpTraceCorrelationKey fromIdAndBytes(@NonNull String keyId, byte @NonNull [] keyMaterial);
    @NonNull
    public String getKeyId();
    @Override
    @NonNull
    public String toString();
}
```

## `com.soklet.McpTraceCorrelationKeyManager`

Source: [`McpTraceCorrelationKeyManager.java`](../../src/main/java/com/soklet/McpTraceCorrelationKeyManager.java#L33)

```java
@ThreadSafe
public interface McpTraceCorrelationKeyManager {
    @NonNull
    Boolean isEnabled();
    @NonNull
    Optional<@NonNull String> getActiveKeyId();
    @NonNull
    Optional<@NonNull McpTraceCorrelationFingerprint> getFingerprint();
    void rotateActiveKey(@NonNull McpTraceCorrelationKey activeKey);
}
```

## `com.soklet.McpUnknownMirroredHeaderPolicy`

Source: [`McpUnknownMirroredHeaderPolicy.java`](../../src/main/java/com/soklet/McpUnknownMirroredHeaderPolicy.java#L28)

```java
public enum McpUnknownMirroredHeaderPolicy {
    IGNORE, REJECT_REQUESTS;
}
```

## `com.soklet.ResidualActivityEvidence`

Source: [`ResidualActivityEvidence.java`](../../src/main/java/com/soklet/ResidualActivityEvidence.java#L32)

```java
@ThreadSafe
public final class ResidualActivityEvidence {
    @NonNull
    public Set<@NonNull ResidualActivityType> getResidualActivityTypes();
    @NonNull
    public String getSummary();
}
```

## `com.soklet.ResidualActivityType`

Source: [`ResidualActivityType.java`](../../src/main/java/com/soklet/ResidualActivityType.java#L20)

```java
public enum ResidualActivityType {
    CALLBACK, STREAM, CONNECTION, EVENT_LOOP, EXECUTOR_TASK, LIFECYCLE_CALL;
}
```

## `com.soklet.ShutdownCleanup`

Source: [`ShutdownCleanup.java`](../../src/main/java/com/soklet/ShutdownCleanup.java#L32)

```java
@ThreadSafe
public final class ShutdownCleanup {
    @NonNull
    public static ShutdownCleanup fromTimeoutAndAction(@NonNull Duration timeout, @NonNull Action action);
    @NonNull
    public Duration getTimeout();
    @NotThreadSafe
    @FunctionalInterface
    public interface Action {
        void performCleanup(@NonNull ShutdownResult shutdownResult) throws Exception;
    }
}
```

## `com.soklet.ShutdownCleanupFailureReason`

Source: [`ShutdownCleanupFailureReason.java`](../../src/main/java/com/soklet/ShutdownCleanupFailureReason.java#L20)

```java
public enum ShutdownCleanupFailureReason {
    FAILED, TIMED_OUT;
}
```

## `com.soklet.ShutdownComponentDisposition`

Source: [`ShutdownComponentDisposition.java`](../../src/main/java/com/soklet/ShutdownComponentDisposition.java#L20)

```java
public enum ShutdownComponentDisposition {
    NOT_STARTED, GRACEFUL_TERMINATION, FORCED_TERMINATION, UNEXPECTED_TERMINATION, RESIDUAL_ACTIVITY, TERMINATION_UNKNOWN;
}
```

## `com.soklet.ShutdownComponentResult`

Source: [`ShutdownComponentResult.java`](../../src/main/java/com/soklet/ShutdownComponentResult.java#L29)

```java
@ThreadSafe
public final class ShutdownComponentResult {
    @NonNull
    public ShutdownComponentType getShutdownComponentType();
    @NonNull
    public ShutdownComponentDisposition getShutdownComponentDisposition();
    @NonNull
    public List<@NonNull Throwable> getThrowables();
    @NonNull
    public Optional<@NonNull ResidualActivityEvidence> getResidualActivityEvidence();
}
```

## `com.soklet.ShutdownComponentType`

Source: [`ShutdownComponentType.java`](../../src/main/java/com/soklet/ShutdownComponentType.java#L20)

```java
public enum ShutdownComponentType {
    HTTP, SSE, MCP, FRAMEWORK;
}
```

## `com.soklet.ShutdownContext`

Source: [`ShutdownContext.java`](../../src/main/java/com/soklet/ShutdownContext.java#L34)

```java
@ThreadSafe
public final class ShutdownContext {
    @NonNull
    public ShutdownPhase getShutdownPhase();
    @NonNull
    public Duration getRemainingTime();
}
```

## `com.soklet.ShutdownDisposition`

Source: [`ShutdownDisposition.java`](../../src/main/java/com/soklet/ShutdownDisposition.java#L20)

```java
public enum ShutdownDisposition {
    NOT_STARTED, GRACEFUL, FORCED, INCOMPLETE;
}
```

## `com.soklet.ShutdownPhase`

Source: [`ShutdownPhase.java`](../../src/main/java/com/soklet/ShutdownPhase.java#L24)

```java
public enum ShutdownPhase {
    GRACEFUL, FORCED;
}
```

## `com.soklet.ShutdownResult`

Source: [`ShutdownResult.java`](../../src/main/java/com/soklet/ShutdownResult.java#L41)

```java
@ThreadSafe
public final class ShutdownResult {
    @NonNull
    public ShutdownDisposition getShutdownDisposition();
    @NonNull
    public StartupDisposition getStartupDisposition();
    @NonNull
    public List<@NonNull ShutdownComponentResult> getShutdownComponentResults();
    @NonNull
    public Optional<@NonNull ShutdownComponentResult> getShutdownComponentResult(@NonNull ShutdownComponentType shutdownComponentType);
    @NonNull
    public Optional<@NonNull Throwable> getStartupFailureCause();
    @NonNull
    public Optional<@NonNull UnexpectedShutdownComponentTermination> getUnexpectedShutdownComponentTermination();
    @NonNull
    public Boolean isComplete();
}
```

## `com.soklet.ShutdownTrigger`

Source: [`ShutdownTrigger.java`](../../src/main/java/com/soklet/ShutdownTrigger.java#L33)

```java
public enum ShutdownTrigger {
    ENTER_KEY;
}
```

## `com.soklet.Soklet`

Source: [`Soklet.java`](../../src/main/java/com/soklet/Soklet.java#L151)

```java
@ThreadSafe
public final class Soklet implements AutoCloseable {
    @NonNull
    public static Soklet fromConfig(@NonNull SokletConfig sokletConfig);
    public void start();
    @NonNull
    public CompletionStage<@NonNull ShutdownResult> shutdown();
    @NonNull
    @CheckReturnValue
    public ShutdownResult awaitShutdown() throws InterruptedException;
    @Override
    public void close();
    @NonNull
    public SokletStatus getStatus();
    @NonNull
    public Optional<@NonNull ShutdownResult> getShutdownResult();
}
```

## `com.soklet.SokletApplication`

Source: [`SokletApplication.java`](../../src/main/java/com/soklet/SokletApplication.java#L58)

```java
@ThreadSafe
public final class SokletApplication {
    @NonNull
    public static ShutdownResult run(@NonNull SokletConfig sokletConfig);
    @NonNull
    public static ShutdownResult run(@NonNull SokletConfig sokletConfig, @NonNull ShutdownTrigger @NonNull... additionalShutdownTriggers);
    @NonNull
    public static SokletApplication fromConfig(@NonNull SokletConfig sokletConfig);
    @NonNull
    public ShutdownResult run();
    @NonNull
    public ShutdownResult run(@NonNull ShutdownTrigger @NonNull... additionalShutdownTriggers);
    @NonNull
    public ShutdownResult run(@NonNull ShutdownCleanup shutdownCleanup, @NonNull ShutdownTrigger @NonNull... additionalShutdownTriggers);
}
```

## `com.soklet.SokletConfig`

Source: [`SokletConfig.java`](../../src/main/java/com/soklet/SokletConfig.java#L40)

```java
@ThreadSafe
public final class SokletConfig {
    @NonNull
    public static Builder withHttpServer(@NonNull HttpServer httpServer);
    @NonNull
    public static Builder withSseServer(@NonNull SseServer sseServer);
    @NonNull
    public static Builder withMcpServer(@NonNull McpServer mcpServer);
    @NonNull
    public InstanceProvider getInstanceProvider();
    @NonNull
    public ValueConverterRegistry getValueConverterRegistry();
    @NonNull
    public RequestBodyMarshaler getRequestBodyMarshaler();
    @NonNull
    public ResourceMethodResolver getResourceMethodResolver();
    @NonNull
    public ResourceMethodParameterProvider getResourceMethodParameterProvider();
    @NonNull
    public ResponseMarshaler getResponseMarshaler();
    @NonNull
    public RequestInterceptor getRequestInterceptor();
    @NonNull
    public List<@NonNull LifecycleObserver> getLifecycleObservers();
    @NonNull
    public MetricsCollector getMetricsCollector();
    @NonNull
    public CorsAuthorizer getCorsAuthorizer();
    @NonNull
    public Optional<@NonNull HttpServer> getHttpServer();
    @NonNull
    public Optional<@NonNull SseServer> getSseServer();
    @NonNull
    public Optional<@NonNull McpServer> getMcpServer();
    @NonNull
    public LifecyclePolicy getLifecyclePolicy();
    @NotThreadSafe
    public static final class Builder {
        @NonNull
        public Builder httpServer(@Nullable HttpServer httpServer);
        @NonNull
        public Builder sseServer(@Nullable SseServer sseServer);
        @NonNull
        public Builder mcpServer(@Nullable McpServer mcpServer);
        @NonNull
        public Builder lifecyclePolicy(@Nullable LifecyclePolicy lifecyclePolicy);
        @NonNull
        public Builder instanceProvider(@Nullable InstanceProvider instanceProvider);
        @NonNull
        public Builder valueConverterRegistry(@Nullable ValueConverterRegistry valueConverterRegistry);
        @NonNull
        public Builder requestBodyMarshaler(@Nullable RequestBodyMarshaler requestBodyMarshaler);
        @NonNull
        public Builder resourceMethodResolver(@Nullable ResourceMethodResolver resourceMethodResolver);
        @NonNull
        public Builder resourceMethodParameterProvider(@Nullable ResourceMethodParameterProvider resourceMethodParameterProvider);
        @NonNull
        public Builder responseMarshaler(@Nullable ResponseMarshaler responseMarshaler);
        @NonNull
        public Builder requestInterceptor(@Nullable RequestInterceptor requestInterceptor);
        @NonNull
        public Builder lifecycleObserver(@Nullable LifecycleObserver lifecycleObserver);
        @NonNull
        public Builder lifecycleObservers(@Nullable Collection<? extends @NonNull LifecycleObserver> lifecycleObservers);
        @NonNull
        public Builder metricsCollector(@Nullable MetricsCollector metricsCollector);
        @NonNull
        public Builder corsAuthorizer(@Nullable CorsAuthorizer corsAuthorizer);
        @NonNull
        public SokletConfig build();
    }
}
```

## `com.soklet.SokletLifecycleException`

Source: [`SokletLifecycleException.java`](../../src/main/java/com/soklet/SokletLifecycleException.java#L31)

```java
@NotThreadSafe
public abstract sealed class SokletLifecycleException extends RuntimeException permits SokletShutdownCleanupException, SokletShutdownIncompleteException, SokletStartupException, SokletUnexpectedTerminationException {
    @NonNull
    public ShutdownResult getShutdownResult();
}
```

## `com.soklet.SokletShutdownCleanupException`

Source: [`SokletShutdownCleanupException.java`](../../src/main/java/com/soklet/SokletShutdownCleanupException.java#L27)

```java
@NotThreadSafe
public final class SokletShutdownCleanupException extends SokletLifecycleException {
    @NonNull
    public ShutdownCleanupFailureReason getShutdownCleanupFailureReason();
    @NonNull
    public Duration getShutdownCleanupTimeout();
}
```

## `com.soklet.SokletShutdownIncompleteException`

Source: [`SokletShutdownIncompleteException.java`](../../src/main/java/com/soklet/SokletShutdownIncompleteException.java#L28)

```java
@NotThreadSafe
public final class SokletShutdownIncompleteException extends SokletLifecycleException {
}
```

## `com.soklet.SokletStartupException`

Source: [`SokletStartupException.java`](../../src/main/java/com/soklet/SokletStartupException.java#L27)

```java
@NotThreadSafe
public final class SokletStartupException extends SokletLifecycleException {
    @NonNull
    public StartupDisposition getStartupDisposition();
}
```

## `com.soklet.SokletStatus`

Source: [`SokletStatus.java`](../../src/main/java/com/soklet/SokletStatus.java#L20)

```java
public enum SokletStatus {
    NEW, STARTING, RUNNING, SHUTTING_DOWN, CLOSED;
}
```

## `com.soklet.SokletUnexpectedTerminationException`

Source: [`SokletUnexpectedTerminationException.java`](../../src/main/java/com/soklet/SokletUnexpectedTerminationException.java#L27)

```java
@NotThreadSafe
public final class SokletUnexpectedTerminationException extends SokletLifecycleException {
    @NonNull
    public UnexpectedShutdownComponentTermination getUnexpectedShutdownComponentTermination();
}
```

## `com.soklet.StartupContext`

Source: [`StartupContext.java`](../../src/main/java/com/soklet/StartupContext.java#L36)

```java
@ThreadSafe
public final class StartupContext {
    @NonNull
    public Duration getRemainingTime();
    @NonNull
    public Boolean isCancelationRequested();
}
```

## `com.soklet.StartupDisposition`

Source: [`StartupDisposition.java`](../../src/main/java/com/soklet/StartupDisposition.java#L20)

```java
public enum StartupDisposition {
    NOT_ATTEMPTED, READY, CANCELED, TIMED_OUT, FAILED;
}
```

## `com.soklet.UnexpectedShutdownComponentTermination`

Source: [`UnexpectedShutdownComponentTermination.java`](../../src/main/java/com/soklet/UnexpectedShutdownComponentTermination.java#L28)

```java
@ThreadSafe
public final class UnexpectedShutdownComponentTermination {
    @NonNull
    public ShutdownComponentType getShutdownComponentType();
    @NonNull
    public Optional<@NonNull Throwable> getCause();
}
```

## `com.soklet.annotation.McpAppTool`

Source: [`McpAppTool.java`](../../src/main/java/com/soklet/annotation/McpAppTool.java#L38)

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpAppTool {
    @NonNull
    String resourceUri() default "";
    McpAppToolMetadata.@NonNull Visibility @NonNull [] visibility() default { McpAppToolMetadata.Visibility.MODEL, McpAppToolMetadata.Visibility.APP };
}
```

## `com.soklet.annotation.McpHeader`

Source: [`McpHeader.java`](../../src/main/java/com/soklet/annotation/McpHeader.java#L55)

```java
@Target( {
    @NonNull
    String name();
}
```

## `com.soklet.annotation.McpMayRequestInput`

Source: [`McpMayRequestInput.java`](../../src/main/java/com/soklet/annotation/McpMayRequestInput.java#L36)

```java
@Target( {
    @NonNull
    McpInputRequestType type();
    @NonNull
    McpInputRequirement requirement();
}
```

## `com.soklet.annotation.McpPrompt`

Source: [`McpPrompt.java`](../../src/main/java/com/soklet/annotation/McpPrompt.java#L36)

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpPrompt {
    @NonNull
    String name();
    @NonNull
    String title() default "";
    @NonNull
    String description() default "";
    @NonNull
    McpMayRequestInput @NonNull [] mayRequestInput() default {};
    @NonNull
    McpRequestStateMode requestStateMode() default McpRequestStateMode.NONE;
}
```

## `com.soklet.annotation.McpPromptArgument`

Source: [`McpPromptArgument.java`](../../src/main/java/com/soklet/annotation/McpPromptArgument.java#L38)

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpPromptArgument {
    @NonNull
    String name() default "";
    @NonNull
    String title() default "";
    @NonNull
    String description() default "";
}
```

## `com.soklet.annotation.McpPromptCompletion`

Source: [`McpPromptCompletion.java`](../../src/main/java/com/soklet/annotation/McpPromptCompletion.java#L32)

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpPromptCompletion {
    @NonNull
    String name();
}
```

## `com.soklet.annotation.McpResource`

Source: [`McpResource.java`](../../src/main/java/com/soklet/annotation/McpResource.java#L39)

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpResource {
    @NonNull
    String uri();
    @NonNull
    String name();
    @NonNull
    String title() default "";
    @NonNull
    String description() default "";
    @NonNull
    String mimeType() default "";
    long sizeInBytes() default -1;
    long cacheTimeToLiveInMilliseconds() default 0;
    @NonNull
    McpCacheScope cacheScope() default McpCacheScope.PRIVATE;
    @NonNull
    McpMayRequestInput @NonNull [] mayRequestInput() default {};
    @NonNull
    McpRequestStateMode requestStateMode() default McpRequestStateMode.NONE;
}
```

## `com.soklet.annotation.McpResourceCompletion`

Source: [`McpResourceCompletion.java`](../../src/main/java/com/soklet/annotation/McpResourceCompletion.java#L33)

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpResourceCompletion {
    @NonNull
    String uri();
}
```

## `com.soklet.annotation.McpResourceList`

Source: [`McpResourceList.java`](../../src/main/java/com/soklet/annotation/McpResourceList.java#L33)

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpResourceList {
}
```

## `com.soklet.annotation.McpResourceUriParameter`

Source: [`McpResourceUriParameter.java`](../../src/main/java/com/soklet/annotation/McpResourceUriParameter.java#L36)

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpResourceUriParameter {
    @NonNull
    String name() default "";
}
```

## `com.soklet.annotation.McpServerEndpoint`

Source: [`McpServerEndpoint.java`](../../src/main/java/com/soklet/annotation/McpServerEndpoint.java#L40)

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpServerEndpoint {
    @NonNull
    String path();
    @NonNull
    String name();
    @NonNull
    String version();
    @NonNull
    String title() default "";
    @NonNull
    String description() default "";
    @NonNull
    String websiteUrl() default "";
    @NonNull
    String instructions() default "";
    @NonNull
    String toolRateLimiterName() default "";
    long resourceListCacheTimeToLiveInMilliseconds() default 0;
    @NonNull
    McpCacheScope resourceListCacheScope() default McpCacheScope.PRIVATE;
    long resourceTemplateListCacheTimeToLiveInMilliseconds() default 0;
    @NonNull
    McpCacheScope resourceTemplateListCacheScope() default McpCacheScope.PRIVATE;
}
```

## `com.soklet.annotation.McpTool`

Source: [`McpTool.java`](../../src/main/java/com/soklet/annotation/McpTool.java#L49)

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpTool {
    @NonNull
    String name();
    @NonNull
    String title() default "";
    @NonNull
    String description() default "";
    @NonNull
    String rateLimiterName() default "";
    boolean structuredContentMirroredAsText() default true;
    @NonNull
    McpMayRequestInput @NonNull [] mayRequestInput() default {};
    @NonNull
    McpRequestStateMode requestStateMode() default McpRequestStateMode.NONE;
}
```

## `com.soklet.annotation.McpToolArgument`

Source: [`McpToolArgument.java`](../../src/main/java/com/soklet/annotation/McpToolArgument.java#L39)

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpToolArgument {
    @NonNull
    String name() default "";
    @NonNull
    String title() default "";
    @NonNull
    String description() default "";
}
```

## `com.soklet.annotation.McpToolProperty`

Source: [`McpToolProperty.java`](../../src/main/java/com/soklet/annotation/McpToolProperty.java#L37)

```java
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpToolProperty {
    @NonNull
    String name() default "";
    @NonNull
    String title() default "";
    @NonNull
    String description() default "";
}
```

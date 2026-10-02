package com.soklet.conformance.legacy;

import com.soklet.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded public-API loopback fixture for legacy notification and credential checks. */
public final class McpLegacyRuntimeFixture {
  private static Path output;
  private static final List<McpProtocolVersion> VERSIONS = List.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25);
  private static final Map<String, State> STATES = new ConcurrentHashMap<>();
  private static final Set<McpSubscriptionNotificationType> FAMILIES = Set.of(McpSubscriptionNotificationType.TOOLS_LIST_CHANGED,
      McpSubscriptionNotificationType.PROMPTS_LIST_CHANGED, McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
      McpSubscriptionNotificationType.RESOURCE_UPDATED);
  private static final AtomicInteger EVENT_COUNT = new AtomicInteger();
  private static final class State {
    volatile int tools, prompts, resources, content, contentPadding;
    final Map<String, String> tokens = Map.of("A", UUID.randomUUID().toString(), "B", UUID.randomUUID().toString(), "C", UUID.randomUUID().toString());
    final Set<String> revoked = ConcurrentHashMap.newKeySet();
    final AtomicInteger activeGets = new AtomicInteger();
    final McpSubscriptionEventPublisher publisher = McpSubscriptionEventPublisher.fromInMemoryDefaults();
  }
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) throw new IllegalArgumentException("One existing empty output directory required");
    output = Path.of(arguments[0]).toRealPath();
    try (var entries = Files.list(output)) {
      if (entries.findAny().isPresent()) throw new IllegalArgumentException("Empty output required");
    }
    for (McpProtocolVersion version : VERSIONS) STATES.put(version.getWireValue(), new State());
    StringJoiner credentials = new StringJoiner(",", "{", "}");
    for (McpProtocolVersion version : VERSIONS) {
      State state = STATES.get(version.getWireValue());
      credentials.add("\"" + version.getWireValue() + "\":{\"A\":\"" + state.tokens.get("A") + "\",\"B\":\"" + state.tokens.get("B") + "\",\"C\":\"" + state.tokens.get("C") + "\"}");
    }
    Files.writeString(output.resolve("credentials.json"), credentials.toString());
    McpServer server = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
        .corsAuthorizer(CorsAuthorizer.rejectAllInstance())
        .endpointRegistry(McpEndpointRegistry.fromEndpoints(VERSIONS.stream().map(McpLegacyRuntimeFixture::endpoint).toList()))
        .admissionController(context -> {
          String revision = context.getProtocolVersion().getWireValue();
          String credential = credential(context.getRequest(), revision);
          event(revision, "rpc-admission", credential + ":" + context.getOperationType().name(), "");
          return authorized(revision, credential) ? McpAdmissionDecision.accepted(identity(credential))
              : McpAdmissionDecision.rejected(rejection());
        })
        .requestRateLimiter(context -> McpRateLimitDecision.allowed()).toolRateLimiter(context -> McpRateLimitDecision.allowed())
        .catalogAccessPolicy(McpCatalogAccessPolicy.fromEvaluators(
            (requestContext, toolRegistration, invocationFeatures) -> {
              String name = toolRegistration.getName();
              State state = STATES.get(requestContext.getProtocolVersion().getWireValue());
              return name.equals("fixture_status") || name.equals(state.tools == 0 ? "catalog_before" : "catalog_after");
            },
            (requestContext, promptRegistration, invocationFeatures) -> {
              State state = STATES.get(requestContext.getProtocolVersion().getWireValue());
              return promptRegistration.getName().equals(state.prompts == 0 ? "prompt_before" : "prompt_after");
            }))
        .sessionConfig(McpSessionConfig.withOwnerKeyResolver(identity -> "disposable-owner")
            .maximumSessionIdleDuration(Duration.ofMinutes(10)).maximumSessionDuration(Duration.ofMinutes(15))
            .transportAdmissionController((context, invocationFeatures) -> {
              String revision = context.getProtocolVersion().getWireValue();
              String credential = credential(context.getRequest(), revision);
              boolean allowed = authorized(revision, credential);
              event(revision, context.isReauthorization() ? "get-renewal" : context.getRequest().getHttpMethod().name(),
                  credential + ":" + (allowed ? "allowed" : "denied"), "");
              return allowed ? McpSessionTransportAdmissionDecision.accepted(identity(credential), Instant.now().plusSeconds(6), context.getNotificationTypes())
                  : McpSessionTransportAdmissionDecision.rejected(rejection());
            }).build())
        .subscriptionAuthorizer((authorizationContext, invocationFeatures) -> {
          String revision = authorizationContext.getInitialRequestContext().getProtocolVersion().getWireValue();
          String credential = credential(authorizationContext.getInitialRequestContext().getRequest(), revision);
          boolean allowed = authorized(revision, credential);
          event(revision, authorizationContext.getPreviousValidUntil().isPresent() ? "uri-renewal" : "uri-initial",
              credential + ":" + (allowed ? "allowed" : "denied") + ":context:" + authorizationContext.getApplicationContext().orElse("none"),
              authorizationContext.getResourceSubscriptionUris().iterator().next().toString());
          return allowed ? McpSubscriptionAuthorization.Allowed.withValidUntil(Instant.now().plusSeconds(6)).applicationContext("credential-" + credential).build()
              : McpSubscriptionAuthorization.deniedInstance();
        })
        .keepAliveInterval(Duration.ofSeconds(1)).maximumSubscriptionAuthorizationDuration(Duration.ofSeconds(6)).subscriptionAuthorizationTimeout(Duration.ofSeconds(1))
        .maximumSubscriptionDuration(Duration.ofMinutes(10)).requestTimeout(Duration.ofSeconds(10)).build();
    MetricsCollector metrics = new MetricsCollector() {
      @Override public void didRecordMcpMetricsEvent(McpMetricsEvent metricsEvent) {
        try {
          if (metricsEvent instanceof McpMetricsEvent.SubscriptionOpened opened) {
            STATES.get(revision(opened.getEndpointPath())).activeGets.incrementAndGet();
            event(revision(opened.getEndpointPath()), "get-opened", "", "");
          } else if (metricsEvent instanceof McpMetricsEvent.SubscriptionClosed closed) {
            STATES.get(revision(closed.getEndpointPath())).activeGets.decrementAndGet();
            event(revision(closed.getEndpointPath()), "get-closed", closed.getReason().name(), "");
          }
        } catch (Exception exception) { throw new IllegalStateException(exception); }
      }
    };
    LifecyclePolicy lifecycle = LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
        .startupCancelationTimeout(Duration.ofSeconds(2)).gracefulShutdownTimeout(Duration.ofSeconds(2))
        .forcedShutdownTimeout(Duration.ofSeconds(1)).build();
    try (Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
        .resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of())).metricsCollector(metrics).lifecyclePolicy(lifecycle).build())) {
      Runtime.getRuntime().addShutdownHook(new Thread(soklet::close, "notification-host-fixture-shutdown"));
      soklet.start();
      System.out.println("{\"ready\":true,\"port\":" + server.getDiagnostics().getBoundAddress().orElseThrow().getPort() + "}");
      System.out.flush();
      long deadline = System.nanoTime() + Duration.ofSeconds(70).toNanos();
      while (!Files.exists(output.resolve("stop")) && System.nanoTime() - deadline < 0L) {
        Path commandPath = output.resolve("command");
        if (Files.isRegularFile(commandPath)) {
          if (Files.size(commandPath) > 256) throw new IllegalArgumentException("Command too large");
          String[] command = Files.readString(commandPath).strip().split("\\|", -1);
          if (command.length != 3 || !command[0].matches("[0-9]{1,5}") || !STATES.containsKey(command[1]))
            throw new IllegalArgumentException("Invalid private command");
          State state = STATES.get(command[1]);
          if (command[2].equals("gap") && state.activeGets.get() != 0) { Thread.sleep(25); continue; }
          switch (command[2]) {
            case "tools" -> { state.tools = 1 - state.tools; state.publisher.publishToolsListChanged(); }
            case "prompts" -> { state.prompts = 1 - state.prompts; state.publisher.publishPromptsListChanged(); }
            case "resources" -> { state.resources = 1 - state.resources; state.publisher.publishResourcesListChanged(); }
            case "resource" -> { ++state.content; state.publisher.publishResourceUpdated(URI.create("fixture:///catalog")); }
            case "resource-long" -> {
              ++state.content; state.contentPadding = Math.min(4096, state.contentPadding + 31);
              state.publisher.publishResourceUpdated(URI.create("fixture:///catalog"));
            }
            case "template" -> { ++state.content; state.publisher.publishResourceUpdated(URI.create("fixture:///item/example")); }
            case "repeat-tools" -> state.publisher.publishToolsListChanged();
            case "repeat-prompts" -> state.publisher.publishPromptsListChanged();
            case "repeat-resources" -> state.publisher.publishResourcesListChanged();
            case "revoke-a" -> { state.revoked.add("A"); server.getSubscriptionReconciler().reconcileSubscriptions(); }
            case "revoke-b" -> { state.revoked.add("B"); server.getSubscriptionReconciler().reconcileSubscriptions(); }
            case "reconcile" -> server.getSubscriptionReconciler().reconcileSubscriptions();
            case "state" -> { }
            case "gap" -> { }
            default -> throw new IllegalArgumentException("Unknown private command");
          }
          event(command[1], "command", command[2], "");
          Files.delete(commandPath);
          Files.writeString(output.resolve("ack-" + command[0]), status(command[1]));
        }
        Thread.sleep(25);
      }
    }
    System.out.println("{\"format\":1,\"event\":\"stopped\",\"clean\":true}");
    System.out.flush();
  }
  private static String credential(Request request, String revision) {
    String value = request.getHeader("Authorization").orElse("");
    for (Map.Entry<String,String> entry : STATES.get(revision).tokens.entrySet())
      if (value.equals("Bearer " + entry.getValue())) return entry.getKey();
    return "unknown";
  }
  private static boolean authorized(String revision, String credential) {
    return !credential.equals("unknown") && !STATES.get(revision).revoked.contains(credential);
  }
  private static McpAdmissionIdentity identity(String credential) {
    return McpAdmissionIdentity.withRateLimitPartitionKey("disposable-quota").authorizationPartitionKey("disposable-authorization")
        .principal("disposable-owner").applicationContext("credential-" + credential).build();
  }
  private static McpAdmissionRejection rejection() {
    return McpAdmissionRejection.withStatusCodeAndError(401, McpJsonRpcError.fromApplication(-31903, "Credential rejected"))
        .addHeader("WWW-Authenticate", BearerAuthenticationChallenge.withResourceMetadataUri(URI.create("https://fixture.invalid/.well-known/oauth-protected-resource"))
            .error(BearerAuthenticationError.INVALID_TOKEN).build().getHeaderValue()).build();
  }
  private static String revision(String endpointPath) { return endpointPath.substring("/mcp-".length()); }
  private static McpEndpoint endpoint(McpProtocolVersion version) {
    String revision = version.getWireValue(); Set<McpProtocolVersion> versions = Set.of(version); State state = STATES.get(revision);
    List<McpToolRegistration<?>> tools = new ArrayList<>();
    for (String phase : List.of("before", "after")) {
      McpJsonObject schema = McpJsonObject.builder().put("type", "object")
          .put("properties", McpJsonObject.builder().put(phase + "_marker",
              McpJsonObject.builder().put("type", "string").put("description", "Optional " + phase + " catalog marker").build()).build()).build();
      tools.add(McpToolRegistration.withName("catalog_" + phase, versions).inputSchema(schema)
          .handler((requestContext, toolArguments, invocationFeatures) -> {
            event(revision, "tool-called", "catalog_" + phase, "");
            return McpCompleteResult.fromToolText("SOKLET-CATALOG-" + phase.toUpperCase(Locale.ROOT) + "-" + revision);
          }).description("Disposable " + phase + " catalog tool. Return its exact result without other actions.").build());
    }
    tools.add(McpToolRegistration.withName("fixture_status", versions).jsonObjectArguments()
        .handler((requestContext, toolArguments, invocationFeatures) -> McpCompleteResult.fromToolText(status(revision)))
        .description("Read the disposable local fixture status; has no side effects.").build());
    List<McpPromptRegistration> prompts = new ArrayList<>();
    for (String phase : List.of("before", "after"))
      prompts.add(McpPromptRegistration.withName("prompt_" + phase, versions)
          .handler((requestContext, promptGetContext, invocationFeatures) -> {
            event(revision, "prompt-read", "prompt_" + phase, "");
            return McpCompleteResult.fromPromptOutput(McpPromptOutput.fromMessages(
                McpPromptMessage.fromUserText("SOKLET-PROMPT-" + phase.toUpperCase(Locale.ROOT) + "-" + revision)));
          }).description("Disposable " + phase + " prompt catalog entry.").build());
    McpResourceReadHandler read = (requestContext, resourceReadContext, invocationFeatures) -> {
      event(revision, "resource-read", "content-" + state.content, resourceReadContext.getUri().toString());
      return McpCompleteResult.fromResourceOutput(McpResourceOutput.fromContent(McpTextResourceContents
          .withUriAndText(resourceReadContext.getUri(), "SOKLET-RESOURCE-CONTENT-" + state.content + "-" + revision + "X".repeat(state.contentPadding))
          .mimeType("text/plain").build()));
    };
    return McpEndpoint.withPath("/mcp-" + revision,
        McpImplementation.withNameAndVersion("Soklet Legacy Runtime Fixture", "1").build(), versions)
        .sessionProtocolVersions(versions).subscriptionProtocolVersions(versions).toolRegistrations(tools).promptRegistrations(prompts)
        .resourceRegistrations(List.of(McpResourceRegistration.withUriAndName(URI.create("fixture:///catalog"), "catalog", versions).handler(read).build(),
            McpResourceRegistration.withUriTemplateAndName("fixture:///item/{key}", "item", versions).handler(read).build()))
        .resourceListHandler((requestContext, resourceListContext, invocationFeatures) -> {
          event(revision, "resources-list", "phase-" + state.resources, "");
          return McpResourcePage.builder().resourceDescriptors(List.of(McpResourceDescriptor
              .withUriAndName(URI.create("fixture:///catalog"), state.resources == 0 ? "resource_before" : "resource_after")
              .description("Disposable resource catalog phase " + state.resources).mimeType("text/plain").build())).build();
        }, versions)
        .subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(state.publisher, FAMILIES).build()).build();
  }
  private static String status(String revision) {
    State state = STATES.get(revision);
    return "{\"revision\":\"" + revision + "\",\"tools\":" + state.tools + ",\"prompts\":" + state.prompts
        + ",\"resources\":" + state.resources + ",\"content\":" + state.content
        + ",\"contentPadding\":" + state.contentPadding + ",\"activeGets\":" + state.activeGets.get() + "}";
  }
  private static synchronized void event(String revision, String kind, String detail, String uri) throws Exception {
    if (EVENT_COUNT.incrementAndGet() > 5000) throw new IllegalStateException("Bounded fixture event count exceeded");
    for (String value : List.of(revision, kind, detail, uri))
      if (!value.matches("[A-Za-z0-9:/._-]*")) throw new IllegalArgumentException("Invalid disposable event field");
    Files.writeString(output.resolve("events.ndjson"), "{\"at\":\"" + Instant.now() + "\",\"revision\":\"" + revision
        + "\",\"event\":\"" + kind + "\",\"detail\":\"" + detail + "\",\"uri\":\"" + uri + "\"}\n",
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }
}

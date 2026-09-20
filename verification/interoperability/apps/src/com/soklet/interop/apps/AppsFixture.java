/*
 * Copyright 2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet.interop.apps;

import com.soklet.CorsAuthorizer;
import com.soklet.LifecycleObserver;
import com.soklet.LifecyclePolicy;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpAdmissionRejection;
import com.soklet.McpAppResourceMetadata;
import com.soklet.McpAppToolMetadata;
import com.soklet.McpCachePolicy;
import com.soklet.McpCatalogAccessPolicy;
import com.soklet.McpCompleteResult;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcException;
import com.soklet.McpJsonString;
import com.soklet.McpLocalizationContext;
import com.soklet.McpLocalizationResult;
import com.soklet.McpLocalizer;
import com.soklet.McpRateLimiter;
import com.soklet.McpRequestContext;
import com.soklet.McpResourceDescriptor;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourcePage;
import com.soklet.McpResourceRegistration;
import com.soklet.McpServer;
import com.soklet.McpSubscriptionAuthorizer;
import com.soklet.McpTextContent;
import com.soklet.McpTextResourceContents;
import com.soklet.McpToolOutput;
import com.soklet.McpToolRegistration;
import com.soklet.ResourceMethodResolver;
import com.soklet.SimulatorConfig;
import com.soklet.SokletConfig;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * Public-API-only, disposable Apps qualification fixture, not a production
 * credential store or an official core-conformance scenario. Per-request
 * admission snapshots a caller; no locale, tenant, or authorization is taken
 * from tool arguments, clientInfo, Apps audience flags, or result metadata.
 */
public final class AppsFixture {
	public static final String PATH = "/apps";
	public static final URI UI_URI = URI.create("ui://soklet/catalog-v1");
	public static final String MIME = "text/html;profile=mcp-app";
	public static final String TOOL = "show_catalog";
	public static final String REFRESH = "refresh_catalog";
	public static final String RAW_CANARY = "fixture-private-canary";
	private static final int MAXIMUM_SHELL_BYTES = 512 * 1024;
	private static final int MAXIMUM_CALLERS = 8;
	private static final LifecyclePolicy LIFECYCLE = LifecyclePolicy.builder()
			.startupTimeout(Duration.ofSeconds(5))
			.startupCancelationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownTimeout(Duration.ofSeconds(2))
			.forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	private final Map<String, Caller> credentials = new ConcurrentHashMap<>();
	private final McpEndpoint endpoint;

	/** Immutable, application-controlled identity for one independent request. */
	public record Caller(String subject, String tenant, String locale, boolean allowed) {
		public Caller {
			if (requireNonNull(subject).isBlank() || subject.length() > 64
					|| !Set.of("alpha", "beta").contains(tenant)
					|| !Set.of("en-US", "pt-BR", "ar").contains(locale))
				throw new IllegalArgumentException("Invalid disposable fixture caller.");
		}
	}

	public AppsFixture(String shell, Map<String, Caller> credentials) {
		requireNonNull(shell);
		if (shell.isBlank() || shell.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_SHELL_BYTES)
			throw new IllegalArgumentException("Apps fixture shell is empty or oversized.");
		requireNonNull(credentials).forEach(this::setCaller);
		McpResourceDescriptor descriptor = McpResourceDescriptor
				.withUriAndName(UI_URI, "catalog_view").title("Catalog view").mimeType(MIME).build();
		McpAppResourceMetadata resourceMetadata = McpAppResourceMetadata.builder()
				.contentSecurityPolicy(McpAppResourceMetadata.ContentSecurityPolicy.builder().build())
				.permissions(Set.of()).prefersBorder(true).build();
		this.endpoint = McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion("soklet-apps-fixture", "fixture-v1").build())
				.toolRegistrations(java.util.List.of(tool(TOOL, false), tool(REFRESH, true)))
				.resourceRegistrations(java.util.List.of(McpResourceRegistration.withUriAndName(UI_URI, "catalog_view")
						.handler((context, resource, features) -> {
							// Independent of listing, tool visibility, or prior tool execution.
							if (!caller(context).allowed())
								throw new McpJsonRpcException(McpJsonRpcError.fromApplication(
										-31904, "Resource unavailable."));
							return McpCompleteResult.fromResourceOutput(McpResourceOutput.fromContent(
									McpTextResourceContents.withUriAndText(UI_URI, shell).mimeType(MIME)
											.appResourceMetadata(resourceMetadata).build()));
						}).title("Catalog view").mimeType(MIME).build()))
				.resourceListCachePolicy(McpCachePolicy.privateNoCacheInstance())
				.resourceListHandler((context, list, features) -> McpResourcePage.builder()
						.resourceDescriptors(caller(context).allowed() ? List.of(descriptor) : List.of()).build())
				.build();
	}

	/** Changes only future request admissions; an already admitted identity is immutable. */
	public synchronized void setCaller(String token, Caller caller) {
		if (!requireNonNull(token).matches("[A-Za-z0-9_-]{8,128}")
				|| (!this.credentials.containsKey(token) && this.credentials.size() >= MAXIMUM_CALLERS))
			throw new IllegalArgumentException("Invalid disposable fixture credential.");
		this.credentials.put(token, requireNonNull(caller));
	}

	public synchronized void revoke(String token) {
		this.credentials.remove(requireNonNull(token));
	}

	public SimulatorConfig simulatorConfig() {
		return SimulatorConfig.builder().configureMcpServer(builder -> configure(builder.port(0)))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(LIFECYCLE).build();
	}

	/** Loopback-only configuration for the later independently supervised host run. */
	public SokletConfig serverConfig(int port) {
		return serverConfig(port, LifecycleObserver.defaultInstance());
	}

	/** Allows the disposable process launcher to suppress raw framework diagnostics. */
	public SokletConfig serverConfig(int port, LifecycleObserver lifecycleObserver) {
		return SokletConfig.withMcpServer(configure(McpServer.withPort(port)).build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(requireNonNull(lifecycleObserver))
				.lifecyclePolicy(LIFECYCLE).build();
	}

	private McpServer.Builder configure(McpServer.Builder builder) {
		return builder.host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"))
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(this.endpoint)))
				.admissionController(context -> {
					String authorization = context.getRequest().getHeader("Authorization").orElse("");
					Caller admitted = authorization.startsWith("Bearer ")
							? this.credentials.get(authorization.substring(7)) : null;
					if (admitted == null)
						return McpAdmissionDecision.rejected(McpAdmissionRejection.withStatusCodeAndError(
								401, McpJsonRpcError.fromApplication(-31901, "Authentication required."))
								.addHeader("WWW-Authenticate", "Bearer").build());
					String partition = admitted.tenant() + ":" + admitted.subject();
					return McpAdmissionDecision.accepted(McpAdmissionIdentity
							.withRateLimitPartitionKey(partition).authorizationPartitionKey(partition)
							.principal(admitted).build());
				})
				.catalogAccessPolicy(McpCatalogAccessPolicy.fromEvaluators(
						(context, tool, features) -> caller(context).allowed(),
						(context, prompt, features) -> false))
				.requestRateLimiter(McpRateLimiter.fromInMemoryDefaults())
				.toolRateLimiter(McpRateLimiter.fromInMemoryDefaults())
				.subscriptionAuthorizer(McpSubscriptionAuthorizer.denyAllInstance())
				.localizer(McpLocalizer.withFallbackLocale(Locale.US, request -> {
					Caller admitted = caller(request.getRequestContext());
					return McpLocalizationContext.withLocale(Locale.forLanguageTag(admitted.locale()),
							text -> McpLocalizationResult.localized(translate(admitted.locale(), text.getDefaultText())))
							.build();
				}).build())
				.toolResultSanitizer((context, name, arguments, result) -> {
					McpToolOutput raw = (McpToolOutput) result.getPayload();
					McpJsonObject data = (McpJsonObject) raw.getStructuredContent().orElseThrow();
					String summary = ((McpJsonString) data.find("summary").orElseThrow()).getValue();
					// Explicit allowlist for this fixture. _meta is not a secret channel.
					return result.toBuilder().payload(McpToolOutput.builder()
							.content(java.util.List.of(McpTextContent.fromText(summary))).structuredContent(data)
							.error(raw.isError()).build())
							.metadata(McpJsonObject.builder().put("example/view", "catalog-v1").build()).build();
				});
	}

	private McpToolRegistration<McpJsonObject> tool(String name, boolean appOnly) {
		McpAppToolMetadata.Builder app = McpAppToolMetadata.builder();
		if (appOnly)
			app.visibility(Set.of(McpAppToolMetadata.Visibility.APP));
		else
			app.resourceUri(UI_URI);
		return McpToolRegistration.withName(name)
				.inputSchema(McpJsonObject.builder().put("type", "object")
						.put("properties", McpJsonObject.emptyInstance()).put("additionalProperties", false).build())
				.handler((context, arguments, features) -> {
					Caller admitted = caller(context);
					McpJsonObject data = data(admitted);
					return McpCompleteResult.withToolOutput(McpToolOutput.builder()
							.content(java.util.List.of(McpTextContent.fromText(RAW_CANARY))).structuredContent(data).build())
							.metadata(McpJsonObject.builder().put("example/private", RAW_CANARY)
									.put("example/view", "catalog-v1").build()).build();
				}).title(appOnly ? "Refresh catalog" : "Show catalog")
				.structuredContentMirroredAsText(false).appToolMetadata(app.build()).build();
	}

	private static Caller caller(McpRequestContext context) {
		return (Caller) context.getAdmissionIdentity().getPrincipal().orElseThrow();
	}

	private static McpJsonObject data(Caller caller) {
		String summary = switch (caller.locale()) {
			case "pt-BR" -> "Catálogo " + caller.tenant() + ": 1 item.";
			case "ar" -> "الكتالوج " + caller.tenant() + ": عنصر واحد.";
			default -> "Catalog " + caller.tenant() + ": 1 item.";
		};
		return McpJsonObject.builder().put("locale", caller.locale())
				.put("direction", caller.locale().equals("ar") ? "rtl" : "ltr")
				.put("tenant", caller.tenant()).put("title", translate(caller.locale(), "Catalog view"))
				.put("refreshLabel", translate(caller.locale(), "Refresh catalog"))
				.put("summary", summary)
				// Deliberately hostile-looking translation tests text-only rendering.
				.put("itemLabel", translate(caller.locale(), "Toy") + " <img src=x onerror=alert(1)>")
				.put("amount", new BigDecimal("1234.5")).put("currency", "USD")
				.put("updatedAt", "2026-09-19T12:00:00Z").put("timeZone", "UTC").build();
	}

	private static String translate(String locale, String text) {
		return switch (locale) {
			case "pt-BR" -> switch (text) {
				case "Catalog view" -> "Catálogo";
				case "Show catalog" -> "Mostrar catálogo";
				case "Refresh catalog" -> "Atualizar catálogo";
				case "Toy" -> "Brinquedo";
				default -> text;
			};
			case "ar" -> switch (text) {
				case "Catalog view" -> "الكتالوج";
				case "Show catalog" -> "عرض الكتالوج";
				case "Refresh catalog" -> "تحديث الكتالوج";
				case "Toy" -> "لعبة";
				default -> text;
			};
			default -> text;
		};
	}
}

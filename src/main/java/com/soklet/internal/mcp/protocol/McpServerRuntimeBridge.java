/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import com.soklet.CorsAuthorizer;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpEndpoint;
import com.soklet.McpImplementation;
import com.soklet.McpJsonArray;
import com.soklet.McpJsonBoolean;
import com.soklet.McpJsonNull;
import com.soklet.McpJsonNumber;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpJsonValue;
import com.soklet.McpRequestId;
import com.soklet.McpRequestRejection;
import com.soklet.Request;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Internal bridge between Soklet's public MCP server and the package-private
 * protocol runtime. This is not an application extension point.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpServerRuntimeBridge {
	@NonNull
	private static final McpJsonCodec CANONICAL_JSON_CODEC =
			new McpJsonCodec(McpJsonLimits.productionDefaults());
	@NonNull
	private final McpHttpServerRuntime runtime;

	/**
	 * Creates a discovery-only listener projection.
	 */
	public McpServerRuntimeBridge(@NonNull String host, int port,
			@NonNull McpEndpoint publicEndpoint,
			@NonNull Set<@NonNull String> allowedHosts, boolean requireOrigin,
			@NonNull CorsAuthorizer corsAuthorizer,
			boolean corsAuthorizerExplicitlyConfigured,
			@NonNull AdmissionAdapter admissionAdapter,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer) {
		this(host, port, publicEndpoint, allowedHosts, requireOrigin,
				corsAuthorizer, corsAuthorizerExplicitlyConfigured, admissionAdapter,
				startupDiagnosticConsumer, throwable -> {});
	}

	/**
	 * Creates a discovery-only listener projection with runtime-failure reporting.
	 */
	public McpServerRuntimeBridge(@NonNull String host, int port,
			@NonNull McpEndpoint publicEndpoint,
			@NonNull Set<@NonNull String> allowedHosts, boolean requireOrigin,
			@NonNull CorsAuthorizer corsAuthorizer,
			boolean corsAuthorizerExplicitlyConfigured,
			@NonNull AdmissionAdapter admissionAdapter,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer) {
		this(host, port, publicEndpoint, allowedHosts, requireOrigin,
				corsAuthorizer, corsAuthorizerExplicitlyConfigured, admissionAdapter,
				Optional.empty(), List.of(), startupDiagnosticConsumer,
				unexpectedTerminationConsumer);
	}

	/**
	 * Creates a listener projection from immutable executable tool plans.
	 */
	public McpServerRuntimeBridge(@NonNull String host, int port,
			@NonNull McpEndpoint publicEndpoint,
			@NonNull Set<@NonNull String> allowedHosts, boolean requireOrigin,
			@NonNull CorsAuthorizer corsAuthorizer,
			boolean corsAuthorizerExplicitlyConfigured,
			@NonNull AdmissionAdapter admissionAdapter,
			@NonNull Optional<@NonNull RateLimitAdapter> requestRateLimitAdapter,
			@NonNull List<@NonNull ToolPlan> toolPlans,
			@NonNull Consumer<@NonNull String> startupDiagnosticConsumer,
			@NonNull Consumer<@NonNull Throwable> unexpectedTerminationConsumer) {
		requireNonNull(host);
		requireNonNull(publicEndpoint);
		requireNonNull(allowedHosts);
		requireNonNull(corsAuthorizer);
		requireNonNull(admissionAdapter);
		requireNonNull(requestRateLimitAdapter);
		List<ToolPlan> immutableToolPlans = List.copyOf(requireNonNull(toolPlans));
		requireNonNull(startupDiagnosticConsumer);
		requireNonNull(unexpectedTerminationConsumer);

		McpImplementation publicInformation = publicEndpoint.getServerInformation();
		McpImplementationMetadata implementation = new McpImplementationMetadata(
				publicInformation.getName(), publicInformation.getVersion(),
				publicInformation.getTitle(), publicInformation.getDescription(),
				publicInformation.getWebsiteUrl(), List.of(),
				com.soklet.internal.mcp.protocol.McpJsonObject.empty());
		McpNormalizedEndpoint.Builder endpointBuilder =
				McpNormalizedEndpoint.withServerInformation(implementation);
		publicEndpoint.getInstructions().ifPresent(endpointBuilder::instructions);
		Map<String, McpApplicationToolRoute> toolRoutes = new LinkedHashMap<>();
		for (ToolPlan toolPlan : immutableToolPlans) {
			requireNonNull(toolPlan);
			McpNormalizedToolDescriptor descriptor = new McpNormalizedToolDescriptor(
					toolPlan.name(),
					(com.soklet.internal.mcp.protocol.McpJsonObject)
							toInternal(toolPlan.inputSchemaDocument()),
					toolPlan.outputSchemaDocument().map(value ->
							(com.soklet.internal.mcp.protocol.McpJsonObject)
									toInternal(value)),
					(com.soklet.internal.mcp.protocol.McpJsonObject)
							toInternal(toolPlan.descriptorFields()),
					(com.soklet.internal.mcp.protocol.McpJsonObject)
							toInternal(toolPlan.metadata()));
			endpointBuilder.tool(McpNormalizedOperation.tool(
					descriptor, McpMirroredHeaderPlan.empty()));

			McpRateLimiter internalToolRateLimiter = context ->
					toInternalRateLimitDecision(requireNonNull(
							toolPlan.toolRateLimitAdapter().acquire(
									toRateLimitInput(context, publicEndpoint)),
							"The MCP tool rate limiter returned null."));
			McpApplicationToolRoute route = new McpApplicationToolRoute(
					invocation -> invokeTool(toolPlan, invocation, publicEndpoint),
					internalToolRateLimiter);
			if (toolRoutes.putIfAbsent(toolPlan.name(), route) != null)
				throw new IllegalArgumentException(
						"Duplicate tool plan '" + toolPlan.name() + "'.");
		}
		McpNormalizedEndpoint endpoint = endpointBuilder.build();

		McpRequestAdmissionPolicy internalAdmissionPolicy = context -> {
			AdmissionInput input = new AdmissionInput(context.request(), publicEndpoint,
					context.endpointPathParameters(), context.jsonRpcMethod(),
					context.notification(), context.requestId().map(McpServerRuntimeBridge::toPublic),
					context.protocolVersion(), context.operationName(),
					context.clientInformation().map(McpServerRuntimeBridge::toPublic),
					context.clientCapabilities().map(value ->
							(McpJsonObject) toPublic(value.toJsonObject())),
					context.requestMetadata().map(value ->
							(McpJsonObject) toPublic(value)));
			McpAdmissionDecision decision = requireNonNull(admissionAdapter.admit(input),
					"The MCP request-admission policy returned null.");
			return toInternal(decision);
		};

		McpHttpEndpointPolicy endpointPolicy = new McpHttpEndpointPolicy(
				publicEndpoint.getPath(), allowedHosts,
				requireOrigin ? McpAbsentOriginPolicy.REQUIRE_ORIGIN
						: McpAbsentOriginPolicy.ALLOW,
				corsAuthorizer, internalAdmissionPolicy, Optional.empty(),
				McpApplicationRequestInterceptor.passThroughInstance(),
				McpUnknownMirroredHeaderPolicy.IGNORE,
				corsAuthorizerExplicitlyConfigured);
		if (requestRateLimitAdapter.isPresent()) {
			RateLimitAdapter adapter = requestRateLimitAdapter.orElseThrow();
			endpointPolicy = endpointPolicy.withRequestRateLimiter(context ->
					toInternalRateLimitDecision(requireNonNull(
							adapter.acquire(toRateLimitInput(context, publicEndpoint)),
							"The MCP request rate limiter returned null.")));
		}
		McpHttpTransportConfiguration defaults =
				McpHttpTransportConfiguration.productionDefaults(port);
		McpHttpTransportConfiguration transport = new McpHttpTransportConfiguration(
				host, port, defaults.selectorResolution(), defaults.requestHeaderTimeout(),
				defaults.requestBodyTimeout(), defaults.responseWriteIdleTimeout(),
				defaults.keepAliveInterval(), defaults.shutdownTimeout(),
				defaults.readBufferSize(), defaults.acceptBacklog(),
				defaults.maximumAggregateRequestBytes(), defaults.maximumRequestBodyBytes(),
				defaults.maximumHeaderCount(), defaults.maximumHeaderBytes(),
				defaults.maximumRequestTargetBytes(), defaults.maximumConnections(),
				defaults.connectionWriterConcurrency(), defaults.requestProcessorConcurrency(),
				defaults.requestProcessorQueueCapacity(), defaults.streamQueueCapacity());
		this.runtime = new McpHttpServerRuntime(transport, endpointPolicy, endpoint,
				McpJsonLimits.productionDefaults(),
				McpApplicationRequestRouter.fromToolRoutes(toolRoutes),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(),
				startupDiagnosticConsumer, unexpectedTerminationConsumer);
	}

	@NonNull
	public InetSocketAddress start() throws IOException {
		return this.runtime.start();
	}

	public void stop() {
		this.runtime.stop();
	}

	public boolean stopAndReportResidualHandlers() {
		return this.runtime.stopAndReportResidualApplicationExecutions();
	}

	public boolean isStarted() {
		return getRuntimeState().started();
	}

	@NonNull
	public Optional<@NonNull InetSocketAddress> getBoundAddress() {
		return getRuntimeState().boundAddress();
	}

	public boolean hasResidualHandlers() {
		return getRuntimeState().residualHandlers();
	}

	/**
	 * Captures the listener state through one runtime lifecycle lock acquisition.
	 *
	 * @return atomic runtime state
	 */
	@NonNull
	public RuntimeState getRuntimeState() {
		McpHttpServerLifecycleSnapshot snapshot = this.runtime.lifecycleSnapshot();
		return new RuntimeState(snapshot.started(), snapshot.stopRequired(),
				snapshot.boundAddress(), snapshot.residualApplicationExecutions());
	}

	/**
	 * Atomic internal lifecycle projection used by the public server adapter.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record RuntimeState(boolean started, boolean stopRequired,
			@NonNull Optional<@NonNull InetSocketAddress> boundAddress,
			boolean residualHandlers) {
		public RuntimeState {
			requireNonNull(boundAddress);
			if (started != boundAddress.isPresent())
				throw new IllegalArgumentException(
						"A started MCP listener must have exactly one bound address.");
			if (started && !stopRequired)
				throw new IllegalArgumentException(
						"A started MCP listener must require a stop transition.");
		}
	}

	/**
	 * Immutable catalog-and-execution source for one tool.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record ToolPlan(@NonNull String name,
			@NonNull McpJsonObject inputSchemaDocument,
			@NonNull Optional<@NonNull McpJsonObject> outputSchemaDocument,
			@NonNull McpJsonObject descriptorFields,
			@NonNull McpJsonObject metadata,
			boolean mirrorStructuredContentAsText,
			@NonNull RateLimitAdapter toolRateLimitAdapter,
			@NonNull ToolInvoker invoker) {
		public ToolPlan {
			name = McpProtocolSupport.requireNonBlank(name, "Tool name");
			requireNonNull(inputSchemaDocument);
			requireNonNull(outputSchemaDocument);
			requireNonNull(descriptorFields);
			requireNonNull(metadata);
			requireNonNull(toolRateLimitAdapter);
			requireNonNull(invoker);
		}

		@Override
		@NonNull
		public String toString() {
			return "ToolPlan[outputSchemaPresent=" + outputSchemaDocument.isPresent()
					+ ", descriptorFieldCount=" + descriptorFields.getMembers().size()
					+ ", metadataFieldCount=" + metadata.getMembers().size()
					+ ", mirrorStructuredContentAsText="
					+ mirrorStructuredContentAsText + "]";
		}
	}

	/**
	 * Erased invocation input whose public values require no package-private
	 * conversion in the public server adapter.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record ToolInvocation(@NonNull Request request,
			@NonNull McpEndpoint endpoint,
			@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
			@NonNull String jsonRpcMethod,
			@NonNull McpRequestId requestId,
			@NonNull String protocolVersion,
			@NonNull String operationName,
			@NonNull Optional<@NonNull McpImplementation> clientInformation,
			@NonNull McpJsonObject clientCapabilitiesJson,
			@NonNull McpJsonObject requestMetadata,
			@NonNull McpAdmissionIdentity admissionIdentity,
			@NonNull McpJsonObject rawArguments) {
		public ToolInvocation {
			requireNonNull(request);
			requireNonNull(endpoint);
			endpointPathParameters = Map.copyOf(
					requireNonNull(endpointPathParameters));
			jsonRpcMethod = McpProtocolSupport.requireNonBlank(
					jsonRpcMethod, "JSON-RPC method");
			requireNonNull(requestId);
			protocolVersion = McpProtocolSupport.requireNonBlank(
					protocolVersion, "Protocol version");
			operationName = McpProtocolSupport.requireNonBlank(
					operationName, "Operation name");
			requireNonNull(clientInformation);
			requireNonNull(clientCapabilitiesJson);
			requireNonNull(requestMetadata);
			requireNonNull(admissionIdentity);
			requireNonNull(rawArguments);
		}

		@Override
		@NonNull
		public String toString() {
			return "ToolInvocation[endpointPathParameterCount="
					+ endpointPathParameters.size()
					+ ", clientInformationPresent=" + clientInformation.isPresent()
					+ ", rawArgumentMemberCount=" + rawArguments.getMembers().size()
					+ ", authenticated=" + admissionIdentity.isAuthenticated() + "]";
		}
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface ToolInvoker {
		@NonNull
		ToolInvocationResult invoke(@NonNull ToolInvocation invocation)
				throws Exception;
	}

	/**
	 * Bridge-owned erased tool result.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public sealed interface ToolInvocationResult permits ToolInvocationResult.Complete,
			ToolInvocationResult.Structured, ToolInvocationResult.InvalidInput {
		@NonNull
		static Complete complete(@NonNull McpJsonObject resultFields,
				@NonNull McpJsonObject metadata) {
			return new Complete(resultFields, metadata);
		}

		@NonNull
		static Structured structured(@NonNull McpJsonValue structuredContent,
				@NonNull McpJsonObject metadata) {
			return new Structured(structuredContent, metadata);
		}

		@NonNull
		static InvalidInput invalidInput() {
			return InvalidInput.INSTANCE;
		}

		/**
		 * Explicit advanced result fields preserved without synthesized content.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record Complete(@NonNull McpJsonObject resultFields,
				@NonNull McpJsonObject metadata) implements ToolInvocationResult {
			public Complete {
				requireNonNull(resultFields);
				requireNonNull(metadata);
			}
		}

		/**
		 * Typed structured result whose compatibility mirror remains framework-owned.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record Structured(@NonNull McpJsonValue structuredContent,
				@NonNull McpJsonObject metadata) implements ToolInvocationResult {
			public Structured {
				requireNonNull(structuredContent);
				requireNonNull(metadata);
			}
		}

		/**
		 * Input validation or binding failed before the typed handler ran.
		 *
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		enum InvalidInput implements ToolInvocationResult {
			INSTANCE
		}
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface RateLimitAdapter {
		@NonNull
		RateLimitResult acquire(@NonNull RateLimitInput input) throws Exception;
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record RateLimitInput(@NonNull Request request,
			@NonNull McpEndpoint endpoint,
			@NonNull McpAdmissionIdentity admissionIdentity,
			@NonNull RateLimitTarget target,
			@NonNull String jsonRpcMethod,
			@NonNull Optional<@NonNull String> operationName) {
		public RateLimitInput {
			requireNonNull(request);
			requireNonNull(endpoint);
			requireNonNull(admissionIdentity);
			requireNonNull(target);
			jsonRpcMethod = McpProtocolSupport.requireNonBlank(
					jsonRpcMethod, "JSON-RPC method");
			requireNonNull(operationName);
		}

		@Override
		@NonNull
		public String toString() {
			return "RateLimitInput[target=" + target
					+ ", operationNamePresent=" + operationName.isPresent()
					+ ", authenticated=" + admissionIdentity.isAuthenticated() + "]";
		}
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public enum RateLimitTarget {
		REQUEST,
		TOOL
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public sealed interface RateLimitResult permits RateLimitResult.Allowed,
			RateLimitResult.Denied {
		@NonNull
		static Allowed allowed() {
			return new Allowed();
		}

		@NonNull
		static Denied denied(@NonNull Duration retryAfter) {
			return new Denied(retryAfter);
		}

		/**
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record Allowed() implements RateLimitResult {
		}

		/**
		 * @author <a href="https://www.revetkn.com">Mark Allen</a>
		 */
		@ThreadSafe
		record Denied(@NonNull Duration retryAfter) implements RateLimitResult {
			public Denied {
				requireNonNull(retryAfter);
				if (retryAfter.isNegative())
					throw new IllegalArgumentException(
							"Retry-After must not be negative.");
			}
		}
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	@FunctionalInterface
	public interface AdmissionAdapter {
		@NonNull
		McpAdmissionDecision admit(@NonNull AdmissionInput input) throws Exception;
	}

	/**
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record AdmissionInput(@NonNull Request request,
			@NonNull McpEndpoint endpoint,
			@NonNull Map<@NonNull String, @NonNull String> endpointPathParameters,
			@NonNull String jsonRpcMethod, boolean notification,
			@NonNull Optional<@NonNull McpRequestId> requestId,
			@NonNull String protocolVersion,
			@NonNull Optional<@NonNull String> operationName,
			@NonNull Optional<@NonNull McpImplementation> clientInformation,
			@NonNull Optional<@NonNull McpJsonObject> clientCapabilitiesJson,
			@NonNull Optional<@NonNull McpJsonObject> requestMetadata) {
		public AdmissionInput {
			requireNonNull(request);
			requireNonNull(endpoint);
			endpointPathParameters = Map.copyOf(requireNonNull(endpointPathParameters));
			requireNonNull(jsonRpcMethod);
			requireNonNull(requestId);
			requireNonNull(protocolVersion);
			requireNonNull(operationName);
			requireNonNull(clientInformation);
			requireNonNull(clientCapabilitiesJson);
			requireNonNull(requestMetadata);
		}
	}

	@NonNull
	private static McpWireResult invokeTool(@NonNull ToolPlan toolPlan,
			@NonNull McpApplicationInvocation invocation,
			@NonNull McpEndpoint publicEndpoint) throws Exception {
		McpJsonRpcMessage.Request request = invocation.request();
		com.soklet.internal.mcp.protocol.McpJsonValue argumentsValue =
				request.params().fields().members().get("arguments");
		com.soklet.internal.mcp.protocol.McpJsonObject arguments =
				argumentsValue instanceof com.soklet.internal.mcp.protocol.McpJsonObject object
						? object : com.soklet.internal.mcp.protocol.McpJsonObject.empty();
		McpRequestMetadata requestMetadata = request.params().metadata();
		ToolInvocation toolInvocation = new ToolInvocation(
				invocation.sokletRequest().orElseThrow(() ->
						new IllegalStateException(
								"A production MCP tool invocation requires its Soklet request.")),
				publicEndpoint, Map.of(), request.method(), toPublic(request.id()),
				requestMetadata.protocolVersion(), toolPlan.name(),
				requestMetadata.clientInformation().map(McpServerRuntimeBridge::toPublic),
				(McpJsonObject) toPublic(
						requestMetadata.clientCapabilities().toJsonObject()),
				(McpJsonObject) toPublic(requestMetadata.toJsonObject()),
				toPublic(invocation.admissionIdentity().admittedIdentity()),
				(McpJsonObject) toPublic(arguments));
		ToolInvocationResult result = requireNonNull(
				toolPlan.invoker().invoke(toolInvocation),
				"The MCP tool invoker returned null.");

		if (result instanceof ToolInvocationResult.InvalidInput)
			throw new McpInvalidApplicationInputException();

		com.soklet.internal.mcp.protocol.McpJsonObject resultFields;
		com.soklet.internal.mcp.protocol.McpJsonObject resultMetadata;
		if (result instanceof ToolInvocationResult.Complete complete) {
			resultFields = (com.soklet.internal.mcp.protocol.McpJsonObject)
					toInternal(complete.resultFields());
			resultMetadata = (com.soklet.internal.mcp.protocol.McpJsonObject)
					toInternal(complete.metadata());
		} else if (result instanceof ToolInvocationResult.Structured structured) {
			com.soklet.internal.mcp.protocol.McpJsonValue structuredContent =
					toInternal(structured.structuredContent());
			List<com.soklet.internal.mcp.protocol.McpJsonValue> content =
					new ArrayList<>();
			if (toolPlan.mirrorStructuredContentAsText()) {
				Map<String, com.soklet.internal.mcp.protocol.McpJsonValue> textBlock =
						new LinkedHashMap<>();
				textBlock.put("type",
						new com.soklet.internal.mcp.protocol.McpJsonString("text"));
				textBlock.put("text", new com.soklet.internal.mcp.protocol.McpJsonString(
						CANONICAL_JSON_CODEC.toJson(structuredContent)));
				content.add(new com.soklet.internal.mcp.protocol.McpJsonObject(textBlock));
			}
			Map<String, com.soklet.internal.mcp.protocol.McpJsonValue> fields =
					new LinkedHashMap<>();
			fields.put("content",
					new com.soklet.internal.mcp.protocol.McpJsonArray(content));
			fields.put("structuredContent", structuredContent);
			resultFields = new com.soklet.internal.mcp.protocol.McpJsonObject(fields);
			resultMetadata = (com.soklet.internal.mcp.protocol.McpJsonObject)
					toInternal(structured.metadata());
		} else {
			throw new IllegalArgumentException("Unsupported MCP tool invocation result.");
		}

		McpResultMetadata metadata = new McpResultMetadata(
				Optional.empty(), resultMetadata);
		return McpWireResult.complete(resultFields,
				metadata.isEmpty() ? Optional.empty() : Optional.of(metadata));
	}

	@NonNull
	private static RateLimitInput toRateLimitInput(
			@NonNull McpRateLimitContext context,
			@NonNull McpEndpoint publicEndpoint) {
		RateLimitTarget target = switch (context.target()) {
			case REQUEST -> RateLimitTarget.REQUEST;
			case TOOL -> RateLimitTarget.TOOL;
		};
		return new RateLimitInput(context.request(), publicEndpoint,
				toPublic(context.admissionIdentity().admittedIdentity()), target,
				context.jsonRpcMethod(), context.operationName());
	}

	@NonNull
	private static McpRateLimitDecision toInternalRateLimitDecision(
			@NonNull RateLimitResult result) {
		if (result instanceof RateLimitResult.Allowed)
			return McpRateLimitDecision.allowed();
		if (result instanceof RateLimitResult.Denied denied)
			return McpRateLimitDecision.denied(denied.retryAfter());
		throw new IllegalArgumentException("Unsupported MCP rate-limit result.");
	}

	@NonNull
	private static McpRequestId toPublic(@NonNull McpJsonRpcId requestId) {
		if (requestId instanceof McpJsonRpcId.StringId stringId)
			return McpRequestId.fromString(stringId.value());
		if (requestId instanceof McpJsonRpcId.IntegerId integerId)
			return McpRequestId.fromInteger(integerId.value());
		throw new IllegalArgumentException("Unsupported MCP request ID.");
	}

	@NonNull
	private static McpAdmissionIdentity toPublic(
			com.soklet.internal.mcp.protocol.@NonNull McpAdmissionIdentity identity) {
		com.soklet.McpAdmissionIdentity.Builder builder =
				com.soklet.McpAdmissionIdentity.withRateLimitPartitionKey(
						identity.rateLimitPartitionKey().orElseThrow());
		identity.authorizationPartitionKey().ifPresent(
				builder::authorizationPartitionKey);
		identity.principal().ifPresent(builder::principal);
		identity.applicationContext().ifPresent(builder::applicationContext);
		return builder.build();
	}

	@NonNull
	private static McpImplementation toPublic(
			@NonNull McpImplementationMetadata metadata) {
		McpImplementation.Builder builder = McpImplementation.withNameAndVersion(
				metadata.name(), metadata.version());
		metadata.title().ifPresent(builder::title);
		metadata.description().ifPresent(builder::description);
		metadata.websiteUrl().ifPresent(builder::websiteUrl);
		return builder.build();
	}

	private static com.soklet.internal.mcp.protocol.@NonNull McpAdmissionDecision toInternal(
			@NonNull McpAdmissionDecision decision) {
		if (decision instanceof McpAdmissionDecision.Accepted accepted)
			return com.soklet.internal.mcp.protocol.McpAdmissionDecision.accepted(
					toInternal(accepted.identity()));
		if (decision instanceof McpAdmissionDecision.Rejected rejected)
			return com.soklet.internal.mcp.protocol.McpAdmissionDecision.rejected(
					toInternal(rejected.rejection()));
		throw new IllegalArgumentException("Unsupported MCP admission decision.");
	}

	private static com.soklet.internal.mcp.protocol.@NonNull McpAdmissionIdentity toInternal(
			@NonNull McpAdmissionIdentity identity) {
		com.soklet.internal.mcp.protocol.McpAdmissionIdentity.Builder builder =
				com.soklet.internal.mcp.protocol.McpAdmissionIdentity
						.withRateLimitPartitionKey(identity.getRateLimitPartitionKey());
		identity.getAuthorizationPartitionKey().ifPresent(builder::authorizationPartitionKey);
		identity.getPrincipal().ifPresent(builder::principal);
		identity.getApplicationContext().ifPresent(builder::applicationContext);
		return builder.build();
	}

	private static com.soklet.internal.mcp.protocol.@NonNull McpRequestRejection toInternal(
			@NonNull McpRequestRejection rejection) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		rejection.getHeaders().forEach((name, values) ->
				headers.put(name, List.copyOf(values)));
		com.soklet.McpJsonRpcError error = rejection.getJsonRpcError();
		return new com.soklet.internal.mcp.protocol.McpRequestRejection(
				rejection.getStatusCode(),
				new com.soklet.internal.mcp.protocol.McpJsonRpcError(
						error.getCode(), error.getMessage(), error.getData().map(
								McpServerRuntimeBridge::toInternal)),
				headers);
	}

	@NonNull
	private static McpJsonValue toPublic(
			com.soklet.internal.mcp.protocol.@NonNull McpJsonValue value) {
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonString string)
			return new McpJsonString(string.value());
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonNumber number)
			return new McpJsonNumber(number.value());
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonBoolean bool)
			return new McpJsonBoolean(
					bool == com.soklet.internal.mcp.protocol.McpJsonBoolean.TRUE);
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonNull)
			return McpJsonNull.INSTANCE;
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonArray array) {
			List<McpJsonValue> elements = new ArrayList<>();
			array.values().forEach(element -> elements.add(toPublic(element)));
			return McpJsonArray.fromElements(elements);
		}
		if (value instanceof com.soklet.internal.mcp.protocol.McpJsonObject object) {
			Map<String, McpJsonValue> members = new LinkedHashMap<>();
			object.members().forEach((name, member) -> members.put(name, toPublic(member)));
			return McpJsonObject.fromMembers(members);
		}
		throw new IllegalArgumentException("Unsupported internal MCP JSON value.");
	}

	private static com.soklet.internal.mcp.protocol.@NonNull McpJsonValue toInternal(
			@NonNull McpJsonValue value) {
		if (value instanceof McpJsonString string)
			return new com.soklet.internal.mcp.protocol.McpJsonString(string.value());
		if (value instanceof McpJsonNumber number)
			return new com.soklet.internal.mcp.protocol.McpJsonNumber(number.value());
		if (value instanceof McpJsonBoolean bool)
			return com.soklet.internal.mcp.protocol.McpJsonBoolean
					.fromBoolean(bool.value());
		if (value instanceof McpJsonNull)
			return com.soklet.internal.mcp.protocol.McpJsonNull.INSTANCE;
		if (value instanceof McpJsonArray array) {
			List<com.soklet.internal.mcp.protocol.McpJsonValue> elements = new ArrayList<>();
			array.getElements().forEach(element -> elements.add(toInternal(element)));
			return new com.soklet.internal.mcp.protocol.McpJsonArray(elements);
		}
		if (value instanceof McpJsonObject object) {
			Map<String, com.soklet.internal.mcp.protocol.McpJsonValue> members =
					new LinkedHashMap<>();
			object.getMembers().forEach((name, member) ->
					members.put(name, toInternal(member)));
			return new com.soklet.internal.mcp.protocol.McpJsonObject(members);
		}
		throw new IllegalArgumentException("Unsupported public MCP JSON value.");
	}
}

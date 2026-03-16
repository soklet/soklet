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

package com.soklet;

import com.soklet.DefaultMcpHandlerResolver.AnnotatedPromptBinding;
import com.soklet.DefaultMcpHandlerResolver.AnnotatedResourceBinding;
import com.soklet.DefaultMcpHandlerResolver.AnnotatedResourceListBinding;
import com.soklet.DefaultMcpHandlerResolver.AnnotatedToolBinding;
import com.soklet.DefaultMcpHandlerResolver.ProgrammaticPromptBinding;
import com.soklet.DefaultMcpHandlerResolver.ProgrammaticResourceBinding;
import com.soklet.DefaultMcpHandlerResolver.ProgrammaticResourceListBinding;
import com.soklet.DefaultMcpHandlerResolver.ProgrammaticToolBinding;
import com.soklet.DefaultMcpHandlerResolver.PromptBinding;
import com.soklet.DefaultMcpHandlerResolver.ResolvedEndpoint;
import com.soklet.DefaultMcpHandlerResolver.ResourceBinding;
import com.soklet.DefaultMcpHandlerResolver.ResourceListBinding;
import com.soklet.DefaultMcpHandlerResolver.ToolBinding;
import com.soklet.annotation.McpArgument;
import com.soklet.annotation.McpEndpointPathParameter;
import com.soklet.annotation.McpUriParameter;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.soklet.Utilities.extractContentTypeFromHeaderValue;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Internal MCP request dispatcher for framework-owned transport behavior.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMcpRuntime {
	@NonNull
	private static final String SUPPORTED_PROTOCOL_VERSION = "2025-11-25";
	@NonNull
	private static final McpObject EMPTY_OBJECT = new McpObject(Map.of());
	@NonNull
	private final Soklet soklet;
	@NonNull
	private final ConcurrentHashMap<@NonNull String, @NonNull CopyOnWriteArrayList<@NonNull McpStreamState>> mcpStreamsBySessionId;

	DefaultMcpRuntime(@NonNull Soklet soklet) {
		requireNonNull(soklet);
		this.soklet = soklet;
		this.mcpStreamsBySessionId = new ConcurrentHashMap<>();
	}

	@NonNull
	RequestResult handleRequest(@NonNull Request request) {
		requireNonNull(request);

		try {
			if (request.isContentTooLarge())
				return plainTextResponse(request, 413, "Request entity too large");

			McpServer mcpServer = getSoklet().getSokletConfig().getMcpServer().orElse(null);

			if (mcpServer == null)
				return plainTextResponse(request, 501, "MCP server is not configured.");

			ResolvedEndpoint resolvedEndpoint = resolveEndpoint(request, mcpServer).orElse(null);

			if (resolvedEndpoint == null)
				return requestResultFromMarshaledResponse(request, getSoklet().getSokletConfig().getResponseMarshaler().forNotFound(request));

			String sessionId = request.getHeader("MCP-Session-Id").orElse(null);
			String origin = request.getHeader("Origin").orElse(null);

			if (!mcpServer.getOriginPolicy().isAllowed(new McpOriginCheckContext(request, resolvedEndpoint.endpointClass(),
					request.getHttpMethod(), origin, sessionId)))
				return plainTextResponse(request, 403, "Forbidden");

			return switch (request.getHttpMethod()) {
				case POST -> handlePostRequest(request, mcpServer, resolvedEndpoint);
				case GET -> handleGetRequest(request, mcpServer, resolvedEndpoint);
				case DELETE -> handleDeleteRequest(request, mcpServer, resolvedEndpoint);
				default -> requestResultFromMarshaledResponse(request,
						getSoklet().getSokletConfig().getResponseMarshaler().forMethodNotAllowed(request, Set.of(HttpMethod.POST, HttpMethod.GET, HttpMethod.DELETE)));
			};
		} catch (Throwable throwable) {
			return RequestResult.fromMarshaledResponse(getSoklet().provideFailsafeMarshaledResponse(request, throwable));
		}
	}

	@NonNull
	private RequestResult handlePostRequest(@NonNull Request request,
																					@NonNull McpServer mcpServer,
																					@NonNull ResolvedEndpoint resolvedEndpoint) {
		requireNonNull(request);
		requireNonNull(mcpServer);
		requireNonNull(resolvedEndpoint);

		String contentType = request.getContentType().orElse(null);

		if (!extractContentTypeFromHeaderValue(contentType)
				.filter("application/json"::equalsIgnoreCase)
				.isPresent())
			return plainTextResponse(request, 400, "MCP POST requests must use Content-Type: application/json");

		if (!acceptsMediaType(request, "application/json") || !acceptsMediaType(request, "text/event-stream"))
			return plainTextResponse(request, 406, "MCP POST requests must accept both application/json and text/event-stream.");

		ParsedJsonRpcRequest parsedRequest;

		try {
			parsedRequest = parseJsonRpcRequest(request);
		} catch (JsonRpcErrorTransport transport) {
			return jsonRpcErrorResponse(request, transport.requestId(), transport.error());
		}

		if (parsedRequest.operationKind() == null)
			return jsonRpcErrorResponse(request, parsedRequest.requestId(), McpJsonRpcError.fromCodeAndMessage(-32601, "Method not found"));

		Optional<McpStoredSession> storedSession = Optional.empty();
		String sessionId = request.getHeader("MCP-Session-Id").orElse(null);
		String protocolVersionHeader = request.getHeader("MCP-Protocol-Version").orElse(null);

		if (parsedRequest.operationKind() != McpOperationKind.INITIALIZE) {
			if (sessionId == null)
				return plainTextResponse(request, 400, "Missing MCP-Session-Id header.");

			if (protocolVersionHeader == null)
				return plainTextResponse(request, 400, "Missing MCP-Protocol-Version header.");

			storedSession = mcpServer.getSessionStore().findBySessionId(sessionId);

			if (storedSession.isEmpty()) {
				observeIdleExpiredSessionIfPresent(mcpServer, sessionId);
				return plainTextResponse(request, 404, "Unknown MCP session.");
			}

			if (storedSession.get().terminatedAt() != null
					|| !storedSession.get().endpointClass().equals(resolvedEndpoint.endpointClass()))
				return plainTextResponse(request, 404, "Unknown MCP session.");

			if (!Objects.equals(storedSession.get().protocolVersion(), protocolVersionHeader))
				return plainTextResponse(request, 400, "MCP-Protocol-Version does not match the negotiated session version.");
		}

		Map<String, String> endpointPathParameters = resolvedEndpoint.endpointPathDeclaration().extractPlaceholders(request.getResourcePath());
		DefaultMcpRequestContext requestContext = new DefaultMcpRequestContext(
				request,
				resolvedEndpoint.endpointClass(),
				parsedRequest.method(),
				parsedRequest.operationKind(),
				Optional.ofNullable(parsedRequest.requestId()),
				Optional.ofNullable(sessionId),
				Optional.ofNullable(protocolVersionHeader),
				storedSession.map(McpStoredSession::negotiatedCapabilities),
				storedSession.map(McpStoredSession::sessionContext)
		);
		Optional<McpStoredSession> finalStoredSession = storedSession;

		try {
			RequestResult requestResult = mcpServer.getRequestInterceptor().interceptRequest(requestContext, () ->
					dispatchObservedPostRequest(request, mcpServer, resolvedEndpoint, endpointPathParameters, parsedRequest, finalStoredSession, requestContext));

			if (requestResult == null)
				throw new IllegalStateException(format("%s::interceptRequest returned null for MCP operation %s",
						McpRequestInterceptor.class.getSimpleName(), parsedRequest.method()));

			return requestResult;
		} catch (JsonRpcErrorTransport transport) {
			return jsonRpcErrorResponse(request,
					transport.requestId() == null ? parsedRequest.requestId() : transport.requestId(),
					transport.error());
		} catch (Throwable throwable) {
			return jsonRpcErrorResponse(request, parsedRequest.requestId(), McpJsonRpcError.fromCodeAndMessage(-32603, "Internal error"));
		}
	}

	@NonNull
	private RequestResult handleGetRequest(@NonNull Request request,
																				 @NonNull McpServer mcpServer,
																				 @NonNull ResolvedEndpoint resolvedEndpoint) throws Exception {
		requireNonNull(request);
		requireNonNull(mcpServer);
		requireNonNull(resolvedEndpoint);

		String sessionId = request.getHeader("MCP-Session-Id").orElse(null);
		String protocolVersionHeader = request.getHeader("MCP-Protocol-Version").orElse(null);

		if (!acceptsMediaType(request, "text/event-stream"))
			return plainTextResponse(request, 406, "MCP GET requests must accept text/event-stream.");

		if (request.getHeader("Last-Event-ID").isPresent())
			return plainTextResponse(request, 400, "Last-Event-ID is not supported for MCP GET streams.");

		if (sessionId == null)
			return plainTextResponse(request, 400, "Missing MCP-Session-Id header.");

		if (protocolVersionHeader == null)
			return plainTextResponse(request, 400, "Missing MCP-Protocol-Version header.");

		McpStoredSession storedSession = mcpServer.getSessionStore().findBySessionId(sessionId).orElse(null);

		if (storedSession == null) {
			observeIdleExpiredSessionIfPresent(mcpServer, sessionId);
			return plainTextResponse(request, 404, "Unknown MCP session.");
		}

		if (storedSession.terminatedAt() != null || !storedSession.endpointClass().equals(resolvedEndpoint.endpointClass()))
			return plainTextResponse(request, 404, "Unknown MCP session.");

		if (!Objects.equals(storedSession.protocolVersion(), protocolVersionHeader))
			return plainTextResponse(request, 400, "MCP-Protocol-Version does not match the negotiated session version.");

		Optional<Response> admissionResponse = mcpServer.getRequestAdmissionPolicy().checkRequest(
				new DefaultMcpAdmissionContext(
						request,
						request.getHttpMethod(),
						resolvedEndpoint.endpointClass(),
						Optional.empty(),
						Optional.empty(),
						Optional.empty(),
						Optional.of(sessionId)
				));

		if (admissionResponse.isPresent())
			return httpResponse(request, admissionResponse.get());

		if (!storedSession.initialized())
			return plainTextResponse(request, 400, "MCP session is not initialized.");

		if (!storedSession.initializedNotificationReceived())
			return plainTextResponse(request, 400, "MCP session has not received notifications/initialized.");

		touchSession(mcpServer, storedSession);
		registerMcpStream(request, resolvedEndpoint.endpointClass(), sessionId);
		return requestResultFromMarshaledResponse(request, eventStreamResponse());
	}

	@NonNull
	private RequestResult dispatchObservedPostRequest(@NonNull Request request,
																										@NonNull McpServer mcpServer,
																										@NonNull ResolvedEndpoint resolvedEndpoint,
																										@NonNull Map<String, String> endpointPathParameters,
																										@NonNull ParsedJsonRpcRequest parsedRequest,
																										@NonNull Optional<McpStoredSession> storedSession,
																										@NonNull DefaultMcpRequestContext requestContext) throws Exception {
		requireNonNull(request);
		requireNonNull(mcpServer);
		requireNonNull(resolvedEndpoint);
		requireNonNull(endpointPathParameters);
		requireNonNull(parsedRequest);
		requireNonNull(storedSession);
		requireNonNull(requestContext);

		Optional<Response> admissionResponse = mcpServer.getRequestAdmissionPolicy().checkRequest(
				new DefaultMcpAdmissionContext(
						request,
						request.getHttpMethod(),
						resolvedEndpoint.endpointClass(),
						Optional.of(parsedRequest.method()),
						Optional.ofNullable(parsedRequest.operationKind()),
						Optional.ofNullable(parsedRequest.requestId()),
						Optional.ofNullable(request.getHeader("MCP-Session-Id").orElse(null))
				));

		if (admissionResponse.isPresent())
			return httpResponse(request, admissionResponse.get());

		Instant handlingStarted = Instant.now();
		List<Throwable> throwables = new ArrayList<>(2);
		safelyInvokeLifecycleObserver(LogEventType.LIFECYCLE_OBSERVER_DID_START_MCP_REQUEST_HANDLING_FAILED,
				format("An exception occurred while invoking %s::didStartMcpRequestHandling", LifecycleObserver.class.getSimpleName()),
				request,
				throwables,
				lifecycleObserver -> lifecycleObserver.didStartMcpRequestHandling(request,
						resolvedEndpoint.endpointClass(),
						requestContext.getSessionId().orElse(null),
						parsedRequest.method(),
						parsedRequest.requestId()));
		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didStartMcpRequestHandling", MetricsCollector.class.getSimpleName()),
				request,
				metricsCollector -> metricsCollector.didStartMcpRequestHandling(request,
						resolvedEndpoint.endpointClass(),
						requestContext.getSessionId().orElse(null),
						parsedRequest.method(),
						parsedRequest.requestId()));

		RequestResult requestResult;

		try {
			requestResult = dispatchPostRequestAfterAdmission(request, mcpServer, resolvedEndpoint, endpointPathParameters, parsedRequest, storedSession, requestContext);
		} catch (JsonRpcErrorTransport transport) {
			requestResult = jsonRpcErrorResponse(request,
					transport.requestId() == null ? parsedRequest.requestId() : transport.requestId(),
					transport.error());
		} catch (Throwable throwable) {
			throwables.add(throwable);
			requestResult = jsonRpcErrorResponse(request, parsedRequest.requestId(), McpJsonRpcError.fromCodeAndMessage(-32603, "Internal error"));
		}

		ObservedMcpResult observedMcpResult = observeMcpResult(requestResult, parsedRequest);
		Duration duration = Duration.between(handlingStarted, Instant.now());

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didFinishMcpRequestHandling", MetricsCollector.class.getSimpleName()),
				request,
				metricsCollector -> metricsCollector.didFinishMcpRequestHandling(request,
						resolvedEndpoint.endpointClass(),
						requestContext.getSessionId().orElse(null),
						parsedRequest.method(),
						parsedRequest.requestId(),
						observedMcpResult.requestOutcome(),
						observedMcpResult.jsonRpcError(),
						duration,
						List.copyOf(throwables)));
		safelyInvokeLifecycleObserver(LogEventType.LIFECYCLE_OBSERVER_DID_FINISH_MCP_REQUEST_HANDLING_FAILED,
				format("An exception occurred while invoking %s::didFinishMcpRequestHandling", LifecycleObserver.class.getSimpleName()),
				request,
				null,
				lifecycleObserver -> lifecycleObserver.didFinishMcpRequestHandling(request,
						resolvedEndpoint.endpointClass(),
						requestContext.getSessionId().orElse(null),
						parsedRequest.method(),
						parsedRequest.requestId(),
						observedMcpResult.requestOutcome(),
						observedMcpResult.jsonRpcError(),
						duration,
						List.copyOf(throwables)));

		return requestResult;
	}

	@NonNull
	private RequestResult dispatchPostRequestAfterAdmission(@NonNull Request request,
																													@NonNull McpServer mcpServer,
																													@NonNull ResolvedEndpoint resolvedEndpoint,
																													@NonNull Map<String, String> endpointPathParameters,
																													@NonNull ParsedJsonRpcRequest parsedRequest,
																													@NonNull Optional<McpStoredSession> storedSession,
																													@NonNull DefaultMcpRequestContext requestContext) throws Exception {
		requireNonNull(request);
		requireNonNull(mcpServer);
		requireNonNull(resolvedEndpoint);
		requireNonNull(endpointPathParameters);
		requireNonNull(parsedRequest);
		requireNonNull(storedSession);
		requireNonNull(requestContext);

		return switch (parsedRequest.operationKind()) {
			case INITIALIZE -> handleInitialize(request, mcpServer, resolvedEndpoint, endpointPathParameters, parsedRequest);
			case NOTIFICATIONS_INITIALIZED ->
					handleInitializedNotification(request, mcpServer, parsedRequest, storedSession.orElseThrow(), requestContext);
			case PING -> handlePing(request, mcpServer, parsedRequest, storedSession.orElse(null));
			case TOOLS_LIST ->
					handleToolsList(request, resolvedEndpoint, parsedRequest, storedSession.orElseThrow(), requestContext);
			case TOOLS_CALL ->
					handleToolCall(request, resolvedEndpoint, endpointPathParameters, parsedRequest, storedSession.orElseThrow(), requestContext);
			case PROMPTS_LIST ->
					handlePromptsList(request, resolvedEndpoint, parsedRequest, storedSession.orElseThrow(), requestContext);
			case PROMPTS_GET ->
					handlePromptGet(request, resolvedEndpoint, endpointPathParameters, parsedRequest, storedSession.orElseThrow(), requestContext);
			case RESOURCES_LIST ->
					handleResourcesList(request, resolvedEndpoint, endpointPathParameters, parsedRequest, storedSession.orElseThrow(), requestContext);
			case RESOURCES_TEMPLATES_LIST ->
					handleResourceTemplatesList(request, resolvedEndpoint, parsedRequest, storedSession.orElseThrow(), requestContext);
			case RESOURCES_READ ->
					handleResourceRead(request, resolvedEndpoint, endpointPathParameters, parsedRequest, storedSession.orElseThrow(), requestContext);
			default ->
					jsonRpcErrorResponse(request, parsedRequest.requestId(), McpJsonRpcError.fromCodeAndMessage(-32601, "Method not found"));
		};
	}

	@NonNull
	private RequestResult handleInitialize(@NonNull Request request,
																				 @NonNull McpServer mcpServer,
																				 @NonNull ResolvedEndpoint resolvedEndpoint,
																				 @NonNull Map<String, String> endpointPathParameters,
																				 @NonNull ParsedJsonRpcRequest parsedRequest) throws Exception {
		requireNonNull(request);
		requireNonNull(mcpServer);
		requireNonNull(resolvedEndpoint);
		requireNonNull(endpointPathParameters);
		requireNonNull(parsedRequest);

		if (request.getHeader("MCP-Session-Id").isPresent())
			return plainTextResponse(request, 400, "MCP-Session-Id must not be sent with initialize.");

		if (parsedRequest.requestId() == null)
			return jsonRpcErrorResponse(request, null, McpJsonRpcError.fromCodeAndMessage(-32600, "initialize requires a request id"));

		McpObject params = parsedRequest.params();
		String requestedProtocolVersion = requiredString(params, "protocolVersion");
		String protocolVersion = negotiateProtocolVersion(requestedProtocolVersion);
		McpObject capabilitiesValue = requiredObject(params, "capabilities");
		McpObject clientInfoValue = optionalObject(params, "clientInfo").orElse(null);
		McpClientCapabilities clientCapabilities = new McpClientCapabilities(capabilitiesValue);
		McpClientInfo clientInfo = clientInfoValue == null ? null : new McpClientInfo(requiredString(clientInfoValue, "name"),
				optionalString(clientInfoValue, "version").orElse(null));
		String sessionId = mcpServer.getIdGenerator().generateId(request);
		Instant now = Instant.now();
		McpStoredSession initialSession = new McpStoredSession(
				sessionId,
				resolvedEndpoint.endpointClass(),
				now,
				now,
				false,
				false,
				null,
				null,
				null,
				McpSessionContext.fromBlankSlate(),
				null,
				0L
		);

		mcpServer.getSessionStore().create(initialSession);
		observeSessionCreated(request, resolvedEndpoint.endpointClass(), sessionId);

		McpEndpoint endpoint = endpointInstance(resolvedEndpoint.endpointClass());
		DefaultMcpInitializationContext initializationContext = new DefaultMcpInitializationContext(
				request,
				protocolVersion,
				clientCapabilities,
				Optional.ofNullable(clientInfo),
				endpointPathParameters,
				getSoklet().getSokletConfig().getValueConverterRegistry()
		);

		try {
			McpSessionContext sessionContext = endpoint.initialize(initializationContext, McpSessionContext.fromBlankSlate());
			McpNegotiatedCapabilities negotiatedCapabilities = negotiatedCapabilitiesFor(resolvedEndpoint);
			McpStoredSession updatedSession = new McpStoredSession(
					initialSession.sessionId(),
					initialSession.endpointClass(),
					initialSession.createdAt(),
					Instant.now(),
					true,
					false,
					protocolVersion,
					clientCapabilities,
					negotiatedCapabilities,
					sessionContext,
					null,
					initialSession.version() + 1L
			);

			if (!mcpServer.getSessionStore().replace(initialSession, updatedSession))
				throw new IllegalStateException("Unable to persist MCP initialization state.");

			McpObject result = initializeResultObject(resolvedEndpoint, protocolVersion, negotiatedCapabilities);
			return jsonRpcSuccessResponse(request, parsedRequest.requestId(), result, Map.of("MCP-Session-Id", Set.of(sessionId)));
		} catch (Throwable throwable) {
			McpJsonRpcError error;

			try {
				error = endpoint.handleError(unwrapInvocationThrowable(throwable),
						new DefaultMcpRequestContext(
								request,
								resolvedEndpoint.endpointClass(),
								parsedRequest.method(),
								parsedRequest.operationKind(),
								Optional.ofNullable(parsedRequest.requestId()),
								Optional.of(sessionId),
								Optional.of(protocolVersion),
								Optional.empty(),
								Optional.of(McpSessionContext.fromBlankSlate())
						));
			} catch (Throwable handleErrorThrowable) {
				safelyLog(LogEvent.with(LogEventType.SERVER_INTERNAL_ERROR,
								format("An exception occurred while invoking %s::handleError during MCP initialization", McpEndpoint.class.getSimpleName()))
						.throwable(handleErrorThrowable)
						.request(request)
						.build());
				error = McpJsonRpcError.fromCodeAndMessage(-32603, "Internal error");
			}

			McpStoredSession terminatedSession = new McpStoredSession(
					initialSession.sessionId(),
					initialSession.endpointClass(),
					initialSession.createdAt(),
					Instant.now(),
					false,
					false,
					protocolVersion,
					clientCapabilities,
					null,
					McpSessionContext.fromBlankSlate(),
					Instant.now(),
					initialSession.version() + 1L
			);
			mcpServer.getSessionStore().replace(initialSession, terminatedSession);
			observeSessionTerminated(resolvedEndpoint.endpointClass(), sessionId,
					Duration.between(initialSession.createdAt(), terminatedSession.terminatedAt()),
					McpSessionTerminationReason.INITIALIZATION_FAILED, throwable);
			mcpServer.getSessionStore().deleteBySessionId(sessionId);
			return jsonRpcErrorResponse(request, parsedRequest.requestId(), error);
		}
	}

	@NonNull
	private RequestResult handleInitializedNotification(@NonNull Request request,
																											@NonNull McpServer mcpServer,
																											@NonNull ParsedJsonRpcRequest parsedRequest,
																											@NonNull McpStoredSession storedSession,
																											@NonNull DefaultMcpRequestContext requestContext) {
		requireNonNull(request);
		requireNonNull(mcpServer);
		requireNonNull(parsedRequest);
		requireNonNull(storedSession);
		requireNonNull(requestContext);

		McpStoredSession updatedSession = new McpStoredSession(
				storedSession.sessionId(),
				storedSession.endpointClass(),
				storedSession.createdAt(),
				Instant.now(),
				storedSession.initialized(),
				true,
				storedSession.protocolVersion(),
				storedSession.clientCapabilities(),
				storedSession.negotiatedCapabilities(),
				storedSession.sessionContext(),
				storedSession.terminatedAt(),
				storedSession.version() + 1L
		);

		if (!mcpServer.getSessionStore().replace(storedSession, updatedSession))
			return jsonRpcErrorResponse(request, parsedRequest.requestId(), McpJsonRpcError.fromCodeAndMessage(-32603, "Internal error"));

		return parsedRequest.requestId() == null
				? emptyAcceptedResponse(request)
				: jsonRpcSuccessResponse(request, parsedRequest.requestId(), EMPTY_OBJECT, Map.of());
	}

	@NonNull
	private RequestResult handlePing(@NonNull Request request,
																	 @NonNull McpServer mcpServer,
																	 @NonNull ParsedJsonRpcRequest parsedRequest,
																	 @Nullable McpStoredSession storedSession) {
		requireNonNull(request);
		requireNonNull(mcpServer);
		requireNonNull(parsedRequest);

		if (storedSession != null)
			touchSession(mcpServer, storedSession);

		return parsedRequest.requestId() == null
				? emptyAcceptedResponse(request)
				: jsonRpcSuccessResponse(request, parsedRequest.requestId(), EMPTY_OBJECT, Map.of());
	}

	@NonNull
	private RequestResult handleToolsList(@NonNull Request request,
																				@NonNull ResolvedEndpoint resolvedEndpoint,
																				@NonNull ParsedJsonRpcRequest parsedRequest,
																				@NonNull McpStoredSession storedSession,
																				@NonNull DefaultMcpRequestContext requestContext) {
		requireNonNull(request);
		requireNonNull(resolvedEndpoint);
		requireNonNull(parsedRequest);
		requireNonNull(storedSession);
		requireNonNull(requestContext);

		RequestResult gateResult = ensureSessionReady(request, storedSession, parsedRequest.requestId());

		if (gateResult != null)
			return gateResult;

		touchSession(getMcpServer(), storedSession);

		List<Map.Entry<String, ToolBinding>> toolBindings = new ArrayList<>(resolvedEndpoint.toolsByName().entrySet());
		toolBindings.sort(Map.Entry.comparingByKey());
		List<McpValue> tools = new ArrayList<>(toolBindings.size());

		for (Map.Entry<String, ToolBinding> toolEntry : toolBindings) {
			ToolBinding toolBinding = toolEntry.getValue();
			McpSchema schema = schemaForToolBinding(toolBinding);
			Map<String, McpValue> tool = new LinkedHashMap<>();
			tool.put("name", new McpString(toolBinding.name()));
			tool.put("description", new McpString(toolBinding.description()));
			tool.put("inputSchema", schema.toValue());
			tools.add(new McpObject(tool));
		}

		return jsonRpcSuccessResponse(request, parsedRequest.requestId(),
				new McpObject(Map.of("tools", new McpArray(tools))), Map.of());
	}

	@NonNull
	private RequestResult handlePromptsList(@NonNull Request request,
																					@NonNull ResolvedEndpoint resolvedEndpoint,
																					@NonNull ParsedJsonRpcRequest parsedRequest,
																					@NonNull McpStoredSession storedSession,
																					@NonNull DefaultMcpRequestContext requestContext) {
		requireNonNull(request);
		requireNonNull(resolvedEndpoint);
		requireNonNull(parsedRequest);
		requireNonNull(storedSession);
		requireNonNull(requestContext);

		RequestResult gateResult = ensureSessionReady(request, storedSession, parsedRequest.requestId());

		if (gateResult != null)
			return gateResult;

		touchSession(getMcpServer(), storedSession);

		List<Map.Entry<String, PromptBinding>> promptBindings = new ArrayList<>(resolvedEndpoint.promptsByName().entrySet());
		promptBindings.sort(Map.Entry.comparingByKey());
		List<McpValue> prompts = new ArrayList<>(promptBindings.size());

		for (Map.Entry<String, PromptBinding> promptEntry : promptBindings) {
			PromptBinding promptBinding = promptEntry.getValue();
			Map<String, McpValue> prompt = new LinkedHashMap<>();
			prompt.put("name", new McpString(promptBinding.name()));
			prompt.put("description", new McpString(promptBinding.description()));

			Optional<String> title = Optional.empty();
			McpSchema schema;

			if (promptBinding instanceof AnnotatedPromptBinding annotatedPromptBinding) {
				title = Optional.ofNullable(annotatedPromptBinding.title());
				schema = schemaForMethod(annotatedPromptBinding.method());
			} else if (promptBinding instanceof ProgrammaticPromptBinding programmaticPromptBinding) {
				title = programmaticPromptBinding.promptHandler().getTitle();
				schema = programmaticPromptBinding.promptHandler().getArgumentsSchema();
			} else {
				throw new IllegalStateException("Unsupported prompt binding type: %s".formatted(promptBinding.getClass().getName()));
			}

			title.ifPresent(value -> prompt.put("title", new McpString(value)));
			prompt.put("arguments", promptArgumentsForSchema(schema));
			prompts.add(new McpObject(prompt));
		}

		return jsonRpcSuccessResponse(request, parsedRequest.requestId(),
				new McpObject(Map.of("prompts", new McpArray(prompts))), Map.of());
	}

	@NonNull
	private RequestResult handleToolCall(@NonNull Request request,
																			 @NonNull ResolvedEndpoint resolvedEndpoint,
																			 @NonNull Map<String, String> endpointPathParameters,
																			 @NonNull ParsedJsonRpcRequest parsedRequest,
																			 @NonNull McpStoredSession storedSession,
																			 @NonNull DefaultMcpRequestContext requestContext) throws Exception {
		requireNonNull(request);
		requireNonNull(resolvedEndpoint);
		requireNonNull(endpointPathParameters);
		requireNonNull(parsedRequest);
		requireNonNull(storedSession);
		requireNonNull(requestContext);

		RequestResult gateResult = ensureSessionReady(request, storedSession, parsedRequest.requestId());

		if (gateResult != null)
			return gateResult;

		String toolName = requiredString(parsedRequest.params(), "name");
		McpObject arguments = optionalArgumentsObject(parsedRequest.params(), "arguments");
		ToolBinding toolBinding = resolvedEndpoint.toolsByName().get(toolName);

		if (toolBinding == null)
			return jsonRpcErrorResponse(request, parsedRequest.requestId(),
					McpJsonRpcError.fromCodeAndMessage(-32602, "Unknown tool '%s'".formatted(toolName)));

		validateArgumentsAgainstSchema(arguments, schemaForToolBinding(toolBinding));
		touchSession(getMcpServer(), storedSession);

		DefaultMcpProgressReporter progressReporter = progressReporter(parsedRequest);
		DefaultMcpToolCallContext toolCallContext = new DefaultMcpToolCallContext(requestContext, Optional.ofNullable(progressReporter));
		McpToolResult toolResult;

		try {
			toolResult = invokeToolBinding(resolvedEndpoint, toolBinding, endpointPathParameters, storedSession.sessionContext(),
					requireClientCapabilities(storedSession), arguments, toolCallContext);
		} catch (JsonRpcErrorTransport transport) {
			if (progressReporter != null && progressReporter.hasMessages()) {
				List<McpObject> messages = new ArrayList<>(progressReporter.messages());
				messages.add(jsonRpcErrorEnvelope(
						transport.requestId() == null ? parsedRequest.requestId() : transport.requestId(),
						transport.error()));
				progressReporter.markCompleted();
				return jsonRpcEventStreamResponse(request, messages, true);
			}

			if (progressReporter != null)
				progressReporter.markCompleted();

			throw transport;
		} catch (Exception e) {
			McpToolResult errorResult = endpointInstance(resolvedEndpoint.endpointClass()).handleToolError(e, toolCallContext);

			if (errorResult == null)
				throw new IllegalStateException("%s::handleToolError returned null".formatted(resolvedEndpoint.endpointClass().getName()));

			toolResult = errorResult;
		}

		McpValue result = toolResultValue(resolvedEndpoint.endpointClass(), toolBinding.name(), storedSession.sessionContext(), toolCallContext, toolResult);
		McpObject terminalEnvelope = jsonRpcSuccessEnvelope(parsedRequest.requestId(), result);

		if (progressReporter == null || !progressReporter.hasMessages()) {
			if (progressReporter != null)
				progressReporter.markCompleted();

			return jsonRpcSuccessResponse(request, parsedRequest.requestId(), result, Map.of());
		}

		List<McpObject> messages = new ArrayList<>(progressReporter.messages());
		messages.add(terminalEnvelope);
		progressReporter.markCompleted();
		return jsonRpcEventStreamResponse(request, messages, true);
	}

	@NonNull
	private RequestResult handlePromptGet(@NonNull Request request,
																				@NonNull ResolvedEndpoint resolvedEndpoint,
																				@NonNull Map<String, String> endpointPathParameters,
																				@NonNull ParsedJsonRpcRequest parsedRequest,
																				@NonNull McpStoredSession storedSession,
																				@NonNull DefaultMcpRequestContext requestContext) throws Exception {
		requireNonNull(request);
		requireNonNull(resolvedEndpoint);
		requireNonNull(endpointPathParameters);
		requireNonNull(parsedRequest);
		requireNonNull(storedSession);
		requireNonNull(requestContext);

		RequestResult gateResult = ensureSessionReady(request, storedSession, parsedRequest.requestId());

		if (gateResult != null)
			return gateResult;

		String promptName = requiredString(parsedRequest.params(), "name");
		McpObject arguments = optionalArgumentsObject(parsedRequest.params(), "arguments");
		PromptBinding promptBinding = resolvedEndpoint.promptsByName().get(promptName);

		if (promptBinding == null)
			return jsonRpcErrorResponse(request, parsedRequest.requestId(),
					McpJsonRpcError.fromCodeAndMessage(-32602, "Unknown prompt '%s'".formatted(promptName)));

		validateArgumentsAgainstSchema(arguments, schemaForPromptBinding(promptBinding));
		touchSession(getMcpServer(), storedSession);

		McpPromptResult promptResult;

		try {
			promptResult = invokePromptBinding(resolvedEndpoint, promptBinding, endpointPathParameters, storedSession.sessionContext(),
					requireClientCapabilities(storedSession), arguments, requestContext);
		} catch (JsonRpcErrorTransport transport) {
			throw transport;
		} catch (Exception e) {
			McpJsonRpcError error = endpointInstance(resolvedEndpoint.endpointClass()).handleError(e, requestContext);
			return jsonRpcErrorResponse(request, parsedRequest.requestId(), error);
		}

		return jsonRpcSuccessResponse(request, parsedRequest.requestId(), promptResultValue(promptBinding, promptResult), Map.of());
	}

	@NonNull
	private RequestResult handleResourcesList(@NonNull Request request,
																						@NonNull ResolvedEndpoint resolvedEndpoint,
																						@NonNull Map<String, String> endpointPathParameters,
																						@NonNull ParsedJsonRpcRequest parsedRequest,
																						@NonNull McpStoredSession storedSession,
																						@NonNull DefaultMcpRequestContext requestContext) throws Exception {
		requireNonNull(request);
		requireNonNull(resolvedEndpoint);
		requireNonNull(endpointPathParameters);
		requireNonNull(parsedRequest);
		requireNonNull(storedSession);
		requireNonNull(requestContext);

		RequestResult gateResult = ensureSessionReady(request, storedSession, parsedRequest.requestId());

		if (gateResult != null)
			return gateResult;

		touchSession(getMcpServer(), storedSession);

		Optional<String> cursor = optionalString(parsedRequest.params(), "cursor");
		DefaultMcpListResourcesContext listResourcesContext = new DefaultMcpListResourcesContext(requestContext, cursor);
		McpListResourcesResult listResourcesResult = resolvedEndpoint.resourceListBinding() == null
				? literalFallbackResourcesListResult(resolvedEndpoint)
				: invokeResourcesListBinding(resolvedEndpoint, resolvedEndpoint.resourceListBinding(), endpointPathParameters, storedSession.sessionContext(), listResourcesContext);

		List<McpValue> resources = new ArrayList<>(listResourcesResult.resources().size());

		for (McpListedResource resource : listResourcesResult.resources()) {
			Map<String, McpValue> resourceValue = new LinkedHashMap<>();
			resourceValue.put("uri", new McpString(resource.uri()));
			resourceValue.put("name", new McpString(resource.name()));
			resourceValue.put("mimeType", new McpString(resource.mimeType()));

			if (resource.title() != null)
				resourceValue.put("title", new McpString(resource.title()));

			if (resource.description() != null)
				resourceValue.put("description", new McpString(resource.description()));

			if (resource.sizeBytes() != null)
				resourceValue.put("size", new McpNumber(new BigDecimal(resource.sizeBytes())));

			resources.add(new McpObject(resourceValue));
		}

		Map<String, McpValue> result = new LinkedHashMap<>();
		result.put("resources", new McpArray(resources));

		if (listResourcesResult.nextCursor() != null)
			result.put("nextCursor", new McpString(listResourcesResult.nextCursor()));

		return jsonRpcSuccessResponse(request, parsedRequest.requestId(), new McpObject(result), Map.of());
	}

	@NonNull
	private RequestResult handleResourceTemplatesList(@NonNull Request request,
																								 @NonNull ResolvedEndpoint resolvedEndpoint,
																								 @NonNull ParsedJsonRpcRequest parsedRequest,
																								 @NonNull McpStoredSession storedSession,
																								 @NonNull DefaultMcpRequestContext requestContext) {
		requireNonNull(request);
		requireNonNull(resolvedEndpoint);
		requireNonNull(parsedRequest);
		requireNonNull(storedSession);
		requireNonNull(requestContext);

		RequestResult gateResult = ensureSessionReady(request, storedSession, parsedRequest.requestId());

		if (gateResult != null)
			return gateResult;

		touchSession(getMcpServer(), storedSession);

		List<McpValue> resourceTemplates = new ArrayList<>();

		for (ResourceBinding resourceBinding : resolvedEndpoint.resourcesByUri().values()) {
			if (!isUriTemplate(resourceBinding.uri()))
				continue;

			Map<String, McpValue> templateValue = new LinkedHashMap<>();
			templateValue.put("uriTemplate", new McpString(resourceBinding.uri()));
			templateValue.put("name", new McpString(resourceBinding.name()));
			templateValue.put("mimeType", new McpString(resourceBinding.mimeType()));

			if (resourceBinding.optionalDescription().isPresent())
				templateValue.put("description", new McpString(resourceBinding.optionalDescription().orElseThrow()));

			resourceTemplates.add(new McpObject(templateValue));
		}

		Map<String, McpValue> result = new LinkedHashMap<>();
		result.put("resourceTemplates", new McpArray(resourceTemplates));
		return jsonRpcSuccessResponse(request, parsedRequest.requestId(), new McpObject(result), Map.of());
	}

	@NonNull
	private RequestResult handleResourceRead(@NonNull Request request,
																					 @NonNull ResolvedEndpoint resolvedEndpoint,
																					 @NonNull Map<String, String> endpointPathParameters,
																					 @NonNull ParsedJsonRpcRequest parsedRequest,
																					 @NonNull McpStoredSession storedSession,
																					 @NonNull DefaultMcpRequestContext requestContext) throws Exception {
		requireNonNull(request);
		requireNonNull(resolvedEndpoint);
		requireNonNull(endpointPathParameters);
		requireNonNull(parsedRequest);
		requireNonNull(storedSession);
		requireNonNull(requestContext);

		RequestResult gateResult = ensureSessionReady(request, storedSession, parsedRequest.requestId());

		if (gateResult != null)
			return gateResult;

		String requestedUri = requiredString(parsedRequest.params(), "uri");
		MatchedResourceBinding matchedResourceBinding = matchResourceBinding(resolvedEndpoint, requestedUri).orElse(null);

		if (matchedResourceBinding == null)
			return jsonRpcErrorResponse(request, parsedRequest.requestId(),
					McpJsonRpcError.fromCodeAndMessage(-32602, "Unknown resource '%s'".formatted(requestedUri)));

		touchSession(getMcpServer(), storedSession);

		McpResourceContents resourceContents;

		try {
			resourceContents = invokeResourceBinding(resolvedEndpoint, matchedResourceBinding.binding(), endpointPathParameters,
					matchedResourceBinding.uriParameters(), storedSession.sessionContext(), requestedUri, requestContext);
		} catch (JsonRpcErrorTransport transport) {
			throw transport;
		} catch (Exception e) {
			McpJsonRpcError error = endpointInstance(resolvedEndpoint.endpointClass()).handleError(e, requestContext);
			return jsonRpcErrorResponse(request, parsedRequest.requestId(), error);
		}

		return jsonRpcSuccessResponse(request, parsedRequest.requestId(),
				new McpObject(Map.of("contents", new McpArray(List.of(resourceContentsValue(resourceContents))))), Map.of());
	}

	@NonNull
	private RequestResult handleDeleteRequest(@NonNull Request request,
																						@NonNull McpServer mcpServer,
																						@NonNull ResolvedEndpoint resolvedEndpoint) throws Exception {
		requireNonNull(request);
		requireNonNull(mcpServer);
		requireNonNull(resolvedEndpoint);

		String sessionId = request.getHeader("MCP-Session-Id").orElse(null);
		String protocolVersionHeader = request.getHeader("MCP-Protocol-Version").orElse(null);

		if (sessionId == null)
			return plainTextResponse(request, 400, "Missing MCP-Session-Id header.");

		if (protocolVersionHeader == null)
			return plainTextResponse(request, 400, "Missing MCP-Protocol-Version header.");

		McpStoredSession storedSession = mcpServer.getSessionStore().findBySessionId(sessionId).orElse(null);

		if (storedSession == null) {
			observeIdleExpiredSessionIfPresent(mcpServer, sessionId);
			return plainTextResponse(request, 404, "Unknown MCP session.");
		}

		if (storedSession.terminatedAt() != null || !storedSession.endpointClass().equals(resolvedEndpoint.endpointClass()))
			return plainTextResponse(request, 404, "Unknown MCP session.");

		if (!Objects.equals(storedSession.protocolVersion(), protocolVersionHeader))
			return plainTextResponse(request, 400, "MCP-Protocol-Version does not match the negotiated session version.");

		Optional<Response> admissionResponse = mcpServer.getRequestAdmissionPolicy().checkRequest(
				new DefaultMcpAdmissionContext(
						request,
						request.getHttpMethod(),
						resolvedEndpoint.endpointClass(),
						Optional.empty(),
						Optional.empty(),
						Optional.empty(),
						Optional.of(sessionId)
				));

		if (admissionResponse.isPresent())
			return httpResponse(request, admissionResponse.get());

		Instant terminatedAt = Instant.now();
		McpStoredSession terminatedSession = new McpStoredSession(
				storedSession.sessionId(),
				storedSession.endpointClass(),
				storedSession.createdAt(),
				terminatedAt,
				storedSession.initialized(),
				storedSession.initializedNotificationReceived(),
				storedSession.protocolVersion(),
				storedSession.clientCapabilities(),
				storedSession.negotiatedCapabilities(),
				storedSession.sessionContext(),
				terminatedAt,
				storedSession.version() + 1L
		);

		if (!mcpServer.getSessionStore().replace(storedSession, terminatedSession))
			return plainTextResponse(request, 404, "Unknown MCP session.");

		observeSessionTerminated(resolvedEndpoint.endpointClass(), sessionId,
				Duration.between(storedSession.createdAt(), terminatedAt),
				McpSessionTerminationReason.CLIENT_REQUESTED, null);
		terminateStreamsForSession(request, resolvedEndpoint.endpointClass(), sessionId, McpStreamTerminationReason.SESSION_TERMINATED, null);
		mcpServer.getSessionStore().deleteBySessionId(sessionId);
		return requestResultFromMarshaledResponse(request, MarshaledResponse.withStatusCode(204).build());
	}

	@Nullable
	private RequestResult ensureSessionReady(@NonNull Request request,
																					 @NonNull McpStoredSession storedSession,
																					 @Nullable McpJsonRpcRequestId requestId) {
		requireNonNull(request);
		requireNonNull(storedSession);

		if (!storedSession.initialized())
			return jsonRpcErrorResponse(request, requestId, McpJsonRpcError.fromCodeAndMessage(-32603, "Session not initialized"));

		if (!storedSession.initializedNotificationReceived())
			return jsonRpcErrorResponse(request, requestId, McpJsonRpcError.fromCodeAndMessage(-32600, "Session has not received notifications/initialized yet"));

		return null;
	}

	private void touchSession(@NonNull McpServer mcpServer,
														@NonNull McpStoredSession storedSession) {
		requireNonNull(mcpServer);
		requireNonNull(storedSession);

		McpStoredSession updatedSession = new McpStoredSession(
				storedSession.sessionId(),
				storedSession.endpointClass(),
				storedSession.createdAt(),
				Instant.now(),
				storedSession.initialized(),
				storedSession.initializedNotificationReceived(),
				storedSession.protocolVersion(),
				storedSession.clientCapabilities(),
				storedSession.negotiatedCapabilities(),
				storedSession.sessionContext(),
				storedSession.terminatedAt(),
				storedSession.version() + 1L
		);

		// Lost CAS here is acceptable: a concurrent successful replace also implies session activity.
		mcpServer.getSessionStore().replace(storedSession, updatedSession);
	}

	@NonNull
	private String negotiateProtocolVersion(@NonNull String requestedProtocolVersion) {
		requireNonNull(requestedProtocolVersion);
		return SUPPORTED_PROTOCOL_VERSION;
	}

	@NonNull
	private McpNegotiatedCapabilities negotiatedCapabilitiesFor(@NonNull ResolvedEndpoint resolvedEndpoint) {
		requireNonNull(resolvedEndpoint);

		Map<String, McpValue> capabilities = new LinkedHashMap<>();

		if (!resolvedEndpoint.toolsByName().isEmpty())
			capabilities.put("tools", EMPTY_OBJECT);

		if (!resolvedEndpoint.promptsByName().isEmpty())
			capabilities.put("prompts", EMPTY_OBJECT);

		if (resolvedEndpoint.resourceListBinding() != null || !resolvedEndpoint.resourcesByUri().isEmpty())
			capabilities.put("resources", EMPTY_OBJECT);

		return new McpNegotiatedCapabilities(new McpObject(capabilities));
	}

	@NonNull
	private McpListResourcesResult literalFallbackResourcesListResult(@NonNull ResolvedEndpoint resolvedEndpoint) {
		requireNonNull(resolvedEndpoint);

		List<McpListedResource> resources = new ArrayList<>();

		for (ResourceBinding resourceBinding : resolvedEndpoint.resourcesByUri().values()) {
			if (isUriTemplate(resourceBinding.uri()))
				continue;

			McpListedResource listedResource = McpListedResource.fromComponents(
					resourceBinding.uri(),
					resourceBinding.name(),
					resourceBinding.mimeType());

			if (resourceBinding.optionalDescription().isPresent())
				listedResource = listedResource.withDescription(resourceBinding.optionalDescription().orElseThrow());

			resources.add(listedResource);
		}

		return McpListResourcesResult.fromResources(resources);
	}

	private void observeIdleExpiredSessionIfPresent(@NonNull McpServer mcpServer,
																								 @NonNull String sessionId) {
		requireNonNull(mcpServer);
		requireNonNull(sessionId);

		if (!(mcpServer.getSessionStore() instanceof DefaultMcpSessionStore defaultMcpSessionStore))
			return;

		defaultMcpSessionStore.takeExpiredSession(sessionId).ifPresent(expiredSession ->
				observeSessionTerminated(expiredSession.endpointClass(),
						expiredSession.sessionId(),
						Duration.between(expiredSession.createdAt(), Instant.now()),
						McpSessionTerminationReason.IDLE_TIMEOUT,
						null));
	}

	@NonNull
	private McpListResourcesResult invokeResourcesListBinding(@NonNull ResolvedEndpoint resolvedEndpoint,
																														@NonNull ResourceListBinding resourceListBinding,
																														@NonNull Map<String, String> endpointPathParameters,
																														@NonNull McpSessionContext sessionContext,
																														@NonNull DefaultMcpListResourcesContext listResourcesContext) throws Exception {
		requireNonNull(resolvedEndpoint);
		requireNonNull(resourceListBinding);
		requireNonNull(endpointPathParameters);
		requireNonNull(sessionContext);
		requireNonNull(listResourcesContext);

		if (resourceListBinding instanceof ProgrammaticResourceListBinding programmaticResourceListBinding)
			return programmaticResourceListBinding.resourceListHandler().handle(
					new DefaultMcpResourceListHandlerContext(listResourcesContext, sessionContext, endpointPathParameters,
							getSoklet().getSokletConfig().getValueConverterRegistry()));

		if (resourceListBinding instanceof AnnotatedResourceListBinding annotatedResourceListBinding) {
			Object endpointInstance = endpointInstance(resolvedEndpoint.endpointClass());
			Method method = annotatedResourceListBinding.method();
			List<Object> arguments = new ArrayList<>(method.getParameterCount());

			for (Parameter parameter : method.getParameters())
				arguments.add(valueForAnnotatedResourcesListParameter(parameter, endpointPathParameters, sessionContext, listResourcesContext));

			Object result = invokeMethod(endpointInstance, method, arguments);

			if (result instanceof McpListResourcesResult mcpListResourcesResult)
				return mcpListResourcesResult;

			throw new IllegalStateException("Expected McpListResourcesResult from %s::%s".formatted(
					method.getDeclaringClass().getName(), method.getName()));
		}

		throw new IllegalStateException("Unsupported resource list binding type: %s".formatted(resourceListBinding.getClass().getName()));
	}

	@NonNull
	private McpToolResult invokeToolBinding(@NonNull ResolvedEndpoint resolvedEndpoint,
																					@NonNull ToolBinding toolBinding,
																					@NonNull Map<String, String> endpointPathParameters,
																					@NonNull McpSessionContext sessionContext,
																					@NonNull McpClientCapabilities clientCapabilities,
																					@NonNull McpObject arguments,
																					@NonNull DefaultMcpToolCallContext toolCallContext) throws Exception {
		requireNonNull(resolvedEndpoint);
		requireNonNull(toolBinding);
		requireNonNull(endpointPathParameters);
		requireNonNull(sessionContext);
		requireNonNull(clientCapabilities);
		requireNonNull(arguments);
		requireNonNull(toolCallContext);

		if (toolBinding instanceof ProgrammaticToolBinding programmaticToolBinding)
			return programmaticToolBinding.toolHandler().handle(new DefaultMcpToolHandlerContext(
					toolCallContext,
					sessionContext,
					clientCapabilities,
					arguments,
					endpointPathParameters,
					getSoklet().getSokletConfig().getValueConverterRegistry()));

		if (toolBinding instanceof AnnotatedToolBinding annotatedToolBinding) {
			Object endpointInstance = endpointInstance(resolvedEndpoint.endpointClass());
			Method method = annotatedToolBinding.method();
			List<Object> argumentsForMethod = new ArrayList<>(method.getParameterCount());

			for (Parameter parameter : method.getParameters())
				argumentsForMethod.add(valueForAnnotatedToolParameter(parameter, endpointPathParameters, sessionContext, clientCapabilities, arguments, toolCallContext));

			Object result = invokeMethod(endpointInstance, method, argumentsForMethod);

			if (result instanceof McpToolResult mcpToolResult)
				return mcpToolResult;

			throw new IllegalStateException("Expected McpToolResult from %s::%s".formatted(
					method.getDeclaringClass().getName(), method.getName()));
		}

		throw new IllegalStateException("Unsupported tool binding type: %s".formatted(toolBinding.getClass().getName()));
	}

	@NonNull
	private McpPromptResult invokePromptBinding(@NonNull ResolvedEndpoint resolvedEndpoint,
																							@NonNull PromptBinding promptBinding,
																							@NonNull Map<String, String> endpointPathParameters,
																							@NonNull McpSessionContext sessionContext,
																							@NonNull McpClientCapabilities clientCapabilities,
																							@NonNull McpObject arguments,
																							@NonNull DefaultMcpRequestContext requestContext) throws Exception {
		requireNonNull(resolvedEndpoint);
		requireNonNull(promptBinding);
		requireNonNull(endpointPathParameters);
		requireNonNull(sessionContext);
		requireNonNull(clientCapabilities);
		requireNonNull(arguments);
		requireNonNull(requestContext);

		if (promptBinding instanceof ProgrammaticPromptBinding programmaticPromptBinding)
			return programmaticPromptBinding.promptHandler().handle(new DefaultMcpPromptHandlerContext(
					requestContext,
					sessionContext,
					clientCapabilities,
					arguments,
					endpointPathParameters,
					getSoklet().getSokletConfig().getValueConverterRegistry()));

		if (promptBinding instanceof AnnotatedPromptBinding annotatedPromptBinding) {
			Object endpointInstance = endpointInstance(resolvedEndpoint.endpointClass());
			Method method = annotatedPromptBinding.method();
			List<Object> argumentsForMethod = new ArrayList<>(method.getParameterCount());

			for (Parameter parameter : method.getParameters())
				argumentsForMethod.add(valueForAnnotatedPromptParameter(parameter, endpointPathParameters, sessionContext, clientCapabilities, arguments, requestContext));

			Object result = invokeMethod(endpointInstance, method, argumentsForMethod);

			if (result instanceof McpPromptResult mcpPromptResult)
				return mcpPromptResult;

			throw new IllegalStateException("Expected McpPromptResult from %s::%s".formatted(
					method.getDeclaringClass().getName(), method.getName()));
		}

		throw new IllegalStateException("Unsupported prompt binding type: %s".formatted(promptBinding.getClass().getName()));
	}

	@NonNull
	private McpResourceContents invokeResourceBinding(@NonNull ResolvedEndpoint resolvedEndpoint,
																										@NonNull ResourceBinding resourceBinding,
																										@NonNull Map<String, String> endpointPathParameters,
																										@NonNull Map<String, String> uriParameters,
																										@NonNull McpSessionContext sessionContext,
																										@NonNull String requestedUri,
																										@NonNull DefaultMcpRequestContext requestContext) throws Exception {
		requireNonNull(resolvedEndpoint);
		requireNonNull(resourceBinding);
		requireNonNull(endpointPathParameters);
		requireNonNull(uriParameters);
		requireNonNull(sessionContext);
		requireNonNull(requestedUri);
		requireNonNull(requestContext);

		if (resourceBinding instanceof ProgrammaticResourceBinding programmaticResourceBinding)
			return programmaticResourceBinding.resourceHandler().handle(new DefaultMcpResourceHandlerContext(
					requestContext,
					sessionContext,
					requestedUri,
					uriParameters,
					endpointPathParameters,
					getSoklet().getSokletConfig().getValueConverterRegistry()));

		if (resourceBinding instanceof AnnotatedResourceBinding annotatedResourceBinding) {
			Object endpointInstance = endpointInstance(resolvedEndpoint.endpointClass());
			Method method = annotatedResourceBinding.method();
			List<Object> argumentsForMethod = new ArrayList<>(method.getParameterCount());

			for (Parameter parameter : method.getParameters())
				argumentsForMethod.add(valueForAnnotatedResourceParameter(parameter, endpointPathParameters, uriParameters, sessionContext, requestContext));

			Object result = invokeMethod(endpointInstance, method, argumentsForMethod);

			if (result instanceof McpResourceContents mcpResourceContents)
				return mcpResourceContents;

			throw new IllegalStateException("Expected McpResourceContents from %s::%s".formatted(
					method.getDeclaringClass().getName(), method.getName()));
		}

		throw new IllegalStateException("Unsupported resource binding type: %s".formatted(resourceBinding.getClass().getName()));
	}

	@NonNull
	private Object valueForAnnotatedResourcesListParameter(@NonNull Parameter parameter,
																												 @NonNull Map<String, String> endpointPathParameters,
																												 @NonNull McpSessionContext sessionContext,
																												 @NonNull DefaultMcpListResourcesContext listResourcesContext) {
		requireNonNull(parameter);
		requireNonNull(endpointPathParameters);
		requireNonNull(sessionContext);
		requireNonNull(listResourcesContext);

		McpEndpointPathParameter endpointPathParameter = parameter.getAnnotation(McpEndpointPathParameter.class);

		if (endpointPathParameter != null) {
			String parameterName = annotationValueOrParameterName(endpointPathParameter.value(), parameter);
			String value = endpointPathParameters.get(parameterName);

			if (value == null)
				throw new IllegalArgumentException("Missing endpoint path parameter '%s'".formatted(parameterName));

			return convertStringValue(value, parameter.getType());
		}

		if (McpListResourcesContext.class.equals(parameter.getType()))
			return listResourcesContext;

		if (McpRequestContext.class.equals(parameter.getType()))
			return listResourcesContext.requestContext();

		if (McpSessionContext.class.equals(parameter.getType()))
			return sessionContext;

		throw new IllegalArgumentException("Unsupported @McpListResources parameter type: %s".formatted(parameter.getType().getName()));
	}

	@NonNull
	private Object valueForAnnotatedToolParameter(@NonNull Parameter parameter,
																								@NonNull Map<String, String> endpointPathParameters,
																								@NonNull McpSessionContext sessionContext,
																								@NonNull McpClientCapabilities clientCapabilities,
																								@NonNull McpObject arguments,
																								@NonNull DefaultMcpToolCallContext toolCallContext) throws JsonRpcErrorTransport {
		requireNonNull(parameter);
		requireNonNull(endpointPathParameters);
		requireNonNull(sessionContext);
		requireNonNull(clientCapabilities);
		requireNonNull(arguments);
		requireNonNull(toolCallContext);

		if (McpToolCallContext.class.equals(parameter.getType()))
			return toolCallContext;

		if (McpRequestContext.class.equals(parameter.getType()))
			return toolCallContext.getRequestContext();

		if (McpSessionContext.class.equals(parameter.getType()))
			return sessionContext;

		if (McpClientCapabilities.class.equals(parameter.getType()))
			return clientCapabilities;

		McpEndpointPathParameter endpointPathParameter = parameter.getAnnotation(McpEndpointPathParameter.class);

		if (endpointPathParameter != null) {
			String parameterName = annotationValueOrParameterName(endpointPathParameter.value(), parameter);
			String value = endpointPathParameters.get(parameterName);

			if (value == null)
				throw invalidParams("Missing endpoint path parameter '%s'".formatted(parameterName));

			return valueForPathParameter(parameter, value);
		}

		McpArgument argument = parameter.getAnnotation(McpArgument.class);

		if (argument != null)
			return valueForAnnotatedArgument(parameter, argument, arguments);

		throw new IllegalArgumentException("Unsupported @McpTool parameter type: %s".formatted(parameter.getType().getName()));
	}

	@NonNull
	private Object valueForAnnotatedPromptParameter(@NonNull Parameter parameter,
																									@NonNull Map<String, String> endpointPathParameters,
																									@NonNull McpSessionContext sessionContext,
																									@NonNull McpClientCapabilities clientCapabilities,
																									@NonNull McpObject arguments,
																									@NonNull DefaultMcpRequestContext requestContext) throws JsonRpcErrorTransport {
		requireNonNull(parameter);
		requireNonNull(endpointPathParameters);
		requireNonNull(sessionContext);
		requireNonNull(clientCapabilities);
		requireNonNull(arguments);
		requireNonNull(requestContext);

		if (McpRequestContext.class.equals(parameter.getType()))
			return requestContext;

		if (McpSessionContext.class.equals(parameter.getType()))
			return sessionContext;

		if (McpClientCapabilities.class.equals(parameter.getType()))
			return clientCapabilities;

		McpEndpointPathParameter endpointPathParameter = parameter.getAnnotation(McpEndpointPathParameter.class);

		if (endpointPathParameter != null) {
			String parameterName = annotationValueOrParameterName(endpointPathParameter.value(), parameter);
			String value = endpointPathParameters.get(parameterName);

			if (value == null)
				throw invalidParams("Missing endpoint path parameter '%s'".formatted(parameterName));

			return valueForPathParameter(parameter, value);
		}

		McpArgument argument = parameter.getAnnotation(McpArgument.class);

		if (argument != null)
			return valueForAnnotatedArgument(parameter, argument, arguments);

		throw new IllegalArgumentException("Unsupported @McpPrompt parameter type: %s".formatted(parameter.getType().getName()));
	}

	@NonNull
	private Object valueForAnnotatedResourceParameter(@NonNull Parameter parameter,
																										@NonNull Map<String, String> endpointPathParameters,
																										@NonNull Map<String, String> uriParameters,
																										@NonNull McpSessionContext sessionContext,
																										@NonNull DefaultMcpRequestContext requestContext) throws JsonRpcErrorTransport {
		requireNonNull(parameter);
		requireNonNull(endpointPathParameters);
		requireNonNull(uriParameters);
		requireNonNull(sessionContext);
		requireNonNull(requestContext);

		if (McpRequestContext.class.equals(parameter.getType()))
			return requestContext;

		if (McpSessionContext.class.equals(parameter.getType()))
			return sessionContext;

		McpEndpointPathParameter endpointPathParameter = parameter.getAnnotation(McpEndpointPathParameter.class);

		if (endpointPathParameter != null) {
			String parameterName = annotationValueOrParameterName(endpointPathParameter.value(), parameter);
			String value = endpointPathParameters.get(parameterName);

			if (value == null)
				throw invalidParams("Missing endpoint path parameter '%s'".formatted(parameterName));

			return valueForPathParameter(parameter, value);
		}

		McpUriParameter uriParameter = parameter.getAnnotation(McpUriParameter.class);

		if (uriParameter != null) {
			String parameterName = annotationValueOrParameterName(uriParameter.value(), parameter);
			String value = uriParameters.get(parameterName);

			if (value == null)
				throw invalidParams("Missing resource URI parameter '%s'".formatted(parameterName));

			return valueForPathParameter(parameter, value);
		}

		throw new IllegalArgumentException("Unsupported @McpResource parameter type: %s".formatted(parameter.getType().getName()));
	}

	@NonNull
	private Object valueForAnnotatedArgument(@NonNull Parameter parameter,
																					 @NonNull McpArgument argument,
																					 @NonNull McpObject arguments) throws JsonRpcErrorTransport {
		requireNonNull(parameter);
		requireNonNull(argument);
		requireNonNull(arguments);

		String argumentName = annotationValueOrParameterName(argument.value(), parameter);
		McpValue value = arguments.get(argumentName).orElse(null);
		boolean optional = argument.optional();
		Class<?> parameterType = parameter.getType();
		Type genericType = parameter.getParameterizedType();

		if (Optional.class.equals(parameterType)) {
			if (!(genericType instanceof ParameterizedType parameterizedType))
				throw invalidParams("Optional MCP argument '%s' is missing a generic type".formatted(argumentName));

			Type optionalType = parameterizedType.getActualTypeArguments()[0];

			if (!(optionalType instanceof Class<?> optionalTypeClass))
				throw invalidParams("Unsupported Optional MCP argument type for '%s'".formatted(argumentName));

			if (value == null || value instanceof McpNull)
				return Optional.empty();

			return Optional.of(convertMcpValue(value, optionalTypeClass, argumentName));
		}

		if (value == null || value instanceof McpNull) {
			if (!optional)
				throw invalidParams("Missing required argument '%s'".formatted(argumentName));

			if (parameterType.isPrimitive())
				throw invalidParams("Argument '%s' cannot be null".formatted(argumentName));

			return null;
		}

		return convertMcpValue(value, parameterType, argumentName);
	}

	@NonNull
	private Object valueForPathParameter(@NonNull Parameter parameter,
																			 @NonNull String value) {
		requireNonNull(parameter);
		requireNonNull(value);

		if (Optional.class.equals(parameter.getType())) {
			Type genericType = parameter.getParameterizedType();

			if (genericType instanceof ParameterizedType parameterizedType
					&& parameterizedType.getActualTypeArguments()[0] instanceof Class<?> optionalTypeClass)
				return Optional.of(convertStringValue(value, optionalTypeClass));
		}

		return convertStringValue(value, parameter.getType());
	}

	@NonNull
	private McpEndpoint endpointInstance(@NonNull Class<? extends McpEndpoint> endpointClass) {
		requireNonNull(endpointClass);
		return getSoklet().getSokletConfig().getInstanceProvider().provide(endpointClass);
	}

	@NonNull
	private McpSchema schemaForToolBinding(@NonNull ToolBinding toolBinding) {
		requireNonNull(toolBinding);

		if (toolBinding instanceof ProgrammaticToolBinding programmaticToolBinding)
			return programmaticToolBinding.toolHandler().getInputSchema();

		if (toolBinding instanceof AnnotatedToolBinding annotatedToolBinding)
			return schemaForMethod(annotatedToolBinding.method());

		throw new IllegalStateException("Unsupported tool binding type: %s".formatted(toolBinding.getClass().getName()));
	}

	@NonNull
	private McpSchema schemaForPromptBinding(@NonNull PromptBinding promptBinding) {
		requireNonNull(promptBinding);

		if (promptBinding instanceof ProgrammaticPromptBinding programmaticPromptBinding)
			return programmaticPromptBinding.promptHandler().getArgumentsSchema();

		if (promptBinding instanceof AnnotatedPromptBinding annotatedPromptBinding)
			return schemaForMethod(annotatedPromptBinding.method());

		throw new IllegalStateException("Unsupported prompt binding type: %s".formatted(promptBinding.getClass().getName()));
	}

	@NonNull
	private McpSchema schemaForMethod(@NonNull Method method) {
		requireNonNull(method);

		McpSchema.ObjectBuilder schema = McpSchema.object();

		for (Parameter parameter : method.getParameters()) {
			McpArgument argument = parameter.getAnnotation(McpArgument.class);

			if (argument == null)
				continue;

			String argumentName = annotationValueOrParameterName(argument.value(), parameter);
			boolean optional = argument.optional();
			Class<?> argumentType = parameter.getType();
			Type genericType = parameter.getParameterizedType();

			if (Optional.class.equals(argumentType) && genericType instanceof ParameterizedType parameterizedType) {
				Type optionalType = parameterizedType.getActualTypeArguments()[0];

				if (optionalType instanceof Class<?> optionalTypeClass)
					argumentType = optionalTypeClass;

				optional = true;
			}

			if (argumentType.isEnum()) {
				String[] enumValues = new String[argumentType.getEnumConstants().length];

				for (int i = 0; i < argumentType.getEnumConstants().length; i++)
					enumValues[i] = ((Enum<?>) argumentType.getEnumConstants()[i]).name();

				if (optional)
					schema.optionalEnum(argumentName, enumValues);
				else
					schema.requiredEnum(argumentName, enumValues);

				continue;
			}

			McpType mcpType = toMcpType(argumentType);

			if (optional)
				schema.optional(argumentName, mcpType);
			else
				schema.required(argumentName, mcpType);
		}

		return schema.build();
	}

	@NonNull
	private McpType toMcpType(@NonNull Class<?> type) {
		requireNonNull(type);

		if (String.class.equals(type))
			return McpType.STRING;

		if (Boolean.class.equals(type) || Boolean.TYPE.equals(type))
			return McpType.BOOLEAN;

		if (java.util.UUID.class.equals(type))
			return McpType.UUID;

		if (Byte.class.equals(type) || Byte.TYPE.equals(type)
				|| Short.class.equals(type) || Short.TYPE.equals(type)
				|| Integer.class.equals(type) || Integer.TYPE.equals(type)
				|| Long.class.equals(type) || Long.TYPE.equals(type)
				|| java.math.BigInteger.class.equals(type))
			return McpType.INTEGER;

		if (Float.class.equals(type) || Float.TYPE.equals(type)
				|| Double.class.equals(type) || Double.TYPE.equals(type)
				|| BigDecimal.class.equals(type))
			return McpType.NUMBER;

		throw new IllegalArgumentException("Unsupported MCP argument type: %s".formatted(type.getName()));
	}

	@NonNull
	private McpArray promptArgumentsForSchema(@NonNull McpSchema schema) {
		requireNonNull(schema);

		McpObject schemaValue = schema.toValue();
		McpObject properties = (McpObject) schemaValue.get("properties").orElse(new McpObject(Map.of()));
		Set<String> requiredNames = new LinkedHashSet<>();
		McpArray required = (McpArray) schemaValue.get("required").orElse(new McpArray(List.of()));

		for (McpValue requiredValue : required.values()) {
			if (requiredValue instanceof McpString requiredName)
				requiredNames.add(requiredName.value());
		}

		List<McpValue> arguments = new ArrayList<>(properties.values().size());

		for (String argumentName : properties.values().keySet()) {
			Map<String, McpValue> argument = new LinkedHashMap<>();
			argument.put("name", new McpString(argumentName));
			argument.put("required", new McpBoolean(requiredNames.contains(argumentName)));
			arguments.add(new McpObject(argument));
		}

		return new McpArray(arguments);
	}

	private void validateArgumentsAgainstSchema(@NonNull McpObject arguments,
																							@NonNull McpSchema schema) throws JsonRpcErrorTransport {
		requireNonNull(arguments);
		requireNonNull(schema);

		McpObject schemaValue = schema.toValue();
		McpObject properties = optionalObject(schemaValue, "properties").orElse(new McpObject(Map.of()));
		McpValue additionalPropertiesValue = schemaValue.get("additionalProperties").orElse(McpNull.INSTANCE);
		boolean additionalPropertiesAllowed = !(additionalPropertiesValue instanceof McpBoolean mcpBoolean) || mcpBoolean.value();

		if (additionalPropertiesAllowed)
			return;

		for (String argumentName : arguments.values().keySet())
			if (!properties.values().containsKey(argumentName))
				throw invalidParams("Unexpected argument '%s'".formatted(argumentName));
	}

	@NonNull
	private McpObject initializeResultObject(@NonNull ResolvedEndpoint resolvedEndpoint,
																					 @NonNull String protocolVersion,
																					 @NonNull McpNegotiatedCapabilities negotiatedCapabilities) {
		requireNonNull(resolvedEndpoint);
		requireNonNull(protocolVersion);
		requireNonNull(negotiatedCapabilities);

		Map<String, McpValue> serverInfo = new LinkedHashMap<>();
		serverInfo.put("name", new McpString(resolvedEndpoint.name()));
		serverInfo.put("version", new McpString(resolvedEndpoint.version()));

		if (resolvedEndpoint.title() != null)
			serverInfo.put("title", new McpString(resolvedEndpoint.title()));

		if (resolvedEndpoint.description() != null)
			serverInfo.put("description", new McpString(resolvedEndpoint.description()));

		if (resolvedEndpoint.websiteUrl() != null)
			serverInfo.put("websiteUrl", new McpString(resolvedEndpoint.websiteUrl()));

		Map<String, McpValue> result = new LinkedHashMap<>();
		result.put("protocolVersion", new McpString(protocolVersion));
		result.put("capabilities", negotiatedCapabilities.value());
		result.put("serverInfo", new McpObject(serverInfo));

		if (resolvedEndpoint.instructions() != null)
			result.put("instructions", new McpString(resolvedEndpoint.instructions()));

		return new McpObject(result);
	}

	@NonNull
	private ParsedJsonRpcRequest parseJsonRpcRequest(@NonNull Request request) throws JsonRpcErrorTransport {
		requireNonNull(request);

		byte[] body = request.getBody().orElse(null);

		if (body == null || body.length == 0)
			throw new JsonRpcErrorTransport(null, McpJsonRpcError.fromCodeAndMessage(-32700, "Parse error"));

		McpValue bodyValue;

		try {
			bodyValue = McpJsonCodec.parse(body);
		} catch (IllegalArgumentException e) {
			throw new JsonRpcErrorTransport(null, McpJsonRpcError.fromCodeAndMessage(-32700, "Parse error"));
		}

		if (bodyValue instanceof McpArray)
			throw new JsonRpcErrorTransport(null, McpJsonRpcError.fromCodeAndMessage(-32600, "Invalid Request"));

		if (!(bodyValue instanceof McpObject requestObject))
			throw new JsonRpcErrorTransport(null, McpJsonRpcError.fromCodeAndMessage(-32600, "Invalid Request"));

		if (!"2.0".equals(optionalString(requestObject, "jsonrpc").orElse(null)))
			throw new JsonRpcErrorTransport(null, McpJsonRpcError.fromCodeAndMessage(-32600, "Invalid Request"));

		String method = optionalString(requestObject, "method").orElse(null);

		if (method == null || method.isBlank())
			throw new JsonRpcErrorTransport(null, McpJsonRpcError.fromCodeAndMessage(-32600, "Invalid Request"));

		McpJsonRpcRequestId requestId = requestIdFromValue(requestObject.get("id").orElse(null));
		McpObject params = optionalObject(requestObject, "params").orElse(EMPTY_OBJECT);
		return new ParsedJsonRpcRequest(method, operationKindForMethod(method).orElse(null), params, requestId);
	}

	@Nullable
	private static McpJsonRpcRequestId requestIdFromValue(@Nullable McpValue value) throws JsonRpcErrorTransport {
		if (value == null || value instanceof McpNull)
			return null;

		try {
			return new McpJsonRpcRequestId(value);
		} catch (IllegalArgumentException e) {
			throw new JsonRpcErrorTransport(null, McpJsonRpcError.fromCodeAndMessage(-32600, "Invalid Request"));
		}
	}

	@NonNull
	private Optional<McpOperationKind> operationKindForMethod(@NonNull String method) {
		requireNonNull(method);

		return switch (method) {
			case "initialize" -> Optional.of(McpOperationKind.INITIALIZE);
			case "notifications/initialized" -> Optional.of(McpOperationKind.NOTIFICATIONS_INITIALIZED);
			case "ping" -> Optional.of(McpOperationKind.PING);
			case "tools/list" -> Optional.of(McpOperationKind.TOOLS_LIST);
			case "tools/call" -> Optional.of(McpOperationKind.TOOLS_CALL);
			case "prompts/list" -> Optional.of(McpOperationKind.PROMPTS_LIST);
			case "prompts/get" -> Optional.of(McpOperationKind.PROMPTS_GET);
			case "resources/list" -> Optional.of(McpOperationKind.RESOURCES_LIST);
			case "resources/templates/list" -> Optional.of(McpOperationKind.RESOURCES_TEMPLATES_LIST);
			case "resources/read" -> Optional.of(McpOperationKind.RESOURCES_READ);
			default -> Optional.empty();
		};
	}

	@NonNull
	private RequestResult httpResponse(@NonNull Request request,
																		 @NonNull Response response) {
		requireNonNull(request);
		requireNonNull(response);
		return requestResultFromMarshaledResponse(request, marshaledResponseForResponse(response));
	}

	@NonNull
	private RequestResult emptyAcceptedResponse(@NonNull Request request) {
		requireNonNull(request);
		return requestResultFromMarshaledResponse(request, MarshaledResponse.withStatusCode(202).build());
	}

	@NonNull
	private RequestResult plainTextResponse(@NonNull Request request,
																					@NonNull Integer statusCode,
																					@NonNull String message) {
		requireNonNull(request);
		requireNonNull(statusCode);
		requireNonNull(message);

		return requestResultFromMarshaledResponse(request, MarshaledResponse.withStatusCode(statusCode)
				.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
				.body(message.getBytes(StandardCharsets.UTF_8))
				.build());
	}

	@NonNull
	private RequestResult jsonRpcSuccessResponse(@NonNull Request request,
																							 @Nullable McpJsonRpcRequestId requestId,
																							 @NonNull McpValue result,
																							 @NonNull Map<String, Set<String>> headers) {
		requireNonNull(request);
		requireNonNull(result);
		requireNonNull(headers);
		return requestResultFromMarshaledResponse(request, jsonResponse(jsonRpcSuccessEnvelope(requestId, result), headers));
	}

	@NonNull
	private RequestResult jsonRpcErrorResponse(@NonNull Request request,
																						 @Nullable McpJsonRpcRequestId requestId,
																						 @NonNull McpJsonRpcError error) {
		requireNonNull(request);
		requireNonNull(error);
		return requestResultFromMarshaledResponse(request, jsonResponse(jsonRpcErrorEnvelope(requestId, error), Map.of()));
	}

	@NonNull
	private MarshaledResponse jsonResponse(@NonNull McpObject envelope,
																				 @NonNull Map<String, Set<String>> headers) {
		requireNonNull(envelope);
		requireNonNull(headers);

		Map<String, Set<String>> finalHeaders = new LinkedHashMap<>(headers);
		finalHeaders.putIfAbsent("Content-Type", Set.of("application/json; charset=UTF-8"));
		return MarshaledResponse.withStatusCode(200)
				.headers(finalHeaders)
				.body(McpJsonCodec.toUtf8Bytes(envelope))
				.build();
	}

	@NonNull
	private RequestResult jsonRpcEventStreamResponse(@NonNull Request request,
																									 @NonNull List<@NonNull McpObject> messages,
																									 @NonNull Boolean closeAfterReplay) {
		requireNonNull(request);
		requireNonNull(messages);
		requireNonNull(closeAfterReplay);

		return requestResultFromMarshaledResponse(request, eventStreamResponse(messages))
				.copy()
				.mcpStreamMessages(messages)
				.mcpStreamClosedAfterReplay(closeAfterReplay)
				.finish();
	}

	@NonNull
	private MarshaledResponse eventStreamResponse() {
		return eventStreamResponse(List.of());
	}

	@NonNull
	private MarshaledResponse eventStreamResponse(@NonNull List<@NonNull McpObject> messages) {
		requireNonNull(messages);

		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of(
						"Content-Type", Set.of("text/event-stream; charset=UTF-8"),
						"Cache-Control", Set.of("no-cache")
				))
				.body(messages.isEmpty() ? null : encodeEventStreamMessages(messages))
				.build();
	}

	@NonNull
	private MarshaledResponse marshaledResponseForResponse(@NonNull Response response) {
		requireNonNull(response);

		byte[] bodyBytes = null;
		Object body = response.getBody().orElse(null);

		if (body instanceof byte[] bytes)
			bodyBytes = bytes;
		else if (body != null)
			bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

		Map<String, Set<String>> headers = new LinkedHashMap<>(response.getHeaders());

		if (!headers.containsKey("Content-Type") && bodyBytes != null)
			headers.put("Content-Type", Set.of("text/plain; charset=UTF-8"));

		return MarshaledResponse.withStatusCode(response.getStatusCode())
				.headers(headers)
				.cookies(response.getCookies())
				.body(bodyBytes)
				.build();
	}

	@NonNull
	private RequestResult requestResultFromMarshaledResponse(@NonNull Request request,
																													 @NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		return RequestResult.fromMarshaledResponse(getSoklet().applyCommonPropertiesToMarshaledResponse(request, marshaledResponse));
	}

	@NonNull
	private ObservedMcpResult observeMcpResult(@NonNull RequestResult requestResult,
																						 @NonNull ParsedJsonRpcRequest parsedJsonRpcRequest) {
		requireNonNull(requestResult);
		requireNonNull(parsedJsonRpcRequest);

		MarshaledResponse marshaledResponse = requestResult.getMarshaledResponse();

		if (Integer.valueOf(202).equals(marshaledResponse.getStatusCode()))
			return new ObservedMcpResult(McpRequestOutcome.SUCCESS_NOTIFICATION, null);

		McpObject terminalMessage = null;

		if (!requestResult.getMcpStreamMessages().isEmpty())
			terminalMessage = requestResult.getMcpStreamMessages().get(requestResult.getMcpStreamMessages().size() - 1);

		byte[] body = marshaledResponse.getBody().orElse(null);

		if (terminalMessage == null && (body == null || body.length == 0))
			return new ObservedMcpResult(McpRequestOutcome.SUCCESS_RESPONSE, null);

		try {
			McpValue parsedBody = terminalMessage == null ? McpJsonCodec.parse(body) : terminalMessage;

			if (!(parsedBody instanceof McpObject object))
				return new ObservedMcpResult(McpRequestOutcome.SUCCESS_RESPONSE, null);

			McpObject error = optionalObject(object, "error").orElse(null);

			if (error != null) {
				McpValue codeValue = error.get("code").orElse(null);
				McpValue messageValue = error.get("message").orElse(null);

				if (codeValue instanceof McpNumber code && messageValue instanceof McpString message) {
					return new ObservedMcpResult(McpRequestOutcome.JSON_RPC_ERROR,
							McpJsonRpcError.fromCodeAndMessage(code.value().intValueExact(), message.value()));
				}

				return new ObservedMcpResult(McpRequestOutcome.JSON_RPC_ERROR, null);
			}

			if (parsedJsonRpcRequest.operationKind() == McpOperationKind.TOOLS_CALL) {
				McpObject result = optionalObject(object, "result").orElse(null);

				if (result != null) {
					McpValue isErrorValue = result.get("isError").orElse(null);

					if (isErrorValue instanceof McpBoolean isError && isError.value())
						return new ObservedMcpResult(McpRequestOutcome.TOOL_ERROR_RESULT, null);
				}
			}
		} catch (RuntimeException ignored) {
			// If the body is not parseable as JSON-RPC, fall through to success classification.
		}

		return new ObservedMcpResult(McpRequestOutcome.SUCCESS_RESPONSE, null);
	}

	private void observeSessionCreated(@NonNull Request request,
																		 @NonNull Class<? extends McpEndpoint> endpointClass,
																		 @NonNull String sessionId) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(sessionId);

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didCreateMcpSession", MetricsCollector.class.getSimpleName()),
				request,
				metricsCollector -> metricsCollector.didCreateMcpSession(request, endpointClass, sessionId));
		safelyInvokeLifecycleObserver(LogEventType.LIFECYCLE_OBSERVER_DID_CREATE_MCP_SESSION_FAILED,
				format("An exception occurred while invoking %s::didCreateMcpSession", LifecycleObserver.class.getSimpleName()),
				request,
				null,
				lifecycleObserver -> lifecycleObserver.didCreateMcpSession(request, endpointClass, sessionId));
	}

	private void observeSessionTerminated(@NonNull Class<? extends McpEndpoint> endpointClass,
																				@NonNull String sessionId,
																				@NonNull Duration sessionDuration,
																				@NonNull McpSessionTerminationReason terminationReason,
																				@Nullable Throwable throwable) {
		requireNonNull(endpointClass);
		requireNonNull(sessionId);
		requireNonNull(sessionDuration);
		requireNonNull(terminationReason);

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didTerminateMcpSession", MetricsCollector.class.getSimpleName()),
				null,
				metricsCollector -> metricsCollector.didTerminateMcpSession(endpointClass, sessionId, sessionDuration, terminationReason, throwable));
		safelyInvokeLifecycleObserver(LogEventType.LIFECYCLE_OBSERVER_DID_TERMINATE_MCP_SESSION_FAILED,
				format("An exception occurred while invoking %s::didTerminateMcpSession", LifecycleObserver.class.getSimpleName()),
				null,
				null,
				lifecycleObserver -> lifecycleObserver.didTerminateMcpSession(endpointClass, sessionId, sessionDuration, terminationReason, throwable));
	}

	private void registerMcpStream(@NonNull Request request,
																 @NonNull Class<? extends McpEndpoint> endpointClass,
																 @NonNull String sessionId) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(sessionId);

		this.mcpStreamsBySessionId.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>())
				.add(new McpStreamState(request, endpointClass, sessionId, Instant.now()));

		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didEstablishMcpServerSentEventStream", MetricsCollector.class.getSimpleName()),
				request,
				metricsCollector -> metricsCollector.didEstablishMcpServerSentEventStream(request, endpointClass, sessionId));
		safelyInvokeLifecycleObserver(LogEventType.LIFECYCLE_OBSERVER_DID_ESTABLISH_MCP_SERVER_SENT_EVENT_STREAM_FAILED,
				format("An exception occurred while invoking %s::didEstablishMcpServerSentEventStream", LifecycleObserver.class.getSimpleName()),
				request,
				null,
				lifecycleObserver -> lifecycleObserver.didEstablishMcpServerSentEventStream(request, endpointClass, sessionId));
	}

	void handleClientDisconnectedStream(@NonNull Request request,
																			@NonNull String sessionId) {
		requireNonNull(request);
		requireNonNull(sessionId);

		handleTerminatedStream(request, sessionId, McpStreamTerminationReason.CLIENT_DISCONNECTED, null);
	}

	void handleTerminatedStream(@NonNull Request request,
															@NonNull String sessionId,
															@NonNull McpStreamTerminationReason terminationReason,
															@Nullable Throwable throwable) {
		requireNonNull(request);
		requireNonNull(sessionId);
		requireNonNull(terminationReason);

		CopyOnWriteArrayList<McpStreamState> streamStates = this.mcpStreamsBySessionId.get(sessionId);

		if (streamStates == null)
			return;

		McpStreamState matchedStreamState = null;

		for (McpStreamState streamState : streamStates) {
			if (streamState.request() == request) {
				matchedStreamState = streamState;
				break;
			}
		}

		if (matchedStreamState == null)
			return;

		McpStreamState matchedStreamStateSnapshot = matchedStreamState;

		this.mcpStreamsBySessionId.computeIfPresent(sessionId, (ignored, states) -> {
			states.remove(matchedStreamStateSnapshot);
			return states.isEmpty() ? null : states;
		});

		observeTerminatedMcpStream(matchedStreamState, terminationReason, throwable);
	}

	private void terminateStreamsForSession(@NonNull Request request,
																					@NonNull Class<? extends McpEndpoint> endpointClass,
																					@NonNull String sessionId,
																					@NonNull McpStreamTerminationReason terminationReason,
																					@Nullable Throwable throwable) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(sessionId);
		requireNonNull(terminationReason);

		List<McpStreamState> streamStates = this.mcpStreamsBySessionId.remove(sessionId);

		if (streamStates == null)
			return;

		for (McpStreamState streamState : streamStates)
			observeTerminatedMcpStream(streamState, terminationReason, throwable);
	}

	private void observeTerminatedMcpStream(@NonNull McpStreamState streamState,
																					@NonNull McpStreamTerminationReason terminationReason,
																					@Nullable Throwable throwable) {
		requireNonNull(streamState);
		requireNonNull(terminationReason);

		Duration streamDuration = Duration.between(streamState.establishedAt(), Instant.now());
		safelyInvokeLifecycleObserver(LogEventType.LIFECYCLE_OBSERVER_WILL_TERMINATE_MCP_SERVER_SENT_EVENT_STREAM_FAILED,
				format("An exception occurred while invoking %s::willTerminateMcpServerSentEventStream", LifecycleObserver.class.getSimpleName()),
				streamState.request(),
				null,
				lifecycleObserver -> lifecycleObserver.willTerminateMcpServerSentEventStream(
						streamState.request(),
						streamState.endpointClass(),
						streamState.sessionId(),
						terminationReason,
						throwable));
		safelyCollectMetrics(
				format("An exception occurred while invoking %s::didTerminateMcpServerSentEventStream", MetricsCollector.class.getSimpleName()),
				streamState.request(),
				metricsCollector -> metricsCollector.didTerminateMcpServerSentEventStream(
						streamState.request(),
						streamState.endpointClass(),
						streamState.sessionId(),
						streamDuration,
						terminationReason,
						throwable));
		safelyInvokeLifecycleObserver(LogEventType.LIFECYCLE_OBSERVER_DID_TERMINATE_MCP_SERVER_SENT_EVENT_STREAM_FAILED,
				format("An exception occurred while invoking %s::didTerminateMcpServerSentEventStream", LifecycleObserver.class.getSimpleName()),
				streamState.request(),
				null,
				lifecycleObserver -> lifecycleObserver.didTerminateMcpServerSentEventStream(
						streamState.request(),
						streamState.endpointClass(),
						streamState.sessionId(),
						streamDuration,
						terminationReason,
						throwable));
	}

	private void safelyCollectMetrics(@NonNull String message,
																		@Nullable Request request,
																		@NonNull Consumer<MetricsCollector> invocation) {
		requireNonNull(message);
		requireNonNull(invocation);

		try {
			invocation.accept(getSoklet().getSokletConfig().getMetricsCollector());
		} catch (Throwable throwable) {
			safelyLog(LogEvent.with(LogEventType.METRICS_COLLECTOR_FAILED, message)
					.throwable(throwable)
					.request(request)
					.build());
		}
	}

	private void safelyInvokeLifecycleObserver(@NonNull LogEventType logEventType,
																						 @NonNull String message,
																						 @Nullable Request request,
																						 @Nullable List<Throwable> throwables,
																						 @NonNull Consumer<LifecycleObserver> invocation) {
		requireNonNull(logEventType);
		requireNonNull(message);
		requireNonNull(invocation);

		try {
			invocation.accept(getSoklet().getSokletConfig().getLifecycleObserver());
		} catch (Throwable throwable) {
			if (throwables != null)
				throwables.add(throwable);

			safelyLog(LogEvent.with(logEventType, message)
					.throwable(throwable)
					.request(request)
					.build());
		}
	}

	private void safelyLog(@NonNull LogEvent logEvent) {
		requireNonNull(logEvent);

		try {
			getSoklet().getSokletConfig().getLifecycleObserver().didReceiveLogEvent(logEvent);
		} catch (Throwable ignored) {
			ignored.printStackTrace();
		}
	}

	@NonNull
	private Optional<ResolvedEndpoint> resolveEndpoint(@NonNull Request request,
																										 @NonNull McpServer mcpServer) {
		requireNonNull(request);
		requireNonNull(mcpServer);

		McpHandlerResolver handlerResolver = mcpServer.getHandlerResolver();

		if (!(handlerResolver instanceof DefaultMcpHandlerResolver defaultMcpHandlerResolver))
			throw new IllegalStateException("Unsupported McpHandlerResolver implementation: %s".formatted(handlerResolver.getClass().getName()));

		return defaultMcpHandlerResolver.resolvedEndpointForRequest(request);
	}

	@NonNull
	private String annotationValueOrParameterName(@Nullable String annotationValue,
																								@NonNull Parameter parameter) {
		requireNonNull(parameter);
		return annotationValue == null || annotationValue.isBlank() ? parameter.getName() : annotationValue;
	}

	@NonNull
	private McpClientCapabilities requireClientCapabilities(@NonNull McpStoredSession storedSession) {
		requireNonNull(storedSession);

		McpClientCapabilities clientCapabilities = storedSession.clientCapabilities();

		if (clientCapabilities == null)
			throw new IllegalStateException("No client capabilities available for session '%s'".formatted(storedSession.sessionId()));

		return clientCapabilities;
	}

	@NonNull
	@SuppressWarnings("unchecked")
	private <T> T convertStringValue(@NonNull String value,
																	 @NonNull Class<T> targetType) {
		return convertStringValue(getSoklet().getSokletConfig().getValueConverterRegistry(), value, targetType);
	}

	@NonNull
	@SuppressWarnings("unchecked")
	private static <T> T convertStringValue(@NonNull ValueConverterRegistry valueConverterRegistry,
																					@NonNull String value,
																					@NonNull Class<T> targetType) {
		requireNonNull(valueConverterRegistry);
		requireNonNull(value);
		requireNonNull(targetType);

		if (String.class.equals(targetType))
			return (T) value;

		ValueConverter<Object, Object> valueConverter = valueConverterRegistry.get(String.class, targetType).orElse(null);

		if (valueConverter == null)
			throw new IllegalArgumentException("No ValueConverter registered for String -> %s".formatted(targetType.getName()));

		Object convertedValue;

		try {
			convertedValue = valueConverter.convert(value).orElse(null);
		} catch (com.soklet.converter.ValueConversionException e) {
			throw new IllegalArgumentException("Unable to convert '%s' to %s".formatted(value, targetType.getName()), e);
		}

		if (convertedValue == null)
			throw new IllegalArgumentException("Unable to convert '%s' to %s".formatted(value, targetType.getName()));

		return (T) convertedValue;
	}

	@NonNull
	@SuppressWarnings({"unchecked", "rawtypes"})
	private Object convertMcpValue(@NonNull McpValue value,
																 @NonNull Class<?> targetType,
																 @NonNull String argumentName) throws JsonRpcErrorTransport {
		requireNonNull(value);
		requireNonNull(targetType);
		requireNonNull(argumentName);

		if (McpValue.class.equals(targetType))
			return value;

		if (McpObject.class.equals(targetType)) {
			if (value instanceof McpObject mcpObject)
				return mcpObject;

			throw invalidParams("Argument '%s' must be an object".formatted(argumentName));
		}

		if (McpArray.class.equals(targetType)) {
			if (value instanceof McpArray mcpArray)
				return mcpArray;

			throw invalidParams("Argument '%s' must be an array".formatted(argumentName));
		}

		if (McpString.class.equals(targetType)) {
			if (value instanceof McpString mcpString)
				return mcpString;

			throw invalidParams("Argument '%s' must be a string".formatted(argumentName));
		}

		if (McpNumber.class.equals(targetType)) {
			if (value instanceof McpNumber mcpNumber)
				return mcpNumber;

			throw invalidParams("Argument '%s' must be a number".formatted(argumentName));
		}

		if (McpBoolean.class.equals(targetType)) {
			if (value instanceof McpBoolean mcpBoolean)
				return mcpBoolean;

			throw invalidParams("Argument '%s' must be a boolean".formatted(argumentName));
		}

		if (String.class.equals(targetType)) {
			if (value instanceof McpString mcpString)
				return mcpString.value();

			throw invalidParams("Argument '%s' must be a string".formatted(argumentName));
		}

		if (Boolean.class.equals(targetType) || Boolean.TYPE.equals(targetType)) {
			if (value instanceof McpBoolean mcpBoolean)
				return mcpBoolean.value();

			throw invalidParams("Argument '%s' must be a boolean".formatted(argumentName));
		}

		if (targetType.isEnum()) {
			if (!(value instanceof McpString mcpString))
				throw invalidParams("Argument '%s' must be a string".formatted(argumentName));

			try {
				return Enum.valueOf((Class<? extends Enum>) targetType, mcpString.value());
			} catch (IllegalArgumentException e) {
				throw invalidParams("Argument '%s' has invalid enum value '%s'".formatted(argumentName, mcpString.value()));
			}
		}

		if (java.util.UUID.class.equals(targetType)) {
			if (!(value instanceof McpString mcpString))
				throw invalidParams("Argument '%s' must be a string".formatted(argumentName));

			try {
				return convertStringValue(mcpString.value(), (Class<?>) targetType);
			} catch (IllegalArgumentException e) {
				throw invalidParams("Argument '%s' is invalid".formatted(argumentName));
			}
		}

		if (value instanceof McpNumber mcpNumber)
			return convertNumberValue(mcpNumber.value(), targetType, argumentName);

		throw invalidParams("Argument '%s' has unsupported type".formatted(argumentName));
	}

	@NonNull
	private Object convertNumberValue(@NonNull BigDecimal value,
																		@NonNull Class<?> targetType,
																		@NonNull String argumentName) throws JsonRpcErrorTransport {
		requireNonNull(value);
		requireNonNull(targetType);
		requireNonNull(argumentName);

		try {
			if (BigDecimal.class.equals(targetType))
				return value;

			if (java.math.BigInteger.class.equals(targetType))
				return value.toBigIntegerExact();

			if (Byte.class.equals(targetType) || Byte.TYPE.equals(targetType))
				return value.byteValueExact();

			if (Short.class.equals(targetType) || Short.TYPE.equals(targetType))
				return value.shortValueExact();

			if (Integer.class.equals(targetType) || Integer.TYPE.equals(targetType))
				return value.intValueExact();

			if (Long.class.equals(targetType) || Long.TYPE.equals(targetType))
				return value.longValueExact();

			if (Float.class.equals(targetType) || Float.TYPE.equals(targetType))
				return value.floatValue();

			if (Double.class.equals(targetType) || Double.TYPE.equals(targetType))
				return value.doubleValue();
		} catch (ArithmeticException e) {
			throw invalidParams("Argument '%s' has invalid numeric value".formatted(argumentName));
		}

		throw invalidParams("Argument '%s' has unsupported numeric target type".formatted(argumentName));
	}

	@NonNull
	private String requiredString(@NonNull McpObject object,
																@NonNull String propertyName) throws JsonRpcErrorTransport {
		return optionalString(object, propertyName)
				.orElseThrow(() -> new JsonRpcErrorTransport(null, McpJsonRpcError.fromCodeAndMessage(-32602,
						"Missing or invalid parameter '%s'".formatted(propertyName))));
	}

	@NonNull
	private McpObject requiredObject(@NonNull McpObject object,
																	 @NonNull String propertyName) throws JsonRpcErrorTransport {
		return optionalObject(object, propertyName)
				.orElseThrow(() -> new JsonRpcErrorTransport(null, McpJsonRpcError.fromCodeAndMessage(-32602,
						"Missing or invalid parameter '%s'".formatted(propertyName))));
	}

	@NonNull
	private Optional<String> optionalString(@NonNull McpObject object,
																					@NonNull String propertyName) {
		requireNonNull(object);
		requireNonNull(propertyName);

		McpValue value = object.get(propertyName).orElse(null);
		return value instanceof McpString mcpString ? Optional.of(mcpString.value()) : Optional.empty();
	}

	@NonNull
	private Optional<McpObject> optionalObject(@NonNull McpObject object,
																						 @NonNull String propertyName) {
		requireNonNull(object);
		requireNonNull(propertyName);

		McpValue value = object.get(propertyName).orElse(null);
		return value instanceof McpObject mcpObject ? Optional.of(mcpObject) : Optional.empty();
	}

	@NonNull
	private Optional<McpProgressToken> optionalProgressToken(@NonNull McpObject object,
																													 @NonNull String propertyName) throws JsonRpcErrorTransport {
		requireNonNull(object);
		requireNonNull(propertyName);

		McpValue value = object.get(propertyName).orElse(null);

		if (value == null || value instanceof McpNull)
			return Optional.empty();

		try {
			return Optional.of(new McpProgressToken(value));
		} catch (IllegalArgumentException e) {
			throw invalidParams("Missing or invalid parameter '%s'".formatted(propertyName));
		}
	}

	@NonNull
	private McpObject optionalArgumentsObject(@NonNull McpObject object,
																						@NonNull String propertyName) throws JsonRpcErrorTransport {
		requireNonNull(object);
		requireNonNull(propertyName);

		McpValue value = object.get(propertyName).orElse(null);

		if (value == null || value instanceof McpNull)
			return EMPTY_OBJECT;

		if (value instanceof McpObject mcpObject)
			return mcpObject;

		throw invalidParams("Missing or invalid parameter '%s'".formatted(propertyName));
	}

	@NonNull
	private McpJsonRpcError invalidParamsError(@NonNull String message) {
		requireNonNull(message);
		return McpJsonRpcError.fromCodeAndMessage(-32602, message);
	}

	@NonNull
	private JsonRpcErrorTransport invalidParams(@NonNull String message) {
		requireNonNull(message);
		return new JsonRpcErrorTransport(null, invalidParamsError(message));
	}

	@Nullable
	private DefaultMcpProgressReporter progressReporter(@NonNull ParsedJsonRpcRequest parsedRequest) throws JsonRpcErrorTransport {
		requireNonNull(parsedRequest);

		McpObject meta = optionalObject(parsedRequest.params(), "_meta").orElse(null);

		if (meta == null)
			return null;

		McpProgressToken progressToken = optionalProgressToken(meta, "progressToken").orElse(null);
		return progressToken == null ? null : new DefaultMcpProgressReporter(progressToken);
	}

	@NonNull
	private McpValue toolResultValue(@NonNull Class<? extends McpEndpoint> endpointClass,
																	 @NonNull String toolName,
																	 @NonNull McpSessionContext sessionContext,
																	 @NonNull DefaultMcpToolCallContext toolCallContext,
																	 @NonNull McpToolResult toolResult) {
		requireNonNull(endpointClass);
		requireNonNull(toolName);
		requireNonNull(sessionContext);
		requireNonNull(toolCallContext);
		requireNonNull(toolResult);

		Map<String, McpValue> result = new LinkedHashMap<>();
		List<McpValue> content = new ArrayList<>(toolResult.getContent().size());

		for (McpTextContent textContent : toolResult.getContent())
			content.add(textContentValue(textContent));

		result.put("content", new McpArray(content));
		result.put("isError", new McpBoolean(toolResult.isError()));

		toolResult.getStructuredContent().ifPresent(structuredContent ->
				result.put("structuredContent", getMcpServer().getResponseMarshaler().marshalStructuredContent(structuredContent,
						new DefaultMcpStructuredContentContext(endpointClass, toolName, toolCallContext, sessionContext))));

		return new McpObject(result);
	}

	@NonNull
	private McpValue promptResultValue(@NonNull PromptBinding promptBinding,
																		 @NonNull McpPromptResult promptResult) {
		requireNonNull(promptBinding);
		requireNonNull(promptResult);

		Map<String, McpValue> result = new LinkedHashMap<>();
		String description = promptResult.description() == null ? promptBinding.description() : promptResult.description();

		if (description != null)
			result.put("description", new McpString(description));

		List<McpValue> messages = new ArrayList<>(promptResult.messages().size());

		for (McpPromptMessage message : promptResult.messages()) {
			Map<String, McpValue> messageValue = new LinkedHashMap<>();
			messageValue.put("role", new McpString(message.role().name().toLowerCase()));
			messageValue.put("content", textContentValue(message.content()));
			messages.add(new McpObject(messageValue));
		}

		result.put("messages", new McpArray(messages));
		return new McpObject(result);
	}

	@NonNull
	private McpValue resourceContentsValue(@NonNull McpResourceContents resourceContents) {
		requireNonNull(resourceContents);

		Map<String, McpValue> value = new LinkedHashMap<>();
		value.put("uri", new McpString(resourceContents.uri()));
		value.put("mimeType", new McpString(resourceContents.mimeType()));

		if (resourceContents.text() != null)
			value.put("text", new McpString(resourceContents.text()));

		if (resourceContents.blobBase64() != null)
			value.put("blob", new McpString(resourceContents.blobBase64()));

		return new McpObject(value);
	}

	@NonNull
	private McpValue textContentValue(@NonNull McpTextContent textContent) {
		requireNonNull(textContent);
		return new McpObject(Map.of(
				"type", new McpString("text"),
				"text", new McpString(textContent.text())
		));
	}

	@NonNull
	private Optional<MatchedResourceBinding> matchResourceBinding(@NonNull ResolvedEndpoint resolvedEndpoint,
																																@NonNull String requestedUri) {
		requireNonNull(resolvedEndpoint);
		requireNonNull(requestedUri);

		List<MatchedResourceBinding> matches = new ArrayList<>();

		for (ResourceBinding resourceBinding : resolvedEndpoint.resourcesByUri().values()) {
			Map<String, String> uriParameters = uriParametersForTemplate(resourceBinding.uri(), requestedUri).orElse(null);

			if (uriParameters != null)
				matches.add(new MatchedResourceBinding(resourceBinding, uriParameters));
		}

		if (matches.isEmpty())
			return Optional.empty();

		if (matches.size() == 1)
			return Optional.of(matches.get(0));

		matches.sort(Comparator
				.comparingLong((MatchedResourceBinding matchedResourceBinding) -> placeholderCount(matchedResourceBinding.binding().uri()))
				.thenComparingLong(matchedResourceBinding -> -literalCount(matchedResourceBinding.binding().uri())));
		return Optional.of(matches.get(0));
	}

	@NonNull
	private Optional<Map<String, String>> uriParametersForTemplate(@NonNull String template,
																																 @NonNull String requestedUri) {
		requireNonNull(template);
		requireNonNull(requestedUri);

		String[] templateComponents = template.split("/", -1);
		String[] requestedComponents = requestedUri.split("/", -1);

		if (templateComponents.length != requestedComponents.length)
			return Optional.empty();

		Map<String, String> uriParameters = new LinkedHashMap<>();

		for (int i = 0; i < templateComponents.length; i++) {
			String templateComponent = templateComponents[i];
			String requestedComponent = requestedComponents[i];

			if (isUriPlaceholder(templateComponent)) {
				uriParameters.put(templateComponent.substring(1, templateComponent.length() - 1), requestedComponent);
				continue;
			}

			if (!templateComponent.equals(requestedComponent))
				return Optional.empty();
		}

		return Optional.of(uriParameters);
	}

	private boolean isUriPlaceholder(@NonNull String value) {
		requireNonNull(value);
		return value.length() >= 2 && value.startsWith("{") && value.endsWith("}");
	}

	private boolean isUriTemplate(@NonNull String uri) {
		requireNonNull(uri);

		for (String component : uri.split("/", -1))
			if (isUriPlaceholder(component))
				return true;

		return false;
	}

	private long placeholderCount(@NonNull String uriTemplate) {
		requireNonNull(uriTemplate);
		long count = 0L;

		for (String component : uriTemplate.split("/", -1))
			if (isUriPlaceholder(component))
				count++;

		return count;
	}

	private long literalCount(@NonNull String uriTemplate) {
		requireNonNull(uriTemplate);
		long count = 0L;

		for (String component : uriTemplate.split("/", -1))
			if (!isUriPlaceholder(component))
				count++;

		return count;
	}

	@NonNull
	private Object invokeMethod(@NonNull Object instance,
															@NonNull Method method,
															@NonNull List<Object> arguments) throws Exception {
		requireNonNull(instance);
		requireNonNull(method);
		requireNonNull(arguments);

		try {
			Object result = method.invoke(instance, arguments.toArray());
			return result instanceof Optional<?> optional ? optional.orElse(null) : result;
		} catch (InvocationTargetException e) {
			throw unwrapInvocationThrowable(e);
		}
	}

	@NonNull
	private static Exception unwrapInvocationThrowable(@NonNull Throwable throwable) {
		requireNonNull(throwable);

		if (throwable instanceof InvocationTargetException invocationTargetException && invocationTargetException.getTargetException() != null)
			throwable = invocationTargetException.getTargetException();

		if (throwable instanceof Exception exception)
			return exception;

		return new RuntimeException(throwable);
	}

	@NonNull
	private McpObject jsonRpcSuccessEnvelope(@Nullable McpJsonRpcRequestId requestId,
																					 @NonNull McpValue result) {
		requireNonNull(result);

		Map<String, McpValue> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", new McpString("2.0"));
		envelope.put("id", requestId == null ? McpNull.INSTANCE : requestId.value());
		envelope.put("result", result);
		return new McpObject(envelope);
	}

	@NonNull
	private McpObject jsonRpcErrorEnvelope(@Nullable McpJsonRpcRequestId requestId,
																				 @NonNull McpJsonRpcError error) {
		requireNonNull(error);

		Map<String, McpValue> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", new McpString("2.0"));
		envelope.put("id", requestId == null ? McpNull.INSTANCE : requestId.value());
		envelope.put("error", new McpObject(Map.of(
				"code", new McpNumber(new BigDecimal(error.code())),
				"message", new McpString(error.message())
		)));
		return new McpObject(envelope);
	}

	@NonNull
	private byte[] encodeEventStreamMessages(@NonNull List<@NonNull McpObject> messages) {
		requireNonNull(messages);
		return McpEventStreamPayloads.fromMessages(messages);
	}

	@NonNull
	private Boolean acceptsMediaType(@NonNull Request request,
																	 @NonNull String mediaType) {
		requireNonNull(request);
		requireNonNull(mediaType);

		Set<String> acceptHeaderValues = request.getHeaders().get("Accept");

		if (acceptHeaderValues == null || acceptHeaderValues.isEmpty())
			return true;

		String[] requestedTypeAndSubtype = mediaType.toLowerCase().split("/", 2);

		if (requestedTypeAndSubtype.length != 2)
			throw new IllegalArgumentException("Invalid media type '%s'".formatted(mediaType));

		AcceptMediaRange bestMatch = null;

		for (String acceptHeaderValue : acceptHeaderValues) {
			if (acceptHeaderValue == null)
				continue;

			for (String fragment : acceptHeaderValue.split(",")) {
				AcceptMediaRange mediaRange = AcceptMediaRange.parse(fragment).orElse(null);

				if (mediaRange == null || !mediaRange.matches(requestedTypeAndSubtype[0], requestedTypeAndSubtype[1]))
					continue;

				if (bestMatch == null
						|| mediaRange.specificity() > bestMatch.specificity()
						|| (mediaRange.specificity() == bestMatch.specificity()
						&& mediaRange.quality().compareTo(bestMatch.quality()) > 0))
					bestMatch = mediaRange;
			}
		}

		return bestMatch != null && bestMatch.quality().compareTo(BigDecimal.ZERO) > 0;
	}

	@NonNull
	private Soklet getSoklet() {
		return this.soklet;
	}

	@NonNull
	private McpServer getMcpServer() {
		return getSoklet().getSokletConfig().getMcpServer().orElseThrow();
	}

	@NonNull
	Boolean hasActiveStream(@NonNull String sessionId) {
		requireNonNull(sessionId);

		CopyOnWriteArrayList<McpStreamState> streamStates = this.mcpStreamsBySessionId.get(sessionId);
		return streamStates != null && !streamStates.isEmpty();
	}

	private static final class DefaultMcpProgressReporter implements McpProgressReporter {
		@NonNull
		private final McpProgressToken progressToken;
		@NonNull
		private final List<@NonNull McpObject> messages;
		@Nullable
		private BigDecimal lastProgress;
		@NonNull
		private Boolean completed;

		private DefaultMcpProgressReporter(@NonNull McpProgressToken progressToken) {
			requireNonNull(progressToken);
			this.progressToken = progressToken;
			this.messages = new ArrayList<>();
			this.lastProgress = null;
			this.completed = false;
		}

		@NonNull
		@Override
		public synchronized McpProgressToken getProgressToken() {
			return this.progressToken;
		}

		@Override
		public synchronized void reportProgress(@NonNull BigDecimal progress,
																						@Nullable BigDecimal total,
																						@Nullable String message) {
			requireNonNull(progress);

			if (this.completed)
				throw new IllegalStateException("This MCP progress reporter is no longer active.");

			if (this.lastProgress != null && progress.compareTo(this.lastProgress) < 0)
				throw new IllegalArgumentException("MCP progress values must be monotonically increasing.");

			Map<String, McpValue> params = new LinkedHashMap<>();
			params.put("progressToken", this.progressToken.value());
			params.put("progress", new McpNumber(progress));

			if (total != null)
				params.put("total", new McpNumber(total));

			if (message != null)
				params.put("message", new McpString(message));

			this.messages.add(new McpObject(Map.of(
					"jsonrpc", new McpString("2.0"),
					"method", new McpString("notifications/progress"),
					"params", new McpObject(params)
			)));
			this.lastProgress = progress;
		}

		@NonNull
		private synchronized List<@NonNull McpObject> messages() {
			return List.copyOf(this.messages);
		}

		@NonNull
		private synchronized Boolean hasMessages() {
			return !this.messages.isEmpty();
		}

		private synchronized void markCompleted() {
			this.completed = true;
		}
	}

	private record ObservedMcpResult(
			@NonNull McpRequestOutcome requestOutcome,
			@Nullable McpJsonRpcError jsonRpcError
	) {
		private ObservedMcpResult {
			requireNonNull(requestOutcome);
		}
	}

	private record ParsedJsonRpcRequest(
			@NonNull String method,
			@Nullable McpOperationKind operationKind,
			@NonNull McpObject params,
			@Nullable McpJsonRpcRequestId requestId
	) {
		private ParsedJsonRpcRequest {
			requireNonNull(method);
			requireNonNull(params);
		}
	}

	private record AcceptMediaRange(
			@NonNull String type,
			@NonNull String subtype,
			@NonNull BigDecimal quality
	) {
		private AcceptMediaRange {
			requireNonNull(type);
			requireNonNull(subtype);
			requireNonNull(quality);
		}

		@NonNull
		private static Optional<AcceptMediaRange> parse(@Nullable String fragment) {
			fragment = fragment == null ? null : fragment.trim();

			if (fragment == null || fragment.isBlank())
				return Optional.empty();

			String[] segments = fragment.split(";");
			String[] typeAndSubtype = segments[0].trim().toLowerCase().split("/", 2);

			if (typeAndSubtype.length != 2)
				return Optional.empty();

			BigDecimal quality = BigDecimal.ONE;

			for (int i = 1; i < segments.length; i++) {
				String segment = segments[i].trim();
				int equalsIndex = segment.indexOf('=');

				if (equalsIndex == -1)
					continue;

				String name = segment.substring(0, equalsIndex).trim();
				String value = segment.substring(equalsIndex + 1).trim();

				if (!"q".equalsIgnoreCase(name))
					continue;

				try {
					quality = new BigDecimal(value);
				} catch (NumberFormatException e) {
					return Optional.empty();
				}
			}

			return Optional.of(new AcceptMediaRange(typeAndSubtype[0], typeAndSubtype[1], quality));
		}

		@NonNull
		private Boolean matches(@NonNull String requestedType,
														@NonNull String requestedSubtype) {
			requireNonNull(requestedType);
			requireNonNull(requestedSubtype);

			if ("*".equals(this.type) && "*".equals(this.subtype))
				return true;

			if (Objects.equals(this.type, requestedType) && "*".equals(this.subtype))
				return true;

			return Objects.equals(this.type, requestedType) && Objects.equals(this.subtype, requestedSubtype);
		}

		@NonNull
		private Integer specificity() {
			if ("*".equals(this.type) && "*".equals(this.subtype))
				return 0;

			if ("*".equals(this.subtype))
				return 1;

			return 2;
		}
	}

	private static final class JsonRpcErrorTransport extends Exception {
		@Nullable
		private final McpJsonRpcRequestId requestId;
		@NonNull
		private final McpJsonRpcError error;

		private JsonRpcErrorTransport(@Nullable McpJsonRpcRequestId requestId,
																	@NonNull McpJsonRpcError error) {
			super(error.message());
			requireNonNull(error);
			this.requestId = requestId;
			this.error = error;
		}

		@Nullable
		McpJsonRpcRequestId requestId() {
			return this.requestId;
		}

		@NonNull
		McpJsonRpcError error() {
			return this.error;
		}
	}

	private record McpStreamState(
			@NonNull Request request,
			@NonNull Class<? extends McpEndpoint> endpointClass,
			@NonNull String sessionId,
			@NonNull Instant establishedAt
	) {
		private McpStreamState {
			requireNonNull(request);
			requireNonNull(endpointClass);
			requireNonNull(sessionId);
			requireNonNull(establishedAt);
		}
	}

	private record DefaultMcpAdmissionContext(
			@NonNull Request request,
			@NonNull HttpMethod httpMethod,
			@NonNull Class<? extends McpEndpoint> endpointClass,
			@NonNull Optional<String> jsonRpcMethod,
			@NonNull Optional<McpOperationKind> operationKind,
			@NonNull Optional<McpJsonRpcRequestId> jsonRpcRequestId,
			@NonNull Optional<String> sessionId
	) implements McpAdmissionContext {
		private DefaultMcpAdmissionContext {
			requireNonNull(request);
			requireNonNull(httpMethod);
			requireNonNull(endpointClass);
			requireNonNull(jsonRpcMethod);
			requireNonNull(operationKind);
			requireNonNull(jsonRpcRequestId);
			requireNonNull(sessionId);
		}

		@NonNull
		@Override
		public Request getRequest() {
			return this.request;
		}

		@NonNull
		@Override
		public HttpMethod getHttpMethod() {
			return this.httpMethod;
		}

		@NonNull
		@Override
		public Class<? extends McpEndpoint> getEndpointClass() {
			return this.endpointClass;
		}

		@NonNull
		@Override
		public Optional<String> getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		@NonNull
		@Override
		public Optional<McpOperationKind> getOperationKind() {
			return this.operationKind;
		}

		@NonNull
		@Override
		public Optional<McpJsonRpcRequestId> getJsonRpcRequestId() {
			return this.jsonRpcRequestId;
		}

		@NonNull
		@Override
		public Optional<String> getSessionId() {
			return this.sessionId;
		}
	}

	private record DefaultMcpRequestContext(
			@NonNull Request request,
			@NonNull Class<? extends McpEndpoint> endpointClass,
			@NonNull String jsonRpcMethod,
			@NonNull McpOperationKind operationKind,
			@NonNull Optional<McpJsonRpcRequestId> jsonRpcRequestId,
			@NonNull Optional<String> sessionId,
			@NonNull Optional<String> protocolVersion,
			@NonNull Optional<McpNegotiatedCapabilities> negotiatedCapabilities,
			@NonNull Optional<McpSessionContext> sessionContext
	) implements McpRequestContext {
		private DefaultMcpRequestContext {
			requireNonNull(request);
			requireNonNull(endpointClass);
			requireNonNull(jsonRpcMethod);
			requireNonNull(operationKind);
			requireNonNull(jsonRpcRequestId);
			requireNonNull(sessionId);
			requireNonNull(protocolVersion);
			requireNonNull(negotiatedCapabilities);
			requireNonNull(sessionContext);
		}

		@NonNull
		@Override
		public Request getRequest() {
			return this.request;
		}

		@NonNull
		@Override
		public Class<? extends McpEndpoint> getEndpointClass() {
			return this.endpointClass;
		}

		@NonNull
		@Override
		public String getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		@NonNull
		@Override
		public McpOperationKind getOperationKind() {
			return this.operationKind;
		}

		@NonNull
		@Override
		public Optional<McpJsonRpcRequestId> getJsonRpcRequestId() {
			return this.jsonRpcRequestId;
		}

		@NonNull
		@Override
		public Optional<String> getSessionId() {
			return this.sessionId;
		}

		@NonNull
		@Override
		public Optional<String> getProtocolVersion() {
			return this.protocolVersion;
		}

		@NonNull
		@Override
		public Optional<McpNegotiatedCapabilities> getNegotiatedCapabilities() {
			return this.negotiatedCapabilities;
		}

		@NonNull
		@Override
		public Optional<McpSessionContext> getSessionContext() {
			return this.sessionContext;
		}
	}

	private record DefaultMcpInitializationContext(
			@NonNull Request request,
			@NonNull String protocolVersion,
			@NonNull McpClientCapabilities clientCapabilities,
			@NonNull Optional<McpClientInfo> clientInfo,
			@NonNull Map<String, String> endpointPathParameters,
			@NonNull ValueConverterRegistry valueConverterRegistry
	) implements McpInitializationContext {
		private DefaultMcpInitializationContext {
			requireNonNull(request);
			requireNonNull(protocolVersion);
			requireNonNull(clientCapabilities);
			requireNonNull(clientInfo);
			requireNonNull(endpointPathParameters);
			requireNonNull(valueConverterRegistry);
		}

		@NonNull
		@Override
		public Request getRequest() {
			return this.request;
		}

		@NonNull
		@Override
		public String getProtocolVersion() {
			return this.protocolVersion;
		}

		@NonNull
		@Override
		public McpClientCapabilities getClientCapabilities() {
			return this.clientCapabilities;
		}

		@NonNull
		@Override
		public Optional<McpClientInfo> getClientInfo() {
			return this.clientInfo;
		}

		@NonNull
		@Override
		public Optional<String> getEndpointPathParameter(@NonNull String name) {
			requireNonNull(name);
			return Optional.ofNullable(this.endpointPathParameters.get(name));
		}

		@NonNull
		@Override
		public <T> Optional<T> getEndpointPathParameter(@NonNull String name,
																										@NonNull Class<T> type) {
			requireNonNull(name);
			requireNonNull(type);
			String value = this.endpointPathParameters.get(name);
			return value == null ? Optional.empty() : Optional.of(convert(type, value));
		}

		@NonNull
		private <T> T convert(@NonNull Class<T> type,
													@NonNull String value) {
			requireNonNull(type);
			requireNonNull(value);

			if (String.class.equals(type))
				return type.cast(value);

			ValueConverter<Object, Object> valueConverter = this.valueConverterRegistry.get(String.class, type).orElse(null);

			if (valueConverter == null)
				throw new IllegalArgumentException("No ValueConverter registered for String -> %s".formatted(type.getName()));

			Object convertedValue;

			try {
				convertedValue = valueConverter.convert(value).orElse(null);
			} catch (com.soklet.converter.ValueConversionException e) {
				throw new IllegalArgumentException("Unable to convert '%s' to %s".formatted(value, type.getName()), e);
			}

			if (convertedValue == null)
				throw new IllegalArgumentException("Unable to convert '%s' to %s".formatted(value, type.getName()));

			return type.cast(convertedValue);
		}
	}

	private record DefaultMcpListResourcesContext(
			@NonNull McpRequestContext requestContext,
			@NonNull Optional<String> cursor
	) implements McpListResourcesContext {
		private DefaultMcpListResourcesContext {
			requireNonNull(requestContext);
			requireNonNull(cursor);
		}

		@NonNull
		@Override
		public McpRequestContext getRequestContext() {
			return this.requestContext;
		}

		@NonNull
		@Override
		public Optional<String> getCursor() {
			return this.cursor;
		}
	}

	private record DefaultMcpResourceListHandlerContext(
			@NonNull McpListResourcesContext listResourcesContext,
			@NonNull McpSessionContext sessionContext,
			@NonNull Map<String, String> endpointPathParameters,
			@NonNull ValueConverterRegistry valueConverterRegistry
	) implements McpResourceListHandlerContext {
		private DefaultMcpResourceListHandlerContext {
			requireNonNull(listResourcesContext);
			requireNonNull(sessionContext);
			requireNonNull(endpointPathParameters);
			requireNonNull(valueConverterRegistry);
		}

		@NonNull
		@Override
		public McpListResourcesContext getListResourcesContext() {
			return this.listResourcesContext;
		}

		@NonNull
		@Override
		public McpSessionContext getSessionContext() {
			return this.sessionContext;
		}

		@NonNull
		@Override
		public Optional<String> getEndpointPathParameter(@NonNull String name) {
			requireNonNull(name);
			return Optional.ofNullable(this.endpointPathParameters.get(name));
		}

		@NonNull
		@Override
		public <T> Optional<T> getEndpointPathParameter(@NonNull String name,
																										@NonNull Class<T> type) {
			requireNonNull(name);
			requireNonNull(type);
			String value = this.endpointPathParameters.get(name);
			return value == null ? Optional.empty() : Optional.of(convert(type, value));
		}

		@NonNull
		private <T> T convert(@NonNull Class<T> type,
													@NonNull String value) {
			requireNonNull(type);
			requireNonNull(value);
			return convertStringValue(this.valueConverterRegistry, value, type);
		}
	}

	private record DefaultMcpToolCallContext(
			@NonNull McpRequestContext requestContext,
			@NonNull Optional<McpProgressReporter> progressReporter
	) implements McpToolCallContext {
		private DefaultMcpToolCallContext {
			requireNonNull(requestContext);
			requireNonNull(progressReporter);
		}

		@NonNull
		@Override
		public McpRequestContext getRequestContext() {
			return this.requestContext;
		}

		@NonNull
		@Override
		public Optional<McpProgressReporter> getProgressReporter() {
			return this.progressReporter;
		}
	}

	private record DefaultMcpToolHandlerContext(
			@NonNull McpToolCallContext toolCallContext,
			@NonNull McpSessionContext sessionContext,
			@NonNull McpClientCapabilities clientCapabilities,
			@NonNull McpObject arguments,
			@NonNull Map<String, String> endpointPathParameters,
			@NonNull ValueConverterRegistry valueConverterRegistry
	) implements McpToolHandlerContext {
		private DefaultMcpToolHandlerContext {
			requireNonNull(toolCallContext);
			requireNonNull(sessionContext);
			requireNonNull(clientCapabilities);
			requireNonNull(arguments);
			requireNonNull(endpointPathParameters);
			requireNonNull(valueConverterRegistry);
		}

		@NonNull
		@Override
		public McpToolCallContext getToolCallContext() {
			return this.toolCallContext;
		}

		@NonNull
		@Override
		public McpSessionContext getSessionContext() {
			return this.sessionContext;
		}

		@NonNull
		@Override
		public McpClientCapabilities getClientCapabilities() {
			return this.clientCapabilities;
		}

		@NonNull
		@Override
		public McpObject getArguments() {
			return this.arguments;
		}

		@NonNull
		@Override
		public Optional<String> getEndpointPathParameter(@NonNull String name) {
			requireNonNull(name);
			return Optional.ofNullable(this.endpointPathParameters.get(name));
		}

		@NonNull
		@Override
		public <T> Optional<T> getEndpointPathParameter(@NonNull String name,
																										@NonNull Class<T> type) {
			requireNonNull(name);
			requireNonNull(type);
			String value = this.endpointPathParameters.get(name);
			return value == null ? Optional.empty() : Optional.of(convertStringValue(this.valueConverterRegistry, value, type));
		}
	}

	private record DefaultMcpPromptHandlerContext(
			@NonNull McpRequestContext requestContext,
			@NonNull McpSessionContext sessionContext,
			@NonNull McpClientCapabilities clientCapabilities,
			@NonNull McpObject arguments,
			@NonNull Map<String, String> endpointPathParameters,
			@NonNull ValueConverterRegistry valueConverterRegistry
	) implements McpPromptHandlerContext {
		private DefaultMcpPromptHandlerContext {
			requireNonNull(requestContext);
			requireNonNull(sessionContext);
			requireNonNull(clientCapabilities);
			requireNonNull(arguments);
			requireNonNull(endpointPathParameters);
			requireNonNull(valueConverterRegistry);
		}

		@NonNull
		@Override
		public McpRequestContext getRequestContext() {
			return this.requestContext;
		}

		@NonNull
		@Override
		public McpSessionContext getSessionContext() {
			return this.sessionContext;
		}

		@NonNull
		@Override
		public McpClientCapabilities getClientCapabilities() {
			return this.clientCapabilities;
		}

		@NonNull
		@Override
		public McpObject getArguments() {
			return this.arguments;
		}

		@NonNull
		@Override
		public Optional<String> getEndpointPathParameter(@NonNull String name) {
			requireNonNull(name);
			return Optional.ofNullable(this.endpointPathParameters.get(name));
		}

		@NonNull
		@Override
		public <T> Optional<T> getEndpointPathParameter(@NonNull String name,
																										@NonNull Class<T> type) {
			requireNonNull(name);
			requireNonNull(type);
			String value = this.endpointPathParameters.get(name);
			return value == null ? Optional.empty() : Optional.of(convertStringValue(this.valueConverterRegistry, value, type));
		}
	}

	private record DefaultMcpResourceHandlerContext(
			@NonNull McpRequestContext requestContext,
			@NonNull McpSessionContext sessionContext,
			@NonNull String requestedUri,
			@NonNull Map<String, String> uriParameters,
			@NonNull Map<String, String> endpointPathParameters,
			@NonNull ValueConverterRegistry valueConverterRegistry
	) implements McpResourceHandlerContext {
		private DefaultMcpResourceHandlerContext {
			requireNonNull(requestContext);
			requireNonNull(sessionContext);
			requireNonNull(requestedUri);
			requireNonNull(uriParameters);
			requireNonNull(endpointPathParameters);
			requireNonNull(valueConverterRegistry);
		}

		@NonNull
		@Override
		public McpRequestContext getRequestContext() {
			return this.requestContext;
		}

		@NonNull
		@Override
		public McpSessionContext getSessionContext() {
			return this.sessionContext;
		}

		@NonNull
		@Override
		public String getRequestedUri() {
			return this.requestedUri;
		}

		@NonNull
		@Override
		public Optional<String> getUriParameter(@NonNull String name) {
			requireNonNull(name);
			return Optional.ofNullable(this.uriParameters.get(name));
		}

		@NonNull
		@Override
		public <T> Optional<T> getUriParameter(@NonNull String name,
																					 @NonNull Class<T> type) {
			requireNonNull(name);
			requireNonNull(type);
			String value = this.uriParameters.get(name);
			return value == null ? Optional.empty() : Optional.of(convertStringValue(this.valueConverterRegistry, value, type));
		}

		@NonNull
		@Override
		public Optional<String> getEndpointPathParameter(@NonNull String name) {
			requireNonNull(name);
			return Optional.ofNullable(this.endpointPathParameters.get(name));
		}

		@NonNull
		@Override
		public <T> Optional<T> getEndpointPathParameter(@NonNull String name,
																										@NonNull Class<T> type) {
			requireNonNull(name);
			requireNonNull(type);
			String value = this.endpointPathParameters.get(name);
			return value == null ? Optional.empty() : Optional.of(convertStringValue(this.valueConverterRegistry, value, type));
		}
	}

	private record DefaultMcpStructuredContentContext(
			@NonNull Class<? extends McpEndpoint> endpointClass,
			@NonNull String toolName,
			@NonNull McpToolCallContext toolCallContext,
			@NonNull McpSessionContext sessionContext
	) implements McpStructuredContentContext {
		private DefaultMcpStructuredContentContext {
			requireNonNull(endpointClass);
			requireNonNull(toolName);
			requireNonNull(toolCallContext);
			requireNonNull(sessionContext);
		}

		@NonNull
		@Override
		public Class<? extends McpEndpoint> getEndpointClass() {
			return this.endpointClass;
		}

		@NonNull
		@Override
		public String getToolName() {
			return this.toolName;
		}

		@NonNull
		@Override
		public McpToolCallContext getToolCallContext() {
			return this.toolCallContext;
		}

		@NonNull
		@Override
		public McpSessionContext getSessionContext() {
			return this.sessionContext;
		}
	}

	private record MatchedResourceBinding(
			@NonNull ResourceBinding binding,
			@NonNull Map<String, String> uriParameters
	) {
		private MatchedResourceBinding {
			requireNonNull(binding);
			requireNonNull(uriParameters);
		}
	}
}

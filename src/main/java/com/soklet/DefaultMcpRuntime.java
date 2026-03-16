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
import com.soklet.DefaultMcpHandlerResolver.AnnotatedResourceListBinding;
import com.soklet.DefaultMcpHandlerResolver.AnnotatedToolBinding;
import com.soklet.DefaultMcpHandlerResolver.ProgrammaticPromptBinding;
import com.soklet.DefaultMcpHandlerResolver.ProgrammaticResourceListBinding;
import com.soklet.DefaultMcpHandlerResolver.ProgrammaticToolBinding;
import com.soklet.DefaultMcpHandlerResolver.PromptBinding;
import com.soklet.DefaultMcpHandlerResolver.ResolvedEndpoint;
import com.soklet.DefaultMcpHandlerResolver.ResourceListBinding;
import com.soklet.DefaultMcpHandlerResolver.ToolBinding;
import com.soklet.annotation.McpArgument;
import com.soklet.annotation.McpEndpointPathParameter;
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
	private static final McpObject EMPTY_OBJECT = new McpObject(Map.of());
	@NonNull
	private final Soklet soklet;

	DefaultMcpRuntime(@NonNull Soklet soklet) {
		requireNonNull(soklet);
		this.soklet = soklet;
	}

	@NonNull
	RequestResult handleRequest(@NonNull Request request) {
		requireNonNull(request);

		try {
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
				case DELETE -> handleDeleteRequest(request, mcpServer, resolvedEndpoint);
				default -> requestResultFromMarshaledResponse(request,
						getSoklet().getSokletConfig().getResponseMarshaler().forMethodNotAllowed(request, Set.of(HttpMethod.POST, HttpMethod.DELETE)));
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

			if (storedSession.isEmpty() || storedSession.get().terminatedAt() != null
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
					dispatchPostRequest(request, mcpServer, resolvedEndpoint, endpointPathParameters, parsedRequest, finalStoredSession, requestContext));

			if (requestResult == null)
				throw new IllegalStateException(format("%s::interceptRequest returned null for MCP operation %s",
						McpRequestInterceptor.class.getSimpleName(), parsedRequest.method()));

			return requestResult;
		} catch (Throwable throwable) {
			return jsonRpcErrorResponse(request, parsedRequest.requestId(), McpJsonRpcError.fromCodeAndMessage(-32603, "Internal error"));
		}
	}

	@NonNull
	private RequestResult dispatchPostRequest(@NonNull Request request,
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

		return switch (parsedRequest.operationKind()) {
			case INITIALIZE -> handleInitialize(request, mcpServer, resolvedEndpoint, endpointPathParameters, parsedRequest);
			case NOTIFICATIONS_INITIALIZED -> handleInitializedNotification(request, mcpServer, parsedRequest, storedSession.orElseThrow(), requestContext);
			case PING -> handlePing(request, mcpServer, parsedRequest, storedSession.orElse(null));
			case TOOLS_LIST -> handleToolsList(request, resolvedEndpoint, parsedRequest, storedSession.orElseThrow(), requestContext);
			case PROMPTS_LIST -> handlePromptsList(request, resolvedEndpoint, parsedRequest, storedSession.orElseThrow(), requestContext);
			case RESOURCES_LIST -> handleResourcesList(request, resolvedEndpoint, endpointPathParameters, parsedRequest, storedSession.orElseThrow(), requestContext);
			default -> jsonRpcErrorResponse(request, parsedRequest.requestId(), McpJsonRpcError.fromCodeAndMessage(-32601, "Method not found"));
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
		String protocolVersion = requiredString(params, "protocolVersion");
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
			McpNegotiatedCapabilities negotiatedCapabilities = new McpNegotiatedCapabilities(EMPTY_OBJECT);
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
			McpJsonRpcError error = endpoint.handleError(unwrapInvocationThrowable(throwable),
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
				? McpListResourcesResult.fromResources(List.of())
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

		if (storedSession == null || storedSession.terminatedAt() != null || !storedSession.endpointClass().equals(resolvedEndpoint.endpointClass()))
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

		mcpServer.getSessionStore().replace(storedSession, updatedSession);
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

		Map<String, McpValue> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", new McpString("2.0"));
		envelope.put("id", requestId == null ? McpNull.INSTANCE : requestId.value());
		envelope.put("result", result);
		return requestResultFromMarshaledResponse(request, jsonResponse(new McpObject(envelope), headers));
	}

	@NonNull
	private RequestResult jsonRpcErrorResponse(@NonNull Request request,
																						 @Nullable McpJsonRpcRequestId requestId,
																						 @NonNull McpJsonRpcError error) {
		requireNonNull(request);
		requireNonNull(error);

		Map<String, McpValue> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", new McpString("2.0"));
		envelope.put("id", requestId == null ? McpNull.INSTANCE : requestId.value());
		envelope.put("error", new McpObject(Map.of(
				"code", new McpNumber(new BigDecimal(error.code())),
				"message", new McpString(error.message())
		)));
		return requestResultFromMarshaledResponse(request, jsonResponse(new McpObject(envelope), Map.of()));
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
	@SuppressWarnings("unchecked")
	private <T> T convertStringValue(@NonNull String value,
																	 @NonNull Class<T> targetType) {
		requireNonNull(value);
		requireNonNull(targetType);

		if (String.class.equals(targetType))
			return (T) value;

		ValueConverter<Object, Object> valueConverter = getSoklet().getSokletConfig().getValueConverterRegistry()
				.get(String.class, targetType)
				.orElse(null);

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
	private Soklet getSoklet() {
		return this.soklet;
	}

	@NonNull
	private McpServer getMcpServer() {
		return getSoklet().getSokletConfig().getMcpServer().orElseThrow();
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
}

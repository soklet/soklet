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

import com.soklet.CancelationToken;
import com.soklet.McpRequestContext;
import com.soklet.McpRequestOutcome;
import com.soklet.McpRequestStateMode;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.microhttp.MicrohttpRequest;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationRequestHandler {
	@NonNull
	McpWireResult handle(@NonNull McpApplicationInvocation invocation) throws Exception;
}

/**
 * Internal control signal for a validated JSON-RPC request whose application
 * input failed schema validation or binding before its typed handler ran.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
final class McpInvalidApplicationInputException extends Exception {
	McpInvalidApplicationInputException() {
		super(null, null, false, false);
	}
}

/**
 * Internal control signal for an intentional application JSON-RPC error.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
final class McpApplicationJsonRpcException extends Exception {
	@NonNull
	private final McpJsonRpcError error;

	McpApplicationJsonRpcException(@NonNull McpJsonRpcError error) {
		super(null, null, false, false);
		this.error = requireNonNull(error);
	}

	@NonNull
	McpJsonRpcError error() {
		return error;
	}
}

/**
 * Internal control signal for a framework-detected protocol error after
 * application dispatch has begun.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
final class McpProtocolJsonRpcException extends Exception {
	@NonNull
	private final McpJsonRpcError error;

	McpProtocolJsonRpcException(@NonNull McpJsonRpcError error) {
		super(null, null, false, false);
		this.error = requireNonNull(error);
	}

	@NonNull
	McpJsonRpcError error() {
		return error;
	}
}

/**
 * Exact executable route for one registered tool.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationToolRoute(@NonNull McpApplicationRequestHandler handler,
		@NonNull McpRateLimiter rateLimiter,
		@NonNull McpInputRequestPlan inputRequestPlan,
		@NonNull McpRequestStateMode requestStateMode) {
	McpApplicationToolRoute(@NonNull McpApplicationRequestHandler handler,
			@NonNull McpRateLimiter rateLimiter) {
		this(handler, rateLimiter, McpInputRequestPlan.empty(),
				McpRequestStateMode.NONE);
	}

	McpApplicationToolRoute(@NonNull McpApplicationRequestHandler handler,
			@NonNull McpRateLimiter rateLimiter,
			@NonNull McpInputRequestPlan inputRequestPlan) {
		this(handler, rateLimiter, inputRequestPlan, McpRequestStateMode.NONE);
	}

	McpApplicationToolRoute {
		requireNonNull(handler);
		requireNonNull(rateLimiter);
		requireNonNull(inputRequestPlan);
		requireNonNull(requestStateMode);
	}
}

/**
 * Exact executable route for one registered prompt.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationPromptRoute(@NonNull McpApplicationRequestHandler handler,
		@NonNull McpInputRequestPlan inputRequestPlan,
		@NonNull McpRequestStateMode requestStateMode) {
	McpApplicationPromptRoute(@NonNull McpApplicationRequestHandler handler) {
		this(handler, McpInputRequestPlan.empty(), McpRequestStateMode.NONE);
	}

	McpApplicationPromptRoute(@NonNull McpApplicationRequestHandler handler,
			@NonNull McpInputRequestPlan inputRequestPlan) {
		this(handler, inputRequestPlan, McpRequestStateMode.NONE);
	}

	McpApplicationPromptRoute {
		requireNonNull(handler);
		requireNonNull(inputRequestPlan);
		requireNonNull(requestStateMode);
	}
}

/**
 * Bridge-neutral resource-read handler. Public model adaptation remains
 * outside the protocol package.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationResourceReadHandler {
	@NonNull
	McpWireResult handle(@NonNull McpApplicationResourceReadInvocation invocation)
			throws Exception;
}

/**
 * Immutable resource-read invocation after exact/template route resolution.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationResourceReadInvocation(
		@NonNull McpApplicationInvocation invocation, @NonNull String uri,
		@NonNull Map<@NonNull String, @NonNull String> templateVariables,
		@NonNull McpResourceCachePolicy cachePolicy) {
	McpApplicationResourceReadInvocation {
		requireNonNull(invocation);
		uri = McpLevelOneUriTemplate.requireValidAbsoluteUri(uri, "Resource URI");
		requireNonNull(templateVariables);
		Map<String, String> copiedVariables = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : templateVariables.entrySet())
			copiedVariables.put(requireNonNull(entry.getKey()),
					requireNonNull(entry.getValue()));
		templateVariables = Collections.unmodifiableMap(copiedVariables);
		requireNonNull(cachePolicy);
	}
}

/**
 * Exact resource-read route and its fixed cache owner.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationResourceReadRoute(
		@NonNull McpApplicationResourceReadHandler handler,
		@NonNull McpResourceCachePolicy cachePolicy,
		@NonNull McpInputRequestPlan inputRequestPlan,
		@NonNull McpRequestStateMode requestStateMode) {
	McpApplicationResourceReadRoute(@NonNull McpApplicationResourceReadHandler handler) {
		this(handler, McpResourceCachePolicy.privateNoCache(),
				McpInputRequestPlan.empty(), McpRequestStateMode.NONE);
	}

	McpApplicationResourceReadRoute(
			@NonNull McpApplicationResourceReadHandler handler,
			@NonNull McpResourceCachePolicy cachePolicy) {
		this(handler, cachePolicy, McpInputRequestPlan.empty(),
				McpRequestStateMode.NONE);
	}

	McpApplicationResourceReadRoute(
			@NonNull McpApplicationResourceReadHandler handler,
			@NonNull McpResourceCachePolicy cachePolicy,
			@NonNull McpInputRequestPlan inputRequestPlan) {
		this(handler, cachePolicy, inputRequestPlan, McpRequestStateMode.NONE);
	}

	McpApplicationResourceReadRoute {
		requireNonNull(handler);
		requireNonNull(cachePolicy);
		requireNonNull(inputRequestPlan);
		requireNonNull(requestStateMode);
	}
}

/**
 * Ordered resource-template route. The two-argument constructor is the bridge
 * seam; parsing is an internal normalization concern.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationResourceTemplateRoute(@NonNull String uriTemplate,
		@NonNull McpApplicationResourceReadRoute readRoute,
		@NonNull McpLevelOneUriTemplate parsedTemplate) {
	McpApplicationResourceTemplateRoute(@NonNull String uriTemplate,
			@NonNull McpApplicationResourceReadRoute readRoute) {
		this(uriTemplate, readRoute, McpLevelOneUriTemplate.parse(uriTemplate));
	}

	McpApplicationResourceTemplateRoute {
		uriTemplate = McpProtocolSupport.requireNonBlank(uriTemplate,
				"Resource URI template route");
		requireNonNull(readRoute);
		requireNonNull(parsedTemplate);
		if (!uriTemplate.equals(parsedTemplate.template()))
			throw new IllegalArgumentException(
					"Parsed resource URI-template route does not match its identity.");
	}
}

/**
 * One resolved URI-template route and its strictly decoded values.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationResourceTemplateMatch(@NonNull String uriTemplate,
		@NonNull McpApplicationResourceReadRoute readRoute,
		@NonNull Map<@NonNull String, @NonNull String> templateVariables) {
	McpApplicationResourceTemplateMatch {
		requireNonNull(uriTemplate);
		requireNonNull(readRoute);
		templateVariables = Collections.unmodifiableMap(
				new LinkedHashMap<>(requireNonNull(templateVariables)));
	}
}

/**
 * Bridge-neutral dynamic resources/list handler.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationResourceListHandler {
	@NonNull
	McpWireResult handle(@NonNull McpApplicationResourceListInvocation invocation)
			throws Exception;
}

/**
 * Immutable dynamic-list invocation. The exact descriptor snapshot is a
 * convenience input only and is never merged into the returned page.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationResourceListInvocation(
		@NonNull McpApplicationInvocation invocation,
		@NonNull Optional<@NonNull String> cursor,
		@NonNull List<@NonNull McpNormalizedResourceDescriptor>
				registeredResourceDescriptors,
		@NonNull McpResourceCachePolicy cachePolicy) {
	McpApplicationResourceListInvocation {
		requireNonNull(invocation);
		requireNonNull(cursor);
		registeredResourceDescriptors = List.copyOf(
				requireNonNull(registeredResourceDescriptors));
		requireNonNull(cachePolicy);
	}
}

/**
 * Sole dynamic resources/list authority for an endpoint.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationResourceListRoute(
		@NonNull McpApplicationResourceListHandler handler) {
	McpApplicationResourceListRoute {
		requireNonNull(handler);
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpApplicationRequestRouter {
	@NonNull
	private final Map<@NonNull String, @NonNull McpApplicationRequestHandler> handlersByMethod;
	@NonNull
	private final Map<@NonNull String, @NonNull McpApplicationToolRoute> toolRoutesByName;
	@NonNull
	private final Map<@NonNull String, @NonNull McpApplicationPromptRoute> promptRoutesByName;
	@NonNull
	private final Map<@NonNull URI, @NonNull McpApplicationResourceReadRoute>
			exactResourceRoutesByUri;
	@NonNull
	private final List<@NonNull McpApplicationResourceTemplateRoute>
			resourceTemplateRoutes;
	@NonNull
	private final Optional<@NonNull McpApplicationResourceListRoute> resourceListRoute;

	private McpApplicationRequestRouter(
			@NonNull Map<@NonNull String, @NonNull McpApplicationRequestHandler> handlersByMethod,
			@NonNull Map<@NonNull String, @NonNull McpApplicationToolRoute> toolRoutesByName,
			@NonNull Map<@NonNull String, @NonNull McpApplicationPromptRoute> promptRoutesByName,
			@NonNull Map<@NonNull URI, @NonNull McpApplicationResourceReadRoute>
					exactResourceRoutesByUri,
			@NonNull List<@NonNull McpApplicationResourceTemplateRoute>
					resourceTemplateRoutes,
			@NonNull Optional<@NonNull McpApplicationResourceListRoute> resourceListRoute) {
		this.handlersByMethod = handlersByMethod;
		this.toolRoutesByName = toolRoutesByName;
		this.promptRoutesByName = promptRoutesByName;
		this.exactResourceRoutesByUri = exactResourceRoutesByUri;
		this.resourceTemplateRoutes = resourceTemplateRoutes;
		this.resourceListRoute = resourceListRoute;
	}

	@NonNull
	static McpApplicationRequestRouter empty() {
		return new McpApplicationRequestRouter(Map.of(), Map.of(), Map.of(),
				Map.of(), List.of(), Optional.empty());
	}

	@NonNull
	static McpApplicationRequestRouter fromHandlers(
			@NonNull Map<@NonNull String, @NonNull McpApplicationRequestHandler> handlersByMethod) {
		return fromHandlersAndToolAndPromptRoutes(handlersByMethod, Map.of(), Map.of());
	}

	@NonNull
	static McpApplicationRequestRouter fromToolRoutes(
			@NonNull Map<@NonNull String, @NonNull McpApplicationToolRoute> toolRoutesByName) {
		return fromHandlersAndToolAndPromptRoutes(Map.of(), toolRoutesByName, Map.of());
	}

	@NonNull
	static McpApplicationRequestRouter fromPromptRoutes(
			@NonNull Map<@NonNull String, @NonNull McpApplicationPromptRoute> promptRoutesByName) {
		return fromHandlersAndToolAndPromptRoutes(Map.of(), Map.of(), promptRoutesByName);
	}

	@NonNull
	static McpApplicationRequestRouter fromToolAndPromptRoutes(
			@NonNull Map<@NonNull String, @NonNull McpApplicationToolRoute> toolRoutesByName,
			@NonNull Map<@NonNull String, @NonNull McpApplicationPromptRoute> promptRoutesByName) {
		return fromHandlersAndToolAndPromptRoutes(
				Map.of(), toolRoutesByName, promptRoutesByName);
	}

	@NonNull
	static McpApplicationRequestRouter fromHandlersAndToolRoutes(
			@NonNull Map<@NonNull String, @NonNull McpApplicationRequestHandler> handlersByMethod,
			@NonNull Map<@NonNull String, @NonNull McpApplicationToolRoute> toolRoutesByName) {
		return fromHandlersAndToolAndPromptRoutes(
				handlersByMethod, toolRoutesByName, Map.of());
	}

	@NonNull
	static McpApplicationRequestRouter fromHandlersAndToolAndPromptRoutes(
			@NonNull Map<@NonNull String, @NonNull McpApplicationRequestHandler> handlersByMethod,
			@NonNull Map<@NonNull String, @NonNull McpApplicationToolRoute> toolRoutesByName,
			@NonNull Map<@NonNull String, @NonNull McpApplicationPromptRoute> promptRoutesByName) {
		return fromHandlersAndOperationRoutes(handlersByMethod, toolRoutesByName,
				promptRoutesByName, Map.of(), List.of(), Optional.empty());
	}

	@NonNull
	static McpApplicationRequestRouter fromResourceRoutes(
			@NonNull Map<@NonNull String, @NonNull McpApplicationResourceReadRoute>
					exactResourceRoutesByUri,
			@NonNull List<@NonNull McpApplicationResourceTemplateRoute>
					resourceTemplateRoutes,
			@NonNull Optional<@NonNull McpApplicationResourceListRoute> resourceListRoute) {
		return fromHandlersAndOperationRoutes(Map.of(), Map.of(), Map.of(),
				exactResourceRoutesByUri, resourceTemplateRoutes, resourceListRoute);
	}

	@NonNull
	static McpApplicationRequestRouter fromHandlersAndOperationRoutes(
			@NonNull Map<@NonNull String, @NonNull McpApplicationRequestHandler> handlersByMethod,
			@NonNull Map<@NonNull String, @NonNull McpApplicationToolRoute> toolRoutesByName,
			@NonNull Map<@NonNull String, @NonNull McpApplicationPromptRoute> promptRoutesByName,
			@NonNull Map<@NonNull String, @NonNull McpApplicationResourceReadRoute>
					exactResourceRoutesByUri,
			@NonNull List<@NonNull McpApplicationResourceTemplateRoute>
					resourceTemplateRoutes,
			@NonNull Optional<@NonNull McpApplicationResourceListRoute> resourceListRoute) {
		requireNonNull(handlersByMethod);
		requireNonNull(toolRoutesByName);
		requireNonNull(promptRoutesByName);
		requireNonNull(exactResourceRoutesByUri);
		requireNonNull(resourceTemplateRoutes);
		requireNonNull(resourceListRoute);
		Map<String, McpApplicationRequestHandler> copied =
				new LinkedHashMap<>(handlersByMethod.size());

		for (Map.Entry<String, McpApplicationRequestHandler> entry : handlersByMethod.entrySet()) {
			String method = requireNonNull(entry.getKey());
			if (method.isBlank())
				throw new IllegalArgumentException("Application MCP methods must not be blank.");
			if ("server/discover".equals(method) || "tools/list".equals(method)
					|| "prompts/list".equals(method)
					|| "resources/list".equals(method)
					|| "resources/templates/list".equals(method))
				throw new IllegalArgumentException(
						"Framework-owned MCP methods cannot be replaced by an application handler.");
			copied.put(method, requireNonNull(entry.getValue()));
		}

		Map<String, McpApplicationToolRoute> copiedToolRoutes =
				new LinkedHashMap<>(toolRoutesByName.size());
		for (Map.Entry<String, McpApplicationToolRoute> entry
				: toolRoutesByName.entrySet()) {
			String name = McpProtocolSupport.requireNonBlank(
					requireNonNull(entry.getKey()), "Tool route name");
			copiedToolRoutes.put(name, requireNonNull(entry.getValue()));
		}

		Map<String, McpApplicationPromptRoute> copiedPromptRoutes =
				new LinkedHashMap<>(promptRoutesByName.size());
		for (Map.Entry<String, McpApplicationPromptRoute> entry
				: promptRoutesByName.entrySet()) {
			String name = McpProtocolSupport.requireNonBlank(
					requireNonNull(entry.getKey()), "Prompt route name");
			copiedPromptRoutes.put(name, requireNonNull(entry.getValue()));
		}

		Map<URI, McpApplicationResourceReadRoute> copiedExactResourceRoutes =
				new LinkedHashMap<>(exactResourceRoutesByUri.size());
		for (Map.Entry<String, McpApplicationResourceReadRoute> entry
				: exactResourceRoutesByUri.entrySet()) {
			String wireUri = McpLevelOneUriTemplate.requireValidAbsoluteUri(
					requireNonNull(entry.getKey()), "Exact resource route URI");
			URI uri = URI.create(wireUri);
			if (copiedExactResourceRoutes.putIfAbsent(uri,
					requireNonNull(entry.getValue())) != null)
				throw new IllegalArgumentException(
						"Equivalent exact resource route URIs are not permitted: "
								+ wireUri);
		}

		List<McpApplicationResourceTemplateRoute> copiedResourceTemplateRoutes =
				List.copyOf(resourceTemplateRoutes);
		for (int left = 0; left < copiedResourceTemplateRoutes.size(); ++left) {
			McpApplicationResourceTemplateRoute leftRoute = requireNonNull(
					copiedResourceTemplateRoutes.get(left));
			for (int right = left + 1; right < copiedResourceTemplateRoutes.size(); ++right) {
				McpApplicationResourceTemplateRoute rightRoute = requireNonNull(
						copiedResourceTemplateRoutes.get(right));
				if (leftRoute.parsedTemplate().potentiallyOverlaps(
						rightRoute.parsedTemplate()))
					throw new IllegalArgumentException(
							"Potentially overlapping resource URI-template routes '"
									+ leftRoute.uriTemplate() + "' and '"
									+ rightRoute.uriTemplate() + "'.");
			}
		}

		return new McpApplicationRequestRouter(
				Collections.unmodifiableMap(copied),
				Collections.unmodifiableMap(copiedToolRoutes),
				Collections.unmodifiableMap(copiedPromptRoutes),
				Collections.unmodifiableMap(copiedExactResourceRoutes),
				copiedResourceTemplateRoutes, resourceListRoute);
	}

	@NonNull
	Optional<@NonNull McpApplicationRequestHandler> resolve(@NonNull String method) {
		return Optional.ofNullable(handlersByMethod.get(requireNonNull(method)));
	}

	@NonNull
	Optional<@NonNull McpApplicationToolRoute> resolveTool(@NonNull String name) {
		return Optional.ofNullable(toolRoutesByName.get(requireNonNull(name)));
	}

	@NonNull
	Optional<@NonNull McpApplicationPromptRoute> resolvePrompt(@NonNull String name) {
		return Optional.ofNullable(promptRoutesByName.get(requireNonNull(name)));
	}

	@NonNull
	Optional<@NonNull McpApplicationResourceReadRoute> resolveExactResource(
			@NonNull String uri) {
		String wireUri = McpLevelOneUriTemplate.requireValidAbsoluteUri(
				requireNonNull(uri), "Exact resource route URI");
		return Optional.ofNullable(exactResourceRoutesByUri.get(URI.create(wireUri)));
	}

	@NonNull
	Optional<@NonNull McpApplicationResourceTemplateMatch> resolveResourceTemplate(
			@NonNull String uri) {
		requireNonNull(uri);
		McpApplicationResourceTemplateMatch match = null;
		for (McpApplicationResourceTemplateRoute route : resourceTemplateRoutes) {
			Optional<Map<String, String>> variables = route.parsedTemplate().match(uri);
			if (variables.isEmpty())
				continue;
			if (match != null)
				throw new IllegalStateException(
						"A resource URI matched more than one normalized template route.");
			match = new McpApplicationResourceTemplateMatch(route.uriTemplate(),
					route.readRoute(), variables.orElseThrow());
		}
		return Optional.ofNullable(match);
	}

	@NonNull
	Optional<@NonNull McpApplicationResourceListRoute> resourceListRoute() {
		return resourceListRoute;
	}

	boolean hasToolRoutes() {
		return !toolRoutesByName.isEmpty();
	}

	boolean hasPromptRoutes() {
		return !promptRoutesByName.isEmpty();
	}

	boolean hasResourceReadRoutes() {
		return !exactResourceRoutesByUri.isEmpty() || !resourceTemplateRoutes.isEmpty();
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationExecutionConfiguration(int handlerConcurrency,
		int handlerQueueCapacity, @NonNull Duration requestDeadline,
		@NonNull Duration timerResolution) {
	McpApplicationExecutionConfiguration {
		if (handlerConcurrency < 1)
			throw new IllegalArgumentException("Handler concurrency must be positive.");
		if (handlerQueueCapacity < 1)
			throw new IllegalArgumentException("Handler queue capacity must be positive.");
		positiveDuration(requestDeadline, "Request deadline");
		positiveDuration(timerResolution, "Timer resolution");
	}

	@NonNull
	static McpApplicationExecutionConfiguration productionDefaults() {
		return new McpApplicationExecutionConfiguration(
				32, 128, Duration.ofSeconds(60), Duration.ofMillis(10));
	}

	private static void positiveDuration(@NonNull Duration value,
			@NonNull String description) {
		requireNonNull(value);
		if (value.isZero() || value.isNegative())
			throw new IllegalArgumentException(description + " must be positive.");
		try {
			if (value.toNanos() < 1L)
				throw new IllegalArgumentException(description + " must be positive.");
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					description + " must fit in a signed nanosecond duration.", exception);
		}
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationClock {
	@NonNull
	McpApplicationClock SYSTEM = System::nanoTime;

	long nanoTime();
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
interface McpProtocolDeadlineCycle {
	void run(long nowNanos);
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
interface McpApplicationCancellation extends CancelationToken {
	boolean isActive();

	boolean isCancellationRequested();

	@NonNull
	Optional<@NonNull StreamTerminationReason> reason();

	@Override
	@NonNull
	default Boolean isCanceled() {
		return isCancellationRequested();
	}

	@Override
	@NonNull
	default Optional<@NonNull StreamTerminationReason> getCancelationReason() {
		return reason();
	}

	@Override
	@NonNull
	default Optional<@NonNull Throwable> getCancelationCause() {
		return Optional.empty();
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpApplicationCancellationState implements McpApplicationCancellation {
	@NonNull
	private final AtomicReference<@Nullable StreamTerminationReason> reason;
	@NonNull
	private final Object callbacksLock;
	@NonNull
	private final List<@NonNull CallbackRegistration> callbackRegistrations;
	private boolean callbacksReleased;

	McpApplicationCancellationState() {
		this.reason = new AtomicReference<>();
		this.callbacksLock = new Object();
		this.callbackRegistrations = new ArrayList<>();
		this.callbacksReleased = false;
	}

	@Override
	public boolean isActive() {
		synchronized (callbacksLock) {
			return !callbacksReleased && reason.get() == null;
		}
	}

	@Override
	public boolean isCancellationRequested() {
		return reason.get() != null;
	}

	@Override
	@NonNull
	public Optional<@NonNull StreamTerminationReason> reason() {
		return Optional.ofNullable(reason.get());
	}

	@Override
	@NonNull
	public AutoCloseable onCancel(@NonNull Runnable callback) {
		CallbackRegistration registration = new CallbackRegistration(
				requireNonNull(callback));
		boolean runImmediately;
		boolean completed;
		synchronized (callbacksLock) {
			runImmediately = callbacksReleased && reason.get() != null;
			completed = callbacksReleased && reason.get() == null;
			if (!runImmediately && !completed)
				callbackRegistrations.add(registration);
		}
		if (runImmediately)
			registration.runIfOpen();
		else if (completed)
			registration.close();
		return registration;
	}

	boolean cancel(@NonNull StreamTerminationReason reason) {
		if (!fixReason(reason))
			return false;
		releaseCallbacks();
		return true;
	}

	boolean fixReason(@NonNull StreamTerminationReason reason) {
		StreamTerminationReason requiredReason = requireNonNull(reason);
		if (requiredReason == StreamTerminationReason.COMPLETED)
			throw new IllegalArgumentException(
					"Cancellation reason cannot be COMPLETED.");
		synchronized (callbacksLock) {
			if (callbacksReleased || this.reason.get() != null)
				return false;
			this.reason.set(requiredReason);
			return true;
		}
	}

	void complete() {
		List<CallbackRegistration> registrations;
		synchronized (callbacksLock) {
			if (callbacksReleased)
				return;
			if (reason.get() != null)
				throw new IllegalStateException(
						"A canceled invocation cannot complete normally.");
			callbacksReleased = true;
			registrations = List.copyOf(callbackRegistrations);
			callbackRegistrations.clear();
		}
		for (CallbackRegistration registration : registrations)
			registration.close();
	}

	void releaseCallbacks() {
		List<CallbackRegistration> registrations;
		synchronized (callbacksLock) {
			if (callbacksReleased)
				return;
			if (reason.get() == null)
				throw new IllegalStateException(
						"Cancellation callbacks require a fixed reason.");
			callbacksReleased = true;
			registrations = List.copyOf(callbackRegistrations);
			callbackRegistrations.clear();
		}
		for (CallbackRegistration registration : registrations)
			registration.runIfOpen();
	}

	/**
	 * One independently removable callback registration.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	private final class CallbackRegistration implements AutoCloseable {
		@NonNull
		private final Runnable callback;
		@NonNull
		private final AtomicBoolean open;

		private CallbackRegistration(@NonNull Runnable callback) {
			this.callback = requireNonNull(callback);
			this.open = new AtomicBoolean(true);
		}

		@Override
		public void close() {
			if (!open.compareAndSet(true, false))
				return;
			synchronized (callbacksLock) {
				callbackRegistrations.remove(this);
			}
		}

		private void runIfOpen() {
			if (!open.compareAndSet(true, false))
				return;
			try {
				callback.run();
			} catch (Throwable ignored) {
				// Application callback failures cannot escape the cancellation path.
			}
		}
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpApplicationInvocation {
	private final @Nullable Request sokletRequest;
	private final @Nullable McpRequestContext publicRequestContext;
	private final McpJsonRpcMessage.@NonNull Request request;
	@NonNull
	private final McpEffectiveAdmissionIdentity admissionIdentity;
	@NonNull
	private final Optional<@NonNull McpFrameworkRequestStateContinuation>
			frameworkRequestStateContinuation;
	@NonNull
	private final McpApplicationCancellation cancellation;
	@NonNull
	private final McpApplicationNotificationWriter notificationWriter;
	@NonNull
	private final McpApplicationHandlerEntryGuard handlerEntryGuard;

	McpApplicationInvocation(@Nullable Request sokletRequest,
			@Nullable McpRequestContext publicRequestContext,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull McpApplicationCancellation cancellation,
			@NonNull McpApplicationNotificationWriter notificationWriter,
			@NonNull McpApplicationHandlerEntryGuard handlerEntryGuard) {
		this(sokletRequest, publicRequestContext, request, admissionIdentity,
				Optional.empty(), cancellation, notificationWriter,
				handlerEntryGuard);
	}

	McpApplicationInvocation(@Nullable Request sokletRequest,
			@Nullable McpRequestContext publicRequestContext,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull Optional<@NonNull McpFrameworkRequestStateContinuation>
					frameworkRequestStateContinuation,
			@NonNull McpApplicationCancellation cancellation,
			@NonNull McpApplicationNotificationWriter notificationWriter,
			@NonNull McpApplicationHandlerEntryGuard handlerEntryGuard) {
		this.sokletRequest = sokletRequest;
		this.publicRequestContext = publicRequestContext;
		this.request = requireNonNull(request);
		this.admissionIdentity = requireNonNull(admissionIdentity);
		this.frameworkRequestStateContinuation = requireNonNull(
				frameworkRequestStateContinuation);
		this.cancellation = requireNonNull(cancellation);
		this.notificationWriter = requireNonNull(notificationWriter);
		this.handlerEntryGuard = requireNonNull(handlerEntryGuard);
	}

	McpJsonRpcMessage.@NonNull Request request() {
		return request;
	}

	@NonNull
	Optional<@NonNull Request> sokletRequest() {
		return Optional.ofNullable(sokletRequest);
	}

	@NonNull
	Optional<@NonNull McpRequestContext> publicRequestContext() {
		return Optional.ofNullable(publicRequestContext);
	}

	@NonNull
	McpEffectiveAdmissionIdentity admissionIdentity() {
		return admissionIdentity;
	}

	@NonNull
	Optional<@NonNull McpFrameworkRequestStateContinuation>
	frameworkRequestStateContinuation() {
		return frameworkRequestStateContinuation;
	}

	boolean isCancellationRequested() {
		return cancellation.isCancellationRequested();
	}

	@NonNull
	Optional<@NonNull StreamTerminationReason> cancellationReason() {
		return cancellation.reason();
	}

	boolean isActive() {
		return cancellation.isActive();
	}

	@NonNull
	CancelationToken cancelationToken() {
		return cancellation;
	}

	boolean sendNotification(McpJsonRpcMessage.@NonNull Notification notification)
			throws InterruptedException {
		return notificationWriter.write(requireNonNull(notification));
	}

	void requireHandlerEntry() throws InterruptedException {
		handlerEntryGuard.requireEntry();
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationNotificationWriter {
	boolean write(McpJsonRpcMessage.@NonNull Notification notification)
			throws InterruptedException;
}

/**
 * Commits entry into the public application handler at the request's current
 * cancellation and deadline boundary.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationHandlerEntryGuard {
	void requireEntry() throws InterruptedException;
}

/**
 * Prevents application dispatch from entering an interceptor after transport
 * ownership has already been canceled during registration.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationEntryGate {
	boolean allowsEntry();

	@NonNull
	static McpApplicationEntryGate alwaysInstance() {
		return () -> true;
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationResponse(int status, @NonNull String reason,
		@NonNull Optional<@NonNull McpJsonRpcMessage> message,
		@NonNull McpRequestOutcome outcome,
		@NonNull List<@NonNull Throwable> throwables) {
	McpApplicationResponse {
		if (status < 100 || status > 599)
			throw new IllegalArgumentException("HTTP status must be between 100 and 599.");
		requireNonNull(reason);
		requireNonNull(message);
		requireNonNull(outcome);
		throwables = List.copyOf(requireNonNull(throwables));
	}

	@NonNull
	static McpApplicationResponse success(@NonNull McpJsonRpcId id,
			@NonNull McpWireResult result) {
		McpWireResult requiredResult = requireNonNull(result);
		McpRequestOutcome outcome = McpResultType.INPUT_REQUIRED.equals(
				requiredResult.resultType())
				? McpRequestOutcome.INPUT_REQUIRED : McpRequestOutcome.COMPLETE;
		return new McpApplicationResponse(200, "OK", Optional.of(
				new McpJsonRpcMessage.ResultResponse(requireNonNull(id), requiredResult,
						McpJsonObject.empty())), outcome, List.of());
	}

	@NonNull
	static McpApplicationResponse internalError(@NonNull McpJsonRpcId id,
			int status, @NonNull String reason) {
		return error(id, status, reason, McpJsonRpcError.INTERNAL_ERROR,
				"Internal error", McpRequestOutcome.INTERNAL_ERROR, List.of());
	}

	@NonNull
	static McpApplicationResponse internalError(@NonNull McpJsonRpcId id,
			int status, @NonNull String reason, @NonNull Throwable throwable) {
		return error(id, status, reason, McpJsonRpcError.INTERNAL_ERROR,
				"Internal error", McpRequestOutcome.INTERNAL_ERROR,
				List.of(requireNonNull(throwable)));
	}

	@NonNull
	static McpApplicationResponse capacityRejected(@NonNull McpJsonRpcId id) {
		return error(id, 503, "Service Unavailable", McpJsonRpcError.INTERNAL_ERROR,
				"Internal error", McpRequestOutcome.REJECTED, List.of());
	}

	@NonNull
	static McpApplicationResponse queuedDeadline(@NonNull McpJsonRpcId id) {
		return error(id, 503, "Service Unavailable", McpJsonRpcError.INTERNAL_ERROR,
				"Internal error", McpRequestOutcome.DEADLINE_EXCEEDED, List.of());
	}

	@NonNull
	static McpApplicationResponse invalidParams(@NonNull McpJsonRpcId id) {
		return error(id, 400, "Bad Request", McpJsonRpcError.INVALID_PARAMS,
				"Invalid params", McpRequestOutcome.PROTOCOL_ERROR, List.of());
	}

	@NonNull
	static McpApplicationResponse applicationJsonRpcError(
			@NonNull McpJsonRpcId id, @NonNull McpJsonRpcError error) {
		return new McpApplicationResponse(400, "Bad Request", Optional.of(
				new McpJsonRpcMessage.ErrorResponse(Optional.of(requireNonNull(id)),
						requireNonNull(error), McpJsonObject.empty())),
				McpRequestOutcome.APPLICATION_ERROR, List.of());
	}

	@NonNull
	static McpApplicationResponse protocolJsonRpcError(
			@NonNull McpJsonRpcId id, @NonNull McpJsonRpcError error) {
		return new McpApplicationResponse(400, "Bad Request", Optional.of(
				new McpJsonRpcMessage.ErrorResponse(Optional.of(requireNonNull(id)),
						requireNonNull(error), McpJsonObject.empty())),
				McpRequestOutcome.PROTOCOL_ERROR, List.of());
	}

	@NonNull
	static McpApplicationResponse activeDeadline() {
		// Phase 3B.1 has no frozen pre-commit active-handler timeout wire mapping.
		// An empty 504 closes the JSON-only response lifetime without claiming one.
		return new McpApplicationResponse(504, "Gateway Timeout", Optional.empty(),
				McpRequestOutcome.DEADLINE_EXCEEDED, List.of());
	}

	@NonNull
	private static McpApplicationResponse error(@NonNull McpJsonRpcId id,
			int status, @NonNull String reason, int code, @NonNull String message,
			@NonNull McpRequestOutcome outcome,
			@NonNull List<@NonNull Throwable> throwables) {
		McpJsonRpcError error = new McpJsonRpcError(code, message, Optional.empty());
		return new McpApplicationResponse(status, reason, Optional.of(
				new McpJsonRpcMessage.ErrorResponse(Optional.of(requireNonNull(id)), error,
						McpJsonObject.empty())), outcome, throwables);
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@FunctionalInterface
interface McpApplicationResponseWriter {
	boolean write(@NonNull McpApplicationResponse response);

	default boolean writeNotification(McpJsonRpcMessage.@NonNull Notification notification)
			throws InterruptedException {
		return false;
	}
}

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpApplicationExecutionSnapshot(int configuredHandlerConcurrency,
		int configuredHandlerQueueCapacity, int activeHandlerSlots, int queuedRequests,
		int maximumObservedActiveHandlerSlots, int maximumObservedQueuedRequests,
		int activeIdentifiedRequestExchanges, int retainedExchanges,
		int retainedTransportLeases,
		long admittedRequests,
		long capacityRejections, long deadlineExpirations,
		long protocolDeadlineExpirations,
		long terminalResponses, long abandonedResponses, long responseCleanups,
		boolean accepting, boolean terminated) {
}

/**
 * One listener-generation's application execution state. Protocol parsing is
 * deliberately outside this type; handler admission returns immediately and
 * never consumes a protocol request-processing thread while application work
 * is queued or running.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpApplicationExecution {
	@ThreadSafe
	private record TransportLease(@NonNull MicrohttpRequest transportRequest,
			@NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		private TransportLease {
			requireNonNull(transportRequest);
			requireNonNull(responseWriter);
			requireNonNull(terminalCleanup);
		}
	}

	private enum TerminalState {
		OPEN,
		RESPONSE_OFFERED,
		ABANDONED
	}

	@NonNull
	private final McpApplicationExecutionConfiguration configuration;
	@NonNull
	private final McpApplicationClock clock;
	private final @Nullable McpProtocolDeadlineCycle protocolDeadlineCycle;
	@NonNull
	private final McpApplicationRequestInterceptor defaultRequestInterceptor;
	@NonNull
	private final ExecutorService handlerExecutor;
	@NonNull
	private final McpApplicationHandlerDispatcher dispatcher;
	@NonNull
	private final Object executionBoundaryLock;
	@NonNull
	private final Map<@NonNull MicrohttpRequest, @NonNull Exchange> requestsByIdentity;
	@NonNull
	private final ConcurrentHashMap<@NonNull Long, @NonNull Exchange> retainedExchanges;
	@NonNull
	private final AtomicLong exchangeSequence;
	@NonNull
	private final AtomicLong admittedRequests;
	@NonNull
	private final AtomicLong capacityRejections;
	@NonNull
	private final AtomicLong deadlineExpirations;
	@NonNull
	private final AtomicLong protocolDeadlineExpirations;
	@NonNull
	private final AtomicLong terminalResponses;
	@NonNull
	private final AtomicLong abandonedResponses;
	@NonNull
	private final AtomicLong responseCleanups;
	@NonNull
	private final AtomicBoolean started;
	@NonNull
	private final AtomicBoolean stopped;
	@NonNull
	private final AtomicReference<@Nullable StreamTerminationReason> stoppingReason;
	@NonNull
	private final Thread timerThread;

	McpApplicationExecution(
			@NonNull McpApplicationExecutionConfiguration configuration,
			@NonNull McpApplicationClock clock) {
		this(configuration, clock, McpApplicationHandlerExecutorFactory.production());
	}

	McpApplicationExecution(
			@NonNull McpApplicationExecutionConfiguration configuration,
			@NonNull McpApplicationClock clock,
			@NonNull McpApplicationHandlerExecutorFactory executorFactory) {
		this(configuration, clock, executorFactory, null);
	}

	McpApplicationExecution(
			@NonNull McpApplicationExecutionConfiguration configuration,
			@NonNull McpApplicationClock clock,
			@NonNull McpApplicationHandlerExecutorFactory executorFactory,
			@Nullable McpProtocolDeadlineCycle protocolDeadlineCycle) {
		this(configuration, clock, executorFactory, protocolDeadlineCycle,
				McpApplicationRequestInterceptor.passThroughInstance());
	}

	McpApplicationExecution(
			@NonNull McpApplicationExecutionConfiguration configuration,
			@NonNull McpApplicationClock clock,
			@NonNull McpApplicationHandlerExecutorFactory executorFactory,
			@Nullable McpProtocolDeadlineCycle protocolDeadlineCycle,
			@NonNull McpApplicationRequestInterceptor requestInterceptor) {
		this.configuration = requireNonNull(configuration);
		this.clock = requireNonNull(clock);
		this.protocolDeadlineCycle = protocolDeadlineCycle;
		this.defaultRequestInterceptor = requireNonNull(requestInterceptor);
		this.handlerExecutor = requireNonNull(requireNonNull(executorFactory).create(
				configuration.handlerConcurrency()),
				"The application handler executor factory returned null.");
		this.dispatcher = new McpApplicationHandlerDispatcher(
				configuration.handlerConcurrency(), configuration.handlerQueueCapacity(),
				handlerExecutor);
		this.executionBoundaryLock = new Object();
		this.requestsByIdentity = Collections.synchronizedMap(new IdentityHashMap<>());
		this.retainedExchanges = new ConcurrentHashMap<>();
		this.exchangeSequence = new AtomicLong();
		this.admittedRequests = new AtomicLong();
		this.capacityRejections = new AtomicLong();
		this.deadlineExpirations = new AtomicLong();
		this.protocolDeadlineExpirations = new AtomicLong();
		this.terminalResponses = new AtomicLong();
		this.abandonedResponses = new AtomicLong();
		this.responseCleanups = new AtomicLong();
		this.started = new AtomicBoolean();
		this.stopped = new AtomicBoolean();
		this.stoppingReason = new AtomicReference<>();
		this.timerThread = new Thread(this::runTimerLoop, "soklet-mcp-deadline");
		this.timerThread.setDaemon(false);
	}

	void start() {
		if (stopped.get() || !started.compareAndSet(false, true))
			throw new IllegalStateException("Application execution has already been started.");
		timerThread.start();
	}

	void dispatch(@NonNull MicrohttpRequest transportRequest,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull McpApplicationRequestHandler handler,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		dispatchInternal(transportRequest, null, request, admissionIdentity,
				Optional.empty(), handler, null,
				this.defaultRequestInterceptor, McpApplicationEntryGate.alwaysInstance(),
				deadlineNanos, responseWriter,
				terminalCleanup);
	}

	void dispatchWithSokletRequest(@NonNull MicrohttpRequest transportRequest,
			@NonNull Request sokletRequest,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull McpApplicationRequestHandler handler,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		dispatchInternal(transportRequest, requireNonNull(sokletRequest), request,
				admissionIdentity, Optional.empty(), handler, null,
				this.defaultRequestInterceptor,
				McpApplicationEntryGate.alwaysInstance(), deadlineNanos,
				responseWriter, terminalCleanup);
	}

	void dispatchWithSokletRequest(@NonNull MicrohttpRequest transportRequest,
			@NonNull Request sokletRequest,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull McpApplicationRequestHandler handler,
			@NonNull McpApplicationRequestInterceptor requestInterceptor,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		dispatchInternal(transportRequest, requireNonNull(sokletRequest), request,
				admissionIdentity, Optional.empty(), handler, null, requestInterceptor,
				McpApplicationEntryGate.alwaysInstance(), deadlineNanos,
				responseWriter, terminalCleanup);
	}

	void dispatchWithSokletRequest(@NonNull MicrohttpRequest transportRequest,
			@NonNull Request sokletRequest,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull McpApplicationRequestHandler handler,
			@NonNull McpApplicationRequestInterceptor requestInterceptor,
			@NonNull McpApplicationEntryGate applicationEntryGate,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		dispatchInternal(transportRequest, requireNonNull(sokletRequest), request,
				admissionIdentity, Optional.empty(), handler, null, requestInterceptor,
				requireNonNull(applicationEntryGate), deadlineNanos,
				responseWriter, terminalCleanup);
	}

	void dispatchWithSokletRequest(@NonNull MicrohttpRequest transportRequest,
			@NonNull Request sokletRequest,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull Optional<@NonNull McpFrameworkRequestStateContinuation>
					frameworkRequestStateContinuation,
			@NonNull McpApplicationRequestHandler handler,
			@NonNull McpApplicationRequestInterceptor requestInterceptor,
			@NonNull McpApplicationEntryGate applicationEntryGate,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		dispatchInternal(transportRequest, requireNonNull(sokletRequest), request,
				admissionIdentity, frameworkRequestStateContinuation, handler, null,
				requestInterceptor, requireNonNull(applicationEntryGate),
				deadlineNanos, responseWriter, terminalCleanup);
	}

	void dispatchWithSokletRequest(@NonNull MicrohttpRequest transportRequest,
			@NonNull Request sokletRequest,
			@NonNull McpRequestContext publicRequestContext,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull McpApplicationRequestHandler handler,
			@NonNull McpApplicationRequestInterceptor requestInterceptor,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		dispatchInternal(transportRequest, requireNonNull(sokletRequest), request,
				admissionIdentity, Optional.empty(), handler,
				requireNonNull(publicRequestContext),
				requestInterceptor, McpApplicationEntryGate.alwaysInstance(),
				deadlineNanos, responseWriter, terminalCleanup);
	}

	void dispatchWithSokletRequest(@NonNull MicrohttpRequest transportRequest,
			@NonNull Request sokletRequest,
			@NonNull McpRequestContext publicRequestContext,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull McpApplicationRequestHandler handler,
			@NonNull McpApplicationRequestInterceptor requestInterceptor,
			@NonNull McpApplicationEntryGate applicationEntryGate,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		dispatchInternal(transportRequest, requireNonNull(sokletRequest), request,
				admissionIdentity, Optional.empty(), handler,
				requireNonNull(publicRequestContext),
				requestInterceptor, requireNonNull(applicationEntryGate),
				deadlineNanos, responseWriter, terminalCleanup);
	}

	void dispatchWithSokletRequest(@NonNull MicrohttpRequest transportRequest,
			@NonNull Request sokletRequest,
			@NonNull McpRequestContext publicRequestContext,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull Optional<@NonNull McpFrameworkRequestStateContinuation>
					frameworkRequestStateContinuation,
			@NonNull McpApplicationRequestHandler handler,
			@NonNull McpApplicationRequestInterceptor requestInterceptor,
			@NonNull McpApplicationEntryGate applicationEntryGate,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		dispatchInternal(transportRequest, requireNonNull(sokletRequest), request,
				admissionIdentity, frameworkRequestStateContinuation, handler,
				requireNonNull(publicRequestContext), requestInterceptor,
				requireNonNull(applicationEntryGate), deadlineNanos,
				responseWriter, terminalCleanup);
	}

	private void dispatchInternal(@NonNull MicrohttpRequest transportRequest,
			@Nullable Request sokletRequest,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
			@NonNull Optional<@NonNull McpFrameworkRequestStateContinuation>
					frameworkRequestStateContinuation,
			@NonNull McpApplicationRequestHandler handler,
			@Nullable McpRequestContext publicRequestContext,
			@NonNull McpApplicationRequestInterceptor requestInterceptor,
			@NonNull McpApplicationEntryGate applicationEntryGate,
			long deadlineNanos, @NonNull McpApplicationResponseWriter responseWriter,
			@NonNull Runnable terminalCleanup) {
		requireNonNull(transportRequest);
		requireNonNull(request);
		requireNonNull(admissionIdentity);
		requireNonNull(frameworkRequestStateContinuation);
		requireNonNull(handler);
		requireNonNull(requestInterceptor);
		requireNonNull(applicationEntryGate);
		requireNonNull(responseWriter);
		requireNonNull(terminalCleanup);

		if (stopped.get()) {
			terminalCleanup.run();
			return;
		}

		long exchangeId = exchangeSequence.incrementAndGet();
		Exchange exchange = new Exchange(exchangeId, transportRequest, sokletRequest, request,
				publicRequestContext, admissionIdentity,
				frameworkRequestStateContinuation, handler, requestInterceptor,
				applicationEntryGate, deadlineNanos, responseWriter,
				terminalCleanup);

		McpApplicationHandlerDispatcher.Ticket ticket = dispatcher.newTicket(
				exchange::runHandler, exchange::submissionFailed);
		exchange.bindTicket(ticket);
		requestsByIdentity.put(transportRequest, exchange);
		retainedExchanges.put(exchangeId, exchange);
		if (clock.nanoTime() - deadlineNanos >= 0L) {
			exchange.onDeadline();
			return;
		}

		McpApplicationHandlerDispatcher.Admission admission = dispatcher.admit(ticket);
		switch (admission) {
			case DISPATCHED, QUEUED -> {
				admittedRequests.incrementAndGet();
				signalDeadlineTimer();
			}
			case REJECTED -> {
				capacityRejections.incrementAndGet();
				exchange.respond(McpApplicationResponse.capacityRejected(request.id()));
			}
			case CLOSED -> exchange.releaseAfterClosure();
			case CANCELED -> exchange.cleanupRetainedExchange();
		}
	}

	void recordProtocolDeadlineExpiration() {
		protocolDeadlineExpirations.incrementAndGet();
		deadlineExpirations.incrementAndGet();
	}

	void recordStreamDeadlineExpiration() {
		deadlineExpirations.incrementAndGet();
	}

	/*
	 * Linearizes a bounded protocol-state mutation with this listener
	 * generation's stop boundary. Production reservations must not invoke user
	 * code or perform blocking work while the boundary is held.
	 */
	@NonNull
	<T extends @NonNull Object> Optional<@NonNull T> reserveProtocolOperationIfRunning(
			@NonNull Supplier<@NonNull T> reservation) {
		requireNonNull(reservation);
		synchronized (executionBoundaryLock) {
			if (stopped.get())
				return Optional.empty();
			return Optional.of(requireNonNull(reservation.get()));
		}
	}

	void cancel(@NonNull MicrohttpRequest request,
			@NonNull StreamTerminationReason reason,
			@Nullable Throwable cause) {
		Exchange exchange = requestsByIdentity.get(requireNonNull(request));
		if (exchange != null)
			exchange.cancel(reason, cause);
	}

	void runTimerCycle() {
		long now = clock.nanoTime();
		if (protocolDeadlineCycle != null)
			protocolDeadlineCycle.run(now);
		for (Exchange exchange : retainedExchanges.values()) {
			try {
				exchange.onTimer(now);
			} catch (Throwable throwable) {
				exchange.cancel(StreamTerminationReason.INTERNAL_ERROR, throwable);
			}
		}
	}

	@NonNull
	McpApplicationExecutionSnapshot snapshot() {
		return snapshot(0);
	}

	@NonNull
	McpApplicationExecutionSnapshot snapshot(
			int activeIdentifiedRequestExchanges) {
		McpApplicationHandlerDispatcher.Snapshot dispatcherSnapshot = dispatcher.snapshot();
		return new McpApplicationExecutionSnapshot(
				dispatcherSnapshot.concurrency(),
				dispatcherSnapshot.queueCapacity(),
				dispatcherSnapshot.activeSlots(),
				dispatcherSnapshot.queueDepth(),
				dispatcherSnapshot.maximumObservedActiveSlots(),
				dispatcherSnapshot.maximumObservedQueueDepth(),
				activeIdentifiedRequestExchanges,
				retainedExchanges.size(),
				(int) retainedExchanges.values().stream()
						.filter(Exchange::hasTransportLease).count(),
				admittedRequests.get(),
				capacityRejections.get(),
				deadlineExpirations.get(),
				protocolDeadlineExpirations.get(),
				terminalResponses.get(),
				abandonedResponses.get(),
				responseCleanups.get(),
				dispatcherSnapshot.accepting(),
				isTerminated());
	}

	void stop() {
		stop(StreamTerminationReason.SERVER_STOPPING);
	}

	void stop(@NonNull StreamTerminationReason reason) {
		requireNonNull(reason);
		synchronized (executionBoundaryLock) {
			if (stopped.get())
				return;
			stoppingReason.set(reason);
			stopped.set(true);
		}

		dispatcher.stopAccepting();
		for (Exchange exchange : List.copyOf(retainedExchanges.values())) {
			exchange.cancel(reason, null);
			// A terminal response may have won before shutdown while its handler is
			// still unwinding. It remains application work and receives the same
			// cooperative interruption signal.
			exchange.requestInterrupt();
		}
		// All dispatched tickets have been signaled explicitly. Graceful executor
		// shutdown is essential here: shutdownNow may discard a dispatcher-owned
		// runnable promoted while its current worker is still returning, leaving
		// the logical handler slot charged forever.
		handlerExecutor.shutdown();
		LockSupport.unpark(timerThread);
	}

	boolean awaitTermination(@NonNull Duration timeout) throws InterruptedException {
		requireNonNull(timeout);
		if (timeout.isNegative())
			throw new IllegalArgumentException("Termination timeout must not be negative.");
		long timeoutNanos = timeout.toNanos();
		if (timeoutNanos == 0L)
			return isTerminated();
		long startedAt = System.nanoTime();

		if (timerThread.isAlive()) {
			long milliseconds = TimeUnit.NANOSECONDS.toMillis(timeoutNanos);
			int nanoseconds = (int) (timeoutNanos
					- TimeUnit.MILLISECONDS.toNanos(milliseconds));
			timerThread.join(milliseconds, nanoseconds);
		}

		long elapsed = System.nanoTime() - startedAt;
		long remaining = Math.max(0L, timeoutNanos - Math.max(0L, elapsed));
		if (!handlerExecutor.isTerminated() && remaining > 0L)
			handlerExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
		return isTerminated();
	}

	boolean isTerminated() {
		McpApplicationHandlerDispatcher.Snapshot dispatcherSnapshot =
				dispatcher.snapshot();
		return stopped.get() && !timerThread.isAlive() && handlerExecutor.isTerminated()
				&& dispatcherSnapshot.activeSlots() == 0
				&& dispatcherSnapshot.queueDepth() == 0
				&& retainedExchanges.isEmpty()
				&& requestsByIdentity.isEmpty();
	}

	private void runTimerLoop() {
		while (!stopped.get()) {
			try {
				runTimerCycle();
			} catch (Throwable ignored) {
				// One cycle failure must not permanently disable request deadlines.
			}

			if (!stopped.get())
				LockSupport.parkNanos(configuration.timerResolution().toNanos());
		}
	}

	void signalDeadlineTimer() {
		LockSupport.unpark(timerThread);
	}

	@NonNull
	private StreamTerminationReason stoppingReason() {
		return requireNonNull(stoppingReason.get(),
				"A stopped application execution must have a stopping reason.");
	}

	@ThreadSafe
	private final class Exchange {
		private final long exchangeId;
		private final @Nullable Request sokletRequest;
		private final @Nullable McpRequestContext publicRequestContext;
		private final McpJsonRpcMessage.@NonNull Request request;
		@NonNull
		private final McpEffectiveAdmissionIdentity admissionIdentity;
		@NonNull
		private final Optional<@NonNull McpFrameworkRequestStateContinuation>
				frameworkRequestStateContinuation;
		@NonNull
		private final McpApplicationRequestHandler handler;
		@NonNull
		private final McpApplicationRequestInterceptor requestInterceptor;
		@NonNull
		private final McpApplicationEntryGate applicationEntryGate;
		private final long deadlineNanos;
		@NonNull
		private final AtomicReference<@Nullable TransportLease> transportLease;
		@NonNull
		private final Object terminalLock;
		@NonNull
		private final McpApplicationCancellationState cancellation;
		@NonNull
		private final AtomicBoolean handlerRunning;
		@NonNull
		private final AtomicBoolean handlerFinished;
		@NonNull
		private TerminalState terminalState;
		private boolean handlerEntryCommitted;
		private boolean publicHandlerEntryCommitted;
		private McpApplicationHandlerDispatcher.@Nullable Ticket ticket;

		private Exchange(long exchangeId, @NonNull MicrohttpRequest transportRequest,
				@Nullable Request sokletRequest,
				McpJsonRpcMessage.@NonNull Request request,
				@Nullable McpRequestContext publicRequestContext,
				@NonNull McpEffectiveAdmissionIdentity admissionIdentity,
				@NonNull Optional<@NonNull McpFrameworkRequestStateContinuation>
						frameworkRequestStateContinuation,
				@NonNull McpApplicationRequestHandler handler,
				@NonNull McpApplicationRequestInterceptor requestInterceptor,
				@NonNull McpApplicationEntryGate applicationEntryGate,
				long deadlineNanos,
				@NonNull McpApplicationResponseWriter responseWriter,
				@NonNull Runnable terminalCleanup) {
			this.exchangeId = exchangeId;
			this.sokletRequest = sokletRequest;
			this.publicRequestContext = publicRequestContext;
			this.request = request;
			this.admissionIdentity = admissionIdentity;
			this.frameworkRequestStateContinuation = requireNonNull(
					frameworkRequestStateContinuation);
			this.handler = handler;
			this.requestInterceptor = requireNonNull(requestInterceptor);
			this.applicationEntryGate = requireNonNull(applicationEntryGate);
			this.deadlineNanos = deadlineNanos;
			this.transportLease = new AtomicReference<>(new TransportLease(
					transportRequest, responseWriter, terminalCleanup));
			this.terminalLock = new Object();
			this.cancellation = new McpApplicationCancellationState();
			this.handlerRunning = new AtomicBoolean();
			this.handlerFinished = new AtomicBoolean();
			this.terminalState = TerminalState.OPEN;
			this.handlerEntryCommitted = false;
			this.publicHandlerEntryCommitted = false;
		}

		private void bindTicket(
				McpApplicationHandlerDispatcher.@NonNull Ticket ticket) {
			this.ticket = requireNonNull(ticket);
		}

		private void runHandler() {
			if (!handlerRunning.compareAndSet(false, true))
				throw new IllegalStateException("An MCP exchange cannot run twice.");

			try {
				if (clock.nanoTime() - deadlineNanos >= 0L) {
					onDeadline(false);
					return;
				}
				if (!beginApplicationInvocation())
					return;

				McpApplicationInvocation invocation = new McpApplicationInvocation(
						sokletRequest, publicRequestContext, request,
						admissionIdentity, frameworkRequestStateContinuation,
						cancellation,
						this::writeNotification, this::requirePublicHandlerEntry);
				AtomicBoolean handlerInvoked = new AtomicBoolean();
				AtomicBoolean interceptorActive = new AtomicBoolean(true);
				Thread interceptorThread = Thread.currentThread();
				McpWireResult result;
				try {
					result = this.requestInterceptor.intercept(invocation, () -> {
						if (!interceptorActive.get())
							throw new IllegalStateException(
									"An MCP interceptor continuation cannot be invoked after interception returns.");
						if (Thread.currentThread() != interceptorThread)
							throw new IllegalStateException(
									"An MCP interceptor continuation must be invoked on the interceptor thread.");
						if (!handlerInvoked.compareAndSet(false, true))
							throw new IllegalStateException(
									"An MCP interceptor continuation may be invoked only once.");
						if (!commitDownstreamInvocation())
							throw new InterruptedException(
									"The MCP request was canceled before handler invocation.");
						return handler.handle(invocation);
					});
				} finally {
					interceptorActive.set(false);
				}
				if (result == null)
					throw new IllegalStateException(
							"An MCP application interceptor or handler returned null.");
				respond(McpApplicationResponse.success(request.id(), result));
			} catch (McpInvalidApplicationInputException exception) {
				if (!cancellation.isCancellationRequested())
					respond(McpApplicationResponse.invalidParams(request.id()));
			} catch (McpProtocolJsonRpcException exception) {
				if (!cancellation.isCancellationRequested())
					respond(McpApplicationResponse.protocolJsonRpcError(
							request.id(), exception.error()));
			} catch (McpApplicationJsonRpcException exception) {
				if (!cancellation.isCancellationRequested())
					respond(McpApplicationResponse.applicationJsonRpcError(
							request.id(), exception.error()));
			} catch (McpRequestStateUnavailableException exception) {
				if (!cancellation.isCancellationRequested())
					respond(McpApplicationResponse.internalError(
							request.id(), 503, "Service Unavailable"));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				if (!cancellation.isCancellationRequested())
					respond(McpApplicationResponse.internalError(
							request.id(), 500, "Internal Server Error", exception));
			} catch (Throwable throwable) {
				if (!cancellation.isCancellationRequested())
					respond(McpApplicationResponse.internalError(
							request.id(), 500, "Internal Server Error", throwable));
			} finally {
				handlerRunning.set(false);
				handlerFinished.set(true);
				cleanupRetainedExchange();
			}
		}

		private boolean beginApplicationInvocation() {
			if (!applicationEntryGate.allowsEntry())
				return false;

			boolean shutdown;
			synchronized (executionBoundaryLock) {
				shutdown = stopped.get();
				if (!shutdown) {
					synchronized (terminalLock) {
						if (terminalState != TerminalState.OPEN
								|| cancellation.isCancellationRequested())
							return false;
					}
				}
			}

			if (shutdown)
				cancel(stoppingReason(), null);
			return !shutdown;
		}

		private boolean commitDownstreamInvocation() {
			if (clock.nanoTime() - deadlineNanos >= 0L) {
				onDeadline(false);
				return false;
			}

			boolean shutdown;
			synchronized (executionBoundaryLock) {
				shutdown = stopped.get();
				if (!shutdown) {
					synchronized (terminalLock) {
						if (terminalState != TerminalState.OPEN
								|| cancellation.isCancellationRequested())
							return false;
						if (handlerEntryCommitted)
							throw new IllegalStateException(
									"An MCP handler entry cannot be committed twice.");
						handlerEntryCommitted = true;
					}
				}
			}

			if (shutdown)
				cancel(stoppingReason(), null);
			return !shutdown;
		}

		private void requirePublicHandlerEntry() throws InterruptedException {
			if (clock.nanoTime() - deadlineNanos >= 0L) {
				onDeadline(false);
				throw new InterruptedException(
						"The MCP request deadline expired before public handler entry.");
			}

			boolean shutdown;
			synchronized (executionBoundaryLock) {
				shutdown = stopped.get();
				if (!shutdown) {
					synchronized (terminalLock) {
						if (terminalState != TerminalState.OPEN
								|| cancellation.isCancellationRequested())
							throw new InterruptedException(
									"The MCP request was canceled before public handler entry.");
						if (!handlerEntryCommitted)
							throw new IllegalStateException(
									"Public MCP handler entry requires a committed downstream invocation.");
						if (publicHandlerEntryCommitted)
							throw new IllegalStateException(
									"Public MCP handler entry cannot be committed twice.");
						publicHandlerEntryCommitted = true;
					}
				}
			}

			if (shutdown) {
				cancel(stoppingReason(), null);
				throw new InterruptedException(
						"The MCP server stopped before public handler entry.");
			}
		}

		private void submissionFailed(@NonNull Throwable throwable) {
			try {
				if (!cancellation.isCancellationRequested())
					respond(McpApplicationResponse.internalError(
							request.id(), 500, "Internal Server Error", throwable));
			} finally {
				// The dispatcher has already changed the ticket to REJECTED and
				// released its slot. Cancellation may also have detached the lease.
				cleanupRetainedExchange();
			}
		}

		private boolean respond(@NonNull McpApplicationResponse response) {
			TransportLease lease;
			boolean shutdown;
			boolean deadlineExpired;
			synchronized (executionBoundaryLock) {
				shutdown = stopped.get();
				if (!shutdown) {
					deadlineExpired = clock.nanoTime() - deadlineNanos >= 0L;
					if (!deadlineExpired) {
						synchronized (terminalLock) {
							if (terminalState != TerminalState.OPEN
									|| cancellation.isCancellationRequested())
								return false;
							lease = requireNonNull(transportLease.get(),
									"An open exchange must retain its transport lease.");
							terminalState = TerminalState.RESPONSE_OFFERED;
							cancellation.complete();
						}
					} else {
						lease = null;
					}
				} else {
					deadlineExpired = false;
					lease = null;
				}
			}
			if (shutdown) {
				cancel(stoppingReason(), null);
				return false;
			}
			if (deadlineExpired) {
				onDeadline(false);
				return false;
			}

			boolean accepted = false;
			try {
				accepted = requireNonNull(lease).responseWriter().write(response);
			} catch (Throwable ignored) {
				// The terminal reservation still wins when the transport callback fails.
			} finally {
				if (accepted)
					terminalResponses.incrementAndGet();
				else
					abandonedResponses.incrementAndGet();
				releaseResponseOwnership();
			}
			return accepted;
		}

		private boolean writeNotification(
				McpJsonRpcMessage.@NonNull Notification notification)
				throws InterruptedException {
			requireNonNull(notification);
			TransportLease lease;
			boolean shutdown;
			boolean deadlineExpired;
			synchronized (executionBoundaryLock) {
				shutdown = stopped.get();
				if (!shutdown) {
					deadlineExpired = clock.nanoTime() - deadlineNanos >= 0L;
					if (!deadlineExpired) {
						synchronized (terminalLock) {
							if (terminalState != TerminalState.OPEN
									|| cancellation.isCancellationRequested())
								return false;
							lease = requireNonNull(transportLease.get(),
									"An open exchange must retain its transport lease.");
						}
					} else {
						lease = null;
					}
				} else {
					deadlineExpired = false;
					lease = null;
				}
			}
			if (shutdown) {
				cancel(stoppingReason(), null);
				return false;
			}
			if (deadlineExpired) {
				onDeadline(false);
				return false;
			}

			return requireNonNull(lease).responseWriter()
					.writeNotification(notification);
		}

		private void cancel(@NonNull StreamTerminationReason reason,
				@Nullable Throwable ignored) {
			boolean cancelBeforeDispatch;
			boolean releaseCancellationCallbacks;
			synchronized (terminalLock) {
				if (terminalState != TerminalState.OPEN)
					return;
				// Retain only the application-visible reason. Transport exceptions can
				// retain connection internals and are detached with the response lease.
				releaseCancellationCallbacks = cancellation.fixReason(
						requireNonNull(reason));
				terminalState = TerminalState.ABANDONED;
				cancelBeforeDispatch = dispatcher.cancelBeforeDispatch(ticket());
			}

			if (releaseCancellationCallbacks)
				cancellation.releaseCallbacks();
			if (!cancelBeforeDispatch)
				ticket().requestInterrupt();
			abandonedResponses.incrementAndGet();
			releaseResponseOwnership();
		}

		private void onTimer(long now) {
			if (now - deadlineNanos >= 0L)
				onDeadline();
		}

		private void onDeadline() {
			onDeadline(true);
		}

		private void onDeadline(boolean requestInterrupt) {
			boolean canceledBeforeDispatch;
			boolean releaseCancellationCallbacks = false;
			McpApplicationResponse response;
			TransportLease lease;
			boolean shutdown;
			synchronized (executionBoundaryLock) {
				shutdown = stopped.get();
				if (!shutdown) {
					synchronized (terminalLock) {
						if (terminalState != TerminalState.OPEN)
							return;
						releaseCancellationCallbacks = cancellation.fixReason(
								StreamTerminationReason.RESPONSE_TIMEOUT);
						canceledBeforeDispatch = dispatcher.cancelBeforeDispatch(ticket());
						lease = requireNonNull(transportLease.get(),
								"An open exchange must retain its transport lease.");
						terminalState = TerminalState.RESPONSE_OFFERED;
						response = canceledBeforeDispatch
								? McpApplicationResponse.queuedDeadline(request.id())
								: McpApplicationResponse.activeDeadline();
					}
				} else {
					canceledBeforeDispatch = false;
					response = null;
					lease = null;
				}
			}
			if (shutdown) {
				cancel(stoppingReason(), null);
				return;
			}

			if (releaseCancellationCallbacks)
				cancellation.releaseCallbacks();
			deadlineExpirations.incrementAndGet();
			if (!canceledBeforeDispatch && requestInterrupt)
				ticket().requestInterrupt();
			boolean accepted = false;
			try {
				accepted = requireNonNull(lease).responseWriter().write(
						requireNonNull(response));
			} catch (Throwable ignored) {
				// A deadline still owns the terminal outcome when its write fails.
			} finally {
				if (accepted)
					terminalResponses.incrementAndGet();
				else
					abandonedResponses.incrementAndGet();
				releaseResponseOwnership();
			}
		}

		private void releaseAfterClosure() {
			boolean releaseCancellationCallbacks = false;
			synchronized (terminalLock) {
				if (terminalState == TerminalState.OPEN) {
					releaseCancellationCallbacks = cancellation.fixReason(
							stoppingReason());
					terminalState = TerminalState.ABANDONED;
					abandonedResponses.incrementAndGet();
				}
			}
			if (releaseCancellationCallbacks)
				cancellation.releaseCallbacks();
			releaseResponseOwnership();
		}

		private void requestInterrupt() {
			ticket().requestInterrupt();
		}

		private void releaseResponseOwnership() {
			TransportLease lease = transportLease.getAndSet(null);
			if (lease == null)
				return;

			requestsByIdentity.remove(lease.transportRequest(), this);
			responseCleanups.incrementAndGet();
			try {
				try {
					lease.terminalCleanup().run();
				} catch (Throwable ignored) {
					// Cleanup failures must not retain the exchange indefinitely.
				}
			} finally {
				cleanupRetainedExchange();
			}
		}

		private void cleanupRetainedExchange() {
			McpApplicationHandlerDispatcher.TicketState ticketState = ticket().state();
			boolean handlerCannotRun = ticketState ==
					McpApplicationHandlerDispatcher.TicketState.CANCELED
					|| ticketState == McpApplicationHandlerDispatcher.TicketState.REJECTED;
			if (transportLease.get() == null && (handlerFinished.get() || handlerCannotRun))
				retainedExchanges.remove(exchangeId, this);
		}

		private boolean hasTransportLease() {
			return transportLease.get() != null;
		}

		private McpApplicationHandlerDispatcher.@NonNull Ticket ticket() {
			return requireNonNull(ticket, "Application handler ticket has not been bound.");
		}
	}
}

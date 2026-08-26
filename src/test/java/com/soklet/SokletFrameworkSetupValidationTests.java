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

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.String.format;

/** Focused direct-owner coverage for framework-setup validation precedence. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class SokletFrameworkSetupValidationTests {
	@NonNull
	private static final String NO_RESOURCE_METHODS = format(
			"No Soklet Resource Methods were found. First, try to rebuild and see if that solves the problem. If not, please ensure your %s is configured correctly. See https://www.soklet.com/docs/request-handling#resource-method-resolution for details.",
			ResourceMethodResolver.class.getSimpleName());
	@NonNull
	private static final String MISSING_HTTP = format(
			"Resource Methods were found, but no %s is configured. See https://www.soklet.com/docs/server-configuration for details.",
			HttpServer.class.getSimpleName());
	@NonNull
	private static final String MISSING_SSE = format(
			"Resource Methods annotated with @%s were found, but no %s is configured. See https://www.soklet.com/docs/server-sent-events for details.",
			SseEventSource.class.getSimpleName(), SseServer.class.getSimpleName());

	@Test
	void resolverThrowWinsBeforeEveryConfigurationValidation() {
		IllegalStateException exactFailure = new IllegalStateException(
				"simulated resolver failure");
		CountingHttpServer http = new CountingHttpServer();
		CountingRejectingInstanceProvider instanceProvider =
				new CountingRejectingInstanceProvider();
		SokletConfig config = SokletConfig.withHttpServer(http)
				.resourceMethodResolver(throwingResolver(exactFailure))
				.instanceProvider(instanceProvider)
				.build();

		SokletStartupException startup = assertCompleteSetupFailure(config,
				Set.of(InternalParticipantKind.HTTP));

		Assertions.assertSame(exactFailure, startup.getCause());
		Assertions.assertEquals(0, http.initializeCalls());
		Assertions.assertEquals(0, http.startCalls());
		Assertions.assertEquals(0, instanceProvider.provisionCalls());
	}

	@Test
	void noResourceMethodsWinsBeforeLaterValidation() {
		CountingHttpServer http = new CountingHttpServer();
		CountingRejectingInstanceProvider instanceProvider =
				new CountingRejectingInstanceProvider();
		SokletConfig config = SokletConfig.withHttpServer(http)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.instanceProvider(instanceProvider)
				.build();

		SokletStartupException startup = assertCompleteSetupFailure(config,
				Set.of(InternalParticipantKind.HTTP));

		Assertions.assertInstanceOf(IllegalStateException.class,
				startup.getCause());
		Assertions.assertEquals(NO_RESOURCE_METHODS,
				startup.getCause().getMessage());
		Assertions.assertEquals(0, http.initializeCalls());
		Assertions.assertEquals(0, http.startCalls());
		Assertions.assertEquals(0, instanceProvider.provisionCalls());
	}

	@Test
	void missingHttpWinsBeforeMissingSseAndRemovedInjection()
			throws Exception {
		McpServer mcp = newMcpServer();
		CountingRejectingInstanceProvider instanceProvider =
				new CountingRejectingInstanceProvider();
		SokletConfig config = SokletConfig.withMcpServer(mcp)
				.resourceMethodResolver(multiInvalidResolver())
				.instanceProvider(instanceProvider)
				.build();

		SokletStartupException startup = assertCompleteSetupFailure(config,
				Set.of(InternalParticipantKind.MCP));

		Assertions.assertInstanceOf(IllegalStateException.class,
				startup.getCause());
		Assertions.assertEquals(MISSING_HTTP, startup.getCause().getMessage());
		Assertions.assertFalse(mcp.isStarted());
		Assertions.assertEquals(0, instanceProvider.provisionCalls());
	}

	@Test
	void missingSseWinsBeforeRemovedInjection() throws Exception {
		CountingHttpServer http = new CountingHttpServer();
		CountingRejectingInstanceProvider instanceProvider =
				new CountingRejectingInstanceProvider();
		SokletConfig config = SokletConfig.withHttpServer(http)
				.resourceMethodResolver(multiInvalidResolver())
				.instanceProvider(instanceProvider)
				.build();

		SokletStartupException startup = assertCompleteSetupFailure(config,
				Set.of(InternalParticipantKind.HTTP));

		Assertions.assertInstanceOf(IllegalStateException.class,
				startup.getCause());
		Assertions.assertEquals(MISSING_SSE, startup.getCause().getMessage());
		Assertions.assertEquals(0, http.initializeCalls());
		Assertions.assertEquals(0, http.startCalls());
		Assertions.assertEquals(0, instanceProvider.provisionCalls());
	}

	@Test
	void removedInjectionScanUsesSignatureThenParameterOrder()
			throws Exception {
		CountingHttpServer http = new CountingHttpServer();
		CountingSseServer sse = new CountingSseServer();
		CountingRejectingInstanceProvider instanceProvider =
				new CountingRejectingInstanceProvider();
		Method expectedMethod = MultiInvalidResource.class.getDeclaredMethod(
				"alpha", DerivedHttpServer.class, HttpServer.class);
		Parameter expectedParameter = expectedMethod.getParameters()[0];
		String expectedMessage = format(
				"Resource Method %s declares unsupported parameter %s of type %s. HttpServer resource-method injection was removed in Soklet 4.0; inject an application service through InstanceProvider instead.",
				expectedMethod, expectedParameter,
				DerivedHttpServer.class.getTypeName());
		SokletConfig config = SokletConfig.withHttpServer(http)
				.sseServer(sse)
				.resourceMethodResolver(multiInvalidResolver())
				.instanceProvider(instanceProvider)
				.build();

		SokletStartupException startup = assertCompleteSetupFailure(config,
				Set.of(InternalParticipantKind.HTTP,
						InternalParticipantKind.SSE));

		Assertions.assertInstanceOf(IllegalStateException.class,
				startup.getCause());
		Assertions.assertEquals(expectedMessage, startup.getCause().getMessage());
		Assertions.assertEquals(0, http.initializeCalls());
		Assertions.assertEquals(0, http.startCalls());
		Assertions.assertEquals(0, sse.initializeCalls());
		Assertions.assertEquals(0, sse.startCalls());
		Assertions.assertEquals(0, instanceProvider.provisionCalls());
	}

	@Test
	void resolverSnapshotIsFrozenBeforeValidation() throws Exception {
		ResourceMethod stable = onlyResourceMethod(
				StableResource.class.getDeclaredMethod("ok"));
		ResourceMethod laterMutation = onlyResourceMethod(
				MultiInvalidResource.class.getDeclaredMethod("zeta",
						HttpServer.class));
		ChangingResourceMethodSet changing = new ChangingResourceMethodSet(
				stable, laterMutation);
		CountingHttpServer http = new CountingHttpServer();
		SokletConfig config = SokletConfig.withHttpServer(http)
				.resourceMethodResolver(fixedResolver(changing))
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			Assertions.assertDoesNotThrow(soklet::start,
					"Every validation must inspect the one frozen resolver snapshot");
			Assertions.assertTrue(soklet.isStarted());
		}

		Assertions.assertEquals(1, http.initializeCalls());
		Assertions.assertEquals(1, http.startCalls());
		Assertions.assertEquals(1, http.stopCalls());
	}

	@Test
	void removedInjectionScanSortsAnExplicitReverseOrderedSnapshot()
			throws Exception {
		ResourceMethod alpha = onlyResourceMethod(
				MultiInvalidResource.class.getDeclaredMethod("alpha",
						DerivedHttpServer.class, HttpServer.class));
		ResourceMethod zeta = onlyResourceMethod(
				MultiInvalidResource.class.getDeclaredMethod("zeta",
						HttpServer.class));
		LinkedHashSet<ResourceMethod> reverseOrdered = new LinkedHashSet<>();
		reverseOrdered.add(zeta);
		reverseOrdered.add(alpha);

		IllegalStateException failure = Assertions.assertThrows(
				IllegalStateException.class, () -> SokletFrameworkSetup
						.validateNoRemovedHttpServerInjection(reverseOrdered));

		Assertions.assertTrue(failure.getMessage().contains(
				alpha.getMethod().toString()),
				"Signature sorting must beat the resolver set's iteration order");
		Assertions.assertTrue(failure.getMessage().contains(
				DerivedHttpServer.class.getTypeName()),
				"Parameter zero must win within the first sorted method");
	}

	@Test
	void dynamicResolverGuardRunsBeforeEveryConfiguredProvider()
			throws Exception {
		ResourceMethod stable = onlyResourceMethod(
				StableResource.class.getDeclaredMethod("ok"));
		ResourceMethod invalid = onlyResourceMethod(
				InvalidInjectionResource.class.getDeclaredMethod("direct",
						HttpServer.class));
		ResourceMethodResolver resolver = new ResourceMethodResolver() {
			@Override @NonNull public Optional<ResourceMethod> resourceMethodForRequest(
					@NonNull Request request, @NonNull ServerType serverType) {
				return Optional.of(invalid);
			}

			@Override @NonNull public Set<@NonNull ResourceMethod>
			getResourceMethods() {
				return Set.of(stable);
			}
		};
		CountingRejectingInstanceProvider instanceProvider =
				new CountingRejectingInstanceProvider();
		CountingRejectingParameterProvider parameterProvider =
				new CountingRejectingParameterProvider();
		AtomicReference<Throwable> resolutionFailure = new AtomicReference<>();
		CountingHttpServer http = new CountingHttpServer();
		SokletConfig config = SokletConfig.withHttpServer(http)
				.resourceMethodResolver(resolver)
				.instanceProvider(instanceProvider)
				.resourceMethodParameterProvider(parameterProvider)
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(
							@NonNull LogEvent logEvent) {
						if (logEvent.getLogEventType()
								== LogEventType.RESOURCE_METHOD_RESOLUTION_FAILED)
							resolutionFailure.compareAndSet(null,
									logEvent.getThrowable().orElse(null));
					}
				})
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			HttpRequestResult result = http.invoke("/dynamic-invalid")
					.orElseThrow();

			Assertions.assertEquals(500,
					result.getMarshaledResponse().getStatusCode());
			IllegalStateException failure = Assertions.assertInstanceOf(
					IllegalStateException.class, resolutionFailure.get());
			Assertions.assertTrue(failure.getMessage().contains(
					"HttpServer resource-method injection was removed in Soklet 4.0"));
			Assertions.assertEquals(0, instanceProvider.provisionCalls());
			Assertions.assertEquals(0, parameterProvider.provisionCalls());
		}
	}

	@Test
	void dynamicResolverHeadFallbackGuardRunsBeforeEveryConfiguredProvider()
			throws Exception {
		ResourceMethod stable = onlyResourceMethod(
				StableResource.class.getDeclaredMethod("ok"));
		ResourceMethod invalid = onlyResourceMethod(
				InvalidInjectionResource.class.getDeclaredMethod("direct",
						HttpServer.class));
		ResourceMethodResolver resolver = new ResourceMethodResolver() {
			@Override @NonNull public Optional<ResourceMethod> resourceMethodForRequest(
					@NonNull Request request, @NonNull ServerType serverType) {
				return request.getHttpMethod() == HttpMethod.GET
						? Optional.of(invalid) : Optional.empty();
			}

			@Override @NonNull public Set<@NonNull ResourceMethod>
			getResourceMethods() {
				return Set.of(stable);
			}
		};

		assertDynamicRequestGuard(resolver,
				Request.withPath(HttpMethod.HEAD, "/dynamic-invalid").build(),
				LogEventType.REQUEST_PROCESSING_FAILED);
	}

	@Test
	void dynamicResolverAlternateMethodScanGuardRunsBeforeConfiguredProviders()
			throws Exception {
		ResourceMethod stable = onlyResourceMethod(
				StableResource.class.getDeclaredMethod("ok"));
		ResourceMethod invalid = onlyResourceMethod(
				InvalidInjectionResource.class.getDeclaredMethod("direct",
						HttpServer.class));
		ResourceMethodResolver resolver = new ResourceMethodResolver() {
			@Override @NonNull public Optional<ResourceMethod> resourceMethodForRequest(
					@NonNull Request request, @NonNull ServerType serverType) {
				return request.getHttpMethod() == HttpMethod.GET
						? Optional.of(invalid) : Optional.empty();
			}

			@Override @NonNull public Set<@NonNull ResourceMethod>
			getResourceMethods() {
				return Set.of(stable);
			}
		};

		assertDynamicRequestGuard(resolver,
				Request.withPath(HttpMethod.POST, "/dynamic-invalid").build(),
				LogEventType.REQUEST_PROCESSING_FAILED);
	}

	@Test
	void dynamicResolverContentTooLargeLookupGuardRunsBeforeConfiguredProviders()
			throws Exception {
		ResourceMethod stable = onlyResourceMethod(
				StableResource.class.getDeclaredMethod("ok"));
		ResourceMethod invalid = onlyResourceMethod(
				InvalidInjectionResource.class.getDeclaredMethod("direct",
						HttpServer.class));
		AtomicInteger lookupCalls = new AtomicInteger();
		ResourceMethodResolver resolver = new ResourceMethodResolver() {
			@Override @NonNull public Optional<ResourceMethod> resourceMethodForRequest(
					@NonNull Request request, @NonNull ServerType serverType) {
				return lookupCalls.getAndIncrement() == 0
						? Optional.empty() : Optional.of(invalid);
			}

			@Override @NonNull public Set<@NonNull ResourceMethod>
			getResourceMethods() {
				return Set.of(stable);
			}
		};

		assertDynamicRequestGuard(resolver,
				Request.withPath(HttpMethod.GET, "/dynamic-invalid")
						.contentTooLarge(true).build(),
				LogEventType.REQUEST_PROCESSING_FAILED);
		Assertions.assertEquals(2, lookupCalls.get());
	}

	@Test
	void rejectsDirectHttpServerBeforeInstanceProvisioning() throws Exception {
		assertRejectedBeforeInstanceProvisioning(
				InvalidInjectionResource.class.getDeclaredMethod(
						"direct", HttpServer.class));
	}

	@Test
	void rejectsOptionalHttpServerBeforeInstanceProvisioning() throws Exception {
		assertRejectedBeforeInstanceProvisioning(
				InvalidInjectionResource.class.getDeclaredMethod(
						"optional", Optional.class));
	}

	@Test
	void rejectsHttpServerBoundTypeVariableBeforeInstanceProvisioning()
			throws Exception {
		assertRejectedBeforeInstanceProvisioning(
				InvalidInjectionResource.class.getDeclaredMethod(
						"typeVariable", HttpServer.class));
	}

	@Test
	void rejectsOptionalHttpServerBoundTypeVariableBeforeInstanceProvisioning()
			throws Exception {
		assertRejectedBeforeInstanceProvisioning(
				InvalidInjectionResource.class.getDeclaredMethod(
						"optionalTypeVariable", Optional.class));
	}

	@Test
	void rejectsHttpServerImplementationBeforeInstanceProvisioning()
			throws Exception {
		assertRejectedBeforeInstanceProvisioning(
				InvalidInjectionResource.class.getDeclaredMethod(
						"implementation", DefaultHttpServer.class));
	}

	private static void assertRejectedBeforeInstanceProvisioning(
			@NonNull Method invalidMethod) {
		CountingRejectingInstanceProvider instanceProvider =
				new CountingRejectingInstanceProvider();
		HttpServer httpServer = HttpServer.withPort(0).build();
		SokletConfig config = SokletConfig.withHttpServer(httpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(
						Set.of(invalidMethod)))
				.instanceProvider(instanceProvider)
				.build();

		Assertions.assertEquals(0, instanceProvider.provisionCalls());
		SokletStartupException failure = assertCompleteSetupFailure(config,
				Set.of(InternalParticipantKind.HTTP));

		Assertions.assertInstanceOf(IllegalStateException.class,
				failure.getCause());
		Assertions.assertTrue(failure.getCause().getMessage().contains(
				"HttpServer resource-method injection was removed in Soklet 4.0"));
		Assertions.assertTrue(failure.getCause().getMessage().contains(
				invalidMethod.getName()));
		Assertions.assertEquals(0, instanceProvider.provisionCalls(),
				"Startup validation must run before any InstanceProvider call");
		Assertions.assertFalse(httpServer.isStarted(),
				"Rejected injection must not reach transport startup");
	}

	private static void assertDynamicRequestGuard(
			@NonNull ResourceMethodResolver resolver, @NonNull Request request,
			@NonNull LogEventType expectedFailureType) {
		CountingRejectingInstanceProvider instanceProvider =
				new CountingRejectingInstanceProvider();
		CountingRejectingParameterProvider parameterProvider =
				new CountingRejectingParameterProvider();
		AtomicReference<Throwable> requestFailure = new AtomicReference<>();
		CountingHttpServer http = new CountingHttpServer();
		SokletConfig config = SokletConfig.withHttpServer(http)
				.resourceMethodResolver(resolver)
				.instanceProvider(instanceProvider)
				.resourceMethodParameterProvider(parameterProvider)
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(
							@NonNull LogEvent logEvent) {
						if (logEvent.getLogEventType() == expectedFailureType)
							requestFailure.compareAndSet(null,
									logEvent.getThrowable().orElse(null));
					}
				})
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			HttpRequestResult result = http.invoke(request).orElseThrow();

			Assertions.assertEquals(500,
					result.getMarshaledResponse().getStatusCode());
			IllegalStateException failure = Assertions.assertInstanceOf(
					IllegalStateException.class, requestFailure.get());
			Assertions.assertTrue(failure.getMessage().contains(
					"HttpServer resource-method injection was removed in Soklet 4.0"));
			Assertions.assertEquals(0, instanceProvider.provisionCalls());
			Assertions.assertEquals(0, parameterProvider.provisionCalls());
		}
	}

	@NonNull
	private static SokletStartupException assertCompleteSetupFailure(
			@NonNull SokletConfig config,
			@NonNull Set<InternalParticipantKind> expectedKinds) {
		try (Soklet soklet = Soklet.fromConfig(config)) {
			SokletStartupException startup = Assertions.assertThrows(
					SokletStartupException.class, soklet::start);
			InternalShutdownResult result = startup.getInternalShutdownResult();

			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					startup.getInternalStartupDisposition());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					result.startupDisposition());
			Assertions.assertEquals(InternalShutdownDisposition.NOT_STARTED,
					result.disposition());
			Assertions.assertTrue(result.isComplete());
			Assertions.assertSame(result,
					soklet.getDirectLifecycle().result().orElseThrow());
			Assertions.assertEquals(expectedKinds,
					result.participantResults().stream()
							.map(InternalParticipantShutdownResult::kind)
							.collect(java.util.stream.Collectors.toSet()));
			Assertions.assertTrue(result.participantResult(
					InternalParticipantKind.FRAMEWORK_STARTUP).isEmpty());
			for (InternalParticipantShutdownResult participant :
					result.participantResults()) {
				Assertions.assertEquals(
						InternalParticipantShutdownDisposition.NOT_STARTED,
						participant.disposition());
				Assertions.assertEquals(List.of(startup.getCause()),
						participant.failures());
				Assertions.assertTrue(participant.residualActivity().isEmpty());
			}
			Assertions.assertDoesNotThrow(soklet::stop);
			return startup;
		}
	}

	@NonNull
	private static ResourceMethodResolver multiInvalidResolver()
			throws Exception {
		return ResourceMethodResolver.fromMethods(Set.of(
				MultiInvalidResource.class.getDeclaredMethod("zeta",
						HttpServer.class),
				MultiInvalidResource.class.getDeclaredMethod("alpha",
						DerivedHttpServer.class, HttpServer.class)));
	}

	@NonNull
	private static ResourceMethod onlyResourceMethod(@NonNull Method method) {
		return ResourceMethodResolver.fromMethods(Set.of(method))
				.getResourceMethods().iterator().next();
	}

	@NonNull
	private static ResourceMethodResolver throwingResolver(
			@NonNull RuntimeException failure) {
		return new ResourceMethodResolver() {
			@Override @NonNull public Optional<ResourceMethod> resourceMethodForRequest(
					@NonNull Request request, @NonNull ServerType serverType) {
				return Optional.empty();
			}

			@Override @NonNull public Set<@NonNull ResourceMethod>
			getResourceMethods() {
				throw failure;
			}
		};
	}

	@NonNull
	private static ResourceMethodResolver fixedResolver(
			@NonNull Set<ResourceMethod> resourceMethods) {
		return new ResourceMethodResolver() {
			@Override @NonNull public Optional<ResourceMethod> resourceMethodForRequest(
					@NonNull Request request, @NonNull ServerType serverType) {
				return Optional.empty();
			}

			@Override @NonNull public Set<@NonNull ResourceMethod>
			getResourceMethods() {
				return resourceMethods;
			}
		};
	}

	@NonNull
	private static McpServer newMcpServer() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"setup-precedence", "1.0").build())
				.build();
		return McpServer.withPort(0)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.build();
	}

	public static final class InvalidInjectionResource {
		@GET("/invalid/direct")
		public String direct(@NonNull HttpServer httpServer) {
			return httpServer.toString();
		}

		@GET("/invalid/optional")
		public String optional(@NonNull Optional<HttpServer> httpServer) {
			return httpServer.toString();
		}

		@GET("/invalid/type-variable")
		public <T extends HttpServer> String typeVariable(@NonNull T httpServer) {
			return httpServer.toString();
		}

		@GET("/invalid/optional-type-variable")
		public <T extends HttpServer> String optionalTypeVariable(
				@NonNull Optional<T> httpServer) {
			return httpServer.toString();
		}

		@GET("/invalid/implementation")
		public String implementation(@NonNull DefaultHttpServer httpServer) {
			return httpServer.toString();
		}
	}

	public interface DerivedHttpServer extends HttpServer { }

	public static final class MultiInvalidResource {
		@GET("/invalid/alpha")
		@NonNull
		public SseHandshakeResult alpha(@NonNull DerivedHttpServer first,
				@NonNull HttpServer second) {
			return SseHandshakeResult.accept();
		}

		@SseEventSource("/invalid/zeta")
		@NonNull
		public SseHandshakeResult zeta(@NonNull HttpServer server) {
			return SseHandshakeResult.accept();
		}
	}

	public static final class StableResource {
		@GET("/stable")
		@NonNull
		public String ok() {
			return "ok";
		}
	}

	private static final class ChangingResourceMethodSet
			extends AbstractSet<ResourceMethod> {
		@NonNull private final ResourceMethod first;
		@NonNull private final ResourceMethod later;
		@NonNull private final AtomicInteger iteratorCalls = new AtomicInteger();

		private ChangingResourceMethodSet(@NonNull ResourceMethod first,
				@NonNull ResourceMethod later) {
			this.first = first;
			this.later = later;
		}

		@Override @NonNull public Iterator<ResourceMethod> iterator() {
			return List.of(this.iteratorCalls.getAndIncrement() == 0
					? this.first : this.later).iterator();
		}

		@Override public int size() { return 1; }
	}

	private static final class CountingHttpServer implements HttpServer {
		@NonNull private final AtomicBoolean started = new AtomicBoolean();
		@NonNull private final AtomicInteger initializeCalls = new AtomicInteger();
		@NonNull private final AtomicInteger startCalls = new AtomicInteger();
		@NonNull private final AtomicInteger stopCalls = new AtomicInteger();
		@NonNull private final AtomicReference<RequestHandler> requestHandler =
				new AtomicReference<>();

		@Override public void start() {
			this.startCalls.incrementAndGet();
			this.started.set(true);
		}

		@Override public void stop() {
			this.stopCalls.incrementAndGet();
			this.started.set(false);
		}

		@Override @NonNull public Boolean isStarted() {
			return this.started.get();
		}

		@Override public void initialize(@NonNull SokletConfig sokletConfig,
				@NonNull RequestHandler requestHandler) {
			this.initializeCalls.incrementAndGet();
			this.requestHandler.set(requestHandler);
		}

		@NonNull
		Optional<HttpRequestResult> invoke(@NonNull String path) {
			return invoke(Request.withPath(HttpMethod.GET, path).build());
		}

		@NonNull
		Optional<HttpRequestResult> invoke(@NonNull Request request) {
			RequestHandler handler = this.requestHandler.get();
			if (handler == null)
				return Optional.empty();
			AtomicReference<HttpRequestResult> result = new AtomicReference<>();
			handler.handleRequest(request, result::set);
			return Optional.ofNullable(result.get());
		}

		int initializeCalls() { return this.initializeCalls.get(); }
		int startCalls() { return this.startCalls.get(); }
		int stopCalls() { return this.stopCalls.get(); }
	}

	private static final class CountingSseServer implements SseServer {
		@NonNull private final AtomicBoolean started = new AtomicBoolean();
		@NonNull private final AtomicInteger initializeCalls = new AtomicInteger();
		@NonNull private final AtomicInteger startCalls = new AtomicInteger();

		@Override public void start() {
			this.startCalls.incrementAndGet();
			this.started.set(true);
		}

		@Override public void stop() { this.started.set(false); }

		@Override @NonNull public Boolean isStarted() {
			return this.started.get();
		}

		@Override @NonNull public Optional<? extends SseBroadcaster>
		acquireBroadcaster(@Nullable ResourcePath resourcePath) {
			return Optional.empty();
		}

		@Override public void initialize(@NonNull SokletConfig sokletConfig,
				@NonNull RequestHandler requestHandler) {
			this.initializeCalls.incrementAndGet();
		}

		int initializeCalls() { return this.initializeCalls.get(); }
		int startCalls() { return this.startCalls.get(); }
	}

	private static final class CountingRejectingInstanceProvider
			implements InstanceProvider {
		@NonNull
		private final AtomicInteger provisionCalls = new AtomicInteger();

		@Override
		@NonNull
		public <T> T provide(@NonNull Class<T> instanceClass) {
			this.provisionCalls.incrementAndGet();
			throw new AssertionError("InstanceProvider must not run before validation");
		}

		@Override
		@NonNull
		public <T> T provide(@NonNull Parameter parameter) {
			this.provisionCalls.incrementAndGet();
			throw new AssertionError("InstanceProvider must not run before validation");
		}

		int provisionCalls() {
			return this.provisionCalls.get();
		}
	}

	private static final class CountingRejectingParameterProvider
			implements ResourceMethodParameterProvider {
		@NonNull
		private final AtomicInteger provisionCalls = new AtomicInteger();

		@Override @NonNull public List<@Nullable Object>
		parameterValuesForResourceMethod(@NonNull Request request,
				@NonNull ResourceMethod resourceMethod) {
			this.provisionCalls.incrementAndGet();
			throw new AssertionError(
					"ResourceMethodParameterProvider must not run before validation");
		}

		int provisionCalls() { return this.provisionCalls.get(); }
	}
}

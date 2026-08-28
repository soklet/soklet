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

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/**
 * Focused public and live-runtime coverage for MCP handler diagnostics.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
public class McpHandlerQueueDiagnosticsPublicRuntimeTests {
	private static final String HOST = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final LifecyclePolicy LIFECYCLE_POLICY = LifecyclePolicy.builder()
			.startupTimeout(Duration.ofSeconds(5))
			.startupCancellationTimeout(Duration.ofSeconds(2))
			.gracefulShutdownDuration(Duration.ofSeconds(2))
			.forcedShutdownDuration(Duration.ofSeconds(1))
			.build();
	private static final List<String> INTEGER_DIAGNOSTIC_GETTERS = List.of(
			"getRequestHandlerConcurrency",
			"getRequestHandlerQueueCapacity",
			"getActiveHandlerExecutions",
			"getQueuedRequests",
			"getActiveRequestStreams",
			"getActiveSubscriptions");

	@Test
	public void publicSurfaceUsesExactReferenceNonNullDocumentedDiagnosticGetters()
			throws Exception {
		Assertions.assertTrue(McpServerDiagnostics.class.isInterface());
		Method[] declaredGetterMethods =
				McpServerDiagnostics.class.getDeclaredMethods();
		Assertions.assertEquals(12, declaredGetterMethods.length);
		for (Method getter : declaredGetterMethods) {
			Assertions.assertEquals(0, getter.getParameterCount(), getter.toString());
			Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()),
					getter.toString());
			Assertions.assertTrue(Modifier.isAbstract(getter.getModifiers()),
					getter.toString());
		}
		Set<String> declaredMethods = new TreeSet<>(Arrays.stream(
				declaredGetterMethods)
				.map(Method::getName).toList());
		Assertions.assertEquals(Set.of(
				"getStatus", "getBoundAddress",
					"getRequestHandlerConcurrency",
					"getRequestHandlerQueueCapacity",
					"getActiveHandlerExecutions", "getQueuedRequests",
					"getActiveRequestStreams", "getActiveSubscriptions",
					"getProtectionMode",
					"isApplicationRequestStateProtectorConfigured",
					"getProtectionKeyRingFingerprint",
					"getTraceCorrelationConfigurationFingerprint"),
					declaredMethods);

		for (String getterName : INTEGER_DIAGNOSTIC_GETTERS) {
			Method getter = McpServerDiagnostics.class.getMethod(getterName);
			Assertions.assertEquals(Integer.class, getter.getReturnType());
			Assertions.assertFalse(getter.getReturnType().isPrimitive());
			Assertions.assertEquals(0, getter.getParameterCount());
			Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()));
			Assertions.assertTrue(Modifier.isAbstract(getter.getModifiers()));
			Assertions.assertTrue(getter.getAnnotatedReturnType()
					.isAnnotationPresent(NonNull.class));
		}

		assertNonNullReferenceGetter("getProtectionMode",
				McpProtectionMode.class);
		assertNonNullReferenceGetter(
				"isApplicationRequestStateProtectorConfigured", Boolean.class);
		assertNonNullOptionalGetter("getProtectionKeyRingFingerprint",
				McpProtectionKeyRingFingerprint.class);
		assertNonNullOptionalGetter(
				"getTraceCorrelationConfigurationFingerprint",
				McpTraceCorrelationConfigurationFingerprint.class);
		assertNonNullReferenceGetter("getStatus", McpServerStatus.class);
		assertNonNullOptionalGetter("getBoundAddress", InetSocketAddress.class);

		String source = Files.readString(Path.of(
				"src/main/java/com/soklet/McpServerDiagnostics.java"),
				StandardCharsets.UTF_8);
		assertDocumented(source, "getRequestHandlerConcurrency",
				"stable across the owner lifecycle");
		assertDocumented(source, "getRequestHandlerQueueCapacity",
				"stable across the owner lifecycle");
		assertDocumented(source, "getActiveHandlerExecutions",
				"includes residual handlers");
		assertDocumented(source, "getQueuedRequests",
				"completed server stop transition");
		assertDocumented(source, "getActiveRequestStreams",
				"open request-scoped SSE streams");
		assertDocumented(source, "getActiveSubscriptions",
				"never");
		assertDocumented(source, "getProtectionMode",
				"effective framework request-state protection mode");
		assertDocumented(source,
				"isApplicationRequestStateProtectorConfigured",
				"application-owned");
		assertDocumented(source, "getProtectionKeyRingFingerprint",
				"PRODUCTION_KEY_RING");
		assertDocumented(source,
				"getTraceCorrelationConfigurationFingerprint",
				"trace correlation");
	}

	@Test
	public void configuredValuesAndZeroLoadRemainStableAcrossFreshCleanOwners()
			throws Exception {
		McpServer neverStartedServer = serverFor(List.of(
				emptyEndpoint("/mcp/idle-never-started")),
				2, 3, Duration.ofSeconds(5));
		Soklet neverStartedOwner = newSoklet(neverStartedServer);
		McpServerDiagnostics neverStarted = neverStartedServer.getDiagnostics();
		assertDiagnostics(neverStarted, McpServerStatus.NOT_STARTED, false,
				2, 3, 0, 0);
		neverStartedOwner.close();
		neverStartedOwner.close();
		assertDiagnostics(neverStartedServer.getDiagnostics(),
				McpServerStatus.TERMINATED, false, 2, 3, 0, 0);

		McpServer firstServer = serverFor(List.of(
				emptyEndpoint("/mcp/idle-first")),
				2, 3, Duration.ofSeconds(5));
		Soklet firstOwner = newSoklet(firstServer);
		McpServerDiagnostics beforeStart = firstServer.getDiagnostics();
		assertDiagnostics(beforeStart, McpServerStatus.NOT_STARTED, false,
				2, 3, 0, 0);
		McpServerDiagnostics firstStarted;
		McpServerDiagnostics firstStopped;
		InetSocketAddress firstAddress;
		try {
			firstOwner.start();
			firstStarted = firstServer.getDiagnostics();
			assertDiagnostics(firstStarted, McpServerStatus.RUNNING, true,
					2, 3, 0, 0);
			firstAddress = firstStarted.getBoundAddress().orElseThrow();
			assertDiagnostics(beforeStart, McpServerStatus.NOT_STARTED, false,
					2, 3, 0, 0);
		} finally {
			firstOwner.close();
			firstOwner.close();
		}
		firstStopped = firstServer.getDiagnostics();
		assertDiagnostics(firstStopped, McpServerStatus.TERMINATED, true,
				2, 3, 0, 0);
		assertDiagnostics(firstStarted, McpServerStatus.RUNNING, true,
				2, 3, 0, 0);
		Assertions.assertEquals(firstAddress,
				firstStarted.getBoundAddress().orElseThrow());

		McpServer secondServer = serverFor(List.of(
				emptyEndpoint("/mcp/idle-second")),
				2, 3, Duration.ofSeconds(5));
		Soklet secondOwner = newSoklet(secondServer);
		try {
			secondOwner.start();
			assertDiagnostics(secondServer.getDiagnostics(),
					McpServerStatus.RUNNING, true, 2, 3, 0, 0);
			assertDiagnostics(beforeStart, McpServerStatus.NOT_STARTED, false,
					2, 3, 0, 0);
			assertDiagnostics(firstStarted, McpServerStatus.RUNNING, true,
					2, 3, 0, 0);
			Assertions.assertEquals(firstAddress,
					firstStarted.getBoundAddress().orElseThrow());
			assertDiagnostics(firstStopped, McpServerStatus.TERMINATED, true,
					2, 3, 0, 0);
		} finally {
			secondOwner.close();
			secondOwner.close();
		}
		assertDiagnostics(secondServer.getDiagnostics(), McpServerStatus.TERMINATED,
				true, 2, 3, 0, 0);
	}

	@Test
	@Timeout(120)
	public void crossEndpointSaturationPublishesRetainedAndBoundedConcurrentTuples()
			throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1);
		CountDownLatch releaseSecond = new CountDownLatch(1);
		AtomicInteger firstInvocations = new AtomicInteger();
		AtomicInteger secondInvocations = new AtomicInteger();
		McpEndpoint firstEndpoint = endpoint("/mcp/diagnostics-first",
				"diagnostics.first", (request, arguments, features) -> {
					firstInvocations.incrementAndGet();
					firstEntered.countDown();
					Assertions.assertTrue(releaseFirst.await(10,
							TimeUnit.SECONDS),
							"Timed out waiting to release the first endpoint");
					return McpCompleteResult.fromToolText("first");
				});
		McpEndpoint secondEndpoint = endpoint("/mcp/diagnostics-second",
				"diagnostics.second", (request, arguments, features) -> {
					secondInvocations.incrementAndGet();
					secondEntered.countDown();
					Assertions.assertTrue(releaseSecond.await(10,
							TimeUnit.SECONDS),
							"Timed out waiting to release the second endpoint");
					return McpCompleteResult.fromToolText("second");
				});
		McpServer server = serverFor(List.of(firstEndpoint, secondEndpoint),
				1, 1, Duration.ofSeconds(5));
		Soklet owner = newSoklet(server);
		ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
		AtomicBoolean readSnapshots = new AtomicBoolean();
		AtomicInteger snapshotReads = new AtomicInteger();
		CompletableFuture<HttpResponse<String>> first = null;
		CompletableFuture<HttpResponse<String>> second = null;
		Future<?> reader = null;

		try {
			owner.start();
			int port = port(server);
			first = callTool(port, "/mcp/diagnostics-first", "first",
					"diagnostics.first");
			Assertions.assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
			second = callTool(port, "/mcp/diagnostics-second", "second",
					"diagnostics.second");
			McpServerDiagnostics saturated = awaitDiagnostics(server,
					diagnostics -> diagnostics.getActiveHandlerExecutions() == 1
							&& diagnostics.getQueuedRequests() == 1);
			assertDiagnostics(saturated, McpServerStatus.RUNNING, true,
					1, 1, 1, 1);
			Assertions.assertEquals(1, firstInvocations.get());
			Assertions.assertEquals(0, secondInvocations.get());
			HttpResponse<String> rejected = callTool(port,
					"/mcp/diagnostics-second", "rejected",
					"diagnostics.second").get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(503, rejected.statusCode(), rejected.body());
			assertDiagnostics(server.getDiagnostics(), McpServerStatus.RUNNING, true,
					1, 1, 1, 1);
			Assertions.assertEquals(1, firstInvocations.get());
			Assertions.assertEquals(0, secondInvocations.get(),
					"A capacity-rejected request must not enter a handler.");

			readSnapshots.set(true);
			reader = readerExecutor.submit(() -> {
				while (readSnapshots.get()) {
					McpServerDiagnostics diagnostics = server.getDiagnostics();
					assertBoundedStartedTuple(diagnostics, 1, 1);
					snapshotReads.incrementAndGet();
				}
			});
			awaitCondition(() -> snapshotReads.get() > 0);

			releaseFirst.countDown();
			Assertions.assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
			assertDiagnostics(awaitDiagnostics(server,
					diagnostics -> diagnostics.getActiveHandlerExecutions() == 1
							&& diagnostics.getQueuedRequests() == 0),
					McpServerStatus.RUNNING, true, 1, 1, 1, 0);
			releaseSecond.countDown();
			Assertions.assertEquals(200,
					requireNonNull(first).get(5, TimeUnit.SECONDS).statusCode());
			Assertions.assertEquals(200,
					requireNonNull(second).get(5, TimeUnit.SECONDS).statusCode());
			assertDiagnostics(awaitDiagnostics(server,
					diagnostics -> diagnostics.getActiveHandlerExecutions() == 0
							&& diagnostics.getQueuedRequests() == 0),
					McpServerStatus.RUNNING, true, 1, 1, 0, 0);

			readSnapshots.set(false);
			requireNonNull(reader).get(5, TimeUnit.SECONDS);
			Assertions.assertTrue(snapshotReads.get() > 0);
			assertDiagnostics(saturated, McpServerStatus.RUNNING, true,
					1, 1, 1, 1);
		} finally {
			readSnapshots.set(false);
			releaseFirst.countDown();
			releaseSecond.countDown();
			if (first != null)
				first.cancel(true);
			if (second != null)
				second.cancel(true);
			try {
				readerExecutor.shutdownNow();
				Assertions.assertTrue(readerExecutor.awaitTermination(
						5, TimeUnit.SECONDS));
			} finally {
				owner.close();
			}
		}
	}

	@Test
	public void residualStopRetainsOneActiveAndDrainsQueueUntilLateExit()
			throws Exception {
		CountDownLatch activeEntered = new CountDownLatch(1);
		CountDownLatch activeInterrupted = new CountDownLatch(1);
		CountDownLatch releaseActive = new CountDownLatch(1);
		CountDownLatch activeExited = new CountDownLatch(1);
		AtomicInteger invocations = new AtomicInteger();
		McpEndpoint endpoint = endpoint("/mcp/diagnostics-residual",
				"diagnostics.residual", (request, arguments, features) -> {
					invocations.incrementAndGet();
					activeEntered.countDown();
					try {
						awaitIgnoringInterrupts(releaseActive, activeInterrupted);
						return McpCompleteResult.fromToolText("released");
					} finally {
						activeExited.countDown();
					}
				});
		McpServer server = serverFor(List.of(endpoint), 1, 1,
				Duration.ofSeconds(10));
		Soklet owner = newSoklet(server, shortShutdownPolicy());
		CompletableFuture<HttpResponse<String>> active = null;
		CompletableFuture<HttpResponse<String>> queued = null;

		try {
			owner.start();
			int port = port(server);
			active = callTool(port, "/mcp/diagnostics-residual", "active",
					"diagnostics.residual");
			Assertions.assertTrue(activeEntered.await(5, TimeUnit.SECONDS));
			queued = callTool(port, "/mcp/diagnostics-residual", "queued",
					"diagnostics.residual");
			McpServerDiagnostics saturated = awaitDiagnostics(server,
					diagnostics -> diagnostics.getActiveHandlerExecutions() == 1
							&& diagnostics.getQueuedRequests() == 1);

			ShutdownIncompleteException stopFailure = Assertions.assertThrows(
					ShutdownIncompleteException.class, owner::close);
			InternalShutdownResult shutdownResult =
					stopFailure.getInternalShutdownResult();
			Assertions.assertSame(shutdownResult,
					owner.getDirectLifecycle().result().orElseThrow());
			Assertions.assertTrue(activeInterrupted.await(5, TimeUnit.SECONDS));
			McpServerDiagnostics residual = server.getDiagnostics();
			assertDiagnostics(residual,
					McpServerStatus.RESIDUAL_ACTIVITY,
					true, 1, 1, 1, 0);
			Assertions.assertEquals(1, invocations.get(),
					"Stop must not promote queued work.");
			ShutdownIncompleteException repeatedStop = Assertions.assertThrows(
					ShutdownIncompleteException.class, owner::close);
			Assertions.assertSame(shutdownResult,
					repeatedStop.getInternalShutdownResult());

			releaseActive.countDown();
			Assertions.assertTrue(activeExited.await(5, TimeUnit.SECONDS));
			assertDiagnostics(awaitDiagnostics(server,
					diagnostics -> diagnostics.getStatus() == McpServerStatus.RESIDUAL_ACTIVITY
							&& diagnostics.getActiveHandlerExecutions() == 0
							&& diagnostics.getQueuedRequests() == 0),
					McpServerStatus.RESIDUAL_ACTIVITY, true, 1, 1, 0, 0);
			assertDiagnostics(saturated, McpServerStatus.RUNNING, true,
					1, 1, 1, 1);
			assertDiagnostics(residual,
					McpServerStatus.RESIDUAL_ACTIVITY,
					true, 1, 1, 1, 0);

			Assertions.assertSame(shutdownResult,
					owner.getDirectLifecycle().result().orElseThrow(),
					"Late physical cleanup cannot rewrite an immutable residual result.");
			ShutdownIncompleteException lateRepeatedStop = Assertions.assertThrows(
					ShutdownIncompleteException.class, owner::close);
			Assertions.assertSame(shutdownResult,
					lateRepeatedStop.getInternalShutdownResult());
			assertDiagnostics(server.getDiagnostics(),
					McpServerStatus.RESIDUAL_ACTIVITY, true,
					1, 1, 0, 0);
		} finally {
			releaseActive.countDown();
			if (active != null)
				active.cancel(true);
			if (queued != null)
				queued.cancel(true);
			stopAfterIncompleteShutdown(owner);
		}
	}

	private static void assertDocumented(@NonNull String source,
			@NonNull String getterName, @NonNull String requiredFragment) {
		String signatureSuffix = requireNonNull(getterName) + "();";
		int signatureIndex = requireNonNull(source).indexOf(signatureSuffix);
		Assertions.assertTrue(signatureIndex >= 0, signatureSuffix);
		int javadocEnd = source.lastIndexOf("*/", signatureIndex);
		int javadocStart = source.lastIndexOf("/**", javadocEnd);
		Assertions.assertTrue(javadocStart >= 0 && javadocEnd > javadocStart,
				getterName);
		String javadoc = source.substring(javadocStart, javadocEnd + 2);
		Assertions.assertTrue(javadoc.contains("@return"), javadoc);
		Assertions.assertTrue(javadoc.contains(requireNonNull(requiredFragment)),
				javadoc);
	}

	private static void assertNonNullReferenceGetter(
			@NonNull String getterName, @NonNull Class<?> returnType)
			throws Exception {
		Method getter = McpServerDiagnostics.class.getMethod(
				requireNonNull(getterName));
		Assertions.assertEquals(requireNonNull(returnType), getter.getReturnType());
		Assertions.assertFalse(getter.getReturnType().isPrimitive());
		Assertions.assertEquals(0, getter.getParameterCount());
		Assertions.assertTrue(Modifier.isPublic(getter.getModifiers()));
		Assertions.assertTrue(Modifier.isAbstract(getter.getModifiers()));
		Assertions.assertTrue(getter.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));
	}

	private static void assertNonNullOptionalGetter(
			@NonNull String getterName, @NonNull Class<?> elementType)
			throws Exception {
		assertNonNullReferenceGetter(getterName, java.util.Optional.class);
		AnnotatedType annotatedReturnType = McpServerDiagnostics.class
				.getMethod(getterName).getAnnotatedReturnType();
		AnnotatedParameterizedType optionalType = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, annotatedReturnType);
		AnnotatedType optionalElement =
				optionalType.getAnnotatedActualTypeArguments()[0];
		Assertions.assertEquals(requireNonNull(elementType),
				optionalElement.getType());
		Assertions.assertTrue(optionalElement.isAnnotationPresent(NonNull.class));
	}

	private static void assertDiagnostics(
			@NonNull McpServerDiagnostics diagnostics,
			@NonNull McpServerStatus status,
			boolean addressPresent, int concurrency, int queueCapacity,
			int active, int queued) {
		Assertions.assertEquals(requireNonNull(status), diagnostics.getStatus());
		Assertions.assertEquals(addressPresent,
				diagnostics.getBoundAddress().isPresent());
		Assertions.assertEquals(Integer.valueOf(concurrency),
				diagnostics.getRequestHandlerConcurrency());
		Assertions.assertEquals(Integer.valueOf(queueCapacity),
				diagnostics.getRequestHandlerQueueCapacity());
		Assertions.assertEquals(Integer.valueOf(active),
				diagnostics.getActiveHandlerExecutions());
		Assertions.assertEquals(Integer.valueOf(queued),
				diagnostics.getQueuedRequests());
		Assertions.assertEquals(Integer.valueOf(0),
				diagnostics.getActiveRequestStreams());
		Assertions.assertEquals(Integer.valueOf(0),
				diagnostics.getActiveSubscriptions());
		Assertions.assertEquals(McpProtectionMode.NO_FRAMEWORK_KEYS,
				diagnostics.getProtectionMode());
		Assertions.assertEquals(Boolean.FALSE,
				diagnostics.isApplicationRequestStateProtectorConfigured());
		Assertions.assertTrue(
				diagnostics.getProtectionKeyRingFingerprint().isEmpty());
		Assertions.assertTrue(diagnostics
				.getTraceCorrelationConfigurationFingerprint().isEmpty());
		Assertions.assertTrue(active >= 0 && active <= concurrency);
		Assertions.assertTrue(queued >= 0 && queued <= queueCapacity);
		if (queued > 0)
			Assertions.assertEquals(concurrency, active,
					"A physical handler queue requires every slot to be occupied.");
	}

	private static void assertBoundedStartedTuple(
			@NonNull McpServerDiagnostics diagnostics,
			int concurrency, int queueCapacity) {
		Assertions.assertEquals(McpServerStatus.RUNNING, diagnostics.getStatus());
		Assertions.assertTrue(diagnostics.getBoundAddress().isPresent());
		Assertions.assertEquals(Integer.valueOf(concurrency),
				diagnostics.getRequestHandlerConcurrency());
		Assertions.assertEquals(Integer.valueOf(queueCapacity),
				diagnostics.getRequestHandlerQueueCapacity());
		int active = diagnostics.getActiveHandlerExecutions();
		int queued = diagnostics.getQueuedRequests();
		int activeRequestStreams = diagnostics.getActiveRequestStreams();
		int activeSubscriptions = diagnostics.getActiveSubscriptions();
		Assertions.assertTrue(active >= 0 && active <= concurrency);
		Assertions.assertTrue(queued >= 0 && queued <= queueCapacity);
		Assertions.assertEquals(0, activeRequestStreams);
		Assertions.assertEquals(0, activeSubscriptions);
		if (queued > 0)
			Assertions.assertEquals(concurrency, active);
	}

	private static void awaitIgnoringInterrupts(@NonNull CountDownLatch release,
			@NonNull CountDownLatch interrupted) {
		boolean released = false;
		while (!released) {
			try {
				released = release.await(25, TimeUnit.MILLISECONDS);
			} catch (InterruptedException exception) {
				interrupted.countDown();
			}
		}
	}

	@NonNull
	private static McpServerDiagnostics awaitDiagnostics(@NonNull McpServer server,
			@NonNull Predicate<@NonNull McpServerDiagnostics> predicate)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		McpServerDiagnostics latest = server.getDiagnostics();
		while (System.nanoTime() - deadline < 0L) {
			latest = server.getDiagnostics();
			if (predicate.test(latest))
				return latest;
			Thread.sleep(10L);
		}
		Assertions.fail("Timed out waiting for MCP diagnostics; latest=" + latest);
		throw new AssertionError();
	}

	private static void awaitCondition(@NonNull BooleanSupplier condition)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() - deadline < 0L) {
			if (condition.getAsBoolean())
				return;
			Thread.sleep(10L);
		}
		Assertions.fail("Timed out waiting for condition.");
	}

	private static void stopAfterIncompleteShutdown(@NonNull Soklet owner) {
		try {
			requireNonNull(owner).close();
		} catch (ShutdownIncompleteException ignored) {
			// Cleanup replays the immutable incomplete result by contract.
		}
	}

	@NonNull
	private static Soklet newSoklet(@NonNull McpServer server) {
		return newSoklet(server, LIFECYCLE_POLICY);
	}

	@NonNull
	private static Soklet newSoklet(@NonNull McpServer server,
			@NonNull LifecyclePolicy lifecyclePolicy) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(requireNonNull(server))
				.resourceMethodResolver(
						ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(requireNonNull(lifecyclePolicy))
				.build());
	}

	@NonNull
	private static LifecyclePolicy shortShutdownPolicy() {
		return LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(5))
				.startupCancellationTimeout(Duration.ofMillis(100))
				.gracefulShutdownDuration(Duration.ofMillis(100))
				.forcedShutdownDuration(Duration.ofMillis(100))
				.build();
	}

	@NonNull
	private static McpEndpoint emptyEndpoint(@NonNull String path) {
		return McpEndpoint.withPath(requireNonNull(path))
				.serverInformation(McpImplementation.withNameAndVersion(
						"handler-diagnostics-test", "4.0.0-SNAPSHOT").build())
				.build();
	}

	@NonNull
	private static McpEndpoint endpoint(@NonNull String path,
			@NonNull String toolName,
			@NonNull McpToolHandler<McpJsonObject> handler) {
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName(requireNonNull(toolName))
				.jsonArguments()
				.handler(requireNonNull(handler))
				.build();
		return McpEndpoint.withPath(requireNonNull(path))
				.serverInformation(McpImplementation.withNameAndVersion(
						"handler-diagnostics-test", "4.0.0-SNAPSHOT").build())
				.tool(tool)
				.build();
	}

	@NonNull
	private static McpServer serverFor(
			@NonNull List<@NonNull McpEndpoint> endpoints,
			int concurrency, int queueCapacity,
			@NonNull Duration requestTimeout) {
		return McpServer.withPort(0)
				.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(
						List.copyOf(requireNonNull(endpoints))))
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST))
				.requestHandlerConcurrency(concurrency)
				.requestHandlerQueueCapacity(queueCapacity)
				.requestTimeout(requireNonNull(requestTimeout))
				.build();
	}

	private static int port(@NonNull McpServer server) {
		return requireNonNull(server).getDiagnostics().getBoundAddress()
				.orElseThrow().getPort();
	}

	@NonNull
	private static CompletableFuture<HttpResponse<String>> callTool(int port,
			@NonNull String path, @NonNull String id,
			@NonNull String toolName) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://" + HOST + ":" + port
						+ requireNonNull(path)))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_MEDIA_TYPE + "; charset=UTF-8")
				.header("Accept", JSON_MEDIA_TYPE + ", text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.header("Mcp-Method", "tools/call")
				.header("Mcp-Name", requireNonNull(toolName))
				.POST(HttpRequest.BodyPublishers.ofString(
						toolCallBody(id, toolName), StandardCharsets.UTF_8))
				.build();
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build()
				.sendAsync(request,
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@NonNull
	private static String toolCallBody(@NonNull String id,
			@NonNull String toolName) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + requireNonNull(id)
				+ "\",\"method\":\"tools/call\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
				+ "\"name\":\"" + requireNonNull(toolName)
				+ "\",\"arguments\":{}}}";
	}
}

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(15)
class TransportStartupFailureTests {
	enum Mode {
		HTTP_DIRECT, HTTP_TRANSPARENT, HTTP_OWNING, HTTP_NESTED,
		SSE_DIRECT, SSE_TRANSPARENT, SSE_OWNING, SSE_NESTED, MCP
	}

	@Test void httpDirectBindFailure() throws Exception { occupiedPortRetainsRealCauseAndCompleteRollback(Mode.HTTP_DIRECT); }
	@Test void httpTransparentBindFailure() throws Exception { occupiedPortRetainsRealCauseAndCompleteRollback(Mode.HTTP_TRANSPARENT); }
	@Test void httpOwningBindFailure() throws Exception { occupiedPortRetainsRealCauseAndCompleteRollback(Mode.HTTP_OWNING); }
	@Test void httpNestedBindFailure() throws Exception { occupiedPortRetainsRealCauseAndCompleteRollback(Mode.HTTP_NESTED); }
	@Test void sseDirectBindFailure() throws Exception { occupiedPortRetainsRealCauseAndCompleteRollback(Mode.SSE_DIRECT); }
	@Test void sseTransparentBindFailure() throws Exception { occupiedPortRetainsRealCauseAndCompleteRollback(Mode.SSE_TRANSPARENT); }
	@Test void sseOwningBindFailure() throws Exception { occupiedPortRetainsRealCauseAndCompleteRollback(Mode.SSE_OWNING); }
	@Test void sseNestedBindFailure() throws Exception { occupiedPortRetainsRealCauseAndCompleteRollback(Mode.SSE_NESTED); }
	@Test void mcpBindFailure() throws Exception { occupiedPortRetainsRealCauseAndCompleteRollback(Mode.MCP); }

	private void occupiedPortRetainsRealCauseAndCompleteRollback(Mode mode) throws Exception {
		boolean sse = mode.name().startsWith("SSE");
		assumeTrue(!sse || Runtime.version().feature() >= 21, "SSE requires virtual threads");
		AtomicReference<Throwable> observedDelegateFailure = new AtomicReference<>();
		try (ServerSocket occupied = new ServerSocket()) {
			occupied.bind(new InetSocketAddress("127.0.0.1", 0));
			int port = occupied.getLocalPort();
			SokletConfig.Builder builder;
			ShutdownComponentType type;
			if (mode == Mode.MCP) {
				builder = SokletConfig.withMcpServer(mcp(port));
				type = ShutdownComponentType.MCP;
			} else if (sse) {
				SseServer server = SseServer.withPort(port).host("127.0.0.1").build();
				if (mode == Mode.SSE_NESTED) server = new SseDecorator(server, false, observedDelegateFailure);
				if (mode != Mode.SSE_DIRECT) server = new SseDecorator(server, mode != Mode.SSE_TRANSPARENT, observedDelegateFailure);
				builder = SokletConfig.withSseServer(server);
				type = ShutdownComponentType.SSE;
			} else {
				HttpServer server = HttpServer.withPort(port).host("127.0.0.1").concurrency(1).build();
				if (mode == Mode.HTTP_NESTED) server = new HttpDecorator(server, false, observedDelegateFailure);
				if (mode != Mode.HTTP_DIRECT) server = new HttpDecorator(server, mode != Mode.HTTP_TRANSPARENT, observedDelegateFailure);
				builder = SokletConfig.withHttpServer(server);
				type = ShutdownComponentType.HTTP;
			}
			ResourceMethodResolver resolver = mode == Mode.MCP ? ResourceMethodResolver.fromMethods(Set.of())
					: ResourceMethodResolver.fromMethods(Set.of(Routes.class.getMethod(sse ? "events" : "ready")));
			Soklet soklet = Soklet.fromConfig(builder.resourceMethodResolver(resolver)
					.lifecycleObserver(quietObserver()).lifecyclePolicy(policy()).build());
			try {
				SokletStartupException failure = assertThrows(SokletStartupException.class, soklet::start);
				ShutdownResult result = failure.getShutdownResult();
				assertEquals(StartupDisposition.FAILED, result.getStartupDisposition());
				assertSame(result, soklet.awaitShutdown());
				assertSame(failure.getCause(), result.getStartupFailureCause().orElseThrow());
				assertTrue(hasBindCause(failure.getCause()), () -> String.valueOf(failure.getCause()));
				assertTrue(result.isComplete());
				assertEquals(ShutdownComponentDisposition.GRACEFUL_TERMINATION,
						result.getShutdownComponentResult(type).orElseThrow().getShutdownComponentDisposition());
				assertTrue(result.getUnexpectedShutdownComponentTermination().isEmpty());
				if (observedDelegateFailure.get() != null) assertSame(observedDelegateFailure.get(), failure.getCause());
				if (mode == Mode.MCP) {
					assertInstanceOf(BindException.class, failure.getCause());
					assertSame(failure.getCause(), result.getShutdownComponentResult(type).orElseThrow().getThrowables().get(0));
				}
				soklet.close();
			} finally {
				soklet.shutdown();
				soklet.awaitShutdown();
			}
		}
	}

	@Test
	void applicationUncheckedIOExceptionIsNotUnwrappedAsAFrameworkAdapter() throws Exception {
		UncheckedIOException expected = new UncheckedIOException("application-owned failure", new IOException("private cause"));
		McpServer server = mcpBuilder(0).requestHandlerExecutorServiceSupplier(() -> { throw expected; }).build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(quietObserver()).lifecyclePolicy(policy()).build());
		try {
			SokletStartupException failure = assertThrows(SokletStartupException.class, soklet::start);
			assertSame(expected, failure.getCause());
			assertSame(expected, failure.getShutdownResult().getStartupFailureCause().orElseThrow());
			assertTrue(failure.getShutdownResult().getUnexpectedShutdownComponentTermination().isEmpty());
			assertTrue(failure.getShutdownResult().isComplete());
			soklet.close();
		} finally { soklet.shutdown(); soklet.awaitShutdown(); }
	}

	@Test
	void customStartThrowWaitsForRollbackToReportProof() throws Exception {
		IllegalArgumentException expected = new IllegalArgumentException("custom start failed");
		Soklet soklet = custom(expected, null);
		SokletStartupException failure = assertThrows(SokletStartupException.class, soklet::start);
		assertSame(expected, failure.getCause());
		assertTrue(failure.getShutdownResult().isComplete());
		assertEquals(ShutdownComponentDisposition.GRACEFUL_TERMINATION, failure.getShutdownResult()
				.getShutdownComponentResult(ShutdownComponentType.HTTP).orElseThrow().getShutdownComponentDisposition());
		assertTrue(failure.getShutdownResult().getUnexpectedShutdownComponentTermination().isEmpty());
		soklet.close();
	}

	@Test
	void independentFailureBeforeStartThrowRetainsItsControllingEvent() throws Exception {
		IllegalStateException independent = new IllegalStateException("independent failure before ready");
		Soklet soklet = custom(new IllegalArgumentException("later start throw"), independent);
		SokletStartupException failure = assertThrows(SokletStartupException.class, soklet::start);
		assertSame(independent, failure.getCause());
		ShutdownResult result = failure.getShutdownResult();
		assertTrue(result.isComplete());
		assertEquals(ShutdownComponentDisposition.UNEXPECTED_TERMINATION, result
				.getShutdownComponentResult(ShutdownComponentType.HTTP).orElseThrow().getShutdownComponentDisposition());
		assertSame(independent, result.getUnexpectedShutdownComponentTermination().orElseThrow().getCause().orElseThrow());
		soklet.shutdown();
		soklet.awaitShutdown();
	}

	private static Soklet custom(RuntimeException startFailure, RuntimeException independentFailure) throws Exception {
		HttpServer server = new HttpServer() {
			private final TransportIdentity identity = TransportIdentity.create();
			@Override public TransportIdentity getTransportIdentity() { return identity; }
			@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
				TransportTerminationSignal signal = context.getTransportTerminationSignal();
				return new TransportRuntime() {
					@Override public void start(StartupContext ignored) {
						if (independentFailure != null) signal.signalTerminationFailure(independentFailure);
						throw startFailure;
					}
					@Override public void shutdownGracefully(ShutdownContext ignored) { signal.signalTerminated(); }
					@Override public void shutdownForcibly(ShutdownContext ignored) { signal.signalTerminated(); }
				};
			}
		};
		return Soklet.fromConfig(SokletConfig.withHttpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of(Routes.class.getMethod("ready"))))
				.lifecycleObserver(quietObserver()).lifecyclePolicy(policy()).build());
	}

	private static LifecyclePolicy policy() {
		return LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(2))
				.startupCancelationTimeout(Duration.ofSeconds(1))
				.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	}
	private static LifecycleObserver quietObserver() {
		return new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} };
	}
	private static McpServer mcp(int port) { return mcpBuilder(port).build(); }
	private static McpServer.Builder mcpBuilder(int port) {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("startup-test", "1.0").build(),
				Set.of(McpProtocolVersion.V2026_07_28)).build();
		return McpServer.withPort(port).host("127.0.0.1").endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)));
	}
	private static boolean hasBindCause(Throwable cause) {
		for (int depth = 0; cause != null && depth < 10; ++depth, cause = cause.getCause())
			if (cause instanceof BindException) return true;
		return false;
	}
	private static TransportRuntime owning(TransportDelegateAttachment child, TransportTerminationSignal signal,
			AtomicReference<Throwable> observed) {
		TransportRuntime runtime = child.getTransportRuntime();
		return new TransportRuntime() {
			@Override public void start(StartupContext context) {
				try { runtime.start(context); }
				catch (RuntimeException | Error failure) { observed.set(failure); throw failure; }
			}
			@Override public void shutdownGracefully(ShutdownContext context) {
				runtime.shutdownGracefully(context);
				signal.signalTerminated(); // No outer-owned resources; child proof remains independently required.
			}
			@Override public void shutdownForcibly(ShutdownContext context) {
				runtime.shutdownForcibly(context);
				signal.signalTerminated();
			}
		};
	}
	private record HttpDecorator(HttpServer delegate, boolean owning, AtomicReference<Throwable> observed) implements HttpServer {
		@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
		@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
			if (!owning) return context.attachTransparentDelegate(delegate, context.getAdmissionFencedRequestHandler());
			return TransportStartupFailureTests.owning(context.attachTerminationOwningDelegate(delegate,
					context.getAdmissionFencedRequestHandler()), context.getTransportTerminationSignal(), observed);
		}
	}
	private record SseDecorator(SseServer delegate, boolean owning, AtomicReference<Throwable> observed) implements SseServer {
		@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
		@Override public TransportRuntime attach(SseTransportAttachmentContext context, StartupContext startup) {
			if (!owning) return context.attachTransparentDelegate(delegate, context.getAdmissionFencedRequestHandler());
			return TransportStartupFailureTests.owning(context.attachTerminationOwningDelegate(delegate,
					context.getAdmissionFencedRequestHandler()), context.getTransportTerminationSignal(), observed);
		}
		@Override public Optional<? extends SseBroadcaster> acquireBroadcaster(@NonNull ResourcePath resourcePath) {
			return delegate.acquireBroadcaster(resourcePath);
		}
	}
	public static final class Routes {
		@GET("/ready") public String ready() { return "ready"; }
		@SseEventSource("/ready") public SseHandshakeResult events() { return SseHandshakeResult.accept(); }
	}
}

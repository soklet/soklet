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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression coverage for core Soklet ownership of the independent MCP transport.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class SokletMcpLifecycleTests {
	@Test
	public void mcpOnlySokletDoesNotRequireHttpResourceMethods() {
		List<String> events = new ArrayList<>();
		McpServer mcpServer = newMcpServer();
		SokletConfig config = SokletConfig.withMcpServer(mcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new RecordingLifecycleObserver(events, null))
				.build();
		Soklet soklet = Soklet.fromConfig(config);

		try {
			soklet.start();
			Assertions.assertTrue(soklet.isStarted());
			soklet.stop();
			Assertions.assertFalse(soklet.isStarted());

			List<String> eventsAfterFirstStop = List.copyOf(events);
			soklet.stop();

			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"will-start-mcp",
					"did-start-mcp",
					"did-start-soklet",
					"will-stop-soklet",
					"will-stop-mcp",
					"did-stop-mcp-CLEAN",
					"did-stop-soklet"), eventsAfterFirstStop);
			Assertions.assertEquals(eventsAfterFirstStop, events,
					"Stopping an already-stopped MCP-only Soklet must be a no-op");
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void mixedTransportLifecycleUsesConfiguredStartAndStopOrder() {
		List<String> events = new ArrayList<>();
		SokletConfig config = mixedTransportConfig(
				new RecordingLifecycleObserver(events, null));
		Soklet soklet = Soklet.fromConfig(config);

		try {
			soklet.start();
			soklet.stop();

			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"will-start-http",
					"did-start-http",
					"will-start-sse",
					"did-start-sse",
					"will-start-mcp",
					"did-start-mcp",
					"did-start-soklet",
					"will-stop-soklet",
					"will-stop-http",
					"did-stop-http",
					"will-stop-sse",
					"did-stop-sse",
					"will-stop-mcp",
					"did-stop-mcp-CLEAN",
					"did-stop-soklet"), events);
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void manuallyStartedMcpDoesNotPreventStartingMissingHttpAndSseServers() {
		List<String> events = new ArrayList<>();
		SokletConfig config = mixedTransportConfig(
				new RecordingLifecycleObserver(events, null));
		Soklet soklet = Soklet.fromConfig(config);
		McpServer mcpServer = config.getMcpServer().orElseThrow();

		try {
			mcpServer.start();
			soklet.start();

			Assertions.assertTrue(config.getHttpServer().orElseThrow().isStarted());
			Assertions.assertTrue(config.getSseServer().orElseThrow().isStarted());
			Assertions.assertTrue(mcpServer.isStarted());
			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"will-start-http",
					"did-start-http",
					"will-start-sse",
					"did-start-sse",
					"did-start-soklet"), events);

			List<String> eventsAfterFirstStart = List.copyOf(events);
			soklet.start();
			Assertions.assertEquals(eventsAfterFirstStart, events,
					"Starting when every configured transport is running must be a no-op");

			soklet.stop();
			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"will-start-http",
					"did-start-http",
					"will-start-sse",
					"did-start-sse",
					"did-start-soklet",
					"will-stop-soklet",
					"will-stop-http",
					"did-stop-http",
					"will-stop-sse",
					"did-stop-sse",
					"will-stop-mcp",
					"did-stop-mcp-CLEAN",
					"did-stop-soklet"), events);
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void failedMixedStartLeavesManualMcpRunningAndCanBeRetried() {
		List<String> events = new ArrayList<>();
		FailOnceSseServer sseServer = new FailOnceSseServer();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0).build())
				.sseServer(sseServer)
				.mcpServer(newMcpServer())
				.resourceMethodResolver(
						ResourceMethodResolver.fromClasses(Set.of(MixedTransportResource.class)))
				.lifecycleObserver(new RecordingLifecycleObserver(events, null))
				.build();
		Soklet soklet = Soklet.fromConfig(config);
		McpServer mcpServer = config.getMcpServer().orElseThrow();

		try {
			mcpServer.start();
			Assertions.assertThrows(IllegalStateException.class, soklet::start);

			Assertions.assertFalse(config.getHttpServer().orElseThrow().isStarted());
			Assertions.assertFalse(sseServer.isStarted());
			Assertions.assertTrue(mcpServer.isStarted(),
					"Rollback must not stop a transport that this invocation did not start");
			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"will-start-http",
					"did-start-http",
					"will-start-sse",
					"did-fail-start-sse",
					"will-stop-http",
					"did-stop-http",
					"did-fail-start-soklet"), events);

			events.clear();
			soklet.start();
			Assertions.assertTrue(config.getHttpServer().orElseThrow().isStarted());
			Assertions.assertTrue(sseServer.isStarted());
			Assertions.assertTrue(mcpServer.isStarted());
			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"will-start-http",
					"did-start-http",
					"will-start-sse",
					"did-start-sse",
					"did-start-soklet"), events,
					"Retry must start only transports that remain stopped");
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void failedMcpDidStartCallbackRollsBackMcpSseAndHttpInReverseOrder() {
		List<String> events = new ArrayList<>();
		RuntimeException expectedFailure = new RuntimeException("expected MCP did-start failure");
		SokletConfig config = mixedTransportConfig(
				new RecordingLifecycleObserver(events, expectedFailure));
		Soklet soklet = Soklet.fromConfig(config);

		try {
			RuntimeException actualFailure = Assertions.assertThrows(
					RuntimeException.class, soklet::start);

			Assertions.assertSame(expectedFailure, actualFailure);
			Assertions.assertFalse(soklet.isStarted());
			Assertions.assertEquals(List.of(
					"will-start-soklet",
					"will-start-http",
					"did-start-http",
					"will-start-sse",
					"did-start-sse",
					"will-start-mcp",
					"did-start-mcp",
					"did-fail-start-mcp",
					"will-stop-mcp",
					"did-stop-mcp-CLEAN",
					"will-stop-sse",
					"did-stop-sse",
					"will-stop-http",
					"did-stop-http",
					"did-fail-start-soklet"), events);
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void stopContinuesThroughMcpWhenAnEarlierLifecycleCallbackFails() {
		List<String> events = new ArrayList<>();
		RuntimeException expectedFailure = new RuntimeException("expected HTTP will-stop failure");
		AtomicReference<Throwable> globalFailure = new AtomicReference<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void willStopSoklet(@NonNull Soklet soklet) {
				events.add("will-stop-soklet");
			}

			@Override
			public void willStopHttpServer(@NonNull HttpServer httpServer) {
				events.add("will-stop-http");
				throw expectedFailure;
			}

			@Override
			public void didStopHttpServer(@NonNull HttpServer httpServer) {
				events.add("did-stop-http");
			}

			@Override
			public void willStopSseServer(@NonNull SseServer sseServer) {
				events.add("will-stop-sse");
			}

			@Override
			public void didStopSseServer(@NonNull SseServer sseServer) {
				events.add("did-stop-sse");
			}

			@Override
			public void willStopMcpServer(@NonNull McpServer mcpServer) {
				events.add("will-stop-mcp");
			}

			@Override
			public void didStopMcpServer(@NonNull McpServer mcpServer,
					@NonNull McpShutdownOutcome shutdownOutcome) {
				events.add("did-stop-mcp-" + shutdownOutcome.name());
			}

			@Override
			public void didFailToStopSoklet(@NonNull Soklet soklet,
					@NonNull Throwable throwable) {
				events.add("did-fail-stop-soklet");
				globalFailure.set(throwable);
			}

			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				// Keep expected MCP configuration diagnostics out of test output.
			}
		};
		Soklet soklet = Soklet.fromConfig(mixedTransportConfig(observer));

		try {
			soklet.start();
			soklet.stop();

			Assertions.assertFalse(soklet.isStarted());
			Assertions.assertSame(expectedFailure, globalFailure.get());
			Assertions.assertEquals(List.of(
					"will-stop-soklet",
					"will-stop-http",
					"did-stop-http",
					"will-stop-sse",
					"did-stop-sse",
					"will-stop-mcp",
					"did-stop-mcp-CLEAN",
					"did-fail-stop-soklet"), events);
		} finally {
			soklet.stop();
		}
	}

	@Test
	public void httpTransportStillRequiresResourceMethodsWhenMcpIsAlsoConfigured() {
		SokletConfig config = SokletConfig.withMcpServer(newMcpServer())
				.httpServer(HttpServer.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();

		Assertions.assertThrows(IllegalStateException.class,
				() -> Soklet.fromConfig(config));
	}

	@NonNull
	private static SokletConfig mixedTransportConfig(
			@NonNull LifecycleObserver lifecycleObserver) {
		return SokletConfig.withHttpServer(HttpServer.withPort(0).build())
				.sseServer(SseServer.withPort(0).build())
				.mcpServer(newMcpServer())
				.resourceMethodResolver(
						ResourceMethodResolver.fromClasses(Set.of(MixedTransportResource.class)))
				.lifecycleObserver(lifecycleObserver)
				.build();
	}

	@NonNull
	private static McpServer newMcpServer() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(
						McpImplementation.withNameAndVersion("test-server", "1.0").build())
				.build();

		return McpServer.withPort(0)
				.handlerResolver(McpHandlerResolver.fromEndpoints(List.of(endpoint)))
				.requestAdmissionPolicy(McpRequestAdmissionPolicy.acceptAllInstance())
				.build();
	}

	public static final class MixedTransportResource {
		@GET("/http")
		public void http() {
			// No-op.
		}

		@SseEventSource("/events")
		@NonNull
		public SseHandshakeResult events() {
			return SseHandshakeResult.accept();
		}
	}

	private static final class FailOnceSseServer implements SseServer {
		private int startAttempts;
		private boolean started;

		@Override
		public void start() {
			this.startAttempts++;

			if (this.startAttempts == 1)
				throw new IllegalStateException("expected first SSE start failure");

			this.started = true;
		}

		@Override
		public void stop() {
			this.started = false;
		}

		@Override
		@NonNull
		public Boolean isStarted() {
			return this.started;
		}

		@Override
		@NonNull
		public Optional<? extends SseBroadcaster> acquireBroadcaster(
				@Nullable ResourcePath resourcePath) {
			return Optional.empty();
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
				@NonNull RequestHandler requestHandler) {
			// No initialization state is needed for this lifecycle fixture.
		}
	}

	private static final class RecordingLifecycleObserver
			implements LifecycleObserver {
		@NonNull
		private final List<String> events;
		@Nullable
		private final RuntimeException didStartMcpFailure;

		private RecordingLifecycleObserver(@NonNull List<String> events,
				@Nullable RuntimeException didStartMcpFailure) {
			this.events = events;
			this.didStartMcpFailure = didStartMcpFailure;
		}

		@Override
		public void willStartSoklet(@NonNull Soklet soklet) {
			this.events.add("will-start-soklet");
		}

		@Override
		public void didStartSoklet(@NonNull Soklet soklet) {
			this.events.add("did-start-soklet");
		}

		@Override
		public void didFailToStartSoklet(@NonNull Soklet soklet,
				@NonNull Throwable throwable) {
			this.events.add("did-fail-start-soklet");
		}

		@Override
		public void willStopSoklet(@NonNull Soklet soklet) {
			this.events.add("will-stop-soklet");
		}

		@Override
		public void didStopSoklet(@NonNull Soklet soklet) {
			this.events.add("did-stop-soklet");
		}

		@Override
		public void willStartHttpServer(@NonNull HttpServer httpServer) {
			this.events.add("will-start-http");
		}

		@Override
		public void didStartHttpServer(@NonNull HttpServer httpServer) {
			this.events.add("did-start-http");
		}

		@Override
		public void willStopHttpServer(@NonNull HttpServer httpServer) {
			this.events.add("will-stop-http");
		}

		@Override
		public void didStopHttpServer(@NonNull HttpServer httpServer) {
			this.events.add("did-stop-http");
		}

		@Override
		public void willStartSseServer(@NonNull SseServer sseServer) {
			this.events.add("will-start-sse");
		}

		@Override
		public void didStartSseServer(@NonNull SseServer sseServer) {
			this.events.add("did-start-sse");
		}

		@Override
		public void didFailToStartSseServer(@NonNull SseServer sseServer,
				@NonNull Throwable throwable) {
			this.events.add("did-fail-start-sse");
		}

		@Override
		public void willStopSseServer(@NonNull SseServer sseServer) {
			this.events.add("will-stop-sse");
		}

		@Override
		public void didStopSseServer(@NonNull SseServer sseServer) {
			this.events.add("did-stop-sse");
		}

		@Override
		public void willStartMcpServer(@NonNull McpServer mcpServer) {
			this.events.add("will-start-mcp");
		}

		@Override
		public void didStartMcpServer(@NonNull McpServer mcpServer) {
			this.events.add("did-start-mcp");

			if (this.didStartMcpFailure != null)
				throw this.didStartMcpFailure;
		}

		@Override
		public void didFailToStartMcpServer(@NonNull McpServer mcpServer,
				@NonNull Throwable throwable) {
			this.events.add("did-fail-start-mcp");
		}

		@Override
		public void willStopMcpServer(@NonNull McpServer mcpServer) {
			this.events.add("will-stop-mcp");
		}

		@Override
		public void didStopMcpServer(@NonNull McpServer mcpServer,
				@NonNull McpShutdownOutcome shutdownOutcome) {
			this.events.add("did-stop-mcp-" + shutdownOutcome.name());
		}

		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// Keep expected MCP configuration diagnostics out of test output.
		}
	}
}

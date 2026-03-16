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

import com.soklet.annotation.McpServerEndpoint;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

@ThreadSafe
public class McpServerLifecycleTests {
	@Test
	public void mcpServerBuilderRequiresHandlerResolver() {
		Assertions.assertThrows(IllegalStateException.class, () -> McpServer.withPort(0).build());
	}

	@Test
	public void mcpOnlyConfigDoesNotRequireHttpResourceMethodsAndStartsConfiguredMcpServer() throws Exception {
		FakeStandardServer fakeStandardServer = new FakeStandardServer();
		FakeMcpServer fakeMcpServer = new FakeMcpServer();
		SokletConfig sokletConfig = SokletConfig.withServer(fakeStandardServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.mcpServer(fakeMcpServer)
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			Assertions.assertTrue(fakeMcpServer.getInitialized().get());
			soklet.start();
			Assertions.assertTrue(fakeMcpServer.getStarted().get());
		}

		Assertions.assertTrue(fakeMcpServer.getStopped().get());
	}

	@Test
	public void sokletMcpHandlerRejectsUnsupportedGetUntilStreamingIsImplemented() throws Exception {
		FakeStandardServer fakeStandardServer = new FakeStandardServer();
		FakeMcpServer fakeMcpServer = new FakeMcpServer();
		SokletConfig sokletConfig = SokletConfig.withServer(fakeStandardServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.mcpServer(fakeMcpServer)
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			AtomicReference<RequestResult> requestResultHolder = new AtomicReference<>();
			fakeMcpServer.getRequestHandler().orElseThrow()
					.handleRequest(Request.fromPath(HttpMethod.GET, "/mcp"), requestResultHolder::set);

			RequestResult requestResult = requestResultHolder.get();
			Assertions.assertEquals(Integer.valueOf(405), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertTrue(new String(requestResult.getMarshaledResponse().getBody().orElseThrow(), StandardCharsets.UTF_8)
					.startsWith("HTTP 405: Method Not Allowed"));
		}
	}

	@Test
	public void defaultMcpServerProvidesDefaultStrategies() {
		McpHandlerResolver handlerResolver = McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class));
		McpServer mcpServer = McpServer.withPort(0)
				.handlerResolver(handlerResolver)
				.build();

		Assertions.assertSame(handlerResolver, mcpServer.getHandlerResolver());
		Assertions.assertNotNull(mcpServer.getRequestAdmissionPolicy());
		Assertions.assertNotNull(mcpServer.getRequestInterceptor());
		Assertions.assertNotNull(mcpServer.getResponseMarshaler());
		Assertions.assertNotNull(mcpServer.getOriginPolicy());
		Assertions.assertNotNull(mcpServer.getSessionStore());
		Assertions.assertNotNull(mcpServer.getIdGenerator());
	}

	@Test
	public void simulatorCanPerformMcpRequestsThroughConfiguredMcpServer() {
		SokletConfig sokletConfig = SokletConfig.withServer(Server.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.mcpServer(McpServer.withPort(0)
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
						.build())
				.lifecycleObserver(new QuietLifecycle())
				.build();

		Soklet.runSimulator(sokletConfig, simulator -> {
			McpRequestResult requestResult = simulator.performMcpRequest(Request.fromPath(HttpMethod.GET, "/mcp"));
			Assertions.assertInstanceOf(McpRequestResult.ResponseCompleted.class, requestResult);
			Assertions.assertEquals(Integer.valueOf(405), requestResult.getRequestResult().getMarshaledResponse().getStatusCode());
		});
	}

	@Test
	public void simulatorTreatsEventStreamResponsesAsOpenMcpStreams() {
		Soklet.MockServer mockServer = new Soklet.MockServer();
		Soklet.MockMcpServer mockMcpServer = new Soklet.MockMcpServer(new FakeMcpServer());
		Soklet.DefaultSimulator simulator = new Soklet.DefaultSimulator(mockServer, null, mockMcpServer);

		mockMcpServer.initialize(SokletConfig.withServer(Server.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.mcpServer(new FakeMcpServer())
				.lifecycleObserver(new QuietLifecycle())
				.build(), (request, requestResultConsumer) -> requestResultConsumer.accept(
				RequestResult.fromMarshaledResponse(MarshaledResponse.withStatusCode(200)
						.headers(Map.of("Content-Type", Set.of("text/event-stream; charset=UTF-8")))
						.build())));

		McpRequestResult requestResult = simulator.performMcpRequest(Request.fromPath(HttpMethod.GET, "/mcp"));
		Assertions.assertInstanceOf(McpRequestResult.StreamOpened.class, requestResult);
	}

	@Test
	public void simulatorRoutesMcpStreamConsumerErrorsThroughDedicatedHandler() {
		Soklet.MockServer mockServer = new Soklet.MockServer();
		Soklet.MockMcpServer mockMcpServer = new Soklet.MockMcpServer(new FakeMcpServer());
		Soklet.DefaultSimulator simulator = new Soklet.DefaultSimulator(mockServer, null, mockMcpServer);
		AtomicReference<Throwable> streamErrorHolder = new AtomicReference<>();

		mockMcpServer.initialize(SokletConfig.withServer(Server.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.mcpServer(new FakeMcpServer())
				.lifecycleObserver(new QuietLifecycle())
				.build(), (request, requestResultConsumer) -> requestResultConsumer.accept(
				RequestResult.fromMarshaledResponse(MarshaledResponse.withStatusCode(200)
						.headers(Map.of("Content-Type", Set.of("text/event-stream; charset=UTF-8")))
						.build())));

		simulator.onMcpStreamError(streamErrorHolder::set);

		McpRequestResult.StreamOpened streamOpened = (McpRequestResult.StreamOpened) simulator.performMcpRequest(Request.fromPath(HttpMethod.GET, "/mcp"));
		streamOpened.registerMessageConsumer(message -> {
			throw new IllegalStateException("boom");
		});
		streamOpened.emitMessage(new McpObject(Map.of("messages", new McpArray(List.of(new McpString("one"))))));

		Assertions.assertNotNull(streamErrorHolder.get());
		Assertions.assertEquals("boom", streamErrorHolder.get().getMessage());
	}

	@McpServerEndpoint(path = "/mcp", name = "example", version = "1.0.0")
	public static class ExampleMcpEndpoint implements McpEndpoint {}

	private static final class FakeMcpServer implements McpServer {
		private final AtomicBoolean initialized;
		private final AtomicBoolean started;
		private final AtomicBoolean stopped;
		private SokletConfig sokletConfig;
		private RequestHandler requestHandler;

		private FakeMcpServer() {
			this.initialized = new AtomicBoolean();
			this.started = new AtomicBoolean();
			this.stopped = new AtomicBoolean();
		}

		@Override
		public void start() {
			this.started.set(true);
		}

		@Override
		public void stop() {
			this.stopped.set(true);
			this.started.set(false);
		}

		@NonNull
		@Override
		public Boolean isStarted() {
			return this.started.get();
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
													 @NonNull RequestHandler requestHandler) {
			this.sokletConfig = sokletConfig;
			this.requestHandler = requestHandler;
			this.initialized.set(true);
		}

		@NonNull
		@Override
		public McpHandlerResolver getHandlerResolver() {
			return McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class));
		}

		@NonNull
		@Override
		public McpRequestAdmissionPolicy getRequestAdmissionPolicy() {
			return McpRequestAdmissionPolicy.defaultInstance();
		}

		@NonNull
		@Override
		public McpRequestInterceptor getRequestInterceptor() {
			return new McpRequestInterceptor() {};
		}

		@NonNull
		@Override
		public McpResponseMarshaler getResponseMarshaler() {
			return McpResponseMarshaler.defaultInstance();
		}

		@NonNull
		@Override
		public McpOriginPolicy getOriginPolicy() {
			return McpOriginPolicy.nonBrowserClientsOnlyInstance();
		}

		@NonNull
		@Override
		public McpSessionStore getSessionStore() {
			return McpSessionStore.fromInMemory();
		}

		@NonNull
		@Override
		public IdGenerator<String> getIdGenerator() {
			return IdGenerator.defaultInstance();
		}

		@NonNull
		protected AtomicBoolean getInitialized() {
			return this.initialized;
		}

		@NonNull
		protected AtomicBoolean getStarted() {
			return this.started;
		}

		@NonNull
		protected AtomicBoolean getStopped() {
			return this.stopped;
		}

		@NonNull
		protected Optional<SokletConfig> getSokletConfig() {
			return Optional.ofNullable(this.sokletConfig);
		}

		@NonNull
		protected Optional<RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}
	}

	private static final class FakeStandardServer implements Server {
		private final AtomicBoolean started;
		private RequestHandler requestHandler;

		private FakeStandardServer() {
			this.started = new AtomicBoolean();
		}

		@Override
		public void start() {
			this.started.set(true);
		}

		@Override
		public void stop() {
			this.started.set(false);
		}

		@NonNull
		@Override
		public Boolean isStarted() {
			return this.started.get();
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
													 @NonNull RequestHandler requestHandler) {
			this.requestHandler = requestHandler;
		}

		@NonNull
		protected Optional<RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}
	}

	private static class QuietLifecycle implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* no-op */ }
	}
}

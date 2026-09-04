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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class SokletConfigTests {
	@Test
	public void defaultLifecycleObserversContainDefaultObserver() {
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0).build()).build();

		Assertions.assertEquals(List.of(LifecycleObserver.defaultInstance()), config.getLifecycleObservers());
	}

	@Test
	public void singularLifecycleObserverReplacesOrClearsObservers() {
		LifecycleObserver lifecycleObserver = new LifecycleObserver() {
			// Marker observer
		};

		SokletConfig configured = SokletConfig.withHttpServer(HttpServer.withPort(0).build())
				.lifecycleObserver(lifecycleObserver)
				.build();
		SokletConfig cleared = SokletConfig.withHttpServer(
					configured.getHttpServer().orElseThrow())
				.lifecycleObserver(null)
				.build();

		Assertions.assertEquals(List.of(lifecycleObserver), configured.getLifecycleObservers());
		Assertions.assertEquals(List.of(), cleared.getLifecycleObservers());
	}

	@Test
	public void pluralLifecycleObserversReplaceAndPreserveOrder() {
		List<String> calls = new ArrayList<>();
		LifecycleObserver first = namedObserver("first", calls);
		LifecycleObserver second = namedObserver("second", calls);
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0).build())
				.lifecycleObservers(List.of(first, second))
				.build();

		config.getAggregateLifecycleObserver().didStartRequestHandling(ServerType.STANDARD_HTTP, Request.fromPath(HttpMethod.GET, "/"), null);

		Assertions.assertEquals(List.of(first, second), config.getLifecycleObservers());
		Assertions.assertEquals(List.of("first", "second"), calls);
	}

	@Test
	public void lifecycleObserverFanOutInvokesRemainingObserversWhenOneFails() {
		List<String> calls = new ArrayList<>();
		RuntimeException expected = new RuntimeException("expected");
		RuntimeException expectedSuppressed = new RuntimeException("expected suppressed");
		LifecycleObserver first = namedObserver("first", calls);
		LifecycleObserver second = new LifecycleObserver() {
			@Override
			public void didStartRequestHandling(@NonNull ServerType serverType,
																					@NonNull Request request,
																					ResourceMethod resourceMethod) {
				calls.add("second");
				throw expected;
			}
		};
		LifecycleObserver third = namedObserver("third", calls);
		LifecycleObserver fourth = new LifecycleObserver() {
			@Override
			public void didStartRequestHandling(@NonNull ServerType serverType,
																					@NonNull Request request,
																					ResourceMethod resourceMethod) {
				calls.add("fourth");
				throw expectedSuppressed;
			}
		};
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0).build())
				.lifecycleObservers(List.of(first, second, third, fourth))
				.build();

		RuntimeException actual = Assertions.assertThrows(RuntimeException.class,
				() -> config.getAggregateLifecycleObserver().didStartRequestHandling(ServerType.STANDARD_HTTP, Request.fromPath(HttpMethod.GET, "/"), null));

		Assertions.assertSame(expected, actual);
		Assertions.assertArrayEquals(new Throwable[]{expectedSuppressed}, actual.getSuppressed());
		Assertions.assertEquals(List.of("first", "second", "third", "fourth"), calls);
	}

	@Test
	public void mcpServerCanAnchorConfiguration() {
		McpServer mcpServer = newMcpServer();
		SokletConfig config = SokletConfig.withMcpServer(mcpServer).build();

		Assertions.assertSame(mcpServer, config.getMcpServer().orElseThrow());
		Assertions.assertTrue(config.getHttpServer().isEmpty());
		Assertions.assertTrue(config.getSseServer().isEmpty());
	}

	@Test
	public void configurationRequiresAtLeastOneTransportServer() {
		Assertions.assertThrows(IllegalStateException.class,
				() -> SokletConfig.withMcpServer(newMcpServer())
						.mcpServer(null)
						.build());
	}

	@Test
	public void lifecyclePolicyIsStoredExactlyAndBridgedForInternalCallers() {
		LifecyclePolicy lifecyclePolicy = LifecyclePolicy.builder()
				.startupTimeout(Duration.ofSeconds(4))
				.startupCancelationTimeout(Duration.ofSeconds(1))
				.gracefulShutdownTimeout(Duration.ofSeconds(2))
				.forcedShutdownTimeout(Duration.ofSeconds(3))
				.build();
		SokletConfig config = SokletConfig.withMcpServer(newMcpServer())
				.lifecyclePolicy(lifecyclePolicy)
				.build();

		Assertions.assertSame(lifecyclePolicy, config.getLifecyclePolicy());
		InternalLifecyclePolicy internalPolicy =
				config.getInternalLifecyclePolicy();
		Assertions.assertEquals(Duration.ofSeconds(4),
				internalPolicy.startupTimeout());
		Assertions.assertEquals(Duration.ofSeconds(1),
				internalPolicy.startupCancelationTimeout());
		Assertions.assertEquals(Duration.ofSeconds(2),
				internalPolicy.gracefulShutdownTimeout());
		Assertions.assertEquals(Duration.ofSeconds(3),
				internalPolicy.forcedShutdownTimeout());
	}

	@Test
	public void nullLifecyclePolicyRestoresTheDefault() {
		SokletConfig config = SokletConfig.withMcpServer(newMcpServer())
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ZERO)
						.build())
				.lifecyclePolicy(null)
				.build();

		Assertions.assertEquals(Duration.ofSeconds(30),
				config.getLifecyclePolicy().getStartupTimeout());
	}

	@Test
	public void mcpLifecycleCallbacksFanOutInRegistrationOrder() {
		List<String> calls = new ArrayList<>();
		McpServer mcpServer = newMcpServer();
		LifecycleObserver first = new LifecycleObserver() {
			@Override
			public void willStartMcpServer(@NonNull McpServer server) {
				calls.add("first");
			}
		};
		LifecycleObserver second = new LifecycleObserver() {
			@Override
			public void willStartMcpServer(@NonNull McpServer server) {
				calls.add("second");
			}
		};
		SokletConfig config = SokletConfig.withMcpServer(mcpServer)
				.lifecycleObservers(List.of(first, second))
				.build();

		config.getAggregateLifecycleObserver().willStartMcpServer(mcpServer);

		Assertions.assertEquals(List.of("first", "second"), calls);
	}

	@NonNull
	private static McpServer newMcpServer() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("test-server", "1.0").build())
				.build();

		return McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.build();
	}

	@NonNull
	private static LifecycleObserver namedObserver(@NonNull String name,
																								 @NonNull List<String> calls) {
		return new LifecycleObserver() {
			@Override
			public void didStartRequestHandling(@NonNull ServerType serverType,
																					@NonNull Request request,
																					ResourceMethod resourceMethod) {
				calls.add(name);
			}
		};
	}
}

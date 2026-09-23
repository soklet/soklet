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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;

/** Exercises the unified writer's runtime metadata through both production adapters. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingResponseStreamRuntimeTests {
	@Test
	public void httpWriterReceivesRequestAndConfiguredTimeoutsWithFreshExecutionState() throws Exception {
		WriterResource resource = new WriterResource();
		Duration responseTimeout = Duration.ofSeconds(30);
		Duration idleTimeout = Duration.ofSeconds(10);
		int port = findFreePort();
		HttpServer server = HttpServer.withPort(port)
				.streamingResponseTimeout(responseTimeout)
				.streamingResponseIdleTimeout(idleTimeout)
				.build();

		try (Soklet soklet = Soklet.fromConfig(httpConfig(server, resource))) {
			soklet.start();
			for (int index = 0; index < 2; ++index) {
				Instant before = Instant.now();
				Assertions.assertEquals("unified", performHttp(port, HttpMethod.GET));
				Instant after = Instant.now();
				Assertions.assertEquals(index + 1, resource.executions.size());
				Execution execution = resource.executions.get(index);
				assertRequestAndTokenIdentity(resource, index);
				Assertions.assertEquals(Optional.of(idleTimeout), execution.idleTimeout());
				Instant deadline = execution.deadline().orElseThrow();
				Assertions.assertFalse(deadline.isBefore(before.plus(responseTimeout)),
						"The deadline must be based on this response execution");
				Assertions.assertFalse(deadline.isAfter(after.plus(responseTimeout)),
						"The deadline must be established before the response completes");
			}
		}

		assertFreshExecutionState(resource);
	}

	@Test
	public void httpWriterExposesEmptyMetadataWhenTimeoutsAreDisabled() throws Exception {
		WriterResource resource = new WriterResource();
		int port = findFreePort();
		HttpServer server = HttpServer.withPort(port)
				.streamingResponseTimeout(Duration.ZERO)
				.streamingResponseIdleTimeout(Duration.ZERO)
				.build();

		try (Soklet soklet = Soklet.fromConfig(httpConfig(server, resource))) {
			soklet.start();
			Assertions.assertEquals("unified", performHttp(port, HttpMethod.GET));
		}

		Assertions.assertEquals(1, resource.executions.size());
		assertRequestAndTokenIdentity(resource, 0);
		Assertions.assertTrue(resource.executions.get(0).deadline().isEmpty());
		Assertions.assertTrue(resource.executions.get(0).idleTimeout().isEmpty());
	}

	@Test
	public void simulatorWriterReceivesRequestAndEmptyTimeoutsWithFreshExecutionState() {
		WriterResource resource = new WriterResource();
		SokletSimulator.run(simulatorConfig(resource), simulator -> {
			for (int index = 0; index < 2; ++index) {
				Request request = Request.withPath(HttpMethod.GET, "/unified-stream")
						.id("unified-" + index).build();
				HttpRequestResult result = simulator.performHttpRequest(request);
				Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
				Assertions.assertEquals("unified", new String(result.getMarshaledResponse().bodyBytesOrEmpty(),
						StandardCharsets.UTF_8));
				Assertions.assertEquals(index + 1, resource.executions.size());
				assertRequestAndTokenIdentity(resource, index);
				Execution execution = resource.executions.get(index);
				Assertions.assertEquals(request.getId(), execution.request().getId());
				Assertions.assertTrue(execution.deadline().isEmpty());
				Assertions.assertTrue(execution.idleTimeout().isEmpty());
			}
		});

		assertFreshExecutionState(resource);
	}

	@Test
	public void headThroughStreamConvenienceDoesNotInvokeWriterInEitherRuntime() throws Exception {
		WriterResource resource = new WriterResource();
		Assertions.assertTrue(resource.executions.isEmpty(), "Building a response must not invoke its writer");
		SokletSimulator.run(simulatorConfig(resource), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(
					Request.withPath(HttpMethod.HEAD, "/unified-stream").build());
			Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals(0, result.getMarshaledResponse().bodyBytesOrEmpty().length);
		});
		Assertions.assertEquals(1, resource.requests.size());
		Assertions.assertTrue(resource.executions.isEmpty());

		int port = findFreePort();
		try (Soklet soklet = Soklet.fromConfig(httpConfig(HttpServer.withPort(port).build(), resource))) {
			soklet.start();
			Assertions.assertEquals("", performHttp(port, HttpMethod.HEAD));
		}

		Assertions.assertEquals(2, resource.requests.size(), "Both HEAD requests must reach the GET resource");
		Assertions.assertTrue(resource.executions.isEmpty(), "HEAD must suppress the reusable writer");
	}

	private static void assertRequestAndTokenIdentity(WriterResource resource, int index) {
		Execution execution = resource.executions.get(index);
		Assertions.assertSame(resource.requests.get(index), execution.request());
		Assertions.assertSame(execution.request(), execution.responseStream().getRequest());
		Assertions.assertSame(execution.tokenBeforeWrite(), execution.tokenAfterWrite());
		Assertions.assertSame(execution.tokenBeforeWrite(), execution.responseStream().getCancelationToken());
		Assertions.assertFalse(execution.tokenBeforeWrite().isCanceled());
		Assertions.assertTrue(execution.tokenBeforeWrite().getCancelationReason().isEmpty());
	}

	private static void assertFreshExecutionState(WriterResource resource) {
		Assertions.assertEquals(2, resource.executions.size());
		Assertions.assertNotSame(resource.executions.get(0).responseStream(), resource.executions.get(1).responseStream());
		Assertions.assertNotSame(resource.executions.get(0).tokenBeforeWrite(), resource.executions.get(1).tokenBeforeWrite());
	}

	private static SokletConfig httpConfig(HttpServer server, WriterResource resource) {
		return SokletConfig.withHttpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(WriterResource.class)))
				.instanceProvider(provider(resource)).lifecycleObserver(quietObserver()).build();
	}

	private static SimulatorConfig simulatorConfig(WriterResource resource) {
		return SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(WriterResource.class)))
				.instanceProvider(provider(resource)).lifecycleObserver(quietObserver()).build();
	}

	private static InstanceProvider provider(WriterResource resource) {
		return new InstanceProvider() {
			@Override
			public <T> T provide(Class<T> instanceClass) {
				return instanceClass == WriterResource.class ? instanceClass.cast(resource)
						: InstanceProvider.defaultInstance().provide(instanceClass);
			}
		};
	}

	private static LifecycleObserver quietObserver() {
		return new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				// Keep test output quiet; assertions remain outside lifecycle callbacks.
			}
		};
	}

	private static String performHttp(int port, HttpMethod method) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/unified-stream")
				.openConnection();
		connection.setRequestMethod(method.name());
		connection.setConnectTimeout(2_000);
		connection.setReadTimeout(3_000);
		try {
			Assertions.assertEquals(200, connection.getResponseCode());
			try (InputStream inputStream = connection.getInputStream()) {
				return new String(readAll(inputStream), StandardCharsets.UTF_8);
			}
		} finally {
			connection.disconnect();
		}
	}

	public static final class WriterResource {
		private final List<Request> requests = new CopyOnWriteArrayList<>();
		private final List<Execution> executions = new CopyOnWriteArrayList<>();
		private final MarshaledResponse response;

		private WriterResource() {
			StreamingResponseWriter streamingResponseWriter = responseStream -> {
				CancelationToken tokenBeforeWrite = responseStream.getCancelationToken();
				Request request = responseStream.getRequest();
				Optional<Instant> deadline = responseStream.getDeadline();
				Optional<Duration> idleTimeout = responseStream.getIdleTimeout();
				responseStream.write("unified".getBytes(StandardCharsets.UTF_8));
				responseStream.flush();
				this.executions.add(new Execution(responseStream, request, tokenBeforeWrite,
						responseStream.getCancelationToken(), deadline, idleTimeout));
			};
			this.response = MarshaledResponse.withStatusCode(200).stream(streamingResponseWriter).build();
		}

		@GET("/unified-stream")
		public MarshaledResponse stream(Request request) {
			this.requests.add(request);
			return this.response;
		}
	}

	private record Execution(ResponseStream responseStream, Request request, CancelationToken tokenBeforeWrite,
			CancelationToken tokenAfterWrite, Optional<Instant> deadline, Optional<Duration> idleTimeout) {}
}

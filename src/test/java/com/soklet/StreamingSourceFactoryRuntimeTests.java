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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;

/** Runs the checked source contract through both production adapters. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingSourceFactoryRuntimeTests {
	@Test
	@Timeout(value = 240, unit = TimeUnit.SECONDS)
	public void checkedSourcesAreLazyAndReopenedForEachExecution() throws Exception {
		for (boolean reader : new boolean[]{false, true}) {
			AtomicInteger opens = new AtomicInteger();
			AtomicInteger closes = new AtomicInteger();
			SourceResource resource = new SourceResource(body(reader, () -> {
				opens.incrementAndGet();
				return new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8)) {
					@Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
				};
			}, () -> {
				opens.incrementAndGet();
				return new StringReader("source") {
					@Override public void close() { closes.incrementAndGet(); super.close(); }
				};
			}));
			Assertions.assertEquals(0, opens.get());
			SokletSimulator.run(simulatorConfig(resource, new Observation()), simulator -> {
				for (int i = 0; i < 2; ++i)
					Assertions.assertArrayEquals("source".getBytes(StandardCharsets.UTF_8),
							simulator.performHttpRequest(request(HttpMethod.GET)).getMarshaledResponse().bodyBytesOrEmpty());
			});
			Observation observation = new Observation();
			String wire = performHttp(resource, HttpMethod.GET, observation);
			Assertions.assertTrue(wire.contains("source"), wire);
			Assertions.assertEquals(StreamTerminationReason.COMPLETED, observation.termination.get().getReason());
			Assertions.assertEquals(3, opens.get());
			Assertions.assertEquals(3, closes.get());
		}
	}

	@Test
	@Timeout(value = 240, unit = TimeUnit.SECONDS)
	public void checkedAcquisitionFailuresPreserveTheirCause() throws Exception {
		for (boolean reader : new boolean[]{false, true}) {
			IOException failure = new IOException("checked acquisition failure");
			AtomicInteger opens = new AtomicInteger();
			SourceResource resource = new SourceResource(body(reader,
					() -> { opens.incrementAndGet(); throw failure; },
					() -> { opens.incrementAndGet(); throw failure; }));
			Observation simulation = new Observation();
			IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () ->
					SokletSimulator.run(simulatorConfig(resource, simulation), simulator ->
							simulator.performHttpRequest(request(HttpMethod.GET))));
			Assertions.assertSame(failure, exception.getCause());
			assertFailure(simulation, failure);
			Observation http = new Observation();
			performHttp(resource, HttpMethod.GET, http);
			assertFailure(http, failure);
			Assertions.assertEquals(2, opens.get());
		}
	}

	@Test
	@Timeout(value = 240, unit = TimeUnit.SECONDS)
	public void nullSourceIsAProducerFailureInBothRuntimes() throws Exception {
		for (boolean reader : new boolean[]{false, true}) {
			SourceResource resource = new SourceResource(body(reader, () -> null, () -> null));
			Observation simulation = new Observation();
			IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () ->
					SokletSimulator.run(simulatorConfig(resource, simulation), simulator ->
							simulator.performHttpRequest(request(HttpMethod.GET))));
			Assertions.assertInstanceOf(NullPointerException.class, exception.getCause());
			assertFailure(simulation, exception.getCause());
			Observation http = new Observation();
			performHttp(resource, HttpMethod.GET, http);
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, http.termination.get().getReason());
			Assertions.assertInstanceOf(NullPointerException.class, http.termination.get().getCause().orElseThrow());
		}
	}

	@Test
	@Timeout(value = 240, unit = TimeUnit.SECONDS)
	public void headDoesNotInvokeCheckedFactory() throws Exception {
		for (boolean reader : new boolean[]{false, true}) {
			AtomicInteger opens = new AtomicInteger();
			SourceResource resource = new SourceResource(body(reader,
					() -> { opens.incrementAndGet(); throw new IOException("must stay lazy"); },
					() -> { opens.incrementAndGet(); throw new IOException("must stay lazy"); }));
			SokletSimulator.run(simulatorConfig(resource, new Observation()), simulator -> {
				Assertions.assertEquals(200, simulator.performHttpRequest(request(HttpMethod.HEAD))
						.getMarshaledResponse().getStatusCode());
			});
			String wire = performHttp(resource, HttpMethod.HEAD, null);
			Assertions.assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
			Assertions.assertEquals(0, opens.get());
		}
	}

	@Test
	public void simulatorCompletesTokenBeforeReportingSuccess() throws Exception {
		AtomicReference<CancelationToken> token = new AtomicReference<>();
		AtomicReference<CallbackRegistration> originalRegistration = new AtomicReference<>();
		AtomicReference<Object> callbackAtObservation = new AtomicReference<>(Boolean.TRUE);
		AtomicInteger callbacks = new AtomicInteger();
		SourceResource resource = new SourceResource(StreamingResponseBody.fromWriter(responseStream -> {
			token.set(responseStream.getCancelationToken());
			originalRegistration.set(token.get().onCancel(callbacks::incrementAndGet));
			responseStream.write(new byte[]{1});
		}));
		Observation observation = new Observation() {
			@Override
			public void didTerminateResponseStream(@NonNull StreamingResponseHandle handle,
					@NonNull StreamTermination termination) {
				super.didTerminateResponseStream(handle, termination);
				try {
					var callbackField = originalRegistration.get().getClass().getDeclaredField("callback");
					callbackField.setAccessible(true);
					callbackAtObservation.set(callbackField.get(originalRegistration.get()));
				} catch (ReflectiveOperationException exception) {
					throw new AssertionError(exception);
				}
				token.get().onCancel(callbacks::incrementAndGet).close();
			}
		};
		SokletSimulator.run(simulatorConfig(resource, observation), simulator ->
				simulator.performHttpRequest(request(HttpMethod.GET)));
		Assertions.assertEquals(StreamTerminationReason.COMPLETED, observation.termination.get().getReason());
		Assertions.assertNull(callbackAtObservation.get(), "Callback must be released before the success observer runs");
		Assertions.assertFalse(token.get().isCanceled());
		Assertions.assertTrue(token.get().getCancelationReason().isEmpty());
		// Observe deterministic reference release without relying on GC timing.
		var callbackField = originalRegistration.get().getClass().getDeclaredField("callback");
		callbackField.setAccessible(true);
		Assertions.assertNull(callbackField.get(originalRegistration.get()));
		Assertions.assertEquals(0, callbacks.get());
		CallbackRegistration registration = token.get().onCancel(callbacks::incrementAndGet);
		registration.close();
		registration.close();
		Assertions.assertEquals(0, callbacks.get());
	}

	private static StreamingResponseBody body(boolean reader,
			StreamResourceFactory<? extends InputStream> inputStreamFactory,
			StreamResourceFactory<? extends Reader> readerFactory) {
		return reader ? StreamingResponseBody.fromReader(readerFactory, StandardCharsets.UTF_8)
				: StreamingResponseBody.fromInputStream(inputStreamFactory);
	}

	private static Request request(HttpMethod method) {
		return Request.withPath(method, "/source").build();
	}

	private static void assertFailure(Observation observation, Throwable failure) {
		Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, observation.termination.get().getReason());
		Assertions.assertSame(failure, observation.termination.get().getCause().orElseThrow());
	}

	private static SimulatorConfig simulatorConfig(SourceResource resource, Observation observation) {
		return SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SourceResource.class)))
				.instanceProvider(provider(resource)).lifecycleObserver(observation).build();
	}

	private static String performHttp(SourceResource resource, HttpMethod method, Observation observation) throws Exception {
		int port = findFreePort();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SourceResource.class)))
				.instanceProvider(provider(resource))
				.lifecycleObserver(observation == null ? new Observation() : observation).build();
		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000)) {
				socket.setSoTimeout(3000);
				socket.getOutputStream().write((method.name() + " /source HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
						.getBytes(StandardCharsets.ISO_8859_1));
				String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
				if (observation != null)
					Assertions.assertTrue(observation.terminated.await(3, TimeUnit.SECONDS));
				return wire;
			}
		}
	}

	private static InstanceProvider provider(SourceResource resource) {
		return new InstanceProvider() {
			@Override
			public <T> T provide(Class<T> instanceClass) {
				return instanceClass == SourceResource.class ? instanceClass.cast(resource)
						: InstanceProvider.defaultInstance().provide(instanceClass);
			}
		};
	}

	public static final class SourceResource {
		private final StreamingResponseBody body;
		private SourceResource(StreamingResponseBody body) { this.body = body; }
		@GET("/source")
		public MarshaledResponse source() {
			return MarshaledResponse.withStatusCode(200).streamingResponseBody(this.body).build();
		}
	}

	private static class Observation implements LifecycleObserver {
		private final AtomicReference<StreamTermination> termination = new AtomicReference<>();
		private final CountDownLatch terminated = new CountDownLatch(1);
		@Override
		public void didTerminateResponseStream(@NonNull StreamingResponseHandle handle, @NonNull StreamTermination termination) {
			this.termination.set(termination);
			this.terminated.countDown();
		}
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {}
	}
}

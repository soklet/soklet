/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.TestSupport.findFreePort;

/** Runs ownership through the public HTTP and simulator adapters. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingResourceOwnershipRuntimeTests {
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void rootCleanupRunsInReverseOrderOnOwnerAndCanWriteAfterWriterReturns() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			List<String> events = new CopyOnWriteArrayList<>();
			List<Thread> closeThreads = new CopyOnWriteArrayList<>();
			AtomicReference<Thread> owner = new AtomicReference<>();
			AtomicReference<ResponseStream> retained = new AtomicReference<>();
			AtomicInteger callbacks = new AtomicInteger();
			Outcome outcome = execute(simulated, HttpMethod.GET, responseStream -> {
				owner.set(Thread.currentThread());
				retained.set(responseStream);
				responseStream.getCancelationToken().onCancel(callbacks::incrementAndGet);
				responseStream.own((AutoCloseable) () -> {
					events.add("first");
					closeThreads.add(Thread.currentThread());
					responseStream.write(bytes("1"));
				});
				responseStream.own((AutoCloseable) () -> {
					events.add("second");
					closeThreads.add(Thread.currentThread());
					responseStream.write(bytes("2"));
				});
				responseStream.write(bytes("body"));
				events.add("writer-returning");
			});
			assertCompleted(outcome, "body21");
			Assertions.assertEquals(List.of("writer-returning", "second", "first"), events);
			Assertions.assertEquals(List.of(owner.get(), owner.get()), closeThreads);
			Assertions.assertEquals(0, callbacks.get());
			Assertions.assertFalse(retained.get().isOpen());
			Assertions.assertFalse(retained.get().getCancelationToken().isCanceled());
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void nestedUsingClosesItsChildrenBeforeOuterAndRootLifetimesEnd() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			List<String> events = new CopyOnWriteArrayList<>();
			Outcome outcome = execute(simulated, HttpMethod.GET, responseStream -> {
				responseStream.open(() -> closing("root", events));
				responseStream.using(() -> closing("outer", events), outer -> {
					responseStream.open(() -> closing("outer-child", events), resource -> events.add("unexpected-abort"));
					responseStream.using(() -> closing("inner", events), resource -> {}, inner -> {
						responseStream.own(closing("inner-child", events));
						events.add("inner-body");
					});
					events.add("outer-body-resumed");
				});
				events.add("root-body-resumed");
				responseStream.write(bytes("ok"));
			});
			assertCompleted(outcome, "ok");
			Assertions.assertEquals(List.of("inner-body", "inner-child", "inner", "outer-body-resumed",
					"outer-child", "outer", "root-body-resumed", "root"), events);
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void duplicateActiveOwnershipIsRejectedWithoutDoubleClose() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			AtomicInteger closes = new AtomicInteger();
			AtomicReference<Throwable> adoptionFailure = new AtomicReference<>();
			AtomicReference<Throwable> acquisitionFailure = new AtomicReference<>();
			AutoCloseable resource = closes::incrementAndGet;
			Outcome outcome = execute(simulated, HttpMethod.GET, responseStream -> {
				responseStream.own(resource);
				adoptionFailure.set(capture(() -> responseStream.own(resource)));
				acquisitionFailure.set(capture(() -> responseStream.open(() -> resource)));
				responseStream.write(bytes("ok"));
			});
			assertCompleted(outcome, "ok");
			Assertions.assertInstanceOf(IllegalArgumentException.class, adoptionFailure.get());
			Assertions.assertInstanceOf(IllegalArgumentException.class, acquisitionFailure.get());
			Assertions.assertEquals(1, closes.get());
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void ownerChecksAndClosedScopeChecksRunBeforeAcquisition() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			AtomicReference<ResponseStream> retained = new AtomicReference<>();
			AtomicInteger factories = new AtomicInteger();
			List<Throwable> failures = new CopyOnWriteArrayList<>();
			Outcome outcome = execute(simulated, HttpMethod.GET, responseStream -> {
				retained.set(responseStream);
				Thread other = new Thread(() -> {
					addFailure(failures, () -> responseStream.write(bytes("wrong-thread")));
					addFailure(failures, responseStream::flush);
					addFailure(failures, () -> responseStream.open(() -> {
						factories.incrementAndGet();
						return () -> {};
					}));
					addFailure(failures, () -> responseStream.using(() -> {
						factories.incrementAndGet();
						return () -> {};
					}, resource -> {}));
				}, "ownership-wrong-thread");
				other.start();
				other.join(2_000);
				if (other.isAlive()) {
					other.interrupt();
					throw new IllegalStateException("Wrong-thread operations failed to return");
				}
				responseStream.write(bytes("ok"));
			});
			assertCompleted(outcome, "ok");
			Assertions.assertEquals(4, failures.size());
			for (Throwable failure : failures) Assertions.assertInstanceOf(IllegalStateException.class, failure);
			Assertions.assertThrows(IllegalStateException.class, () -> retained.get().write(bytes("late")));
			Assertions.assertThrows(IllegalStateException.class, retained.get()::flush);
			Assertions.assertThrows(IllegalStateException.class, () -> retained.get().open(() -> {
				factories.incrementAndGet();
				return () -> {};
			}));
			Assertions.assertEquals(0, factories.get());
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void headSuppressesWriterFactoriesConsumersAndCallbacks() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			AtomicInteger calls = new AtomicInteger();
			Outcome outcome = execute(simulated, HttpMethod.HEAD, responseStream -> {
				calls.incrementAndGet();
				responseStream.getCancelationToken().onCancel(calls::incrementAndGet);
				responseStream.open(() -> { calls.incrementAndGet(); return calls::incrementAndGet; });
				responseStream.using(() -> { calls.incrementAndGet(); return calls::incrementAndGet; },
						resource -> calls.incrementAndGet());
			});
			Assertions.assertNull(outcome.failure());
			Assertions.assertEquals("", outcome.body());
			Assertions.assertEquals(0, calls.get());
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void producerFailureRemainsPrimaryWhenCleanupAlsoFails() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			IOException producerFailure = new IOException("producer failed");
			IOException closeFailure = new IOException("close failed");
			Outcome outcome = execute(simulated, HttpMethod.GET, responseStream -> {
				responseStream.own((AutoCloseable) () -> { throw closeFailure; });
				throw producerFailure;
			});
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, outcome.termination().getReason());
			Assertions.assertSame(producerFailure, outcome.termination().getCause().orElseThrow());
			Assertions.assertTrue(List.of(producerFailure.getSuppressed()).contains(closeFailure));
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void cleanupOnlyFailureCannotProduceSuccessfulCompletion() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			IOException closeFailure = new IOException("close failed");
			Outcome outcome = execute(simulated, HttpMethod.GET, responseStream -> {
				responseStream.own((AutoCloseable) () -> { throw closeFailure; });
				responseStream.write(bytes("partial"));
			});
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, outcome.termination().getReason());
			Assertions.assertSame(closeFailure, outcome.termination().getCause().orElseThrow());
		}
	}

	private static AutoCloseable closing(String name, List<String> events) { return () -> events.add(name); }
	private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

	private static void assertCompleted(Outcome outcome, String body) {
		Assertions.assertNull(outcome.failure());
		Assertions.assertEquals(body, outcome.body());
		Assertions.assertNotNull(outcome.termination());
		Assertions.assertEquals(StreamTerminationReason.COMPLETED, outcome.termination().getReason());
	}

	private static Throwable capture(CheckedAction action) {
		try { action.run(); return null; } catch (Throwable failure) { return failure; }
	}

	private static void addFailure(List<Throwable> failures, CheckedAction action) {
		Throwable failure = capture(action);
		if (failure != null) failures.add(failure);
	}

	private static Outcome execute(boolean simulated, HttpMethod method, StreamingResponseWriter writer) throws Exception {
		WriterResource resource = new WriterResource(writer);
		Observation observation = new Observation();
		InstanceProvider provider = new InstanceProvider() {
			@Override public <T> T provide(Class<T> type) {
				return type == WriterResource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
			}
		};
		ResourceMethodResolver resolver = ResourceMethodResolver.fromClasses(Set.of(WriterResource.class));
		AtomicReference<String> body = new AtomicReference<>();
		Throwable failure = null;
		if (simulated) {
			SimulatorConfig config = SimulatorConfig.builder().httpServer().resourceMethodResolver(resolver)
					.instanceProvider(provider).lifecycleObserver(observation).build();
			try {
				SokletSimulator.run(config, simulator -> {
					HttpRequestResult result = simulator.performHttpRequest(Request.withPath(method, "/owned").build());
					Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
					body.set(new String(result.getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
				});
			} catch (RuntimeException exception) { failure = exception; }
		} else {
			int port = findFreePort();
			SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port).build())
					.resourceMethodResolver(resolver).instanceProvider(provider).lifecycleObserver(observation).build();
			try (Soklet soklet = Soklet.fromConfig(config)) {
				soklet.start();
				HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/owned").openConnection();
				connection.setRequestMethod(method.name());
				connection.setConnectTimeout(2_000);
				connection.setReadTimeout(3_000);
				try {
					Assertions.assertEquals(200, connection.getResponseCode());
					body.set(new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
				} catch (IOException exception) { failure = exception; }
				finally { connection.disconnect(); }
				if (method != HttpMethod.HEAD)
					Assertions.assertTrue(observation.terminated.await(3, TimeUnit.SECONDS));
			}
		}
		return new Outcome(body.get(), failure, observation.termination.get());
	}

	public static final class WriterResource {
		private final MarshaledResponse response;
		private WriterResource(StreamingResponseWriter writer) {
			this.response = MarshaledResponse.withStatusCode(200).stream(writer).build();
		}
		@GET("/owned") public MarshaledResponse owned() { return this.response; }
	}

	private static final class Observation implements LifecycleObserver {
		private final AtomicReference<StreamTermination> termination = new AtomicReference<>();
		private final CountDownLatch terminated = new CountDownLatch(1);
		@Override public void didTerminateResponseStream(@NonNull StreamingResponseHandle handle, @NonNull StreamTermination termination) {
			this.termination.set(termination);
			this.terminated.countDown();
		}
		@Override public void didReceiveLogEvent(@NonNull LogEvent logEvent) {}
	}

	@FunctionalInterface private interface CheckedAction { void run() throws Exception; }
	private record Outcome(String body, Throwable failure, StreamTermination termination) {}
}

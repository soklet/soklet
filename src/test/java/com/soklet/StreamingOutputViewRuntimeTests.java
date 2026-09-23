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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.soklet.TestSupport.findFreePort;

/** Exercises Java I/O views through the public HTTP and simulator response paths. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingOutputViewRuntimeTests {
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void mixedOutputKeepsOrderAndCopiesCallerBuffersBeforeReturning() throws Exception {
		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(bytes("ABCDEFGHIJKL-π🌱-"));
		for (int index = 0; index < 20_001; ++index)
			expected.write('a' + index % 26);
		for (boolean simulated : new boolean[]{false, true}) {
			Outcome outcome = execute(simulated, responseStream -> {
				OutputStream first = responseStream.asOutputStream();
				OutputStream second = responseStream.asOutputStream();
				first.write('A');
				second.write(0x142); // OutputStream writes the low eight bits.
				byte[] nativeBytes = bytes("CD");
				responseStream.write(nativeBytes);
				Arrays.fill(nativeBytes, (byte) '?');
				byte[] nativeSlice = bytes("xEFy");
				responseStream.write(nativeSlice, 1, 2);
				Arrays.fill(nativeSlice, (byte) '?');
				byte[] viewSlice = bytes("xGHy");
				first.write(viewSlice, 1, 2);
				Arrays.fill(viewSlice, (byte) '?');
				ByteBuffer direct = ByteBuffer.allocateDirect(4);
				direct.put(bytes("xIJy")).position(1).limit(3);
				ByteBuffer readOnly = direct.asReadOnlyBuffer();
				readOnly.mark();
				responseStream.write(readOnly);
				Assertions.assertEquals(1, readOnly.position());
				Assertions.assertEquals(3, readOnly.limit());
				readOnly.reset();
				direct.put(1, (byte) '?').put(2, (byte) '?');
				byte[] heapBytes = bytes("xKLy");
				ByteBuffer heap = ByteBuffer.wrap(heapBytes);
				heap.position(1).limit(3).mark();
				responseStream.write(heap);
				Assertions.assertEquals(1, heap.position());
				Assertions.assertEquals(3, heap.limit());
				heap.reset();
				Arrays.fill(heapBytes, (byte) '?');
				responseStream.write(bytes("-π🌱-"));
				// Cross staging boundaries and leave a final scalar for automatic finalization.
				for (int index = 0; index < 20_001; ++index)
					(index % 2 == 0 ? first : second).write('a' + index % 26);
			});
			assertCompleted(outcome, expected.toByteArray());
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void invalidSlicesDoNotPoisonOutputAndEmptySlicesAreAccepted() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			Outcome outcome = execute(simulated, responseStream -> {
				OutputStream view = responseStream.asOutputStream();
				view.write('a');
				byte[] bytes = bytes("xyz");
				for (int[] bounds : new int[][]{{-1, 1}, {0, -1}, {2, 2}, {4, 0}, {1, Integer.MAX_VALUE}}) {
					Assertions.assertThrows(IndexOutOfBoundsException.class,
							() -> responseStream.write(bytes, bounds[0], bounds[1]));
					Assertions.assertThrows(IndexOutOfBoundsException.class,
							() -> view.write(bytes, bounds[0], bounds[1]));
				}
				Assertions.assertThrows(NullPointerException.class, () -> responseStream.write(bytes, null, 1));
				Assertions.assertThrows(NullPointerException.class, () -> responseStream.write(bytes, 0, null));
				Assertions.assertThrows(NullPointerException.class, () -> responseStream.write((byte[]) null));
				responseStream.write(bytes, bytes.length, 0);
				view.write(bytes, bytes.length, 0);
				responseStream.write(new byte[0]);
				responseStream.write(bytes, 1, 1);
				view.write('b');
			});
			assertCompleted(outcome, bytes("ayb"));
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void closingOneViewFlushesItAndLeavesOtherOutputUsable() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			Outcome outcome = execute(simulated, responseStream -> {
				OutputStream second = responseStream.asOutputStream();
				OutputStream first = responseStream.asOutputStream();
				try (first) {
					first.write('a');
					second.write('b');
				}
				first.close();
				Assertions.assertTrue(responseStream.isOpen());
				Assertions.assertThrows(IOException.class, () -> first.write('!'));
				Assertions.assertThrows(IOException.class, () -> first.write(bytes("!")));
				Assertions.assertThrows(IOException.class, first::flush);
				responseStream.write(bytes("c"));
				second.write('d');
				second.close();
				responseStream.write(bytes("e"));
				responseStream.asOutputStream().write('f');
			});
			assertCompleted(outcome, bytes("abcdef"));
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void ownedZipFinalizationEmitsAReadableCentralDirectory() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			Outcome outcome = execute(simulated, responseStream -> {
				ZipOutputStream zip = responseStream.own(new ZipOutputStream(responseStream.asOutputStream(), StandardCharsets.UTF_8));
				zip.putNextEntry(new ZipEntry("first.txt"));
				zip.write(bytes("first entry"));
				zip.closeEntry();
				zip.putNextEntry(new ZipEntry("second-π.txt"));
				zip.write(bytes("second 🌱 entry"));
				// Scope finalization must close this entry and emit the archive's directory.
			});
			assertCompleted(outcome);
			Path archive = Files.createTempFile("soklet-output-view-", ".zip");
			try {
				Files.write(archive, outcome.body());
				try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
					Assertions.assertEquals(2, zip.size());
					try (var input = zip.getInputStream(zip.getEntry("first.txt"))) {
						Assertions.assertArrayEquals(bytes("first entry"), input.readAllBytes());
					}
					try (var input = zip.getInputStream(zip.getEntry("second-π.txt"))) {
						Assertions.assertArrayEquals(bytes("second 🌱 entry"), input.readAllBytes());
					}
				}
			} finally {
				Files.deleteIfExists(archive);
			}
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void ownedUtf8WriterFlushesAndEmitsBufferedTextDuringFinalization() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			Outcome outcome = execute(simulated, responseStream -> {
				OutputStreamWriter writer = responseStream.own(new OutputStreamWriter(responseStream.asOutputStream(), StandardCharsets.UTF_8));
				writer.write("prefix π ");
				writer.flush();
				responseStream.write(bytes("middle "));
				writer.write(0xD83C);
				writer.write(0xDF31);
				writer.write(" suffix");
			});
			assertCompleted(outcome, bytes("prefix π middle 🌱 suffix"));
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void viewAndNativeLifetimeChecksPreserveTheResponse() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			AtomicReference<ResponseStream> retainedStream = new AtomicReference<>();
			AtomicReference<OutputStream> retainedView = new AtomicReference<>();
			Outcome outcome = execute(simulated, responseStream -> {
				OutputStream view = responseStream.asOutputStream();
				retainedStream.set(responseStream);
				retainedView.set(view);
				AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
				Thread foreign = new Thread(() -> {
					try {
						Assertions.assertThrows(IllegalStateException.class, () -> view.write('!'));
						Assertions.assertThrows(IllegalStateException.class, view::flush);
						Assertions.assertThrows(IllegalStateException.class, view::close);
						Assertions.assertThrows(IllegalStateException.class, responseStream::asOutputStream);
						Assertions.assertThrows(IllegalStateException.class, () -> responseStream.write(bytes("!")));
					} catch (Throwable failure) {
						foreignFailure.set(failure);
					}
				}, "output-view-foreign-thread");
				foreign.setDaemon(true);
				foreign.start();
				foreign.join(2_000);
				if (foreign.isAlive()) {
					foreign.interrupt();
					throw new IllegalStateException("Foreign-thread output did not return");
				}
				Assertions.assertNull(foreignFailure.get());
				view.write('a');
				responseStream.write(bytes("b"));
			}, () -> {
				// Run after the scope on its actual producer thread, including for real HTTP.
				Assertions.assertThrows(IOException.class, () -> retainedView.get().write('!'));
				Assertions.assertThrows(IOException.class, retainedView.get()::flush);
				Assertions.assertThrows(IllegalStateException.class, retainedStream.get()::asOutputStream);
				Assertions.assertThrows(IllegalStateException.class, () -> retainedStream.get().write(bytes("!")));
				Assertions.assertThrows(IllegalStateException.class, retainedStream.get()::flush);
			});
			assertCompleted(outcome, bytes("ab"));
		}
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	public void failureAfterWritingFinalizationBytesCannotReportSuccess() throws Exception {
		for (boolean simulated : new boolean[]{false, true}) {
			IOException failure = new IOException("encoder finalization failed");
			Outcome outcome = execute(simulated, responseStream -> {
				OutputStream view = responseStream.asOutputStream();
				responseStream.own((AutoCloseable) () -> {
					view.write(bytes("trailer"));
					throw failure;
				});
				view.write(bytes("body"));
				view.flush();
			});
			Assertions.assertNotNull(outcome.failure(), "Failed finalization must not return a complete response body");
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, outcome.termination().getReason());
			Assertions.assertSame(failure, outcome.termination().getCause().orElseThrow());
		}
	}

	private static byte[] bytes(String string) { return string.getBytes(StandardCharsets.UTF_8); }

	private static void assertCompleted(Outcome outcome, byte[] body) {
		assertCompleted(outcome);
		Assertions.assertArrayEquals(body, outcome.body());
	}

	private static void assertCompleted(Outcome outcome) {
		Assertions.assertNull(outcome.failure());
		Assertions.assertNotNull(outcome.body());
		Assertions.assertNotNull(outcome.termination());
		Assertions.assertEquals(StreamTerminationReason.COMPLETED, outcome.termination().getReason());
	}

	private static Outcome execute(boolean simulated, StreamingResponseWriter writer) throws Exception {
		return execute(simulated, writer, null);
	}

	private static Outcome execute(boolean simulated, StreamingResponseWriter writer, CheckedAction afterScope) throws Exception {
		WriterResource resource = new WriterResource(writer);
		Observation observation = new Observation();
		InstanceProvider provider = new InstanceProvider() {
			@Override public <T> T provide(Class<T> type) {
				return type == WriterResource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
			}
		};
		ResourceMethodResolver resolver = ResourceMethodResolver.fromClasses(Set.of(WriterResource.class));
		AtomicReference<byte[]> body = new AtomicReference<>();
		Throwable failure = null;
		if (simulated) {
			SimulatorConfig config = SimulatorConfig.builder().httpServer().resourceMethodResolver(resolver)
					.instanceProvider(provider).lifecycleObserver(observation).build();
			try {
				SokletSimulator.run(config, simulator -> {
					HttpRequestResult result = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/output-view").build());
					Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
					body.set(result.getMarshaledResponse().bodyBytesOrEmpty());
					if (afterScope != null)
						afterScope.run();
				});
			} catch (RuntimeException exception) { failure = exception; }
		} else {
			int port = findFreePort();
			AtomicReference<Throwable> afterScopeFailure = new AtomicReference<>();
			CountDownLatch afterScopeCompleted = new CountDownLatch(1);
			ThreadPoolExecutor producerExecutor = afterScope == null ? null
					: new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4)) {
						@Override protected void afterExecute(Runnable task, Throwable throwable) {
							try { afterScope.run(); }
							catch (Throwable failure) { afterScopeFailure.set(failure); }
							finally { afterScopeCompleted.countDown(); }
						}
					};
			HttpServer.Builder serverBuilder = HttpServer.withPort(port);
			if (producerExecutor != null)
				serverBuilder.streamingExecutorServiceSupplier(() -> producerExecutor);
			SokletConfig config = SokletConfig.withHttpServer(serverBuilder.build())
					.resourceMethodResolver(resolver).instanceProvider(provider).lifecycleObserver(observation).build();
			try (Soklet soklet = Soklet.fromConfig(config)) {
				soklet.start();
				HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/output-view").openConnection();
				connection.setConnectTimeout(2_000);
				connection.setReadTimeout(3_000);
				try {
					Assertions.assertEquals(200, connection.getResponseCode());
					try (var input = connection.getInputStream()) { body.set(input.readAllBytes()); }
				} catch (IOException exception) { failure = exception; }
				finally { connection.disconnect(); }
				Assertions.assertTrue(observation.terminated.await(3, TimeUnit.SECONDS));
				if (afterScope != null) {
					Assertions.assertTrue(afterScopeCompleted.await(3, TimeUnit.SECONDS));
					Assertions.assertNull(afterScopeFailure.get());
				}
			} finally {
				if (producerExecutor != null)
					producerExecutor.shutdownNow();
			}
		}
		return new Outcome(body.get(), failure, observation.termination.get());
	}

	public static final class WriterResource {
		private final MarshaledResponse response;
		private WriterResource(StreamingResponseWriter writer) {
			this.response = MarshaledResponse.withStatusCode(200).stream(writer).build();
		}
		@GET("/output-view") public MarshaledResponse outputView() { return this.response; }
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
	private record Outcome(byte[] body, Throwable failure, StreamTermination termination) {}
}

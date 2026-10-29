/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ResponseProcessingCleanupTests {

	@Test
	void corsFailureReleasesOnlyTheAbandonedOwnedChannel(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		Resource resource = new Resource();
		AtomicBoolean fail = new AtomicBoolean();
		SokletSimulator.run(config(resource).corsAuthorizer(CorsAuthorizer.fromWhitelistAuthorizer(origin -> {
			if (fail.getAndSet(false)) throw new IllegalStateException("CORS callback failed");
			return false;
		})).build(), simulator -> {
			for (boolean owned : List.of(true, false)) {
				try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
					resource.response = response(channel, owned);
					fail.set(true);
					MarshaledResponse actual = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/file")
							.headers(Map.of("Origin", List.of("https://example.test"))).build()).getMarshaledResponse();
					assertEquals(500, actual.getStatusCode());
					assertEquals(!owned, channel.isOpen(), "Failed response processing must release owned channels only");
				}
			}
		});
	}

	@Test
	void interceptorReplacementReleasesOnlyTheAbandonedOwnedChannel(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		Resource resource = new Resource();
		SokletSimulator.run(config(resource).requestInterceptor(new RequestInterceptor() {
			@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
					Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
				generator.apply(request);
				writer.accept(MarshaledResponse.fromStatusCode(403));
			}
		}).build(), simulator -> {
			for (boolean owned : List.of(true, false)) {
				try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
					resource.response = response(channel, owned);
					assertEquals(403, simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/file"))
							.getMarshaledResponse().getStatusCode());
					assertEquals(!owned, channel.isOpen());
				}
			}
		});
	}

	@Test
	void failingHeadHookReleasesOnlyTheAbandonedOwnedChannel(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		Resource resource = new Resource();
		SokletSimulator.run(config(resource).responseMarshaler(ResponseMarshaler.builder()
				.headHandler((request, response) -> { throw new IllegalStateException("HEAD hook failed"); }).build())
				.build(), simulator -> {
			for (boolean owned : List.of(true, false)) {
				try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
					resource.response = response(channel, owned);
					assertEquals(500, simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/file"))
							.getMarshaledResponse().getStatusCode());
					assertEquals(!owned, channel.isOpen());
				}
			}
		});
	}

	@Test
	void failingPostProcessorReleasesOnlyTheAbandonedOwnedChannel(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		Resource resource = new Resource();
		AtomicBoolean fail = new AtomicBoolean();
		SokletSimulator.run(config(resource).responseMarshaler(ResponseMarshaler.builder()
				.resourceMethodHandler((request, response, method) -> resource.response)
				.postProcessor(response -> {
					if (fail.getAndSet(false)) throw new IllegalStateException("Postprocessor failed");
					return response;
				}).build()).build(), simulator -> {
			for (boolean owned : List.of(true, false)) {
				try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
					resource.response = response(channel, owned);
					fail.set(true);
					assertEquals(500, simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/marshaled"))
							.getMarshaledResponse().getStatusCode());
					assertEquals(!owned, channel.isOpen());
				}
			}
		});
	}


	@Test
	void interceptorFailureReleasesGeneratedAndSubmittedOwnedBodies(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		for (boolean submit : List.of(true, false)) {
			Resource resource = new Resource();
			try (FileChannel generated = FileChannel.open(path); FileChannel submitted = FileChannel.open(path)) {
				resource.response = response(generated, true);
				SokletSimulator.run(config(resource).requestInterceptor(new RequestInterceptor() {
					@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
							Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
						generator.apply(request);
						if (submit) {
							writer.accept(response(submitted, true));
							throw new IllegalStateException("Interceptor failed after selecting a response");
						}
					}
				}).build(), simulator -> {
					assertEquals(500, simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/file"))
							.getMarshaledResponse().getStatusCode());
					assertFalse(generated.isOpen());
					assertEquals(!submit, submitted.isOpen());
				});
			}
		}
	}

	@Test
	void interceptorCanSelectAnEarlierGeneratedBodyOrBorrowItsChannel(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		for (boolean borrow : List.of(true, false)) {
			Resource resource = new Resource();
			try (FileChannel first = FileChannel.open(path); FileChannel second = FileChannel.open(path)) {
				resource.response = response(first, true);
				SokletSimulator.run(config(resource).requestInterceptor(new RequestInterceptor() {
					@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
							Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
						MarshaledResponse initial = generator.apply(request);
						resource.response = response(second, true);
						generator.apply(request);
						assertTrue(first.isOpen(), "Selection occurs after all generation calls");
						writer.accept(borrow ? response(first, false) : initial.copy().finish());
					}
				}).build(), simulator -> {
					MarshaledResponse result = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/file"))
							.getMarshaledResponse();
					assertEquals(200, result.getStatusCode());
					assertFalse(second.isOpen());
					assertTrue(first.isOpen(), "Final channel belongs to the transport/consumer until consumed");
					assertArrayEquals("body".getBytes(java.nio.charset.StandardCharsets.UTF_8), result.bodyBytesOrNull());
					assertEquals(borrow, first.isOpen());
				});
			}
		}
	}

	@Test
	void postProcessorReplacementRetainsOnlySharedChannels(@TempDir Path directory) throws Exception {
		Path path = Files.writeString(directory.resolve("body"), "body");
		Request request = Request.fromPath(HttpMethod.GET, "/missing");
		for (boolean shared : List.of(true, false)) {
			for (boolean owned : List.of(true, false)) {
				try (FileChannel channel = FileChannel.open(path)) {
					MarshaledResponse original = response(channel, owned);
					MarshaledResponse result = ResponseMarshaler.builder().notFoundHandler(ignored -> original)
							.postProcessor(ignored -> shared ? response(channel, false) : MarshaledResponse.fromStatusCode(404))
							.build().forNotFound(request);
					assertEquals(shared || !owned, channel.isOpen());
					if (shared) assertArrayEquals("body".getBytes(java.nio.charset.StandardCharsets.UTF_8), result.bodyBytesOrNull());
				}
			}
		}
	}

	@Test
	void postProcessorFailureKeepsPrimaryExceptionAndClosesUnreturnedReplacement(@TempDir Path directory) throws Exception {
		Request request = Request.fromPath(HttpMethod.GET, "/missing");
		IllegalStateException failure = new IllegalStateException("Postprocessor failed");
		FailingCloseChannel failed = new FailingCloseChannel();
		MarshaledResponse original = MarshaledResponse.withStatusCode(200).body(failed, 0L, 1L, true).build();
		Throwable thrown = assertThrows(IllegalStateException.class, () -> ResponseMarshaler.builder()
				.notFoundHandler(ignored -> original).postProcessor(ignored -> { throw failure; }).build().forNotFound(request));
		assertSame(failure, thrown);
		assertEquals(1, thrown.getSuppressed().length);
		assertSame(failed.closeFailure, thrown.getSuppressed()[0].getCause());
		assertEquals(1, failed.closeCalls.get());

		Path path = Files.writeString(directory.resolve("body"), "body");
		try (FileChannel replacement = FileChannel.open(path)) {
			FailingCloseChannel old = new FailingCloseChannel();
			MarshaledResponse predecessor = MarshaledResponse.withStatusCode(200).body(old, 0L, 1L, true).build();
			assertThrows(UncheckedIOException.class, () -> ResponseMarshaler.builder().notFoundHandler(ignored -> predecessor)
					.postProcessor(ignored -> response(replacement, true)).build().forNotFound(request));
			assertFalse(replacement.isOpen());
			assertEquals(1, old.closeCalls.get());
		}
		try (FileChannel channel = FileChannel.open(path)) {
			assertThrows(NullPointerException.class, () -> ResponseMarshaler.builder().notFoundHandler(ignored -> response(channel, true))
					.postProcessor(ignored -> null).build().forNotFound(request));
			assertFalse(channel.isOpen());
		}
	}

	private static MarshaledResponse response(FileChannel channel, boolean owned) {
		return MarshaledResponse.withStatusCode(200).body(channel, 0L, 4L, owned).build();
	}

	private static SimulatorConfig.Builder config(Resource resource) {
		return SimulatorConfig.builder().httpServer()
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(1))
						.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
				})
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} });
	}

	public static class Resource {
		private MarshaledResponse response;
		@GET("/file") public MarshaledResponse file() { return this.response; }
		@GET("/marshaled") public String marshaled() { return "body"; }
	}
	private static final class FailingCloseChannel extends FileChannel {
		private final IOException closeFailure = new IOException("close failed");
		private final AtomicInteger closeCalls = new AtomicInteger();
		@Override protected void implCloseChannel() throws IOException { this.closeCalls.incrementAndGet(); throw this.closeFailure; }
		@Override public int read(ByteBuffer buffer) { throw new UnsupportedOperationException(); }
		@Override public long read(ByteBuffer[] buffers, int offset, int length) { throw new UnsupportedOperationException(); }
		@Override public int write(ByteBuffer buffer) { throw new UnsupportedOperationException(); }
		@Override public long write(ByteBuffer[] buffers, int offset, int length) { throw new UnsupportedOperationException(); }
		@Override public long position() { return 0; }
		@Override public FileChannel position(long position) { return this; }
		@Override public long size() { return 1; }
		@Override public FileChannel truncate(long size) { throw new UnsupportedOperationException(); }
		@Override public void force(boolean metadata) { throw new UnsupportedOperationException(); }
		@Override public long transferTo(long position, long count, WritableByteChannel target) { throw new UnsupportedOperationException(); }
		@Override public long transferFrom(ReadableByteChannel source, long position, long count) { throw new UnsupportedOperationException(); }
		@Override public int read(ByteBuffer buffer, long position) { throw new UnsupportedOperationException(); }
		@Override public int write(ByteBuffer buffer, long position) { throw new UnsupportedOperationException(); }
		@Override public MappedByteBuffer map(MapMode mode, long position, long size) { throw new UnsupportedOperationException(); }
		@Override public FileLock lock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
		@Override public FileLock tryLock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
	}
}

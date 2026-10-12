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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class BodylessResourceOwnershipTests {

	@Test
	void rejectedResourceAndMarshalerBodiesReleaseOwnedChannels(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("body.txt"), "x");
		Resource resource = new Resource();
		SokletSimulator.run(config(resource), simulator -> {
			for (int status : List.of(205, 204, 304, 199, 600)) {
				for (boolean owned : List.of(true, false)) {
					for (String path : List.of("/direct", "/marshaled")) {
						try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
							resource.response = MarshaledResponse.withStatusCode(status).body(channel, 0L, 1L, owned).build();
							HttpRequestResult result = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, path));
							assertEquals(500, result.getMarshaledResponse().getStatusCode());
							assertEquals(!owned, channel.isOpen(), status + " " + owned + " " + path);
						}
					}
				}
			}
		});
	}

	@Test
	void emptyResetContentStillReleasesOnlyOwnedChannels(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("body.txt"), "x");
		Resource resource = new Resource();
		SokletSimulator.run(config(resource), simulator -> {
			for (boolean owned : List.of(true, false)) {
				for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
					for (String path : List.of("/direct", "/marshaled")) {
						try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
							resource.response = MarshaledResponse.withStatusCode(205).body(channel, 0L, 0L, owned).build();
							MarshaledResponse response = simulator.performHttpRequest(Request.fromPath(method, path)).getMarshaledResponse();
							assertEquals(205, response.getStatusCode());
							assertTrue(response.getBody().isEmpty());
							assertEquals(List.of("0"), response.getHeaders().get("Content-Length"));
							assertEquals(!owned, channel.isOpen());
						}
					}
				}
			}
		});
	}

	@Test
	void invalidFrameworkAndInterceptorStatusesReleaseOwnedChannels(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("body.txt"), "x");
		Resource resource = new Resource();
		SokletSimulator.run(config(resource), simulator -> {
			for (int status : List.of(199, 600)) {
				for (boolean owned : List.of(true, false)) {
					for (String path : List.of("/missing", "/intercepted")) {
						try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
							resource.response = MarshaledResponse.withStatusCode(status).body(channel, 0L, 1L, owned).build();
							assertEquals(500, simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, path))
									.getMarshaledResponse().getStatusCode());
							assertEquals(!owned, channel.isOpen(), status + " " + owned + " " + path);
						}
					}
				}
			}
		});
	}

	@Test
	void cleanupFailureDoesNotReplaceInvalidResponseFailure() {
		Resource resource = new Resource();
		SokletSimulator.run(config(resource), simulator -> {
			for (String path : List.of("/direct", "/marshaled", "/missing", "/intercepted")) {
				int status = path.equals("/direct") || path.equals("/marshaled") ? 205 : 199;
				FailingCloseChannel channel = new FailingCloseChannel();
				resource.response = MarshaledResponse.withStatusCode(status)
						.body(new MarshaledResponseBody.FileChannel(channel, 0L, 1L, true)).build();
				HttpRequestResult result = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, path));
				assertEquals(500, result.getMarshaledResponse().getStatusCode());
				assertFalse(channel.isOpen());
				assertEquals(1, channel.closeCalls.get());
				Throwable failure = assertInstanceOf(IllegalStateException.class, resource.failure);
				assertEquals(1, failure.getSuppressed().length);
				assertInstanceOf(UncheckedIOException.class, failure.getSuppressed()[0]);
				assertSame(channel.closeFailure, failure.getSuppressed()[0].getCause());
			}
		});
	}

	@Test
	void rejectedStreamingDescriptorNeverAcquiresItsSource() {
		Resource resource = new Resource();
		SokletSimulator.run(config(resource), simulator -> {
			for (int status : List.of(204, 205, 304)) {
				resource.streamingStatus = status;
				assertEquals(500, simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/stream"))
						.getMarshaledResponse().getStatusCode());
			}
			assertEquals(0, resource.sourceAcquisitions.get());
		});
	}

	private static SimulatorConfig config(Resource resource) {
		return SimulatorConfig.builder().httpServer()
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
				})
				.requestInterceptor(new RequestInterceptor() {
					@Override public void interceptRequest(ServerType serverType, Request request, ResourceMethod method,
							Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
						if (request.getResourcePath().getPath().equals("/intercepted"))
							writer.accept(resource.response);
						else RequestInterceptor.super.interceptRequest(serverType, request, method, generator, writer);
					}
				})
				.responseMarshaler(ResponseMarshaler.builder()
						.resourceMethodHandler((request, response, method) -> resource.response)
						.notFoundHandler(request -> resource.response)
						.throwableHandler((request, failure, method) -> {
							resource.failure = failure;
							return MarshaledResponse.fromStatusCode(500);
						}).build())
				.lifecycleObserver(new LifecycleObserver() {}).build();
	}

	public static final class Resource {
		private MarshaledResponse response;
		private Throwable failure;
		private int streamingStatus;
		private final AtomicInteger sourceAcquisitions = new AtomicInteger();
		@GET("/direct") public MarshaledResponse direct() { return this.response; }
		@GET("/marshaled") public String marshaled() { return "value"; }
		@GET("/stream") public MarshaledResponse streaming() {
			return MarshaledResponse.withStatusCode(this.streamingStatus)
					.streamingResponseBody(StreamingResponseBody.fromInputStream(() -> {
						this.sourceAcquisitions.incrementAndGet();
						return new java.io.ByteArrayInputStream(new byte[0]);
					})).build();
		}
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

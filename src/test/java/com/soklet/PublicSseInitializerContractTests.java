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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Public shape and checked-callback tests; connection lifecycle behavior is covered by runtime tests. */
public class PublicSseInitializerContractTests {
	@Test
	public void unicaster_has_only_initialization_writes_and_resource_path() throws Exception {
		Assertions.assertEquals(Set.of("unicastEvent", "unicastComment", "getResourcePath"),
				Arrays.stream(SseUnicaster.class.getDeclaredMethods()).map(Method::getName).collect(java.util.stream.Collectors.toSet()));
		Assertions.assertEquals(ResourcePath.class, SseUnicaster.class.getMethod("getResourcePath").getReturnType());
	}

	@Test
	public void initializer_is_a_standalone_checked_sam_without_a_consumer_overload() throws Exception {
		Method initialize = SseClientInitializer.class.getMethod("initialize", SseUnicaster.class);
		Assertions.assertArrayEquals(new Class<?>[]{Exception.class}, initialize.getExceptionTypes());
		Assertions.assertEquals("sseUnicaster", initialize.getParameters()[0].getName());
		Assertions.assertTrue(initialize.getParameters()[0].isNamePresent());
		Assertions.assertTrue(SseClientInitializer.class.isAnnotationPresent(FunctionalInterface.class));
		Method setter = SseHandshakeResult.Accepted.Builder.class.getMethod("clientInitializer", SseClientInitializer.class);
		Assertions.assertEquals(SseHandshakeResult.Accepted.Builder.class, setter.getReturnType());
		Assertions.assertEquals("clientInitializer", setter.getParameters()[0].getName());
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> SseHandshakeResult.Accepted.Builder.class.getMethod("clientInitializer", Consumer.class));
		ParameterizedType getter = (ParameterizedType) SseHandshakeResult.Accepted.class.getMethod("getClientInitializer").getGenericReturnType();
		Assertions.assertArrayEquals(new java.lang.reflect.Type[]{SseClientInitializer.class}, getter.getActualTypeArguments());
	}

	@Test
	public void checked_initializer_is_stored_lazily_and_throws_the_original_checked_failure() {
		AtomicInteger calls = new AtomicInteger();
		IOException expected = new IOException("initializer acquisition failed");
		CheckedInitializer provider = new CheckedInitializer(calls, expected);
		SseClientInitializer initializer = provider::initialize;
		SseHandshakeResult.Accepted accepted = SseHandshakeResult.Accepted.builder().clientInitializer(initializer).build();
		Assertions.assertEquals(0, calls.get());
		Assertions.assertSame(initializer, accepted.getClientInitializer().orElseThrow());
		IOException actual = Assertions.assertThrows(IOException.class,
				() -> accepted.getClientInitializer().orElseThrow().initialize(new UnusedUnicaster()));
		Assertions.assertSame(expected, actual);
		Assertions.assertEquals(1, calls.get());
	}

	@Test
	public void clearing_an_initializer_preserves_prior_results_and_other_handshake_settings() {
		SseClientInitializer initializer = sseUnicaster -> {};
		Object context = new Object();
		SseHandshakeResult.Accepted.Builder builder = SseHandshakeResult.Accepted.builder()
				.headers(Map.of("X-Test", Set.of("value"))).clientContext(context).clientInitializer(initializer);
		SseHandshakeResult.Accepted first = builder.build();
		SseHandshakeResult.Accepted cleared = builder.clientInitializer(null).build();
		Assertions.assertSame(initializer, first.getClientInitializer().orElseThrow());
		Assertions.assertTrue(cleared.getClientInitializer().isEmpty());
		Assertions.assertEquals(first.getHeaders(), cleared.getHeaders());
		Assertions.assertSame(context, cleared.getClientContext().orElseThrow());
		Assertions.assertTrue(SseHandshakeResult.accept().getClientInitializer().isEmpty());
	}

	@Test
	public void sse_server_exposes_only_lifecycle_admission_setting() throws Exception {
		Method capacity = SseServer.Builder.class.getMethod("streamingLifecycleCapacity", Integer.class);
		Assertions.assertEquals(SseServer.Builder.class, capacity.getReturnType());
		Assertions.assertEquals("streamingLifecycleCapacity", capacity.getParameters()[0].getName());
		Assertions.assertTrue(capacity.getParameters()[0].isNamePresent());
		Assertions.assertThrows(NoSuchMethodException.class, () -> SseServer.Builder.class.getMethod("streamingLifecycleCapacity", int.class));
		for (String name : Set.of("streamingCallbackConcurrency", "streamingCleanupTimeout"))
			Assertions.assertFalse(Arrays.stream(SseServer.Builder.class.getDeclaredMethods()).anyMatch(method -> method.getName().equals(name)));
		for (String name : Set.of("getStreamingLifecycleCapacity", "getStreamingCallbackConcurrency", "getStreamingCleanupTimeout"))
			Assertions.assertThrows(NoSuchMethodException.class, () -> SseServer.class.getMethod(name));
	}

	private static final class CheckedInitializer {
		private final AtomicInteger calls;
		private final IOException failure;
		private CheckedInitializer(AtomicInteger calls, IOException failure) {
			this.calls = calls;
			this.failure = failure;
		}
		private void initialize(SseUnicaster sseUnicaster) throws IOException {
			Assertions.assertNotNull(sseUnicaster);
			this.calls.incrementAndGet();
			throw this.failure;
		}
	}

	/** The callback-under-test does not call these methods. */
	private static final class UnusedUnicaster implements SseUnicaster {
		@Override public void unicastEvent(SseEvent sseEvent) { throw new AssertionError("unused"); }
		@Override public void unicastComment(SseComment sseComment) { throw new AssertionError("unused"); }
		@Override public ResourcePath getResourcePath() { throw new AssertionError("unused"); }
	}
}

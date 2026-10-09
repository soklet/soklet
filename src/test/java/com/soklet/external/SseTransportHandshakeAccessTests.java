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
package com.soklet.external;

import com.soklet.*;
import com.soklet.annotation.SseEventSource;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** A custom transport outside Soklet's package using only public contracts. */
@Timeout(60)
class SseTransportHandshakeAccessTests {
	@Test
	void accessorHasTheApprovedPublicOptionalAndNullabilityContract() throws Exception {
		Method method = HttpRequestResult.class.getMethod("getSseHandshakeResult");
		assertEquals(Optional.class, method.getReturnType());
		assertTrue(method.getAnnotatedReturnType().isAnnotationPresent(NonNull.class));
		AnnotatedParameterizedType result = (AnnotatedParameterizedType) method.getAnnotatedReturnType();
		assertEquals(SseHandshakeResult.class, result.getAnnotatedActualTypeArguments()[0].getType());
		assertTrue(result.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(NonNull.class));
		assertThrows(NoSuchMethodException.class, () -> HttpRequestResult.Builder.class.getMethod("sseHandshakeResult", SseHandshakeResult.class));
		assertThrows(NoSuchMethodException.class, () -> HttpRequestResult.Copier.class.getMethod("sseHandshakeResult", SseHandshakeResult.class));
	}

	@Test
	void customTransportReceivesExactApplicationContextAndCanRunItsInitializer() throws Exception {
		Object clientContext = new Object();
		AtomicInteger calls = new AtomicInteger();
		SseEvent event = SseEvent.withData("bounded replay").build();
		SseComment comment = SseComment.fromComment("ready");
		SseClientInitializer initializer = sseUnicaster -> {
			calls.incrementAndGet();
			assertEquals(ResourcePath.fromPath("/events"), sseUnicaster.getResourcePath());
			sseUnicaster.unicastEvent(event);
			sseUnicaster.unicastComment(comment);
		};
		SseHandshakeResult.Accepted accepted = SseHandshakeResult.Accepted.builder()
				.clientContext(clientContext).clientInitializer(initializer).build();
		try (Fixture fixture = new Fixture(accepted)) {
			HttpRequestResult result = fixture.request("/events");
			assertEquals(200, result.getMarshaledResponse().getStatusCode());
			assertTrue(result.getResponse().isEmpty());
			SseHandshakeResult.Accepted actual = assertInstanceOf(SseHandshakeResult.Accepted.class, result.getSseHandshakeResult().orElseThrow());
			assertSame(accepted, actual);
			assertSame(clientContext, actual.getClientContext().orElseThrow());
			assertSame(initializer, actual.getClientInitializer().orElseThrow());
			assertEquals(0, calls.get(), "Request processing and accessor reads must leave initialization to the custom transport");
			CapturingUnicaster unicaster = new CapturingUnicaster();
			actual.getClientInitializer().orElseThrow().initialize(unicaster);
			assertEquals(1, calls.get());
			assertSame(event, unicaster.event.get());
			assertSame(comment, unicaster.comment.get());
		}
	}

	@Test
	void defaultAcceptanceExposesEmptyOptionalContextAndInitializer() {
		try (Fixture fixture = new Fixture(SseHandshakeResult.accept())) {
			SseHandshakeResult.Accepted accepted = assertInstanceOf(SseHandshakeResult.Accepted.class,
					fixture.request("/events").getSseHandshakeResult().orElseThrow());
			assertTrue(accepted.getClientContext().isEmpty());
			assertTrue(accepted.getClientInitializer().isEmpty());
		}
	}

	@Test
	void rejectionPreservesItsDecisionAndOriginalLogicalResponse() {
		Response response = Response.withStatusCode(403).body("denied").build();
		SseHandshakeResult.Rejected rejected = SseHandshakeResult.rejectWithResponse(response);
		try (Fixture fixture = new Fixture(rejected)) {
			HttpRequestResult result = fixture.request("/events");
			assertSame(rejected, result.getSseHandshakeResult().orElseThrow());
			assertSame(response, result.getResponse().orElseThrow());
			assertEquals(403, result.getMarshaledResponse().getStatusCode());
		}
	}

	@Test
	void successfulHttpStatusDoesNotMakeARejectedHandshakeAccepted() {
		SseHandshakeResult.Rejected rejected = SseHandshakeResult.rejectWithResponse(Response.withStatusCode(200).body("finite reply").build());
		try (Fixture fixture = new Fixture(rejected)) {
			HttpRequestResult result = fixture.request("/events");
			assertEquals(200, result.getMarshaledResponse().getStatusCode());
			assertSame(rejected, result.getSseHandshakeResult().orElseThrow());
			assertInstanceOf(SseHandshakeResult.Rejected.class, result.getSseHandshakeResult().orElseThrow());
		}
	}

	@Test
	void ordinaryAndUnmatchedResultsHaveNoHandshake() {
		assertTrue(HttpRequestResult.fromMarshaledResponse(MarshaledResponse.withStatusCode(200).build()).getSseHandshakeResult().isEmpty());
		try (Fixture fixture = new Fixture(SseHandshakeResult.accept())) {
			HttpRequestResult result = fixture.request("/missing");
			assertEquals(404, result.getMarshaledResponse().getStatusCode());
			assertTrue(result.getSseHandshakeResult().isEmpty());
		}
	}

	@Test
	void copyingPreservesLogicalHandshakeWithoutInvokingOrRenderingItsApplicationReferences() {
		String clientContext = "sse-client-context-canary";
		AtomicInteger calls = new AtomicInteger();
		SseClientInitializer initializer = sseUnicaster -> calls.incrementAndGet();
		SseHandshakeResult.Accepted accepted = SseHandshakeResult.Accepted.builder()
				.clientContext(clientContext).clientInitializer(initializer).build();
		try (Fixture fixture = new Fixture(accepted)) {
			HttpRequestResult result = fixture.request("/events");
			HttpRequestResult copy = result.copy().finish();
			assertEquals(result, copy);
			assertEquals(result.hashCode(), copy.hashCode());
			assertSame(accepted, copy.getSseHandshakeResult().orElseThrow());
			HttpRequestResult replacement = copy.copy().marshaledResponse(MarshaledResponse.withStatusCode(503).build()).finish();
			assertEquals(503, replacement.getMarshaledResponse().getStatusCode());
			assertSame(accepted, replacement.getSseHandshakeResult().orElseThrow(), "The accessor describes a logical decision, not delivery proof");
			for (HttpRequestResult candidate : new HttpRequestResult[]{result, copy, replacement}) {
				assertFalse(candidate.toString().contains(clientContext));
				assertFalse(candidate.toString().contains("sseHandshakeResult="));
			}
			assertEquals(0, calls.get());
		}
	}

	@Test
	void initializerFailureRemainsTheCustomTransportsResponsibility() throws Exception {
		IOException failure = new IOException("initializer failure");
		SseClientInitializer initializer = sseUnicaster -> { throw failure; };
		try (Fixture fixture = new Fixture(SseHandshakeResult.Accepted.builder().clientInitializer(initializer).build())) {
			HttpRequestResult result = fixture.request("/events");
			assertEquals(200, result.getMarshaledResponse().getStatusCode());
			SseHandshakeResult.Accepted accepted = assertInstanceOf(SseHandshakeResult.Accepted.class, result.getSseHandshakeResult().orElseThrow());
			assertSame(failure, assertThrows(IOException.class,
					() -> accepted.getClientInitializer().orElseThrow().initialize(new CapturingUnicaster())));
		}
	}

	@Test
	void sealedAdmissionReturnsUnavailableWithoutAnApplicationHandshake() {
		try (Fixture fixture = new Fixture(SseHandshakeResult.accept())) {
			fixture.soklet.close();
			HttpRequestResult result = fixture.request("/events");
			assertEquals(503, result.getMarshaledResponse().getStatusCode());
			assertTrue(result.getSseHandshakeResult().isEmpty());
			assertEquals(0, fixture.resource.calls.get());
		}
	}

	private static final class Fixture implements AutoCloseable {
		final CustomSseServer server = new CustomSseServer();
		final Resource resource;
		final Soklet soklet;
		Fixture(SseHandshakeResult handshake) {
			this.resource = new Resource(handshake);
			this.soklet = Soklet.fromConfig(SokletConfig.withSseServer(this.server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) {
							return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
						}
					}).lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent logEvent) {}
					}).lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofSeconds(1))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build());
			this.soklet.start();
		}
		HttpRequestResult request(String path) {
			AtomicReference<HttpRequestResult> result = new AtomicReference<>();
			this.server.requestHandler.handleRequest(Request.withPath(HttpMethod.GET, path).build(), result::set);
			return java.util.Objects.requireNonNull(result.get(), "The synchronous public request handler must offer a result");
		}
		@Override public void close() { this.soklet.close(); }
	}

	public static final class Resource {
		private final SseHandshakeResult handshake;
		final AtomicInteger calls = new AtomicInteger();
		Resource(SseHandshakeResult handshake) { this.handshake = handshake; }
		@SseEventSource("/events") public SseHandshakeResult events() { this.calls.incrementAndGet(); return this.handshake; }
	}

	private static final class CustomSseServer implements SseServer {
		final TransportIdentity identity = TransportIdentity.create();
		RequestHandler requestHandler;
		@Override public TransportIdentity getTransportIdentity() { return this.identity; }
		@Override public TransportRuntime attach(SseTransportAttachmentContext attachmentContext, StartupContext startupContext) {
			this.requestHandler = attachmentContext.getAdmissionFencedRequestHandler();
			TransportTerminationSignal signal = attachmentContext.getTransportTerminationSignal();
			return new TransportRuntime() {
				@Override public void start(StartupContext startupContext) {}
				@Override public void shutdownGracefully(ShutdownContext shutdownContext) { signal.signalTerminated(); }
				@Override public void shutdownForcibly(ShutdownContext shutdownContext) { signal.signalTerminated(); }
			};
		}
		@Override public Optional<? extends SseBroadcaster> acquireBroadcaster(ResourcePath resourcePath) { return Optional.empty(); }
	}

	private static final class CapturingUnicaster implements SseUnicaster {
		final AtomicReference<SseEvent> event = new AtomicReference<>();
		final AtomicReference<SseComment> comment = new AtomicReference<>();
		@Override public void unicastEvent(SseEvent sseEvent) { assertTrue(this.event.compareAndSet(null, sseEvent)); }
		@Override public void unicastComment(SseComment sseComment) { assertTrue(this.comment.compareAndSet(null, sseComment)); }
		@Override public ResourcePath getResourcePath() { return ResourcePath.fromPath("/events"); }
	}
}

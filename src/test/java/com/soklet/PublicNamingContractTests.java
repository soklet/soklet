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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class PublicNamingContractTests {
	@Test
	void streamingResponseBodyBuilderAndCopierPreserveBodyRules() throws Exception {
		StreamingResponseWriter streamingResponseWriter = responseStream -> {};
		StreamingResponseBody body = StreamingResponseBody.fromWriter(streamingResponseWriter);
		ResponseCookie cookie = ResponseCookie.with("example", "value").build();
		MarshaledResponse response = MarshaledResponse.withStatusCode(200)
				.streamingResponseBody(body).cookies(Set.of(cookie)).build();
		assertSame(body, response.getStreamingResponseBody().orElseThrow());
		assertSame(streamingResponseWriter, ((StreamingResponseBody.WriterBody) body).getWriter());
		assertSame(body, response.copy().finish().getStreamingResponseBody().orElseThrow());
		assertEquals(Set.of(cookie), response.copy().withoutStreamingResponseBody().finish().getCookies());
		assertTrue(response.copy().streamingResponseBody(null).finish().getStreamingResponseBody().isEmpty());
		assertTrue(MarshaledResponse.withStatusCode(200).streamingResponseBody(body)
				.withoutStreamingResponseBody().build().getStreamingResponseBody().isEmpty());
		assertTrue(MarshaledResponse.withStatusCode(200).streamingResponseBody(body)
				.streamingResponseBody(null).build().getStreamingResponseBody().isEmpty());
		assertThrows(IllegalStateException.class, () -> response.copy().body(new byte[]{1}).finish());
		assertThrows(IllegalStateException.class, () -> MarshaledResponse.withStatusCode(204)
				.streamingResponseBody(body).build());
		assertThrows(NoSuchMethodException.class, () -> MarshaledResponse.class.getMethod("getStream"));
		for (Class<?> owner : List.of(MarshaledResponse.Builder.class, MarshaledResponse.Copier.class)) {
			assertThrows(NoSuchMethodException.class, () -> owner.getMethod("stream", StreamingResponseBody.class));
			assertThrows(NoSuchMethodException.class, () -> owner.getMethod("withoutStream"));
			assertParameter(owner.getMethod("streamingResponseBody", StreamingResponseBody.class),
					StreamingResponseBody.class, "streamingResponseBody");
			assertParameter(owner.getMethod("stream", StreamingResponseWriter.class),
					StreamingResponseWriter.class, "streamingResponseWriter");
		}
	}

	@Test
	void streamingWriterReceivesOneResponseStreamWithItsMetadata() throws Exception {
		assertParameter(StreamingResponseWriter.class.getMethod("writeTo", ResponseStream.class),
				ResponseStream.class, "responseStream");
		assertEquals(Request.class, ResponseStream.class.getMethod("getRequest").getReturnType());
		assertEquals(CancelationToken.class,
				ResponseStream.class.getMethod("getCancelationToken").getReturnType());
		assertNotNull(ResponseStream.class.getMethod("getDeadline"));
		assertNotNull(ResponseStream.class.getMethod("getIdleTimeout"));
		assertThrows(ClassNotFoundException.class, () -> Class.forName("com.soklet.StreamingResponseContext"));
	}

	@Test
	void httpStreamingLifecycleSettingsUseTheSelectedNamesAndBoxedTypes() throws Exception {
		for (String name : List.of("streamingLifecycleCapacity", "streamingCallbackConcurrency")) {
			Method method = HttpServer.Builder.class.getMethod(name, Integer.class);
			assertParameter(method, Integer.class, name);
			assertEquals(HttpServer.Builder.class, method.getReturnType());
			assertThrows(NoSuchMethodException.class, () -> HttpServer.Builder.class.getMethod(name, int.class));
		}
		Method timeout = HttpServer.Builder.class.getMethod("streamingCleanupTimeout", java.time.Duration.class);
		assertParameter(timeout, java.time.Duration.class, "streamingCleanupTimeout");
		assertEquals(HttpServer.Builder.class, timeout.getReturnType());
		for (String name : List.of("getStreamingLifecycleCapacity", "getStreamingCallbackConcurrency", "getStreamingCleanupTimeout"))
			assertThrows(NoSuchMethodException.class, () -> HttpServer.class.getMethod(name));
	}

	@Test
	void streamingOutputHelpersPreserveTheSelectedSignatures() throws Exception {
		Method write = ResponseStream.class.getMethod("write", byte[].class, Integer.class, Integer.class);
		assertArrayEquals(new String[]{"bytes", "offset", "length"},
				java.util.Arrays.stream(write.getParameters()).map(Parameter::getName).toArray(String[]::new));
		assertTrue(java.util.Arrays.stream(write.getParameters()).allMatch(Parameter::isNamePresent));
		assertArrayEquals(new Class<?>[]{java.io.IOException.class, InterruptedException.class}, write.getExceptionTypes());
		assertThrows(NoSuchMethodException.class,
				() -> ResponseStream.class.getMethod("write", byte[].class, int.class, int.class));
		assertThrows(NoSuchMethodException.class, () -> ResponseStream.class.getMethod("writeUtf8", String.class));
		assertEquals(java.io.OutputStream.class, ResponseStream.class.getMethod("asOutputStream").getReturnType());
	}

	@Test
	void resourceOwnershipUsesTheSelectedNamesAndCheckedCallbacks() throws Exception {
		assertParameter(ResponseStream.class.getMethod("open", StreamResourceFactory.class),
				StreamResourceFactory.class, "streamResourceFactory");
		assertParameter(ResponseStream.class.getMethod("open", StreamResourceFactory.class, ResponseStream.ResourceAborter.class),
				ResponseStream.ResourceAborter.class, "resourceAborter");
		assertParameter(ResponseStream.class.getMethod("own", AutoCloseable.class), AutoCloseable.class, "resource");
		assertParameter(ResponseStream.class.getMethod("using", StreamResourceFactory.class, ResponseStream.ResourceConsumer.class),
				ResponseStream.ResourceConsumer.class, "resourceConsumer");
		assertParameter(ResponseStream.class.getMethod("using", StreamResourceFactory.class,
				ResponseStream.ResourceAborter.class, ResponseStream.ResourceConsumer.class),
				ResponseStream.ResourceConsumer.class, "resourceConsumer");
		assertArrayEquals(new Class<?>[]{Exception.class},
				ResponseStream.ResourceAborter.class.getMethod("abort", AutoCloseable.class).getExceptionTypes());
		assertArrayEquals(new Class<?>[]{Exception.class},
				ResponseStream.ResourceConsumer.class.getMethod("accept", AutoCloseable.class).getExceptionTypes());
		assertThrows(NoSuchMethodException.class,
				() -> ResponseStream.class.getMethod("openWithCloseOnCancel", StreamResourceFactory.class));
	}

	@Test
	void transportAttachmentContextsExposeExplicitSignalName() throws Exception {
		for (Class<?> owner : List.of(HttpTransportAttachmentContext.class, SseTransportAttachmentContext.class)) {
			assertEquals(TransportTerminationSignal.class,
					owner.getMethod("getTransportTerminationSignal").getReturnType());
			assertThrows(NoSuchMethodException.class, () -> owner.getMethod("getTerminationSignal"));
		}
	}

	@Test
	void allTenRouteKeysExposeAndPreserveTheirResourcePathDeclaration() throws Exception {
		ResourcePathDeclaration declaration = ResourcePathDeclaration.fromPath("/items/{id}");
		List<Class<?>> owners = List.of(MetricsCollector.HttpServerRouteKey.class,
				MetricsCollector.HttpServerRouteStatusKey.class, MetricsCollector.SseCommentRouteKey.class,
				MetricsCollector.SseEventRouteKey.class, MetricsCollector.SseEventRouteHandshakeFailureKey.class,
				MetricsCollector.SseEventRouteEnqueueOutcomeKey.class, MetricsCollector.SseCommentRouteEnqueueOutcomeKey.class,
				MetricsCollector.SseEventRouteDropKey.class, MetricsCollector.SseCommentRouteDropKey.class,
				MetricsCollector.SseStreamRouteTerminationKey.class);
		assertEquals(10, owners.size());
		for (Class<?> owner : owners) {
			Constructor<?> constructor = owner.getConstructors()[0];
			assertParameter(constructor, ResourcePathDeclaration.class, "resourcePathDeclaration");
			Object[] arguments = new Object[constructor.getParameterCount()];
			for (int index = 0; index < arguments.length; index++) {
				Class<?> type = constructor.getParameterTypes()[index];
				arguments[index] = type == ResourcePathDeclaration.class ? declaration
						: type == String.class ? "2xx" : type.getEnumConstants()[0];
			}
			Object key = constructor.newInstance(arguments);
			Object equalKey = constructor.newInstance(arguments);
			assertSame(declaration, owner.getMethod("getResourcePathDeclaration").invoke(key));
			assertEquals(key, equalKey);
			assertEquals(key.hashCode(), equalKey.hashCode());
			assertThrows(NoSuchMethodException.class, () -> owner.getMethod("getRoute"));
			for (int index = 0; index < arguments.length; index++) {
				if (constructor.getParameterTypes()[index] == ResourcePathDeclaration.class)
					arguments[index] = null;
				else if (constructor.getParameterTypes()[index] == MetricsCollector.RouteType.class)
					arguments[index] = MetricsCollector.RouteType.UNMATCHED;
			}
			assertNull(owner.getMethod("getResourcePathDeclaration").invoke(constructor.newInstance(arguments)));
		}
	}

	@Test
	void commentKeysUseEventEnumNamesWhileEventKeysRetainShortNames() throws Exception {
		ResourcePathDeclaration declaration = ResourcePathDeclaration.fromPath("/events");
		MetricsCollector.SseCommentRouteEnqueueOutcomeKey enqueue = new MetricsCollector.SseCommentRouteEnqueueOutcomeKey(
				MetricsCollector.RouteType.MATCHED, declaration, SseComment.CommentType.COMMENT,
				MetricsCollector.SseEventEnqueueOutcome.ENQUEUED);
		assertEquals(MetricsCollector.SseEventEnqueueOutcome.ENQUEUED, enqueue.getEventEnqueueOutcome());
		MetricsCollector.SseCommentRouteDropKey drop = new MetricsCollector.SseCommentRouteDropKey(
				MetricsCollector.RouteType.MATCHED, declaration, SseComment.CommentType.COMMENT,
				MetricsCollector.SseEventDropReason.QUEUE_FULL);
		assertEquals(MetricsCollector.SseEventDropReason.QUEUE_FULL, drop.getEventDropReason());
		assertParameter(enqueue.getClass().getConstructors()[0], MetricsCollector.SseEventEnqueueOutcome.class, "eventEnqueueOutcome");
		assertParameter(drop.getClass().getConstructors()[0], MetricsCollector.SseEventDropReason.class, "eventDropReason");
		assertThrows(NoSuchMethodException.class, () -> enqueue.getClass().getMethod("getOutcome"));
		assertThrows(NoSuchMethodException.class, () -> drop.getClass().getMethod("getDropReason"));
		assertNotNull(MetricsCollector.SseEventRouteEnqueueOutcomeKey.class.getMethod("getOutcome"));
		assertNotNull(MetricsCollector.SseEventRouteDropKey.class.getMethod("getDropReason"));
		assertNotNull(MetricsCollector.SseStreamRouteTerminationKey.class.getMethod("getTerminationReason"));
	}

	@Test
	void callbackParameterNamesMatchTheirDomainTypes() throws Exception {
		for (Class<?> owner : List.of(LifecycleObserver.class, MetricsCollector.class, DefaultMetricsCollector.class)) {
			for (Method method : owner.getDeclaredMethods()) {
				if (method.getName().equals("didFailToEstablishSseConnection"))
					assertParameter(method, SseConnection.HandshakeFailureReason.class, "connectionHandshakeFailureReason");
				if (Set.of("willTerminateResponseStream", "didTerminateResponseStream").contains(method.getName())) {
					assertParameter(method, StreamingResponseHandle.class, "streamingResponseHandle");
					assertParameter(method, StreamTermination.class, "streamTermination");
				}
				if (Set.of("willTerminateSseConnection", "didTerminateSseConnection").contains(method.getName()))
					assertParameter(method, StreamTermination.class, "streamTermination");
				if (Set.of("didBroadcastSseEvent", "didBroadcastSseComment").contains(method.getName()))
					assertParameter(method, ResourcePathDeclaration.class, "resourcePathDeclaration");
			}
		}
	}

	@Test
	void componentFactoryAndHeaderBuilderFollowApprovedNames() throws Exception {
		ResourcePathDeclaration.Component component = ResourcePathDeclaration.Component.fromValueAndType(
				"id", ResourcePathDeclaration.ComponentType.PLACEHOLDER);
		assertEquals("id", component.getValue());
		assertEquals(ResourcePathDeclaration.ComponentType.PLACEHOLDER, component.getType());
		assertEquals(component, ResourcePathDeclaration.fromPath("/{id}").getComponents().get(0));
		assertThrows(NoSuchMethodException.class, () -> ResourcePathDeclaration.Component.class.getMethod(
				"with", String.class, ResourcePathDeclaration.ComponentType.class));
		assertParameter(ResourcePathDeclaration.Component.class.getMethod("fromValueAndType",
				String.class, ResourcePathDeclaration.ComponentType.class), ResourcePathDeclaration.ComponentType.class, "type");
		assertParameter(ParameterizedHeaderValue.class.getMethod("withName", String.class), String.class, "name");
		assertEquals("text/plain", ParameterizedHeaderValue.withName("text/plain").build().getName());
	}

	@Test
	void approvedShorterNamesAreRetained() throws Exception {
		for (Class<?> owner : List.of(Response.class, MarshaledResponse.class, SseHandshakeResult.Accepted.class))
			assertNotNull(owner.getMethod("getCookies"));
		for (Class<?> owner : List.of(Response.Builder.class, Response.Copier.class, MarshaledResponse.Builder.class,
				MarshaledResponse.Copier.class, SseHandshakeResult.Accepted.Builder.class))
			assertParameter(owner.getMethod("cookies", Set.class), Set.class, "cookies");
		for (Class<?> owner : List.of(StreamTermination.class, UnparsedRequest.class,
				MetricsCollector.TransportFailureKey.class, MetricsCollector.RequestReadFailureKey.class,
				MetricsCollector.RequestRejectionKey.class))
			assertNotNull(owner.getMethod("getReason"));
		assertNotNull(ByteRangeSelection.class.getMethod("getType"));
		assertNotNull(ByteRangeSelection.class.getMethod("getRange"));
		assertNotNull(MarshaledResponseBody.FileChannel.class.getMethod("getChannel"));
		assertNotNull(MarshaledResponseBody.ByteBuffer.class.getMethod("getBuffer"));
		assertNotNull(StreamingResponseBody.class.getMethod("fromWriter", StreamingResponseWriter.class));
		assertNotNull(StreamingResponseBody.WriterBody.class.getMethod("getWriter"));
		assertParameter(StreamTermination.Builder.class.getMethod("reason", StreamTerminationReason.class),
				StreamTerminationReason.class, "reason");
		assertParameter(StreamTermination.Copier.class.getMethod("reason", StreamTerminationReason.class),
				StreamTerminationReason.class, "reason");
	}

	private static void assertParameter(Executable executable, Class<?> type, String expectedName) {
		Parameter parameter = java.util.Arrays.stream(executable.getParameters())
				.filter(candidate -> candidate.getType() == type).findFirst().orElseThrow();
		assertTrue(parameter.isNamePresent(), executable.toString());
		assertEquals(expectedName, parameter.getName(), executable.toString());
	}
}

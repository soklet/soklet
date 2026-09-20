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

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ResponseMarshalerTests {
	@Test
	void unparsedRequestReasonsArePureClassifications() {
		Assertions.assertEquals(Set.of(
				UnparsedRequestReason.MALFORMED_REQUEST,
				UnparsedRequestReason.REQUEST_TARGET_TOO_LONG,
				UnparsedRequestReason.EXPECTATION_FAILED,
				UnparsedRequestReason.REQUEST_HEADERS_TOO_LARGE),
				Set.of(UnparsedRequestReason.values()));
		Assertions.assertThrows(NoSuchMethodException.class, () ->
				UnparsedRequestReason.class.getMethod("getStatusCode"));
	}

	@Test
	void unparsedRequestIsAnImmutableValueWithFreshReadOnlyByteViews() {
		byte[] source = "illegal request".getBytes(StandardCharsets.US_ASCII);
		InetSocketAddress remoteAddress = InetSocketAddress.createUnresolved(
				"sensitive.example", 8443);
		UnparsedRequest request = UnparsedRequest.withServerTypeAndReason(
						ServerType.HTTP,
						UnparsedRequestReason.MALFORMED_REQUEST)
				.remoteAddress(remoteAddress)
				.capturedBytes(source)
				.observedByteCount((long) source.length)
				.captureTruncated(false)
				.build();
		UnparsedRequest equalRequest = UnparsedRequest.withServerTypeAndReason(
						ServerType.HTTP,
						UnparsedRequestReason.MALFORMED_REQUEST)
				.remoteAddress(remoteAddress)
				.capturedBytes("illegal request".getBytes(
						StandardCharsets.US_ASCII))
				.observedByteCount((long) source.length)
				.captureTruncated(false)
				.build();

		source[0] = 'X';
		ByteBuffer firstView = request.getCapturedBytes();
		Assertions.assertTrue(firstView.isReadOnly());
		Assertions.assertFalse(firstView.hasArray());
		Assertions.assertEquals(0, firstView.position());
		Assertions.assertThrows(ReadOnlyBufferException.class, () ->
				firstView.put(0, (byte) 'X'));
		firstView.position(3);
		ByteBuffer secondView = request.getCapturedBytes();
		Assertions.assertNotSame(firstView, secondView);
		Assertions.assertEquals(0, secondView.position());
		Assertions.assertArrayEquals("illegal request".getBytes(
				StandardCharsets.US_ASCII), bytesFrom(secondView));

		Assertions.assertSame(ServerType.HTTP,
				request.getServerType());
		Assertions.assertSame(UnparsedRequestReason.MALFORMED_REQUEST,
				request.getReason());
		Assertions.assertEquals(remoteAddress,
				request.getRemoteAddress().orElseThrow());
		Assertions.assertEquals((long) "illegal request".length(),
				request.getObservedByteCount());
		Assertions.assertFalse(request.isCaptureTruncated());
		Assertions.assertNotSame(request, equalRequest);
		Assertions.assertEquals(request, equalRequest);
		Assertions.assertEquals(request.hashCode(), equalRequest.hashCode());
		Assertions.assertNotEquals(request, truncatedRequest());
		Assertions.assertNotEquals(request, null);
		Assertions.assertNotEquals(request, "request");

		String rendering = request.toString();
		Assertions.assertEquals(
				"UnparsedRequest{serverType=HTTP, "
						+ "reason=MALFORMED_REQUEST, observedByteCount=15, "
						+ "captureTruncated=false}", rendering);
		Assertions.assertFalse(rendering.contains("illegal request"));
		Assertions.assertFalse(rendering.contains("sensitive.example"));
		Assertions.assertFalse(rendering.contains("8443"));
	}

	@Test
	void unparsedRequestSupportsAbsentAddressAndTruncatedCapture() {
		UnparsedRequest request = truncatedRequest();

		Assertions.assertTrue(request.getRemoteAddress().isEmpty());
		Assertions.assertArrayEquals(new byte[]{1, 2},
				bytesFrom(request.getCapturedBytes()));
		Assertions.assertEquals(5L, request.getObservedByteCount());
		Assertions.assertTrue(request.isCaptureTruncated());
	}

	@Test
	void unparsedRequestBuilderRejectsNullAndInconsistentMetadata() {
		Assertions.assertThrows(NullPointerException.class, () ->
				UnparsedRequest.withServerTypeAndReason(null,
						UnparsedRequestReason.MALFORMED_REQUEST));
		Assertions.assertThrows(NullPointerException.class, () ->
				UnparsedRequest.withServerTypeAndReason(
						ServerType.HTTP, null));

		UnparsedRequest.Builder builder =
				UnparsedRequest.withServerTypeAndReason(
						ServerType.HTTP,
						UnparsedRequestReason.MALFORMED_REQUEST);
		Assertions.assertThrows(NullPointerException.class, () ->
				builder.capturedBytes(null));
		Assertions.assertThrows(NullPointerException.class, () ->
				builder.observedByteCount(null));
		Assertions.assertThrows(NullPointerException.class, () ->
				builder.captureTruncated(null));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				builder.observedByteCount(-1L));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				UnparsedRequest.withServerTypeAndReason(
								ServerType.HTTP,
								UnparsedRequestReason.MALFORMED_REQUEST)
						.capturedBytes(new byte[]{1, 2})
						.observedByteCount(1L)
						.build());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				UnparsedRequest.withServerTypeAndReason(
								ServerType.HTTP,
								UnparsedRequestReason.MALFORMED_REQUEST)
						.capturedBytes(new byte[]{1, 2})
						.observedByteCount(2L)
						.captureTruncated(true)
						.build());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				UnparsedRequest.withServerTypeAndReason(
								ServerType.HTTP,
								UnparsedRequestReason.MALFORMED_REQUEST)
						.capturedBytes(new byte[]{1, 2})
						.observedByteCount(3L)
						.captureTruncated(false)
						.build());
	}

	@Test
	void defaultUnparsedRequestResponsesUseConventionalBodylessStatuses() {
		Map<UnparsedRequestReason, Integer> expected =
				new EnumMap<>(UnparsedRequestReason.class);
		expected.put(UnparsedRequestReason.MALFORMED_REQUEST, 400);
		expected.put(UnparsedRequestReason.REQUEST_TARGET_TOO_LONG, 414);
		expected.put(UnparsedRequestReason.EXPECTATION_FAILED, 417);
		expected.put(UnparsedRequestReason.REQUEST_HEADERS_TOO_LARGE, 431);

		for (ServerType serverType : ServerType.values()) {
			expected.forEach((reason, statusCode) -> {
				MarshaledResponse response = ResponseMarshaler.defaultInstance()
						.forUnparsedRequest(UnparsedRequest
								.withServerTypeAndReason(serverType, reason)
								.build());

				Assertions.assertEquals(statusCode, response.getStatusCode());
				Assertions.assertTrue(response.getHeaders().isEmpty());
				Assertions.assertTrue(response.getCookies().isEmpty());
				Assertions.assertTrue(response.getBody().isEmpty());
				Assertions.assertTrue(response.getStreamingResponseBody().isEmpty());
			});
		}
	}

	@Test
	void builderHandlerMayChooseStatusAndRunsBeforeNormalPostProcessor() {
		AtomicReference<UnparsedRequest> receivedRequest =
				new AtomicReference<>();
		AtomicInteger postProcessCount = new AtomicInteger();
		ResponseMarshaler marshaler = ResponseMarshaler.builder()
				.unparsedRequestHandler(request -> {
					receivedRequest.set(request);
					return MarshaledResponse.withStatusCode(418)
							.headers(Map.of("X-Handler", Set.of("true")))
							.body("custom".getBytes(StandardCharsets.UTF_8))
							.build();
				})
				.postProcessor(response -> {
					postProcessCount.incrementAndGet();
					Assertions.assertEquals(Set.of("true"),
							response.getHeaders().get("X-Handler"));
					return response.copy().headers(headers ->
							headers.put("X-Postprocessed", Set.of("true")))
							.finish();
				})
				.build();
		UnparsedRequest request = UnparsedRequest.withServerTypeAndReason(
						ServerType.HTTP,
						UnparsedRequestReason.EXPECTATION_FAILED)
				.build();

		MarshaledResponse response = marshaler.forUnparsedRequest(request);

		Assertions.assertSame(request, receivedRequest.get());
		Assertions.assertEquals(1, postProcessCount.get());
		Assertions.assertEquals(418, response.getStatusCode());
		Assertions.assertEquals(Set.of("true"),
				response.getHeaders().get("X-Postprocessed"));
		Assertions.assertArrayEquals("custom".getBytes(StandardCharsets.UTF_8),
				((MarshaledResponseBody.Bytes) response.getBody()
						.orElseThrow()).getBytes());
	}

	@Test
	void nullBuilderHandlerRestoresBuiltInBehavior() {
		UnparsedRequest request = UnparsedRequest.withServerTypeAndReason(
						ServerType.HTTP,
						UnparsedRequestReason.MALFORMED_REQUEST)
				.build();
		ResponseMarshaler marshaler = ResponseMarshaler.builder()
				.unparsedRequestHandler(ignored ->
						MarshaledResponse.fromStatusCode(418))
				.unparsedRequestHandler(null)
				.build();

		MarshaledResponse response = marshaler.forUnparsedRequest(request);

		Assertions.assertEquals(400, response.getStatusCode());
		Assertions.assertTrue(response.getBody().isEmpty());
	}

	@Test
	void interfaceMethodIsDefaultForImplementationCompatibility()
			throws Exception {
		Method method = ResponseMarshaler.class.getMethod(
				"forUnparsedRequest", UnparsedRequest.class);

		Assertions.assertTrue(method.isDefault());
	}

	@Test
	void lifecycleObserverDefaultAndAggregateForwardingAreStable() {
		UnparsedRequest request = truncatedRequest();
		Assertions.assertDoesNotThrow(() -> LifecycleObserver.defaultInstance()
				.didRejectUnparsedRequest(request));
		List<String> calls = new ArrayList<>();
		LifecycleObserver first = observingLifecycleObserver("first", calls,
				request);
		LifecycleObserver second = observingLifecycleObserver("second", calls,
				request);

		LifecycleObservers.aggregate(List.of(first, second))
				.didRejectUnparsedRequest(request);

		Assertions.assertEquals(List.of("first", "second"), calls);
	}

	@Test
	void lifecycleObserverAggregateInvokesAllAndCollectsFailures() {
		UnparsedRequest request = truncatedRequest();
		List<String> calls = new ArrayList<>();
		RuntimeException firstFailure = new RuntimeException("first");
		RuntimeException secondFailure = new RuntimeException("second");
		LifecycleObserver first = new LifecycleObserver() {
			@Override
			public void didRejectUnparsedRequest(
					@NonNull UnparsedRequest actualRequest) {
				Assertions.assertSame(request, actualRequest);
				calls.add("first");
				throw firstFailure;
			}
		};
		LifecycleObserver middle = observingLifecycleObserver("middle", calls,
				request);
		LifecycleObserver last = new LifecycleObserver() {
			@Override
			public void didRejectUnparsedRequest(
					@NonNull UnparsedRequest actualRequest) {
				Assertions.assertSame(request, actualRequest);
				calls.add("last");
				throw secondFailure;
			}
		};
		LifecycleObserver aggregate = LifecycleObservers.aggregate(
				List.of(first, middle, last));

		RuntimeException actual = Assertions.assertThrows(
				RuntimeException.class,
				() -> aggregate.didRejectUnparsedRequest(request));

		Assertions.assertSame(firstFailure, actual);
		Assertions.assertArrayEquals(new Throwable[]{secondFailure},
				actual.getSuppressed());
		Assertions.assertEquals(List.of("first", "middle", "last"), calls);
	}

	@Test
	void lifecycleObserverAggregatePreservesSharedFailureAndInvokesLaterObservers() {
		for (boolean duplicateRegistration : List.of(false, true)) {
			UnparsedRequest request = truncatedRequest();
			RuntimeException sharedFailure = new RuntimeException("shared");
			RuntimeException distinctFailure = new RuntimeException("distinct");
			AtomicInteger calls = new AtomicInteger();
			LifecycleObserver first = new LifecycleObserver() {
				@Override
				public void didRejectUnparsedRequest(@NonNull UnparsedRequest actualRequest) {
					calls.incrementAndGet();
					throw sharedFailure;
				}
			};
			LifecycleObserver second = duplicateRegistration ? first : new LifecycleObserver() {
				@Override
				public void didRejectUnparsedRequest(@NonNull UnparsedRequest actualRequest) {
					calls.incrementAndGet();
					throw sharedFailure;
				}
			};
			LifecycleObserver last = new LifecycleObserver() {
				@Override
				public void didRejectUnparsedRequest(@NonNull UnparsedRequest actualRequest) {
					calls.incrementAndGet();
					throw distinctFailure;
				}
			};
			RuntimeException actual = Assertions.assertThrows(RuntimeException.class,
					() -> LifecycleObservers.aggregate(List.of(first, second, last))
							.didRejectUnparsedRequest(request));
			Assertions.assertSame(sharedFailure, actual);
			Assertions.assertEquals(3, calls.get());
			Assertions.assertArrayEquals(new Throwable[]{distinctFailure}, actual.getSuppressed());
		}
	}

	@NonNull
	private static UnparsedRequest truncatedRequest() {
		return UnparsedRequest.withServerTypeAndReason(ServerType.SSE,
						UnparsedRequestReason.REQUEST_HEADERS_TOO_LARGE)
				.capturedBytes(new byte[]{1, 2})
				.observedByteCount(5L)
				.captureTruncated(true)
				.build();
	}

	private static byte @NonNull [] bytesFrom(@NonNull ByteBuffer buffer) {
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		return bytes;
	}

	@NonNull
	private static LifecycleObserver observingLifecycleObserver(
			@NonNull String name,
			@NonNull List<String> calls,
			@NonNull UnparsedRequest expectedRequest) {
		return new LifecycleObserver() {
			@Override
			public void didRejectUnparsedRequest(
					@NonNull UnparsedRequest request) {
				Assertions.assertSame(expectedRequest, request);
				calls.add(name);
			}
		};
	}
}

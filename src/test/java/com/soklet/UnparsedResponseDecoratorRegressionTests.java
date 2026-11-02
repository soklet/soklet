package com.soklet;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UnparsedResponseDecoratorRegressionTests {
	@Test
	void inheritedUnparsedHandlerInvokesDecoratedPostprocessorOnceForEveryRejection() {
		AtomicInteger calls = new AtomicInteger();
		ResponseMarshaler secured = ResponseMarshaler.builder().postProcessor(response -> {
			int count = calls.incrementAndGet();
			return response.copy().headers(headers -> headers.put("X-Finalization-Count", List.of(String.valueOf(count)))).finish();
		}).build();
		ResponseMarshaler decorated = new ForwardingMarshaler(secured);
		Map<UnparsedRequestReason, Integer> expected = Map.of(
				UnparsedRequestReason.MALFORMED_REQUEST, 400,
				UnparsedRequestReason.REQUEST_TARGET_TOO_LONG, 414,
				UnparsedRequestReason.EXPECTATION_FAILED, 417,
				UnparsedRequestReason.REQUEST_HEADERS_TOO_LARGE, 431,
				UnparsedRequestReason.REQUEST_READ_TIMEOUT, 408,
				UnparsedRequestReason.REQUEST_TOO_LARGE, 413,
				UnparsedRequestReason.UNSUPPORTED_CONTENT_ENCODING, 415,
				UnparsedRequestReason.REQUEST_BODY_DECOMPRESSION_FAILED, 400);
		for (ServerType serverType : List.of(ServerType.HTTP, ServerType.SSE)) {
			for (var entry : expected.entrySet()) {
				calls.set(0);
				MarshaledResponse response = decorated.forUnparsedRequest(UnparsedRequest.withServerTypeAndReason(serverType, entry.getKey()).build());
				assertEquals(entry.getValue(), response.getStatusCode());
				assertEquals(List.of("1"), response.getHeaders().get("X-Finalization-Count"));
				assertEquals(1, calls.get());
			}
		}
	}

	@Test
	void explicitlyForwardedUnparsedHandlerPreservesCustomizationAndDoesNotFinalizeTwice() {
		AtomicInteger calls = new AtomicInteger();
		ResponseMarshaler delegate = ResponseMarshaler.builder()
				.unparsedRequestHandler(request -> MarshaledResponse.withStatusCode(422).headers(Map.of("X-Custom-Rejection", List.of("yes"))).build())
				.postProcessor(response -> {
					calls.incrementAndGet();
					return response.copy().headers(headers -> headers.put("X-Postprocessed", List.of("yes"))).finish();
				}).build();
		ResponseMarshaler decorated = new ForwardingMarshaler(delegate) {
			@Override public MarshaledResponse forUnparsedRequest(UnparsedRequest request) {
				return delegate.forUnparsedRequest(request);
			}
		};
		MarshaledResponse response = decorated.forUnparsedRequest(UnparsedRequest.withServerTypeAndReason(ServerType.HTTP,
				UnparsedRequestReason.MALFORMED_REQUEST).build());
		assertEquals(422, response.getStatusCode());
		assertEquals(List.of("yes"), response.getHeaders().get("X-Custom-Rejection"));
		assertEquals(List.of("yes"), response.getHeaders().get("X-Postprocessed"));
		assertEquals(1, calls.get());
	}

	private static class ForwardingMarshaler implements ResponseMarshaler {
		private final ResponseMarshaler delegate;
		ForwardingMarshaler(ResponseMarshaler delegate) { this.delegate = delegate; }
		@Override public MarshaledResponse postProcess(MarshaledResponse response) { return this.delegate.postProcess(response); }
		@Override public MarshaledResponse forResourceMethod(Request request, Response response, ResourceMethod method) { return this.delegate.forResourceMethod(request, response, method); }
		@Override public MarshaledResponse forNotFound(Request request) { return this.delegate.forNotFound(request); }
		@Override public MarshaledResponse forMethodNotAllowed(Request request, Set<HttpMethod> allowed) { return this.delegate.forMethodNotAllowed(request, allowed); }
		@Override public MarshaledResponse forContentTooLarge(Request request, ResourceMethod method) { return this.delegate.forContentTooLarge(request, method); }
		@Override public MarshaledResponse forServiceUnavailable(Request request, ResourceMethod method) { return this.delegate.forServiceUnavailable(request, method); }
		@Override public MarshaledResponse forOptions(Request request, Set<HttpMethod> allowed) { return this.delegate.forOptions(request, allowed); }
		@Override public MarshaledResponse forOptionsSplat(Request request) { return this.delegate.forOptionsSplat(request); }
		@Override public MarshaledResponse forThrowable(Request request, Throwable throwable, ResourceMethod method) { return this.delegate.forThrowable(request, throwable, method); }
		@Override public MarshaledResponse forHead(Request request, MarshaledResponse response) { return this.delegate.forHead(request, response); }
		@Override public MarshaledResponse forCorsPreflightAllowed(Request request, CorsPreflight preflight, CorsPreflightResponse response) { return this.delegate.forCorsPreflightAllowed(request, preflight, response); }
		@Override public MarshaledResponse forCorsPreflightRejected(Request request, CorsPreflight preflight) { return this.delegate.forCorsPreflightRejected(request, preflight); }
		@Override public MarshaledResponse forCorsAllowed(Request request, Cors cors, CorsResponse corsResponse, MarshaledResponse response) { return this.delegate.forCorsAllowed(request, cors, corsResponse, response); }
	}
}

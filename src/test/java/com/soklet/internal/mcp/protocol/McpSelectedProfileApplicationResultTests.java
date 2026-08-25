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

package com.soklet.internal.mcp.protocol;

import com.soklet.HttpMethod;
import com.soklet.McpRequestOutcome;
import com.soklet.Request;
import com.soklet.internal.microhttp.MicrohttpRequest;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

@NotThreadSafe
public class McpSelectedProfileApplicationResultTests {
	private static final String CURRENT = "2026-07-28";
	private static final String FAKE = "2099-01-01";
	private static final String MARKER = "com.example/applicationResultKind";
	private static final AtomicInteger REQUEST_SEQUENCE = new AtomicInteger();

	@Test
	public void productionProfileIsIdentityForEveryApplicationResultKind() {
		McpWireResult canonical = completeResult(
				McpProfileApplicationResultKind.TOOL);
		for (McpProfileApplicationResultKind kind
				: McpProfileApplicationResultKind.values())
			Assertions.assertSame(canonical,
					Mcp20260728ProtocolProfile.INSTANCE.renderApplicationResult(
							kind, canonical));
	}

	@Test
	public void selectedProfileRendersEveryLegalCompleteAndInputRequiredResultOnce()
			throws Exception {
		TrackingApplicationProfile fake = new TrackingApplicationProfile();
		McpApplicationExecution execution = execution();
		List<Scenario> scenarios = new ArrayList<>();
		for (McpProfileApplicationResultKind kind
				: McpProfileApplicationResultKind.values()) {
			scenarios.add(new Scenario(kind, method(kind), completeResult(kind)));
			if (kind != McpProfileApplicationResultKind.RESOURCE_LIST)
				scenarios.add(new Scenario(kind, method(kind), inputRequiredResult(kind)));
		}

		try {
			execution.start();
			EnumSet<McpProfileApplicationResultKind> observedKinds = EnumSet.noneOf(
					McpProfileApplicationResultKind.class);
			for (Scenario scenario : scenarios) {
				int callsBefore = fake.calls().size();
				McpApplicationResponse response = dispatch(execution,
						request(scenario.method(), Integer.toString(callsBefore)), fake,
						invocation -> {
							Assertions.assertSame(fake, invocation.protocolProfile());
							return scenario.canonical();
						}, Optional.empty());
				Assertions.assertEquals(callsBefore + 1, fake.calls().size(),
						"Each successful application result must be rendered exactly once.");
				RenderCall call = fake.calls().get(callsBefore);
				Assertions.assertEquals(scenario.kind(), call.kind());
				Assertions.assertSame(scenario.canonical(), call.canonical());
				assertPreservedWithMarker(scenario, response);
				observedKinds.add(call.kind());
			}
			Assertions.assertEquals(
					EnumSet.allOf(McpProfileApplicationResultKind.class), observedKinds);
			Assertions.assertEquals(7, fake.calls().size(),
					"Dynamic resources/list is complete-only; the other kinds support both results.");
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	@Test
	public void interceptorReplacementIsRenderedAndCustomResultsAndErrorsBypass()
			throws Exception {
		TrackingApplicationProfile fake = new TrackingApplicationProfile();
		McpApplicationExecution execution = execution();
		AtomicInteger handlerInvocations = new AtomicInteger();
		McpWireResult replacement = completeResult(
				McpProfileApplicationResultKind.TOOL);

		try {
			execution.start();
			McpApplicationResponse replaced = dispatch(execution,
					request("tools/call", "intercepted"), fake, invocation -> {
						handlerInvocations.incrementAndGet();
						return McpWireResult.complete(McpJsonObject.empty());
					}, Optional.of((invocation, downstream) -> replacement));
			Assertions.assertEquals(0, handlerInvocations.get());
			Assertions.assertEquals(1, fake.calls().size());
			Assertions.assertSame(replacement, fake.calls().get(0).canonical());
			assertPreservedWithMarker(new Scenario(
					McpProfileApplicationResultKind.TOOL, "tools/call", replacement),
					replaced);

			McpWireResult custom = McpWireResult.complete(new McpJsonObject(
					Map.of("custom", McpJsonBoolean.TRUE)));
			McpApplicationResponse customResponse = dispatch(execution,
					request("custom/result", "custom"), fake,
					invocation -> custom, Optional.empty());
			McpJsonRpcMessage.ResultResponse customMessage = Assertions.assertInstanceOf(
					McpJsonRpcMessage.ResultResponse.class,
					customResponse.message().orElseThrow());
			Assertions.assertSame(custom, customMessage.result());
			Assertions.assertFalse(customMessage.result().toJsonObject().members()
					.containsKey(MARKER));
			Assertions.assertEquals(1, fake.calls().size(),
					"Unknown application methods are outside R3C ownership.");

			McpApplicationResponse applicationError = dispatch(execution,
					request("tools/call", "application-error"), fake,
					invocation -> {
						throw new McpApplicationJsonRpcException(new McpJsonRpcError(
								1_001, "Application-owned", Optional.empty()));
					}, Optional.empty());
			McpJsonRpcMessage.ErrorResponse errorMessage = Assertions.assertInstanceOf(
					McpJsonRpcMessage.ErrorResponse.class,
					applicationError.message().orElseThrow());
			Assertions.assertEquals(1_001, errorMessage.error().code());
			Assertions.assertEquals("Application-owned", errorMessage.error().message());
			Assertions.assertEquals(1, fake.calls().size(),
					"Application-owned errors are not application success results.");
		} finally {
			execution.stop();
			Assertions.assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	private static void assertPreservedWithMarker(@NonNull Scenario scenario,
			@NonNull McpApplicationResponse response) {
		McpJsonRpcMessage.ResultResponse message = Assertions.assertInstanceOf(
				McpJsonRpcMessage.ResultResponse.class,
				response.message().orElseThrow());
		McpWireResult rendered = message.result();
		McpWireResult canonical = scenario.canonical();
		Assertions.assertEquals(canonical.resultType(), rendered.resultType());
		Assertions.assertEquals(canonical.fields(), rendered.fields());
		Assertions.assertEquals(canonical.metadata(), rendered.metadata());
		Assertions.assertEquals(
				McpResultType.INPUT_REQUIRED.equals(canonical.resultType())
						? McpRequestOutcome.INPUT_REQUIRED : McpRequestOutcome.COMPLETE,
				response.outcome());

		McpJsonObject canonicalDocument = canonical.toJsonObject();
		McpJsonObject renderedDocument = rendered.toJsonObject();
		Map<String, McpJsonValue> withoutMarker = new LinkedHashMap<>(
				renderedDocument.members());
		Assertions.assertEquals(scenario.kind().name(),
				((McpJsonString) withoutMarker.remove(MARKER)).value());
		Assertions.assertEquals(canonicalDocument.members(), withoutMarker,
				"Content, structured content, metadata, cache, cursor, state, and result type must survive rendering.");
		List<String> expectedOrder = new ArrayList<>(
				canonicalDocument.members().keySet());
		expectedOrder.add(MARKER);
		Assertions.assertEquals(expectedOrder,
				new ArrayList<>(renderedDocument.members().keySet()));
	}

	private static McpWireResult completeResult(
			@NonNull McpProfileApplicationResultKind kind) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		switch (kind) {
			case TOOL -> {
				fields.put("content", new McpJsonArray(List.of(new McpJsonObject(
						Map.of("type", new McpJsonString("text"), "text",
								new McpJsonString("typed"))))));
				fields.put("structuredContent", new McpJsonObject(
						Map.of("answer", new McpJsonNumber(42L))));
			}
			case PROMPT -> {
				fields.put("description", new McpJsonString("prompt"));
				fields.put("messages", new McpJsonArray(List.of()));
			}
			case RESOURCE_READ -> {
				fields.put("contents", new McpJsonArray(List.of()));
				fields.put("cacheScope", new McpJsonString("private"));
				fields.put("ttlMs", new McpJsonNumber(30L));
			}
			case RESOURCE_LIST -> {
				fields.put("resources", new McpJsonArray(List.of()));
				fields.put("nextCursor", new McpJsonString("cursor-2"));
				fields.put("cacheScope", new McpJsonString("public"));
				fields.put("ttlMs", new McpJsonNumber(60L));
			}
		}
		return McpWireResult.complete(new McpJsonObject(fields),
				Optional.of(metadata(kind.name().toLowerCase())));
	}

	private static McpWireResult inputRequiredResult(
			@NonNull McpProfileApplicationResultKind kind) {
		return McpWireResult.inputRequired(method(kind), Optional.empty(),
				Optional.of("protected-state-" + kind.name().toLowerCase()),
				Optional.of(metadata(kind.name().toLowerCase())),
				new McpJsonObject(Map.of("com.example/inputVariant",
						new McpJsonString(kind.name()))));
	}

	private static McpResultMetadata metadata(@NonNull String value) {
		return new McpResultMetadata(Optional.empty(), new McpJsonObject(Map.of(
				"com.example/resultMetadata", new McpJsonString(value))));
	}

	private static String method(@NonNull McpProfileApplicationResultKind kind) {
		return switch (kind) {
			case TOOL -> "tools/call";
			case PROMPT -> "prompts/get";
			case RESOURCE_READ -> "resources/read";
			case RESOURCE_LIST -> "resources/list";
		};
	}

	private static McpApplicationExecution execution() {
		return new McpApplicationExecution(new McpApplicationExecutionConfiguration(
				1, 16, Duration.ofSeconds(30), Duration.ofDays(1)),
				McpApplicationClock.SYSTEM);
	}

	private static McpApplicationResponse dispatch(
			@NonNull McpApplicationExecution execution,
			McpJsonRpcMessage.@NonNull Request request,
			@NonNull McpProtocolProfile profile,
			@NonNull McpApplicationRequestHandler handler,
			@NonNull Optional<@NonNull McpApplicationRequestInterceptor> interceptor)
			throws Exception {
		CountDownLatch ready = new CountDownLatch(1);
		AtomicReference<McpApplicationResponse> response = new AtomicReference<>();
		MicrohttpRequest transport = transportRequest();
		McpApplicationResponseWriter writer = value -> {
			response.set(value);
			ready.countDown();
			return true;
		};
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		if (interceptor.isPresent()) {
			execution.dispatchWithSokletRequest(transport,
					Request.withPath(HttpMethod.POST, "/mcp").build(), request,
					profile, admissionIdentity(), handler,
					interceptor.orElseThrow(), deadline, writer, () -> {});
		} else {
			execution.dispatch(transport, request, profile, admissionIdentity(),
					handler, deadline, writer, () -> {});
		}
		Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS),
				"The application result was not delivered.");
		return requireNonNull(response.get());
	}

	private static MicrohttpRequest transportRequest() {
		return new MicrohttpRequest("POST", "/mcp", "HTTP/1.1", List.of(),
				new byte[0], false, new InetSocketAddress("127.0.0.1",
						20_000 + REQUEST_SEQUENCE.incrementAndGet()));
	}

	private static McpEffectiveAdmissionIdentity admissionIdentity() {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"selected-application-result-test", "4.0.0-SNAPSHOT"))
				.build();
		return McpEffectiveAdmissionIdentity.resolve(endpoint, "/mcp",
				McpAdmissionIdentity.anonymousInstance());
	}

	private static McpJsonRpcMessage.Request request(@NonNull String method,
			@NonNull String id) {
		String fields = switch (method) {
			case "tools/call" -> ",\"name\":\"test\",\"arguments\":{}";
			case "prompts/get" -> ",\"name\":\"test\",\"arguments\":{}";
			case "resources/read" -> ",\"uri\":\"test://resource\"";
			default -> "";
		};
		String json = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
				+ "\",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + FAKE + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}" + fields
				+ "}}";
		McpJsonLimits limits = McpJsonLimits.productionDefaults();
		McpJsonRpcEnvelope envelope = new McpJsonRpcEnvelopeCodec(
				new McpJsonCodec(limits)).decode(json.getBytes(StandardCharsets.UTF_8));
		return new McpRequestWireMapper(limits).map(
				(McpJsonRpcEnvelope.Request) envelope);
	}

	private record Scenario(@NonNull McpProfileApplicationResultKind kind,
			@NonNull String method, @NonNull McpWireResult canonical) {
	}

	private record RenderCall(@NonNull McpProfileApplicationResultKind kind,
			@NonNull McpWireResult canonical) {
	}

	private static final class TrackingApplicationProfile
			implements McpProtocolProfile {
		private final List<RenderCall> calls = new CopyOnWriteArrayList<>();

		@Override
		public @NonNull String revision() {
			return FAKE;
		}

		@Override
		public McpJsonRpcMessage.@NonNull Request mapRequest(
				@NonNull McpRequestWireMapper mapper,
				McpJsonRpcEnvelope.@NonNull Request request) {
			return mapper.map(request);
		}

		@Override
		public @NonNull McpNotificationMetadataValidation
				validateNotificationMetadata(
						McpJsonRpcEnvelope.@NonNull Notification notification) {
			return Mcp20260728ProtocolProfile.INSTANCE
					.validateNotificationMetadata(notification);
		}

		@Override
		public @NonNull McpWireResult renderFrameworkResult(
				@NonNull McpProfileFrameworkResultKind kind,
				@NonNull McpWireResult canonicalResult) {
			return canonicalResult;
		}

		@Override
		public @NonNull McpWireResult renderApplicationResult(
				@NonNull McpProfileApplicationResultKind kind,
				@NonNull McpWireResult canonicalResult) {
			calls.add(new RenderCall(kind, canonicalResult));
			Map<String, McpJsonValue> fields = new LinkedHashMap<>(
					canonicalResult.toJsonObject().members());
			fields.put(MARKER, new McpJsonString(kind.name()));
			return McpWireResult.withPrecomputedJsonObject(canonicalResult,
					new McpJsonObject(fields));
		}

		@Override
		public McpJsonRpcMessage.@NonNull Notification renderFrameworkNotification(
				@NonNull McpProfileFrameworkNotificationKind kind,
				McpJsonRpcMessage.@NonNull Notification canonicalNotification) {
			return canonicalNotification;
		}

		@Override
		public @NonNull McpJsonRpcError renderFrameworkError(
				@NonNull McpProfileErrorKind kind,
				@NonNull McpJsonRpcError canonicalError) {
			return canonicalError;
		}

		private List<RenderCall> calls() {
			return List.copyOf(calls);
		}
	}
}

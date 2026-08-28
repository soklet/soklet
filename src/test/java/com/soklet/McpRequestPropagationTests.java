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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.AdmissionInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestObservationInput;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests MCP-only trace-context and baggage propagation into public contexts.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpRequestPropagationTests {
	private static final String MCP_TRACEPARENT =
			"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
	private static final String HTTP_TRACEPARENT =
			"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";

	@Test
	void validatedMetadataReachesAdmissionAndToolHandlersInsteadOfHttpTraceHeaders()
			throws Exception {
		McpJsonObject metadata = McpJsonObject.builder()
				.put("traceparent", MCP_TRACEPARENT)
				.put("tracestate", " rojo=00f067aa0ba902b7 ,bad,congo=t61rcWkgMzE ")
				.put("baggage", "userId=Am%C3%A9lie;private=true,"
						+ " serverNode = DF%2028,equals=a=b,"
						+ "duplicate=first,duplicate=second")
				.build();
		Request request = requestWithHttpTraceparent();
		DefaultMcpAdmissionContext admission = new DefaultMcpAdmissionContext(
				admissionInput(request, Optional.of(metadata)));
		DefaultMcpRequestContext toolRequest = new DefaultMcpRequestContext(
				requestObservationInput(request, metadata));

		assertEquals("0af7651916cd43dd8448eb211c80319c",
				admission.getTraceContext().orElseThrow().getTraceId());
		TraceContext traceContext = toolRequest.getTraceContext().orElseThrow();
		assertEquals("0af7651916cd43dd8448eb211c80319c",
				traceContext.getTraceId());
		assertEquals("rojo=00f067aa0ba902b7,congo=t61rcWkgMzE",
				traceContext.toTracestateHeaderValue().orElseThrow());
		assertEquals(Map.of(
				"userId", "Amélie",
				"serverNode", "DF 28",
				"equals", "a=b",
				"duplicate", "first"), toolRequest.getBaggage());

		AtomicBoolean invoked = new AtomicBoolean();
		McpToolRegistration<McpJsonObject> registration =
				McpToolRegistration.withName("propagation")
						.jsonArguments()
						.handler((handlerRequest, arguments, features) -> {
							assertEquals(traceContext,
									handlerRequest.getTraceContext().orElseThrow());
							assertEquals("Amélie",
									handlerRequest.getBaggage().get("userId"));
							invoked.set(true);
							return McpCompleteResult.fromToolText("done");
						})
						.build();
		registration.invoke(toolRequest, McpJsonObject.emptyInstance(),
				McpInvocationFeatures.fromFeatures(Map.of()));
		assertTrue(invoked.get());
	}

	@Test
	void invalidOrMistypedMetadataIsOmittedWithoutFallingBackToHttpHeaders() {
		McpJsonObject malformed = McpJsonObject.builder()
				.put("traceparent", MCP_TRACEPARENT.toUpperCase())
				.put("tracestate", "rojo=00f067aa0ba902b7")
				.put("baggage", "valid=retained,invalid value=removed,"
						+ "badPercent=%ZZ,badProperty=value;=missingKey")
				.build();
		Request request = requestWithHttpTraceparent();

		DefaultMcpAdmissionContext admission = new DefaultMcpAdmissionContext(
				admissionInput(request, Optional.of(malformed)));
		DefaultMcpRequestContext toolRequest = new DefaultMcpRequestContext(
				requestObservationInput(request, malformed));
		assertTrue(admission.getTraceContext().isEmpty());
		assertTrue(toolRequest.getTraceContext().isEmpty());
		assertEquals(Map.of("valid", "retained"), toolRequest.getBaggage());

		McpJsonObject mistyped = McpJsonObject.builder()
				.put("traceparent", 7)
				.put("tracestate", true)
				.put("baggage", McpJsonArray.fromElements(List.of()))
				.build();
		DefaultMcpRequestContext mistypedContext = new DefaultMcpRequestContext(
				requestObservationInput(request, mistyped));
		assertTrue(mistypedContext.getTraceContext().isEmpty());
		assertTrue(mistypedContext.getBaggage().isEmpty());

		DefaultMcpAdmissionContext noMetadata = new DefaultMcpAdmissionContext(
				admissionInput(request, Optional.empty()));
		assertTrue(noMetadata.getTraceContext().isEmpty());
	}

	@Test
	void baggageParsingIsBoundedDecodedAndImmutable() {
		List<String> members = new ArrayList<>();
		for (int i = 0; i < 65; i++)
			members.add("key" + i + "=value" + i);
		McpRequestPropagation bounded = McpRequestPropagation.fromMetadata(
				McpJsonObject.builder()
						.put("baggage", String.join(",", members))
						.build());
		assertEquals(64, bounded.baggage().size());
		assertTrue(bounded.baggage().containsKey("key0"));
		assertFalse(bounded.baggage().containsKey("key64"));
		assertEquals(members.subList(0, 64).stream()
				.map(member -> member.substring(0, member.indexOf('=')))
				.toList(), new ArrayList<>(bounded.baggage().keySet()));
		assertThrows(UnsupportedOperationException.class,
				() -> bounded.baggage().put("extra", "value"));

		McpRequestPropagation invalidUtf8 = McpRequestPropagation.fromMetadata(
				McpJsonObject.builder().put("baggage", "value=%C3%28").build());
		assertEquals("�(", invalidUtf8.baggage().get("value"));

		McpRequestPropagation maximumSized = McpRequestPropagation.fromMetadata(
				McpJsonObject.builder()
						.put("baggage", "value=" + "x".repeat(8_186))
						.build());
		assertEquals(8_186, maximumSized.baggage().get("value").length());

		McpRequestPropagation oversized = McpRequestPropagation.fromMetadata(
				McpJsonObject.builder()
						.put("baggage", "value=" + "x".repeat(8_187))
						.build());
		assertTrue(oversized.baggage().isEmpty());
	}

	private static Request requestWithHttpTraceparent() {
		return Request.withPath(HttpMethod.POST, "/mcp")
				.headers(Map.of("traceparent", Set.of(HTTP_TRACEPARENT)))
				.build();
	}

	private static AdmissionInput admissionInput(@NonNull Request request,
			@NonNull Optional<@NonNull McpJsonObject> metadata) {
		return new AdmissionInput(request, endpoint(), Map.of(), "tools/call",
				false, Optional.of(McpRequestId.fromString("request")),
				"2026-07-28", Optional.of("propagation"), Optional.empty(),
				Optional.of(McpJsonObject.emptyInstance()), List.of(), metadata);
	}

	private static RequestObservationInput requestObservationInput(
			@NonNull Request request,
			@NonNull McpJsonObject metadata) {
		return new RequestObservationInput(request, endpoint(), Map.of(),
				"tools/call", Optional.of(McpRequestId.fromString("request")),
				"2026-07-28", Optional.of("propagation"), Optional.empty(),
				McpJsonObject.emptyInstance(), metadata,
				McpAdmissionIdentity.anonymousInstance());
	}

	private static McpEndpoint endpoint() {
		return McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation
						.withNameAndVersion("test-server", "1")
						.build())
				.build();
	}
}

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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Revision-exact readable routing and constrained legacy subscription vocabulary. */
class McpLegacySubscriptionRoutingTests {
	@Test
	void exactAndTemplateRoutesAreResolvedWithoutInvokingResourceHandlers() {
		AtomicInteger calls = new AtomicInteger();
		McpApplicationResourceReadRoute route = new McpApplicationResourceReadRoute(invocation -> {
			calls.incrementAndGet();
			throw new AssertionError("Subscription routing must not read resource content.");
		});
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromResourceRoutes(
				Map.of("test:///exact", route),
				List.of(new McpApplicationResourceTemplateRoute("test:///template/{key}", route)),
				Optional.empty());
		assertTrue(router.resolvesResourceSubscription("test:///exact"));
		assertTrue(router.resolvesResourceSubscription("test:///template/one"));
		assertFalse(router.resolvesResourceSubscription("test:///missing"));
		assertThrows(IllegalArgumentException.class, () -> router.resolvesResourceSubscription("relative"));
		assertEquals(0, calls.get());
	}

	@Test
	void revisionViewCannotSubscribeAResourceDeclaredOnlyOnAnotherRevision() {
		McpApplicationResourceReadRoute route = new McpApplicationResourceReadRoute(invocation -> {
			throw new AssertionError("No handler should execute.");
		});
		McpApplicationRequestRouter router = McpApplicationRequestRouter.fromResourceRoutes(
				Map.of("test:///june", route, "test:///modern", route),
				List.of(new McpApplicationResourceTemplateRoute("test:///modern/{key}", route)),
				Optional.empty());
		McpNormalizedEndpoint june = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("routing", "1"))
				.exactResource(McpNormalizedResourceDescriptor.minimal("test:///june"))
				.build();
		McpApplicationRequestRouter selected = router.resourceView(june, "2025-06-18");
		assertTrue(selected.resolvesResourceSubscription("test:///june"));
		assertFalse(selected.resolvesResourceSubscription("test:///modern"));
		assertFalse(selected.resolvesResourceSubscription("test:///modern/key"));
	}

	@Test
	void dynamicListAndGenericResourceHandlersDoNotEstablishReadableRoutes() {
		McpApplicationRequestRouter router = McpApplicationRequestRouter
				.fromFrameworkHandlersAndValidatedOperationRoutes(
						Map.of("resources/read", invocation -> { throw new AssertionError("No read."); }),
						Map.of(), Map.of(), Map.of(), List.of(),
						Optional.of(new McpApplicationResourceListRoute(invocation -> { throw new AssertionError("No list."); })));
		assertFalse(router.resolvesResourceSubscription("test:///unregistered"));
		for (String method : List.of("resources/subscribe", "resources/unsubscribe")) {
			assertThrows(IllegalArgumentException.class, () -> McpApplicationRequestRouter.fromHandlers(
					Map.of(method, invocation -> { throw new AssertionError("No replacement."); })));
			assertThrows(IllegalArgumentException.class, () -> McpApplicationRequestRouter
					.fromFrameworkHandlersAndValidatedOperationRoutes(Map.of(method, invocation -> { throw new AssertionError(); }),
							Map.of(), Map.of(), Map.of(), List.of(), Optional.empty()));
		}
	}

	@Test
	void exactLegacyVocabularyIncludesOnlyImplementedRequests() {
		for (String method : List.of("resources/subscribe", "resources/unsubscribe", "resources/read", "completion/complete"))
			assertTrue(McpLegacyHttpWire.supportsRequestMethod(method));
		for (String method : List.of("subscriptions/listen", "tasks/get", "skills/get", "sampling/createMessage", "resources/custom"))
			assertFalse(McpLegacyHttpWire.supportsRequestMethod(method));
		assertThrows(NullPointerException.class, () -> McpLegacyHttpWire.supportsRequestMethod(null));
	}

	@Test
	void matchingSubscriptionMirrorsStayLegacyButMismatchesRemainModernFraming() {
		McpJsonRpcEnvelopeCodec envelopes = new McpJsonRpcEnvelopeCodec(new McpJsonCodec(
				new McpJsonLimits(65_536, 256, 16_384, 16_384, 512, 10_000, 16_384, 65_536)));
		McpMirroredHeaderCodec mirrors = new McpMirroredHeaderCodec(McpMirroredHeaderCodec.DEFAULT_MAXIMUM_DECODED_BYTES);
		for (String revision : List.of("2025-06-18", "2025-11-25"))
			for (String method : List.of("resources/subscribe", "resources/unsubscribe")) {
				McpJsonRpcEnvelope envelope = envelopes.decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
						+ method + "\",\"params\":{\"uri\":\"test:///exact\"}}");
				assertEquals(McpLegacyHttpWire.Era.LEGACY, McpLegacyHttpWire.classify(envelope,
						List.of(revision), List.of(method), List.of("test:///exact"), false, mirrors));
				assertEquals(McpLegacyHttpWire.Era.MODERN, McpLegacyHttpWire.classify(envelope,
						List.of(revision), List.of(method), List.of("test:///other"), false, mirrors));
				assertEquals(McpLegacyHttpWire.Era.MODERN, McpLegacyHttpWire.classify(envelope,
						List.of(revision), List.of(method, method), List.of(), false, mirrors));
			}
	}
}

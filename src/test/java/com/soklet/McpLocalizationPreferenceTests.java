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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestObservationInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded {@code Accept-Language} preference derivation and the immutable
 * request-local provider input, per localization plan section 6.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
@Timeout(30)
class McpLocalizationPreferenceTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";

	@Test
	void absentBlankAndMalformedInputAllCollapseToTheEmptyPreference() {
		assertEquals(List.of(), McpLocaleSupport.boundedLanguageRanges(null));
		assertEquals(List.of(), McpLocaleSupport.boundedLanguageRanges(Set.of()));
		assertEquals(List.of(), McpLocaleSupport.boundedLanguageRanges(Set.of("")));
		assertEquals(List.of(), McpLocaleSupport.boundedLanguageRanges(Set.of("   ")));
		assertEquals(List.of(),
				McpLocaleSupport.boundedLanguageRanges(Set.of("en;q=not-a-number")));
		assertEquals(List.of(),
				McpLocaleSupport.boundedLanguageRanges(Set.of("en;q=17")));
	}

	@Test
	void wellFormedRangesParseInDescendingWeightAndPreserveZeroWeightExclusions() {
		List<Locale.LanguageRange> ranges = McpLocaleSupport.boundedLanguageRanges(
				orderedValues("fr-CA;q=0.8, en-US, de;q=0"));

		assertEquals(List.of("en-us", "fr-ca", "de"),
				ranges.stream().map(Locale.LanguageRange::getRange).toList());
		assertEquals(List.of(1.0d, 0.8d, 0.0d),
				ranges.stream().map(Locale.LanguageRange::getWeight).toList());

		// A zero-weight exclusion must survive derivation rather than be dropped.
		assertTrue(ranges.stream().anyMatch(range ->
				range.getWeight() == 0.0d && "de".equals(range.getRange())));
	}

	@Test
	void repeatedHeaderValuesAreCombinedBeforeParsing() {
		List<Locale.LanguageRange> ranges = McpLocaleSupport.boundedLanguageRanges(
				orderedValues("en-US", "fr-CA;q=0.5"));

		assertEquals(List.of("en-us", "fr-ca"),
				ranges.stream().map(Locale.LanguageRange::getRange).toList());
	}

	@Test
	void observationContextRetainsPhysicalHeaderOrderAndDuplicates() {
		List<String> physicalValues = List.of(
				"fr-CA;q=0", "en-US", "fr-CA;q=1");
		DefaultMcpRequestContext context = new DefaultMcpRequestContext(
				new RequestObservationInput(
						Request.withPath(HttpMethod.POST, "/mcp").build(),
						McpEndpoint.withPath("/mcp").serverInformation(
								McpImplementation.withNameAndVersion(
										"physical-preference", "1").build()).build(),
						Map.of(), "tools/call",
						Optional.of(McpRequestId.fromString("request")),
						"2026-07-28", Optional.of("lookup"), Optional.empty(),
						McpJsonObject.emptyInstance(), McpJsonObject.emptyInstance(),
						McpInputResponses.emptyInstance(), Optional.empty(),
						physicalValues, McpAdmissionIdentity.anonymousInstance()));

		assertEquals(physicalValues, context.acceptLanguageValues());
		assertEquals(List.of("en-us", "fr-ca"),
				McpLocaleSupport.boundedLanguageRanges(
						context.acceptLanguageValues()).stream()
						.map(Locale.LanguageRange::getRange).toList());
	}

	@Test
	void physicalDuplicateHeaderFieldsReachTheProviderInWireOrder()
			throws Exception {
		AtomicReference<List<Locale.LanguageRange>> observedRanges =
				new AtomicReference<>();
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> {
					observedRanges.set(List.copyOf(request.getLanguageRanges()));
					return new McpLocalizationContext() {
						@Override
						public Locale getLocale() {
							return Locale.ENGLISH;
						}

						@Override
						public McpLocalizationResult localize(
								McpLocalizableText text) {
							return McpLocalizationResult.useDefaultText();
						}
					};
				})
				.build();
		String path = "/localization/physical-preference";
		McpEndpoint endpoint = McpEndpoint.withPath(path)
				.serverInformation(McpImplementation
						.withNameAndVersion("physical-preference", "1")
						.title("Localized surface")
						.build())
				.build();
		McpServer server = McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK))
				.localizer(localizer)
				.build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build());

		try {
			soklet.start();
			int port = server.getDiagnostics().getBoundAddress()
					.orElseThrow().getPort();
			byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":\"physical\","
					+ "\"method\":\"server/discover\",\"params\":{\"_meta\":{"
					+ "\"io.modelcontextprotocol/protocolVersion\":\""
					+ PROTOCOL_VERSION + "\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}")
					.getBytes(StandardCharsets.UTF_8);
			String requestHead = "POST " + path + " HTTP/1.1\r\n"
					+ "Host: " + LOOPBACK + ':' + port + "\r\n"
					+ "Content-Type: application/json; charset=UTF-8\r\n"
					+ "Accept: application/json, text/event-stream\r\n"
					+ "MCP-Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
					+ "Mcp-Method: server/discover\r\n"
					+ "Accept-Language: fr-CA;q=0\r\n"
					+ "Accept-Language: en-US\r\n"
					+ "Accept-Language: fr-CA;q=1\r\n"
					+ "Content-Length: " + body.length + "\r\n"
					+ "Connection: close\r\n\r\n";

			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress(LOOPBACK, port), 3_000);
				socket.setSoTimeout(5_000);
				socket.getOutputStream().write(
						requestHead.getBytes(StandardCharsets.ISO_8859_1));
				socket.getOutputStream().write(body);
				socket.getOutputStream().flush();
				ByteArrayOutputStream response = new ByteArrayOutputStream();
				socket.getInputStream().transferTo(response);
				String responseText = response.toString(
						StandardCharsets.ISO_8859_1);
				assertTrue(responseText.startsWith("HTTP/1.1 200"), responseText);
			}
		} finally {
			soklet.stop();
		}

		List<Locale.LanguageRange> ranges = observedRanges.get();
		assertEquals(List.of("en-us", "fr-ca"), ranges.stream()
				.map(Locale.LanguageRange::getRange).toList());
		assertEquals(List.of(1.0d, 0.0d), ranges.stream()
				.map(Locale.LanguageRange::getWeight).toList(),
				"The first physical duplicate controls the repeated range's weight.");
	}

	@Test
	void combinedInputAtTheCodeUnitBoundParsesAndOverTheBoundIsNotTruncated() {
		// Padding keeps the range count at one so this exercises only the
		// code-unit bound, independently of the separate 32-range bound.
		String atBound = "en-US" + " ".repeat(4_091);
		assertEquals(4_096, atBound.length());
		assertEquals(List.of("en-us"),
				McpLocaleSupport.boundedLanguageRanges(orderedValues(atBound))
						.stream().map(Locale.LanguageRange::getRange).toList());

		String overBound = atBound + " ";
		assertEquals(4_097, overBound.length());
		assertEquals(List.of(),
				McpLocaleSupport.boundedLanguageRanges(orderedValues(overBound)),
				"Over-limit input must collapse to empty, never truncate.");
	}

	@Test
	void parsedRangeCountAtThirtyTwoParsesAndThirtyThreeIsNotTruncated() {
		assertEquals(32, McpLocaleSupport.boundedLanguageRanges(
				orderedValues(distinctRanges(32))).size());

		assertEquals(List.of(), McpLocaleSupport.boundedLanguageRanges(
				orderedValues(distinctRanges(33))),
				"Over-limit range counts must collapse to empty, never truncate.");
	}

	@Test
	void theRangeBoundIsAppliedAfterJdkAliasExpansionNotToTheRawTokenCount() {
		// The JDK expands deprecated codes: "iw" yields both "iw" and "he".
		assertEquals(List.of("iw", "he"),
				McpLocaleSupport.boundedLanguageRanges(orderedValues("iw"))
						.stream().map(Locale.LanguageRange::getRange).toList());

		// 32 raw tokens whose expansion yields 33 ranges must still collapse,
		// proving the bound is checked against the expanded list.
		String thirtyTwoRawTokensExpandingToThirtyThree =
				distinctRanges(31) + ",iw";
		assertEquals(32, thirtyTwoRawTokensExpandingToThirtyThree.split(",").length);
		assertEquals(List.of(), McpLocaleSupport.boundedLanguageRanges(
				orderedValues(thirtyTwoRawTokensExpandingToThirtyThree)),
				"The 32-range bound must be applied after alias expansion.");
	}

	@Test
	void wildcardAndEqualWeightInputsSurviveDerivationUnchanged() {
		// A wildcard is a legitimate range the provider may act on; Soklet does
		// not filter or reorder it away.
		assertEquals(List.of("*"),
				McpLocaleSupport.boundedLanguageRanges(orderedValues("*"))
						.stream().map(Locale.LanguageRange::getRange).toList());

		List<Locale.LanguageRange> mixed = McpLocaleSupport.boundedLanguageRanges(
				orderedValues("fr-CA;q=0.5, *;q=0.1, en-US"));
		assertEquals(List.of("en-us", "fr-ca", "*"), mixed.stream()
				.map(Locale.LanguageRange::getRange).toList());

		// Equal weights are preserved with no documented ordering guarantee, so
		// the contract only requires that every requested range survives at its
		// exact weight. The JDK may add equivalents (de-DE also yields de-dd),
		// which is expansion rather than reordering.
		List<Locale.LanguageRange> equalWeights =
				McpLocaleSupport.boundedLanguageRanges(
						orderedValues("de-DE;q=0.7, fr-CA;q=0.7, es-ES;q=0.7"));
		Set<String> derived = equalWeights.stream()
				.map(Locale.LanguageRange::getRange)
				.collect(java.util.stream.Collectors.toSet());
		assertTrue(derived.containsAll(Set.of("de-de", "fr-ca", "es-es")),
				derived.toString());
		assertTrue(equalWeights.stream()
				.allMatch(range -> range.getWeight() == 0.7d),
				equalWeights.toString());
	}

	@Test
	void theDerivedPreferenceViewIsImmutable() {
		List<Locale.LanguageRange> ranges =
				McpLocaleSupport.boundedLanguageRanges(orderedValues("en-US"));

		assertThrows(UnsupportedOperationException.class, ranges::clear);
	}

	@Test
	void requestCopiesRangesDefensivelyAndDistinguishesEmptyCursorFromAbsence() {
		List<Locale.LanguageRange> mutable = new ArrayList<>(
				McpLocaleSupport.boundedLanguageRanges(orderedValues("en-US")));
		McpRequestContext requestContext = requestContext();

		DefaultMcpLocalizationRequest present = new DefaultMcpLocalizationRequest(
				requestContext, mutable, Locale.FRENCH, "", Locale.ENGLISH);
		mutable.clear();

		assertSame(requestContext, present.getRequestContext());
		assertEquals(1, present.getLanguageRanges().size(),
				"The request must not alias the caller's mutable list.");
		assertNotSame(mutable, present.getLanguageRanges());
		assertEquals(Optional.of(""), present.getResourceListCursor());
		assertEquals(Optional.of(Locale.FRENCH), present.getContinuationLocale());
		assertEquals(Locale.ENGLISH, present.getFallbackLocale());

		DefaultMcpLocalizationRequest absent = new DefaultMcpLocalizationRequest(
				requestContext, List.of(), null, null, Locale.ENGLISH);

		assertTrue(absent.getResourceListCursor().isEmpty());
		assertTrue(absent.getContinuationLocale().isEmpty());
		assertThrows(UnsupportedOperationException.class,
				() -> absent.getLanguageRanges().clear());
	}

	@Test
	void requestToStringRetainsNoLocaleCursorOrPreferenceDetail() {
		String rendered = new DefaultMcpLocalizationRequest(requestContext(),
				McpLocaleSupport.boundedLanguageRanges(orderedValues("de-CH")),
				Locale.forLanguageTag("pt-BR"), "cursor-secret", Locale.ENGLISH)
				.toString();

		assertFalse(rendered.contains("de"), rendered);
		assertFalse(rendered.contains("pt"), rendered);
		assertFalse(rendered.contains("cursor-secret"), rendered);
		assertFalse(rendered.contains("en"), rendered);
	}

	private static Set<String> orderedValues(String... values) {
		return new LinkedHashSet<>(List.of(values));
	}

	private static String distinctRanges(int count) {
		// Region subtags keep every range distinct and free of alias expansion.
		return IntStream.range(0, count)
				.mapToObj(index -> String.format("en-%03d", index))
				.collect(Collectors.joining(","));
	}

	private static McpRequestContext requestContext() {
		return new DefaultMcpRequestContext(new RequestObservationInput(
				Request.withPath(HttpMethod.POST, "/mcp").build(),
				McpEndpoint.withPath("/mcp").serverInformation(McpImplementation
						.withNameAndVersion("preference-test", "1").build()).build(),
				Map.of(), "tools/call",
				Optional.of(McpRequestId.fromString("request")), "2026-07-28",
				Optional.of("lookup"), Optional.empty(),
				McpJsonObject.emptyInstance(), McpJsonObject.emptyInstance(),
				McpInputResponses.emptyInstance(),
				McpAdmissionIdentity.anonymousInstance()));
	}
}

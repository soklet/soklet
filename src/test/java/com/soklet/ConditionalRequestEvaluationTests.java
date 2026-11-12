/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet;

import com.soklet.ConditionalRequestResult.NotModified;
import com.soklet.ConditionalRequestResult.Precondition;
import com.soklet.ConditionalRequestResult.PreconditionFailed;
import com.soklet.ConditionalRequestResult.PreconditionFailed.Reason;
import com.soklet.ConditionalRequestResult.Proceed;
import com.soklet.internal.microhttp.Header;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConditionalRequestEvaluationTests {
	private static final EntityTag TAG = EntityTag.fromStrongValue("v1");
	private static final Instant MODIFIED = Instant.parse("2026-05-04T01:02:03.456Z");
	private static final String DATE = "Mon, 04 May 2026 01:02:03 GMT";
	private static final String EARLIER = "Mon, 04 May 2026 01:02:02 GMT";

	@Test
	void satisfiedSetsContainOnlyTrueUnsuppressedConditions() {
		assertProceed(evaluate(HttpMethod.PUT, Map.of(
				"If-Match", List.of("\"v1\""),
				"If-Unmodified-Since", List.of(EARLIER),
				"If-None-Match", List.of("\"other\""),
				"If-Modified-Since", List.of(DATE))), Precondition.IF_MATCH, Precondition.IF_NONE_MATCH);
		assertProceed(evaluate(HttpMethod.GET, Map.of(
				"If-Unmodified-Since", List.of(DATE),
				"If-Modified-Since", List.of(EARLIER))),
				Precondition.IF_UNMODIFIED_SINCE, Precondition.IF_MODIFIED_SINCE);
		assertProceed(evaluate(HttpMethod.GET, Map.of("If-None-Match", List.of(""))),
				Precondition.IF_NONE_MATCH);
		assertProceed(evaluate(HttpMethod.PUT, Map.of("If-Modified-Since", List.of(EARLIER))));
		assertProceed(evaluate(HttpMethod.PUT, Map.of("If-Unmodified-Since", List.of("invalid"))));
		assertProceed(evaluate(HttpMethod.GET, Map.of()));
	}

	@Test
	void reachableSatisfiedSetsRespectMethodAndPrecedenceDomains() {
		List<Map<String, List<String>>> firstConditions = List.of(Map.of(),
				Map.of("If-Match", List.of("\"v1\"")), Map.of("If-Unmodified-Since", List.of(DATE)));
		List<Map<String, List<String>>> cacheConditions = List.of(Map.of(),
				Map.of("If-None-Match", List.of("\"other\"")), Map.of("If-Modified-Since", List.of(EARLIER)));
		Set<Set<Precondition>> mutationSets = Set.of(Set.of(), Set.of(Precondition.IF_MATCH),
				Set.of(Precondition.IF_UNMODIFIED_SINCE), Set.of(Precondition.IF_NONE_MATCH),
				Set.of(Precondition.IF_MATCH, Precondition.IF_NONE_MATCH),
				Set.of(Precondition.IF_UNMODIFIED_SINCE, Precondition.IF_NONE_MATCH));
		Set<Set<Precondition>> readSets = new HashSet<>(mutationSets);
		readSets.add(Set.of(Precondition.IF_MODIFIED_SINCE));
		readSets.add(Set.of(Precondition.IF_MATCH, Precondition.IF_MODIFIED_SINCE));
		readSets.add(Set.of(Precondition.IF_UNMODIFIED_SINCE, Precondition.IF_MODIFIED_SINCE));
		for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST,
				HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
			Set<Set<Precondition>> reached = new HashSet<>();
			for (Map<String, List<String>> first : firstConditions) {
				for (Map<String, List<String>> cache : cacheConditions) {
					Map<String, List<String>> headers = new LinkedHashMap<>(first);
					headers.putAll(cache);
					reached.add(assertInstanceOf(Proceed.class, evaluate(method, headers)).getSatisfiedPreconditions());
				}
			}
			assertEquals(method == HttpMethod.GET || method == HttpMethod.HEAD ? readSets : mutationSets,
					reached, method.toString());
		}
	}

	@Test
	void firstDecisiveConditionWinsWithoutInspectingLaterFailures() {
		assertFailure(evaluate(HttpMethod.GET, Map.of(
				"If-Match", List.of("\"other\""),
				"If-None-Match", List.of(overLimit()))), Precondition.IF_MATCH, Reason.CONDITION_FALSE);
		assertFailure(evaluate(HttpMethod.GET, Map.of(
				"If-Unmodified-Since", List.of(EARLIER),
				"If-None-Match", List.of("bad"))), Precondition.IF_UNMODIFIED_SINCE, Reason.CONDITION_FALSE);
		assertFailure(evaluate(HttpMethod.PUT, Map.of(
				"If-Match", List.of("bad"),
				"If-Unmodified-Since", List.of(DATE))), Precondition.IF_MATCH, Reason.MALFORMED);
		assertNotModified(evaluate(HttpMethod.HEAD, Map.of(
				"If-None-Match", List.of("W/\"v1\""),
				"If-Modified-Since", List.of(EARLIER))), Precondition.IF_NONE_MATCH);
	}

	@Test
	void missingRepresentationIsDistinctFromUnavailableValidators() {
		for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT)) {
			Request match = request(method, Map.of("If-Match", List.of("*")));
			assertProceed(ConditionalRequests.evaluate(match, null, null), Precondition.IF_MATCH);
			assertFailure(ConditionalRequests.evaluateWithoutRepresentation(match),
					Precondition.IF_MATCH, Reason.CONDITION_FALSE);
			Request none = request(method, Map.of("If-None-Match", List.of("*")));
			assertProceed(ConditionalRequests.evaluateWithoutRepresentation(none), Precondition.IF_NONE_MATCH);
			if (method == HttpMethod.PUT)
				assertFailure(ConditionalRequests.evaluate(none, null, null), Precondition.IF_NONE_MATCH, Reason.CONDITION_FALSE);
			else
				assertNotModified(ConditionalRequests.evaluate(none, null, null), Precondition.IF_NONE_MATCH);
		}
		assertProceed(ConditionalRequests.evaluateWithoutRepresentation(request(HttpMethod.PUT, Map.of(
				"If-Unmodified-Since", List.of(EARLIER), "If-Modified-Since", List.of(DATE)))));
		assertProceed(ConditionalRequests.evaluateWithoutRepresentation(request(HttpMethod.GET,
				Map.of("If-None-Match", List.of("\"v1\"")))), Precondition.IF_NONE_MATCH);
		assertFailure(ConditionalRequests.evaluateWithoutRepresentation(request(HttpMethod.PUT,
				Map.of("If-Match", List.of("\"v1\"")))), Precondition.IF_MATCH, Reason.CONDITION_FALSE);
	}

	@Test
	void weakAndUnavailableCurrentTagsStillEvaluateTagConditions() {
		for (EntityTag current : new EntityTag[]{null, EntityTag.fromWeakValue("v1")}) {
			for (String value : List.of("\"v1\"", "W/\"v1\"", ""))
				assertFailure(ConditionalRequests.evaluate(request(HttpMethod.PUT,
						Map.of("If-Match", List.of(value))), current, MODIFIED), Precondition.IF_MATCH, Reason.CONDITION_FALSE);
			assertProceed(ConditionalRequests.evaluate(request(HttpMethod.PUT,
					Map.of("If-Match", List.of("*"))), current, MODIFIED), Precondition.IF_MATCH);
		}
		assertProceed(ConditionalRequests.evaluate(request(HttpMethod.PUT,
				Map.of("If-None-Match", List.of("\"v1\""))), null, MODIFIED), Precondition.IF_NONE_MATCH);
		assertFailure(evaluate(HttpMethod.PUT, Map.of("If-Match", List.of("W/\"v1\""))),
				Precondition.IF_MATCH, Reason.CONDITION_FALSE);
		assertProceed(evaluate(HttpMethod.PUT, Map.of("If-Match", List.of("W/\"v1\", \"v1\""))),
				Precondition.IF_MATCH);
	}

	@Test
	void optionsIgnoresAllConditionalFieldsBeforeParsing() {
		for (Map<String, List<String>> headers : List.of(
				Map.<String, List<String>>of(),
				Map.of("If-Match", List.of("\"other\""), "If-None-Match", List.of("*")),
				Map.of("If-Match", List.of("bad"), "If-None-Match", List.of(overLimit()),
						"If-Unmodified-Since", List.of(EARLIER), "If-Modified-Since", List.of(DATE)))) {
			Request options = request(HttpMethod.OPTIONS, headers);
			assertProceed(ConditionalRequests.evaluate(options, TAG, MODIFIED));
			assertProceed(ConditionalRequests.evaluateWithoutRepresentation(options));
			assertTrue(ConditionalRequests.responseFor(options, TAG, MODIFIED).isEmpty());
		}
	}

	@Test
	void directFileOptionsAlsoIgnoresConditionalFields(@TempDir Path root) throws Exception {
		Path file = Files.writeString(root.resolve("options.txt"), "options representation");
		for (Map<String, List<String>> headers : List.of(
				Map.of("If-None-Match", List.of("*")),
				Map.of("If-Match", List.of("bad"), "If-None-Match", List.of(overLimit())))) {
			MarshaledResponse response = MarshaledResponse.withFile(file, request(HttpMethod.OPTIONS, headers))
					.entityTag(TAG).lastModified(MODIFIED).build();
			try {
				assertEquals(200, response.getStatusCode());
				assertEquals(Files.size(file), response.getBodyLength().longValue());
				assertEquals(List.of("\"v1\""), response.getHeaders().get("ETag"));
			} finally {
				response.close();
			}
		}
	}

	@Test
	void repeatedFieldsQuotedCommasAndBoundedEmptyElementsShareOneParser() {
		Map<String, List<String>> headers = Map.of("If-Match", List.of("\"other,tag\",,", "W/\"v1\", \"v1\""));
		assertProceed(evaluate(HttpMethod.PUT, headers), Precondition.IF_MATCH);
		Request physical = Request.withRawUrl(HttpMethod.PUT, "/evaluation")
				.microhttpHeaders(List.of(new Header("If-Match", "\"other,tag\",,"),
						new Header("if-match", "W/\"v1\", \"v1\""))).build();
		assertProceed(ConditionalRequests.evaluate(physical, TAG, MODIFIED), Precondition.IF_MATCH);
		assertNotModified(evaluate(HttpMethod.GET,
				Map.of("If-None-Match", List.of("\"other,tag\"", "W/\"v1\""))), Precondition.IF_NONE_MATCH);
		assertProceed(evaluate(HttpMethod.PUT, Map.of("If-None-Match", List.of(",".repeat(255)))),
				Precondition.IF_NONE_MATCH);
		assertFailure(evaluate(HttpMethod.PUT, Map.of("If-Match", List.of(" , , "))),
				Precondition.IF_MATCH, Reason.CONDITION_FALSE);
	}

	@Test
	void parserLimitCountsEmptyElementsAndPreservesMixedFailurePriority() {
		assertProceed(evaluate(HttpMethod.PUT, Map.of("If-Match", List.of("\"other\",".repeat(255) + "\"v1\""))),
				Precondition.IF_MATCH);
		for (String value : List.of(overLimit(), ",".repeat(256),
				"bad," + "\"other\",".repeat(299) + "\"v1\"",
				"\"other\",".repeat(256) + "\"unclosed"))
			assertFailure(evaluate(HttpMethod.PUT, Map.of("If-Match", List.of(value))),
					Precondition.IF_MATCH, Reason.ENTITY_TAG_LIST_LIMIT_EXCEEDED);
		for (String value : List.of("\"unclosed" + ",bad".repeat(300), "*, \"v1\"", "bad", "\"v1\", bad"))
			assertFailure(evaluate(HttpMethod.PUT, Map.of("If-Match", List.of(value))),
					Precondition.IF_MATCH, Reason.MALFORMED);
	}

	@Test
	void malformedReadCacheConditionSuppressesDateFallbackWithoutClaimingSatisfaction() {
		for (String value : List.of("bad", overLimit(), "\"unclosed")) {
			Map<String, List<String>> headers = Map.of("If-None-Match", List.of(value), "If-Modified-Since", List.of(DATE));
			for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
				assertProceed(evaluate(method, headers));
				assertTrue(ConditionalRequests.responseFor(request(method, headers), TAG, MODIFIED).isEmpty());
			}
			assertFailure(evaluate(HttpMethod.PUT, headers), Precondition.IF_NONE_MATCH,
					value.equals(overLimit()) ? Reason.ENTITY_TAG_LIST_LIMIT_EXCEEDED : Reason.MALFORMED);
		}
	}

	@Test
	void datesHaveWholeSecondPrecisionAndIgnoredDatesDoNotSatisfyPolicy() {
		assertProceed(ConditionalRequests.evaluate(request(HttpMethod.PUT,
				Map.of("If-Unmodified-Since", List.of(DATE))), TAG,
				Instant.parse("2026-05-04T01:02:03.999Z")), Precondition.IF_UNMODIFIED_SINCE);
		assertNotModified(evaluate(HttpMethod.GET, Map.of("If-Modified-Since", List.of(DATE))), Precondition.IF_MODIFIED_SINCE);
		assertFailure(evaluate(HttpMethod.DELETE, Map.of("If-Unmodified-Since", List.of(EARLIER))),
				Precondition.IF_UNMODIFIED_SINCE, Reason.CONDITION_FALSE);
		for (String date : List.of("invalid", DATE)) {
			Proceed ignored = assertProceed(ConditionalRequests.evaluate(request(HttpMethod.PUT,
					Map.of("If-Unmodified-Since", List.of(date))), TAG, null));
			assertFalse(hasEffectiveWriteCondition(ignored));
		}
		assertFalse(hasEffectiveWriteCondition(assertProceed(evaluate(HttpMethod.PUT,
				Map.of("If-Unmodified-Since", List.of("invalid"))))));
		assertTrue(hasEffectiveWriteCondition(assertProceed(evaluate(HttpMethod.PUT,
				Map.of("If-Unmodified-Since", List.of(DATE))), Precondition.IF_UNMODIFIED_SINCE)));
	}

	@Test
	void convenienceResponsesRemainBodylessAndPreserveValidatorAndCacheMetadata() {
		Map<String, List<String>> metadata = Map.of("Cache-Control", List.of("private, max-age=60"),
				"Vary", List.of("Accept-Language"));
		for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT)) {
			Request request = request(method, Map.of("If-None-Match", List.of("W/\"v1\"")));
			Response response = ConditionalRequests.responseFor(request, TAG, MODIFIED, metadata).orElseThrow();
			assertEquals(method == HttpMethod.PUT ? 412 : 304, response.getStatusCode());
			assertTrue(response.getBody().isEmpty());
			assertEquals(List.of("\"v1\""), response.getHeaders().get("ETag"));
			assertEquals(List.of(DATE), response.getHeaders().get("Last-Modified"));
			assertEquals(metadata.get("Vary"), response.getHeaders().get("Vary"));
			assertEquals(metadata.get("Cache-Control"), response.getHeaders().get("Cache-Control"));
			assertFalse(response.getHeaders().containsKey("Content-Type"));
			assertFalse(response.getHeaders().containsKey("Content-Length"));
		}
	}

	@Test
	void resultsHaveDefensiveValueSemanticsAndControlledConstruction() {
		Set<Precondition> source = EnumSet.of(Precondition.IF_NONE_MATCH, Precondition.IF_MATCH);
		Proceed first = new Proceed(source);
		source.clear();
		Proceed second = new Proceed(Set.of(Precondition.IF_MATCH, Precondition.IF_NONE_MATCH));
		assertEquals(second, first);
		assertEquals(second.hashCode(), first.hashCode());
		assertEquals(second.toString(), first.toString());
		assertTrue(first.toString().indexOf("IF_MATCH") < first.toString().indexOf("IF_NONE_MATCH"));
		assertThrows(UnsupportedOperationException.class, () -> first.getSatisfiedPreconditions().clear());
		assertEquals(new NotModified(Precondition.IF_NONE_MATCH), new NotModified(Precondition.IF_NONE_MATCH));
		assertNotEquals(new NotModified(Precondition.IF_NONE_MATCH), new NotModified(Precondition.IF_MODIFIED_SINCE));
		assertEquals(new PreconditionFailed(Precondition.IF_MATCH, Reason.MALFORMED),
				new PreconditionFailed(Precondition.IF_MATCH, Reason.MALFORMED));
		assertNotEquals(new PreconditionFailed(Precondition.IF_MATCH, Reason.MALFORMED),
				new PreconditionFailed(Precondition.IF_MATCH, Reason.CONDITION_FALSE));
		assertEquals(0, Proceed.class.getConstructors().length);
		assertEquals(0, NotModified.class.getConstructors().length);
		assertEquals(0, PreconditionFailed.class.getConstructors().length);
		assertThrows(IllegalArgumentException.class, () -> new Proceed(Set.of(Precondition.IF_MATCH, Precondition.IF_UNMODIFIED_SINCE)));
		assertThrows(IllegalArgumentException.class, () -> new Proceed(Set.of(Precondition.IF_NONE_MATCH, Precondition.IF_MODIFIED_SINCE)));
		assertThrows(IllegalArgumentException.class, () -> new NotModified(Precondition.IF_MATCH));
		assertThrows(IllegalArgumentException.class, () -> new PreconditionFailed(Precondition.IF_MODIFIED_SINCE, Reason.CONDITION_FALSE));
		assertThrows(IllegalArgumentException.class, () -> new PreconditionFailed(Precondition.IF_UNMODIFIED_SINCE, Reason.MALFORMED));
		assertThrows(IllegalArgumentException.class, () -> new PreconditionFailed(Precondition.IF_UNMODIFIED_SINCE, Reason.ENTITY_TAG_LIST_LIMIT_EXCEEDED));
		assertThrows(NullPointerException.class, () -> new Proceed(null));
		assertThrows(NullPointerException.class, () -> new NotModified(null));
		assertThrows(NullPointerException.class, () -> new PreconditionFailed(Precondition.IF_MATCH, null));
	}

	@Test
	void preconditionsNameTheRequestHeadersTheEvaluatorReads() {
		assertEquals("If-Match", Precondition.IF_MATCH.getHeaderName());
		assertEquals("If-Unmodified-Since", Precondition.IF_UNMODIFIED_SINCE.getHeaderName());
		assertEquals("If-None-Match", Precondition.IF_NONE_MATCH.getHeaderName());
		assertEquals("If-Modified-Since", Precondition.IF_MODIFIED_SINCE.getHeaderName());

		Map<Precondition, String> satisfyingValues = Map.of(
				Precondition.IF_MATCH, "\"v1\"",
				Precondition.IF_UNMODIFIED_SINCE, DATE,
				Precondition.IF_NONE_MATCH, "\"other\"",
				Precondition.IF_MODIFIED_SINCE, EARLIER);
		for (Precondition precondition : Precondition.values())
			assertProceed(evaluate(HttpMethod.GET, Map.of(precondition.getHeaderName(),
					List.of(satisfyingValues.get(precondition)))), precondition);
	}

	private static boolean hasEffectiveWriteCondition(Proceed result) {
		return result.getSatisfiedPreconditions().contains(Precondition.IF_MATCH)
				|| result.getSatisfiedPreconditions().contains(Precondition.IF_UNMODIFIED_SINCE);
	}

	private static ConditionalRequestResult evaluate(HttpMethod method, Map<String, List<String>> headers) {
		return ConditionalRequests.evaluate(request(method, headers), TAG, MODIFIED);
	}

	private static Request request(HttpMethod method, Map<String, List<String>> headers) {
		return Request.withPath(method, "/evaluation").headers(headers).build();
	}

	private static String overLimit() {
		return "\"other\",".repeat(256) + "\"v1\"";
	}

	private static Proceed assertProceed(ConditionalRequestResult result, Precondition... conditions) {
		Proceed proceed = assertInstanceOf(Proceed.class, result);
		assertEquals(Set.of(conditions), proceed.getSatisfiedPreconditions());
		return proceed;
	}

	private static void assertFailure(ConditionalRequestResult result, Precondition condition, Reason reason) {
		PreconditionFailed failure = assertInstanceOf(PreconditionFailed.class, result);
		assertEquals(condition, failure.getPrecondition());
		assertEquals(reason, failure.getReason());
	}

	private static void assertNotModified(ConditionalRequestResult result, Precondition condition) {
		assertEquals(condition, assertInstanceOf(NotModified.class, result).getPrecondition());
	}
}

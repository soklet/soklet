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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link MediaRange} parsing and {@link Utilities#extractMediaRangesFromAcceptHeaderValue(String)} ordering.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class MediaRangeTests {
	@Test
	public void parsesBasicMediaRange() {
		MediaRange mediaRange = MediaRange.fromHeaderRepresentation("text/html").orElseThrow();

		Assertions.assertEquals("text", mediaRange.getType());
		Assertions.assertEquals("html", mediaRange.getSubtype());
		Assertions.assertEquals(0, BigDecimal.ONE.compareTo(mediaRange.getQuality()));
		Assertions.assertTrue(mediaRange.getParameters().isEmpty());
		Assertions.assertFalse(mediaRange.isWildcardType());
		Assertions.assertFalse(mediaRange.isWildcardSubtype());
	}

	@Test
	public void parsesQualityAndParameters() {
		MediaRange mediaRange = MediaRange.fromHeaderRepresentation("text/html;level=1;q=0.7;profile=compact").orElseThrow();

		Assertions.assertEquals(0, new BigDecimal("0.7").compareTo(mediaRange.getQuality()));
		// RFC 9110: all non-"q" parameters are media-type parameters, before OR after "q"
		// (the older RFC 7231 accept-ext grammar was removed)
		Assertions.assertEquals(Map.of("level", "1", "profile", "compact"), mediaRange.getParameters());
	}

	@Test
	public void normalizesCaseAndUnquotesParameterValues() {
		MediaRange mediaRange = MediaRange.fromHeaderRepresentation("TEXT/HTML;LEVEL=\"1\";Q=0.5").orElseThrow();

		Assertions.assertEquals("text", mediaRange.getType());
		Assertions.assertEquals("html", mediaRange.getSubtype());
		Assertions.assertEquals(Map.of("level", "1"), mediaRange.getParameters());
		Assertions.assertEquals(0, new BigDecimal("0.5").compareTo(mediaRange.getQuality()));
	}

	@Test
	public void parsesWildcards() {
		MediaRange fullWildcard = MediaRange.fromHeaderRepresentation("*/*").orElseThrow();
		Assertions.assertTrue(fullWildcard.isWildcardType());
		Assertions.assertTrue(fullWildcard.isWildcardSubtype());

		MediaRange subtypeWildcard = MediaRange.fromHeaderRepresentation("text/*;q=0.3").orElseThrow();
		Assertions.assertFalse(subtypeWildcard.isWildcardType());
		Assertions.assertTrue(subtypeWildcard.isWildcardSubtype());
	}

	@Test
	public void rejectsMalformedMediaRanges() {
		// No type/subtype structure
		Assertions.assertTrue(MediaRange.fromHeaderRepresentation("text").isEmpty());
		// Wildcard type with concrete subtype
		Assertions.assertTrue(MediaRange.fromHeaderRepresentation("*/html").isEmpty());
		// Empty components
		Assertions.assertTrue(MediaRange.fromHeaderRepresentation("/html").isEmpty());
		Assertions.assertTrue(MediaRange.fromHeaderRepresentation("text/").isEmpty());
		// Unparseable q
		Assertions.assertTrue(MediaRange.fromHeaderRepresentation("text/html;q=abc").isEmpty());
		// Missing/blank input
		Assertions.assertTrue(MediaRange.fromHeaderRepresentation(null).isEmpty());
		Assertions.assertTrue(MediaRange.fromHeaderRepresentation("   ").isEmpty());
	}

	@Test
	public void firstQualityParameterWins() {
		// RFC 9110: if multiple q parameters are present, all but the first are ignored
		MediaRange mediaRange = MediaRange.fromHeaderRepresentation("text/html;q=0.5;q=0.7").orElseThrow();
		Assertions.assertEquals(0, new BigDecimal("0.5").compareTo(mediaRange.getQuality()));

		// A malformed later q is also ignored rather than invalidating the media range
		MediaRange withMalformedLaterQuality = MediaRange.fromHeaderRepresentation("text/html;q=0.5;q=abc").orElseThrow();
		Assertions.assertEquals(0, new BigDecimal("0.5").compareTo(withMalformedLaterQuality.getQuality()));
	}

	@Test
	public void unescapesQuotedPairSequencesInParameterValues() {
		// RFC 9110 quoted-pair: \" inside a quoted string is a literal quote, \\ a literal backslash
		MediaRange mediaRange = MediaRange.fromHeaderRepresentation("text/html;note=\"a\\\"b\\\\c\"").orElseThrow();
		Assertions.assertEquals("a\"b\\c", mediaRange.getParameters().get("note"));
	}

	@Test
	public void preservesQuotedSemicolonsInParameterValues() {
		MediaRange mediaRange = MediaRange.fromHeaderRepresentation("text/html;note=\"a;b\";q=0.7").orElseThrow();

		Assertions.assertEquals("a;b", mediaRange.getParameters().get("note"));
		Assertions.assertEquals(0, new BigDecimal("0.7").compareTo(mediaRange.getQuality()));
	}

	@Test
	public void rejectsInvalidTypeSubtypeAndParameterNameTokens() {
		Assertions.assertTrue(MediaRange.fromHeaderRepresentation("text/html/json").isEmpty());
		Assertions.assertTrue(MediaRange.fromHeaderRepresentation("te xt/html").isEmpty());
		Assertions.assertTrue(MediaRange.fromHeaderRepresentation("text/h@tml").isEmpty());
		Assertions.assertTrue(MediaRange.fromHeaderRepresentation("text/html;bad name=value").isEmpty());
	}

	@Test
	public void clampsOutOfRangeQuality() {
		Assertions.assertEquals(0, BigDecimal.ONE.compareTo(
				MediaRange.fromHeaderRepresentation("text/html;q=5").orElseThrow().getQuality()));
		Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(
				MediaRange.fromHeaderRepresentation("text/html;q=-1").orElseThrow().getQuality()));
	}

	@Test
	public void equalityIsQualityScaleInsensitive() {
		MediaRange a = MediaRange.fromHeaderRepresentation("text/html;q=0.5").orElseThrow();
		MediaRange b = MediaRange.fromHeaderRepresentation("text/html;q=0.50").orElseThrow();

		Assertions.assertEquals(a, b);
		Assertions.assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	public void extractOrdersByQualityThenSpecificity() {
		List<MediaRange> mediaRanges = Utilities.extractMediaRangesFromAcceptHeaderValue(
				"text/*;q=0.5, */*;q=0.1, application/json;q=0.5, text/html");

		List<String> ordered = mediaRanges.stream()
				.map(mediaRange -> mediaRange.getType() + "/" + mediaRange.getSubtype())
				.toList();

		// text/html (q=1) first; at q=0.5 the concrete application/json outranks text/*; */* (q=0.1) last
		Assertions.assertEquals(List.of("text/html", "application/json", "text/*", "*/*"), ordered);
	}

	@Test
	public void extractOrdersEqualQualityByParameterSpecificity() {
		List<MediaRange> mediaRanges = Utilities.extractMediaRangesFromAcceptHeaderValue(
				"text/html, text/html;level=1, text/html;level=1;profile=compact");

		List<Map<String, String>> orderedParameters = mediaRanges.stream()
				.map(MediaRange::getParameters)
				.toList();

		Assertions.assertEquals(List.of(
				Map.of("level", "1", "profile", "compact"),
				Map.of("level", "1"),
				Map.of()), orderedParameters);
	}

	@Test
	public void extractIsStableForEqualQualityAndSpecificity() {
		List<MediaRange> mediaRanges = Utilities.extractMediaRangesFromAcceptHeaderValue(
				"application/json, text/html, application/xml");

		List<String> ordered = mediaRanges.stream()
				.map(mediaRange -> mediaRange.getType() + "/" + mediaRange.getSubtype())
				.toList();

		Assertions.assertEquals(List.of("application/json", "text/html", "application/xml"), ordered);
	}

	@Test
	public void extractSkipsMalformedFragmentsAndHonorsQuotedCommas() {
		List<MediaRange> mediaRanges = Utilities.extractMediaRangesFromAcceptHeaderValue(
				"text/html;note=\"a,b\", garbage, application/json;q=0.2");

		Assertions.assertEquals(2, mediaRanges.size());
		Assertions.assertEquals("html", mediaRanges.get(0).getSubtype());
		// The quoted comma must not split the first media range
		Assertions.assertEquals("a,b", mediaRanges.get(0).getParameters().get("note"));
		Assertions.assertEquals("json", mediaRanges.get(1).getSubtype());
	}

	@Test
	public void extractReturnsEmptyListForEmptyValue() {
		Assertions.assertTrue(Utilities.extractMediaRangesFromAcceptHeaderValue("").isEmpty());
	}
}

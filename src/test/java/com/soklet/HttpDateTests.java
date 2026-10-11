package com.soklet;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class HttpDateTests {
	private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
	private static final DateTimeFormatter RFC_850 = DateTimeFormatter
			.ofPattern("EEEE, dd-MMM-yy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter SHORT_LEGACY = DateTimeFormatter
			.ofPattern("EEE, dd MMM yy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

	@Test
	void obsoleteDatesResolveTheirCenturyBeforeValidatingTheWeekday() {
		for (DateTimeFormatter format : List.of(RFC_850, SHORT_LEGACY)) {
			assertEquals(NOW, HttpDate.fromHeaderValue(format.format(NOW), NOW).orElseThrow());
			Instant historical = Instant.parse("1994-11-06T08:49:37Z");
			assertEquals(historical, HttpDate.fromHeaderValue(format.format(historical), NOW).orElseThrow());
			assertTrue(HttpDate.fromHeaderValue(format.format(Instant.parse("1926-10-11T12:00:00Z")), NOW).isEmpty(),
					"A matching weekday in the wrong century must not select that century.");
		}
	}

	@Test
	void fiftyYearBoundaryUsesTheWholeTimestampAndAllowsEquality() {
		Instant boundary = Instant.parse("2076-10-11T12:00:00Z");
		Instant previousCentury = Instant.parse("1976-10-11T12:00:01Z");
		for (DateTimeFormatter format : List.of(RFC_850, SHORT_LEGACY)) {
			assertEquals(boundary, HttpDate.fromHeaderValue(format.format(boundary), NOW).orElseThrow());
			assertEquals(boundary.minusSeconds(1),
					HttpDate.fromHeaderValue(format.format(boundary.minusSeconds(1)), NOW).orElseThrow());
			assertEquals(previousCentury,
					HttpDate.fromHeaderValue(format.format(previousCentury), NOW).orElseThrow());
			assertTrue(HttpDate.fromHeaderValue(format.format(boundary.plusSeconds(1)), NOW).isEmpty(),
					"Beyond the cutoff, the weekday must match the previous century.");
		}
	}

	@Test
	void centuryWindowMovesAcrossCenturyBoundaries() {
		Instant past = Instant.parse("2026-10-11T12:00:00Z");
		Instant future = Instant.parse("2126-10-11T12:00:00Z");
		for (DateTimeFormatter format : List.of(RFC_850, SHORT_LEGACY)) {
			assertEquals(past, HttpDate.fromHeaderValue(format.format(past),
					Instant.parse("2030-01-01T00:00:00Z")).orElseThrow());
			assertEquals(future, HttpDate.fromHeaderValue(format.format(future),
					Instant.parse("2080-01-01T00:00:00Z")).orElseThrow());
		}
	}

	@Test
	void centurySelectionPreservesLeapDaysWithoutNormalizingInvalidDates() {
		Instant leapDay = Instant.parse("2000-02-29T12:00:00Z");
		for (DateTimeFormatter format : List.of(RFC_850, SHORT_LEGACY)) {
			assertEquals(leapDay, HttpDate.fromHeaderValue(format.format(leapDay),
					Instant.parse("2050-02-01T00:00:00Z")).orElseThrow());
			assertTrue(HttpDate.fromHeaderValue(format.format(leapDay),
					Instant.parse("2060-01-01T00:00:00Z")).isEmpty(),
					"2100 is not a leap year, and must not normalize February 29 to February 28.");
		}
	}

	@Test
	void allFormatsRejectInvalidCalendarTimeAndWeekdayFields() {
		for (String invalid : List.of("Monday, 11-Oct-26 12:00:00 GMT",
				"Tuesday, 31-Feb-26 12:00:00 GMT", "Sunday, 11-Oct-26 24:00:00 GMT",
				"Sunday, 11-Oct-26 12:60:00 GMT", "Sunday, 11-Oct-26 12:00:60 GMT",
				"Sunday, 11-Oct-26 12:00:00 GMT trailing", "Mon, 11 Oct 26 12:00:00 GMT",
				"Tue, 31 Feb 2026 12:00:00 GMT", "Tue Feb 31 12:00:00 2026"))
			assertTrue(HttpDate.fromHeaderValue(invalid, NOW).isEmpty(), invalid);
	}

	@Test
	void preferredAndAsctimeFormatsKeepFourDigitYears() {
		assertEquals(NOW, HttpDate.fromHeaderValue("Sun, 11 Oct 2026 12:00:00 GMT", NOW).orElseThrow());
		assertEquals(NOW, HttpDate.fromHeaderValue("Sun Oct 11 12:00:00 2026", NOW).orElseThrow());
		Instant early = Instant.parse("0001-01-01T00:00:00Z");
		assertEquals(early, HttpDate.fromHeaderValue(HttpDate.toHeaderValue(early), NOW).orElseThrow());
		assertTrue(HttpDate.fromHeaderValue(null, NOW).isEmpty());
		assertTrue(HttpDate.fromHeaderValue("  ", NOW).isEmpty());
		assertTrue(HttpDate.fromHeaderValue("not a date", NOW).isEmpty());
	}

	@Test
	void existingValidDateSyntaxRemainsSupported() {
		Instant expected = Instant.parse("2026-05-04T01:02:03Z");
		for (String value : List.of("Mon, 4 May 2026 01:02:03 GMT",
				"Mon, 04 May 2026 01:02:03 +0000", "Monday, 04-May-26 01:02:03 UTC",
				"Mon, 04 May 26 01:02:03 UTC", "Mon May  4 01:02:03 2026",
				"Mon May 4 01:02:03 2026"))
			assertEquals(expected, HttpDate.fromHeaderValue(value, NOW).orElseThrow(), value);
	}

	@Test
	void obsoleteIfUnmodifiedSinceStillRejectsAStaleMutation() {
		Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		Request request = Request.withPath(HttpMethod.PUT, "/resource")
				.headers(java.util.Map.of("If-Unmodified-Since", List.of(RFC_850.format(now))))
				.build();
		assertEquals(412, ConditionalRequests.responseFor(request, null, now.plusSeconds(1))
				.orElseThrow().getStatusCode());
	}
}

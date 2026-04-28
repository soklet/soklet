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

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class TraceContextTests {
	private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

	@Test
	public void traceparentParsesW3cExample() {
		TraceContext traceContext = TraceContext.fromHeaderValues(Set.of(TRACEPARENT), List.of("congo=t61rcWkgMzE")).orElseThrow();

		Assertions.assertEquals("0af7651916cd43dd8448eb211c80319c", traceContext.getTraceId());
		Assertions.assertEquals("b7ad6b7169203331", traceContext.getParentId());
		Assertions.assertEquals(Integer.valueOf(1), traceContext.getTraceFlags());
		Assertions.assertTrue(traceContext.isSampled());
		Assertions.assertEquals(TRACEPARENT, traceContext.toTraceparentHeaderValue());
		Assertions.assertEquals("congo=t61rcWkgMzE", traceContext.toTracestateHeaderValue().orElse(null));
	}

	@Test
	public void traceparentRejectsMalformedValues() {
		Assertions.assertTrue(TraceContext.fromHeaderValues(null, null).isEmpty());
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of(), null).isEmpty());
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of(""), null).isEmpty());
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of("  " + TRACEPARENT), null).isEmpty());
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of(TRACEPARENT.toUpperCase()), null).isEmpty());
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of("ff-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"), null).isEmpty());
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of("00-00000000000000000000000000000000-b7ad6b7169203331-01"), null).isEmpty());
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of("00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01"), null).isEmpty());
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-0g"), null).isEmpty());
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of(TRACEPARENT + "-extra"), null).isEmpty());
	}

	@Test
	public void duplicateTraceparentValuesAreMalformed() {
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of(TRACEPARENT, TRACEPARENT), null).isEmpty());
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of("bad", TRACEPARENT), null).isEmpty());
	}

	@Test
	public void higherVersionTraceparentParsesKnownFieldsAndReemitsVersion00() {
		TraceContext traceContext = TraceContext.fromHeaderValues(
				List.of("01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-09-future"),
				null).orElseThrow();
		TraceContext noExtensionTraceContext = TraceContext.fromHeaderValues(
				List.of("01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"),
				null).orElseThrow();
		TraceContext unsampledWithOtherBitsTraceContext = TraceContext.fromHeaderValues(
				List.of("01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-08-future"),
				null).orElseThrow();

		Assertions.assertEquals("0af7651916cd43dd8448eb211c80319c", traceContext.getTraceId());
		Assertions.assertEquals("b7ad6b7169203331", traceContext.getParentId());
		Assertions.assertEquals(Integer.valueOf(1), traceContext.getTraceFlags());
		Assertions.assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", traceContext.toTraceparentHeaderValue());
		Assertions.assertEquals(Integer.valueOf(1), noExtensionTraceContext.getTraceFlags());
		Assertions.assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", noExtensionTraceContext.toTraceparentHeaderValue());
		Assertions.assertEquals(Integer.valueOf(0), unsampledWithOtherBitsTraceContext.getTraceFlags());
		Assertions.assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00", unsampledWithOtherBitsTraceContext.toTraceparentHeaderValue());
		Assertions.assertTrue(TraceContext.fromHeaderValues(
				List.of("01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-09future"),
				null).isEmpty());
	}

	@Test
	public void sampledFlagMasksBitZero() {
		TraceContext unsampled = TraceContext.fromHeaderValues(
				List.of("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00"),
				null).orElseThrow();
		TraceContext sampled = TraceContext.fromHeaderValues(
				List.of("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"),
				null).orElseThrow();
		TraceContext sampledWithOtherBits = TraceContext.fromHeaderValues(
				List.of("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-09"),
				null).orElseThrow();

		Assertions.assertFalse(unsampled.isSampled());
		Assertions.assertTrue(sampled.isSampled());
		Assertions.assertTrue(sampledWithOtherBits.isSampled());
		Assertions.assertEquals(Integer.valueOf(9), sampledWithOtherBits.getTraceFlags());
	}

	@Test
	public void tracestateCombinesNormalizesAndDropsInvalidMembers() {
		TraceContext traceContext = TraceContext.fromHeaderValues(Set.of(TRACEPARENT),
				List.of(" rojo=00f067aa0ba902b7 , ,bad,key=value=bad ",
						"congo=t61rcWkgMzE,rojo=duplicate")).orElseThrow();

		Assertions.assertEquals(List.of(
				TraceStateEntry.fromKeyAndValue("rojo", "00f067aa0ba902b7"),
				TraceStateEntry.fromKeyAndValue("congo", "t61rcWkgMzE")), traceContext.getTraceStateEntries());
		Assertions.assertEquals("rojo=00f067aa0ba902b7,congo=t61rcWkgMzE", traceContext.toTracestateHeaderValue().orElse(null));
	}

	@Test
	public void tracestateWithoutValidTraceparentIsIgnored() {
		Assertions.assertTrue(TraceContext.fromHeaderValues(null, List.of("rojo=00f067aa0ba902b7")).isEmpty());
		Assertions.assertTrue(TraceContext.fromHeaderValues(List.of("bad"), List.of("rojo=00f067aa0ba902b7")).isEmpty());
	}

	@Test
	public void tracestateWithOnlyEmptyOrMalformedMembersIsEmpty() {
		TraceContext traceContext = TraceContext.fromHeaderValues(Set.of(TRACEPARENT),
				List.of(" , ,bad,key=value=bad,UPPER=value")).orElseThrow();

		Assertions.assertEquals(List.of(), traceContext.getTraceStateEntries());
		Assertions.assertTrue(traceContext.toTracestateHeaderValue().isEmpty());
	}

	@Test
	public void tracestateTruncatesToLimits() {
		List<String> members = new ArrayList<>();
		for (int i = 0; i < 34; i++)
			members.add("v" + i + "=a");

		TraceContext entryLimitedTraceContext = TraceContext.fromHeaderValues(Set.of(TRACEPARENT),
				List.of(String.join(",", members))).orElseThrow();

		Assertions.assertEquals(32, entryLimitedTraceContext.getTraceStateEntries().size());
		Assertions.assertEquals("v31", entryLimitedTraceContext.getTraceStateEntries().get(31).getKey());

		String largeEntry = "large=" + "x".repeat(124);
		String retainedEntry0 = "s0=" + "x".repeat(124);
		String retainedEntry1 = "s1=" + "x".repeat(124);
		String retainedEntry2 = "s2=" + "x".repeat(124);
		String retainedEntry3 = "s3=" + "x".repeat(124);
		TraceContext lengthLimitedTraceContext = TraceContext.fromHeaderValues(Set.of(TRACEPARENT),
				List.of(String.join(",", largeEntry, retainedEntry0, retainedEntry1, retainedEntry2, retainedEntry3))).orElseThrow();

		Assertions.assertFalse(lengthLimitedTraceContext.getTraceStateEntries().stream().anyMatch(entry -> entry.getKey().equals("large")));
		Assertions.assertEquals("s0", lengthLimitedTraceContext.getTraceStateEntries().get(0).getKey());
		Assertions.assertTrue(lengthLimitedTraceContext.toTracestateHeaderValue().orElseThrow().length() <= 512);

		TraceContext minimallyTrimmedTraceContext = TraceContext.fromHeaderValues(Set.of(TRACEPARENT),
				List.of(String.join(",",
						"a0=" + "x".repeat(126),
						"a1=" + "x".repeat(126),
						"a2=" + "x".repeat(126),
						"a3=" + "x".repeat(126)))).orElseThrow();

		Assertions.assertEquals(List.of(
				TraceStateEntry.fromKeyAndValue("a0", "x".repeat(126)),
				TraceStateEntry.fromKeyAndValue("a1", "x".repeat(126)),
				TraceStateEntry.fromKeyAndValue("a2", "x".repeat(126))), minimallyTrimmedTraceContext.getTraceStateEntries());
		Assertions.assertTrue(minimallyTrimmedTraceContext.toTracestateHeaderValue().orElseThrow().length() <= 512);
	}

	@Test
	public void traceStateEntryParsesAndSerializesMembers() {
		TraceStateEntry entry = TraceStateEntry.fromMember(" rojo=00f067aa0ba902b7 ").orElseThrow();

		Assertions.assertEquals("rojo", entry.getKey());
		Assertions.assertEquals("00f067aa0ba902b7", entry.getValue());
		Assertions.assertEquals("rojo=00f067aa0ba902b7", entry.toHeaderMemberValue());
		Assertions.assertEquals(entry, TraceStateEntry.fromKeyAndValue("rojo", "00f067aa0ba902b7"));
		Assertions.assertEquals(entry.hashCode(), TraceStateEntry.fromKeyAndValue("rojo", "00f067aa0ba902b7").hashCode());
	}

	@Test
	public void traceStateEntryValidatesKeyGrammar() {
		Assertions.assertTrue(TraceStateEntry.fromMember("a=value").isPresent());
		Assertions.assertTrue(TraceStateEntry.fromMember("a_1-*/=value").isPresent());
		Assertions.assertTrue(TraceStateEntry.fromMember("tenant1@vendor=value").isPresent());
		Assertions.assertTrue(TraceStateEntry.fromMember("1@vendor=value").isPresent());
		Assertions.assertTrue(TraceStateEntry.fromMember("a".repeat(256) + "=value").isPresent());
		Assertions.assertTrue(TraceStateEntry.fromMember("t".repeat(241) + "@vendor=value").isPresent());
		Assertions.assertTrue(TraceStateEntry.fromMember("tenant@" + "v".repeat(14) + "=value").isPresent());

		Assertions.assertTrue(TraceStateEntry.fromMember("-a=value").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("1=value").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("A=value").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("=value").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("a".repeat(257) + "=value").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("@vendor=value").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("tenant@=value").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("tenant@Vendor=value").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("t".repeat(242) + "@vendor=value").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("tenant@" + "v".repeat(15) + "=value").isEmpty());
	}

	@Test
	public void traceStateEntryValidatesValueGrammar() {
		Assertions.assertTrue(TraceStateEntry.fromMember("key=v").isPresent());
		Assertions.assertTrue(TraceStateEntry.fromMember("key=a value").isPresent());
		Assertions.assertTrue(TraceStateEntry.fromMember("key=" + "x".repeat(256)).isPresent());
		Assertions.assertEquals("value", TraceStateEntry.fromMember("key=value ").orElseThrow().getValue());

		Assertions.assertTrue(TraceStateEntry.fromMember("key=").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("key=value,bad").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("key=value=bad").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("key=value\tbad").isEmpty());
		Assertions.assertTrue(TraceStateEntry.fromMember("key=" + "x".repeat(257)).isEmpty());

		Assertions.assertThrows(IllegalArgumentException.class, () -> TraceStateEntry.fromKeyAndValue("key", ""));
		Assertions.assertThrows(IllegalArgumentException.class, () -> TraceStateEntry.fromKeyAndValue("key", "value "));
		Assertions.assertThrows(IllegalArgumentException.class, () -> TraceStateEntry.fromKeyAndValue("key", "value,bad"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> TraceStateEntry.fromKeyAndValue("key", "value=bad"));
	}

	@Test
	public void toStringDoesNotExposeTraceStateValues() {
		TraceContext traceContext = TraceContext.fromHeaderValues(Set.of(TRACEPARENT),
				List.of("vendor=internal-account-token")).orElseThrow();
		TraceStateEntry traceStateEntry = TraceStateEntry.fromKeyAndValue("vendor", "internal-account-token");

		Assertions.assertFalse(traceContext.toString().contains("internal-account-token"));
		Assertions.assertTrue(traceContext.toString().contains("traceStateEntryCount=1"));
		Assertions.assertFalse(traceStateEntry.toString().contains("internal-account-token"));
		Assertions.assertTrue(traceStateEntry.toString().contains("valueLength=22"));
	}

	@Test
	public void traceContextValueEqualityUsesNormalizedState() {
		TraceContext first = TraceContext.fromHeaderValues(Set.of(TRACEPARENT), List.of("rojo=1,congo=2")).orElseThrow();
		TraceContext second = TraceContext.fromHeaderValues(Set.of(TRACEPARENT), List.of("rojo=1", "congo=2")).orElseThrow();

		Assertions.assertEquals(first, second);
		Assertions.assertEquals(first.hashCode(), second.hashCode());
	}
}

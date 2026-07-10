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

import com.code_intelligence.jazzer.junit.FuzzTest;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Fuzz target for {@code Accept} media range parsing and ordering.
 */
@ThreadSafe
public class MediaRangeFuzzTest {
	private static volatile int sink;

	@FuzzTest(maxDuration = "2m")
	public void mediaRangeParsingNeverThrows(byte[] input) {
		String headerValue = new String(input, StandardCharsets.UTF_8);

		// Lenient-parse contract: malformed input yields empty results, never exceptions.
		MediaRange.fromHeaderRepresentation(headerValue).ifPresent(MediaRangeFuzzTest::exercise);

		List<MediaRange> mediaRanges = Utilities.extractMediaRangesFromAcceptHeaderValue(headerValue);

		BigDecimal previousQuality = null;

		for (MediaRange mediaRange : mediaRanges) {
			exercise(mediaRange);

			// Ordering invariant: quality weights are non-increasing.
			if (previousQuality != null && mediaRange.getQuality().compareTo(previousQuality) > 0)
				throw new AssertionError("Media ranges out of quality order for input: " + headerValue);

			previousQuality = mediaRange.getQuality();
		}
	}

	private static void exercise(MediaRange mediaRange) {
		// Parsed-value invariants: quality clamped to [0, 1]; wildcard type implies wildcard subtype.
		if (mediaRange.getQuality().compareTo(BigDecimal.ZERO) < 0 || mediaRange.getQuality().compareTo(BigDecimal.ONE) > 0)
			throw new AssertionError("Quality out of range: " + mediaRange);

		if (mediaRange.isWildcardType() && !mediaRange.isWildcardSubtype())
			throw new AssertionError("Wildcard type with concrete subtype: " + mediaRange);

		sink += mediaRange.getType().length()
				+ mediaRange.getSubtype().length()
				+ mediaRange.getParameters().size()
				+ mediaRange.hashCode()
				+ mediaRange.toString().length();
	}
}

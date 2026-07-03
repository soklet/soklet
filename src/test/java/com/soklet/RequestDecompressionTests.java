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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * Tests for {@link RequestDecompressionPolicy} configuration and the gunzip decompression-bomb guards.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class RequestDecompressionTests {
	@Test
	public void gunzipRoundTrips() throws Exception {
		byte[] original = "hello gzip".getBytes(StandardCharsets.UTF_8);
		byte[] decompressed = DefaultHttpServer.gunzipRequestBody(gzip(original), 1_024L, 100L);

		Assertions.assertArrayEquals(original, decompressed);
	}

	@Test
	public void gunzipRejectsBodyOverAbsoluteLimit() throws Exception {
		byte[] compressed = gzip(new byte[10_000]);

		RequestBodyDecompressionException exception = Assertions.assertThrows(RequestBodyDecompressionException.class,
				() -> DefaultHttpServer.gunzipRequestBody(compressed, 1_024L, 1_000L));

		Assertions.assertEquals(RequestBodyDecompressionException.Reason.DECOMPRESSED_CONTENT_TOO_LARGE, exception.getReason());
		Assertions.assertEquals(413, exception.getReason().getStatusCode());
	}

	@Test
	public void gunzipRejectsBodyOverRatioLimit() throws Exception {
		// ~100KB of zeros compresses to a few hundred bytes; ratio 1 + 8KB allowance is far exceeded
		byte[] compressed = gzip(new byte[100_000]);

		RequestBodyDecompressionException exception = Assertions.assertThrows(RequestBodyDecompressionException.class,
				() -> DefaultHttpServer.gunzipRequestBody(compressed, 1_000_000L, 1L));

		Assertions.assertEquals(RequestBodyDecompressionException.Reason.DECOMPRESSED_CONTENT_TOO_LARGE, exception.getReason());
	}

	@Test
	public void gunzipSmallBodiesSurviveRatioLimitViaAllowance() throws Exception {
		// A tiny, highly-compressible body can exceed the raw ratio but stays under the 8KB additive allowance
		byte[] original = new byte[2_000];
		byte[] decompressed = DefaultHttpServer.gunzipRequestBody(gzip(original), 1_000_000L, 1L);

		Assertions.assertArrayEquals(original, decompressed);
	}

	@Test
	public void gunzipRejectsMalformedContent() {
		RequestBodyDecompressionException exception = Assertions.assertThrows(RequestBodyDecompressionException.class,
				() -> DefaultHttpServer.gunzipRequestBody("not gzip".getBytes(StandardCharsets.UTF_8), 1_024L, 100L));

		Assertions.assertEquals(RequestBodyDecompressionException.Reason.MALFORMED_CONTENT, exception.getReason());
		Assertions.assertEquals(400, exception.getReason().getStatusCode());
	}

	@Test
	public void policyDefaultsAndDisabledInstance() {
		Assertions.assertFalse(RequestDecompressionPolicy.disabledInstance().isEnabled());

		RequestDecompressionPolicy defaults = RequestDecompressionPolicy.fromDefaults();
		Assertions.assertTrue(defaults.isEnabled());
		Assertions.assertTrue(defaults.getMaximumDecompressedBodySizeInBytes().isEmpty());
		Assertions.assertEquals(100, defaults.getMaximumCompressionRatio());
	}

	@Test
	public void gunzipHandlesEmptyGzipMember() throws Exception {
		byte[] decompressed = DefaultHttpServer.gunzipRequestBody(gzip(new byte[0]), 1_024L, 100L);

		Assertions.assertEquals(0, decompressed.length);
	}

	@Test
	public void policyBuilderValidatesLimits() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> RequestDecompressionPolicy.builder().maximumDecompressedBodySizeInBytes(0).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> RequestDecompressionPolicy.builder().maximumCompressionRatio(-1).build());
		// Upper bound keeps ratio arithmetic safely within long range for any legal body size
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> RequestDecompressionPolicy.builder().maximumCompressionRatio(10_001).build());

		RequestDecompressionPolicy policy = RequestDecompressionPolicy.builder()
				.maximumDecompressedBodySizeInBytes(1_024)
				.maximumCompressionRatio(10)
				.build();

		Assertions.assertTrue(policy.isEnabled());
		Assertions.assertEquals(1_024, policy.getMaximumDecompressedBodySizeInBytes().orElseThrow());
		Assertions.assertEquals(10, policy.getMaximumCompressionRatio());
	}

	private static byte[] gzip(byte[] input) throws Exception {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
			gzipOutputStream.write(input);
		}
		return outputStream.toByteArray();
	}
}

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

package com.soklet.internal.microhttp;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Fuzz targets for the incremental HTTP/1.1 request parser.
 */
@ThreadSafe
public class RequestParserFuzzTest {
	private static final InetSocketAddress REMOTE_ADDRESS = new InetSocketAddress("127.0.0.1", 12345);
	private static final int MAX_REQUEST_SIZE = 4_096;
	private static final int MAX_HEADER_COUNT = 64;
	private static final int MAX_REQUEST_TARGET_LENGTH = 2_048;
	private static final int MAX_INCREMENTAL_CHUNKS = 256;
	private static final int MAX_COMPLETED_REQUESTS_PER_DRAIN = 16;

	private enum SplitStrategy {
		SINGLE_BUFFER,
		SINGLE_BYTE,
		INPUT_DERIVED
	}

	@FuzzTest(maxDuration = "2m")
	public void parseIncrementalRequestOnlyRejectsWithDeclaredExceptions(byte[] requestBytes) {
		for (SplitStrategy splitStrategy : SplitStrategy.values()) {
			ByteTokenizer tokenizer = new ByteTokenizer();
			RequestParser parser = new RequestParser(tokenizer, REMOTE_ADDRESS,
					MAX_REQUEST_SIZE, MAX_HEADER_COUNT, MAX_REQUEST_TARGET_LENGTH);

			try {
				feedIncrementally(tokenizer, parser, requestBytes, splitStrategy);
			} catch (ExpectationFailedException | MalformedRequestException | RequestTooLargeException expected) {
				// Invalid, oversized, and unsupported-expectation requests are expected.
				// Other RuntimeExceptions and Errors are fuzz findings.
			}
		}
	}

	@Test
	public void parseIncrementalRequestOnlyRejectsWithDeclaredExceptionsAllowsUnsupportedExpectation() {
		byte[] requestBytes = ("PT /ck HTTP/1.1\r\n"
				+ "Host: xa:0\r\n"
				+ "Expect:\r\n"
				+ "\r\n"
				+ "1G").getBytes(StandardCharsets.US_ASCII);
		parseIncrementalRequestOnlyRejectsWithDeclaredExceptions(requestBytes);
	}

	private static void feedIncrementally(ByteTokenizer tokenizer,
	                                      RequestParser parser,
	                                      byte[] requestBytes,
	                                      SplitStrategy splitStrategy) {
		int offset = 0;
		int chunks = 0;

		while (offset < requestBytes.length) {
			int remaining = requestBytes.length - offset;
			int chunkSize = chunkSize(requestBytes, remaining, chunks, splitStrategy);

			tokenizer.add(ByteBuffer.wrap(requestBytes, offset, chunkSize));
			drainCompletedRequests(tokenizer, parser);

			offset += chunkSize;
			chunks++;
		}

		drainCompletedRequests(tokenizer, parser);
	}

	private static int chunkSize(byte[] requestBytes, int remaining, int chunks, SplitStrategy splitStrategy) {
		if (splitStrategy == SplitStrategy.SINGLE_BUFFER)
			return remaining;

		if (chunks >= MAX_INCREMENTAL_CHUNKS)
			return remaining;

		if (splitStrategy == SplitStrategy.SINGLE_BYTE || requestBytes.length == 0)
			return 1;

		int control = requestBytes[chunks % requestBytes.length] & 0xFF;
		return 1 + (control % remaining);
	}

	private static void drainCompletedRequests(ByteTokenizer tokenizer, RequestParser parser) {
		int completedRequests = 0;

		while (parser.parse()) {
			parser.request();
			parser.reset();
			tokenizer.compact();
			completedRequests++;

			if (completedRequests >= MAX_COMPLETED_REQUESTS_PER_DRAIN || tokenizer.remaining() == 0)
				return;
		}
	}
}

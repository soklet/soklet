/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Assertions;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Coverage-guided checks for bounded JSON-RPC envelope classification.
 */
@ThreadSafe
public class McpJsonRpcEnvelopeCodecFuzzTest {
	private static final McpJsonRpcEnvelopeCodec CODEC =
			new McpJsonRpcEnvelopeCodec(new McpJsonCodec(
					McpJsonLimits.productionDefaults()));
	private static volatile int sink;

	@FuzzTest(maxDuration = "2m")
	public void decodeClassifiesOrRejectsOnlyWithTypedWireFailure(byte[] input) {
		try {
			exercise(CODEC.decode(input));
		} catch (McpWireDecodingException expected) {
			Assertions.assertNotNull(expected.kind());
			Assertions.assertNotNull(expected.readableRequestId());
		}
	}

	private static void exercise(McpJsonRpcEnvelope envelope) {
		if (envelope instanceof McpJsonRpcEnvelope.Request request) {
			sink = request.method().length() + request.extensionFields().members().size()
					+ request.params().map(value -> 1).orElse(0);
		} else if (envelope instanceof McpJsonRpcEnvelope.Notification notification) {
			sink = notification.method().length()
					+ notification.extensionFields().members().size()
					+ notification.params().map(value -> 1).orElse(0);
		} else if (envelope instanceof McpJsonRpcEnvelope.ResultResponse response) {
			sink = response.extensionFields().members().size()
					+ response.result().hashCode();
		} else if (envelope instanceof McpJsonRpcEnvelope.ErrorResponse response) {
			sink = response.extensionFields().members().size()
					+ response.id().map(value -> 1).orElse(0)
					+ response.error().hashCode();
		} else {
			Assertions.fail("Unknown JSON-RPC envelope variant: "
					+ envelope.getClass().getName());
		}
	}
}

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

import java.util.Optional;

/**
 * Contract tests for the internal bounded MCP trace-log carrier.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpTraceLogRecordTests {
	private static final String TRACE_ID =
			"0af7651916cd43dd8448eb211c80319c";
	private static final String TOKEN = "btKe431RVT8H4xxLhBXgUg";

	@Test
	public void exactMachineReadableGrammarCoversIndependentFieldModes() {
		DefaultMcpSecurityControls.TraceCorrelationToken token =
				new DefaultMcpSecurityControls.TraceCorrelationToken(
						"trace-key", TOKEN);

		McpTraceLogRecord tokenOnly = McpTraceLogRecord.capture(
				Optional.of(token), Optional.empty()).orElseThrow();
		McpTraceLogRecord rawOnly = McpTraceLogRecord.capture(
				Optional.empty(), Optional.of(TRACE_ID)).orElseThrow();
		McpTraceLogRecord combined = McpTraceLogRecord.capture(
				Optional.of(token), Optional.of(TRACE_ID)).orElseThrow();

		Assertions.assertEquals(
				"tokenFormat=soklet-mcp-trace-correlation-v1;"
						+ "keyId=trace-key;token=" + TOKEN,
				tokenOnly.toLogMessage());
		Assertions.assertEquals("traceId=" + TRACE_ID,
				rawOnly.toLogMessage());
		Assertions.assertEquals(
				"tokenFormat=soklet-mcp-trace-correlation-v1;"
						+ "keyId=trace-key;token=" + TOKEN
						+ ";traceId=" + TRACE_ID,
				combined.toLogMessage());
		Assertions.assertTrue(McpTraceLogRecord.capture(
				Optional.empty(), Optional.empty()).isEmpty());
	}

	@Test
	public void maximumMessageIsExactAsciiAndDelimiterSafe() {
		String keyId = "k".repeat(64);
		McpTraceLogRecord record = McpTraceLogRecord.capture(
				Optional.of(new DefaultMcpSecurityControls.TraceCorrelationToken(
						keyId, TOKEN)), Optional.of(TRACE_ID)).orElseThrow();
		String message = record.toLogMessage();

		Assertions.assertEquals(McpTraceLogRecord.MAXIMUM_LOG_MESSAGE_CHARACTERS,
				message.length());
		Assertions.assertTrue(message.chars().allMatch(value -> value <= 0x7F));
		String[] fields = message.split(";");
		Assertions.assertArrayEquals(new String[]{
				"tokenFormat=" + McpTraceLogRecord.TOKEN_FORMAT,
				"keyId=" + keyId,
				"token=" + TOKEN,
				"traceId=" + TRACE_ID
		}, fields);
		for (String field : fields)
			Assertions.assertEquals(field.indexOf('='), field.lastIndexOf('='));
	}

	@Test
	public void carrierRejectsValuesOutsideFrozenFieldAlphabets() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpTraceLogRecord.capture(Optional.of(
						new DefaultMcpSecurityControls.TraceCorrelationToken(
								"bad;key", TOKEN)), Optional.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpTraceLogRecord.capture(Optional.of(
						new DefaultMcpSecurityControls.TraceCorrelationToken(
								"trace-key", TOKEN.substring(1))),
						Optional.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpTraceLogRecord.capture(Optional.of(
						new DefaultMcpSecurityControls.TraceCorrelationToken(
								"trace-key", "=" + TOKEN.substring(1))),
						Optional.empty()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpTraceLogRecord.capture(Optional.empty(), Optional.of(
						TRACE_ID.toUpperCase())));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpTraceLogRecord.capture(Optional.empty(), Optional.of(
						"0".repeat(32))));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> McpTraceLogRecord.capture(Optional.empty(), Optional.of(
						TRACE_ID.substring(1))));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpTraceLogRecord.capture(null, Optional.empty()));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpTraceLogRecord.capture(Optional.empty(), null));
	}

	@Test
	public void diagnosticRenderingRedactsEveryHighCardinalityValue() {
		McpTraceLogRecord record = McpTraceLogRecord.capture(
				Optional.of(new DefaultMcpSecurityControls.TraceCorrelationToken(
						"trace-key", TOKEN)), Optional.of(TRACE_ID)).orElseThrow();

		String rendering = record.toString();
		Assertions.assertFalse(rendering.contains(TOKEN), rendering);
		Assertions.assertFalse(rendering.contains(TRACE_ID), rendering);
		Assertions.assertTrue(rendering.contains("token=<redacted>"), rendering);
		Assertions.assertTrue(rendering.contains("traceId=<redacted>"), rendering);
	}
}

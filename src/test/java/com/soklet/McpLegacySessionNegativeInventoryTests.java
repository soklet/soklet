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
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Negative source and frozen-API inventory for legacy MCP session semantics.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(30)
public class McpLegacySessionNegativeInventoryTests {
	private static final Path PUBLIC_SOURCE_ROOT =
			Path.of("src/main/java/com/soklet");
	private static final Path INTERNAL_MCP_SOURCE_ROOT =
			PUBLIC_SOURCE_ROOT.resolve("internal/mcp");
	private static final Path HTTP_RUNTIME_SOURCE = INTERNAL_MCP_SOURCE_ROOT
			.resolve("protocol/McpHttpServerRuntime.java");
	private static final List<Path> REVIEWED_API_SIGNATURES = List.of(
			Path.of("api/mcp/phase-4.signatures.jsonl"),
			Path.of("api/mcp/phase-5.signatures.jsonl"),
			Path.of("api/mcp/phase-6.signatures.jsonl"));
	private static final Pattern LEGACY_STATE_IDENTIFIER = Pattern.compile(
			"\\b[A-Za-z0-9_]*(?:mcp_?session"
					+ "|session_?(?:id|store|state|cache|registry|map|table|cursor"
					+ "|token|manager|storage|repository|record|context)"
					+ "|last_?event_?id|replay)[A-Za-z0-9_]*\\b",
			Pattern.CASE_INSENSITIVE);

	@Test
	public void modernMcpSourceAndReviewedApiHaveNoLegacySessionOrReplayState()
			throws Exception {
		List<Path> sourcePaths = mcpProductionSourcePaths();
		Assertions.assertFalse(sourcePaths.isEmpty(),
				"MCP production source inventory must not be empty.");
		for (Path sourcePath : sourcePaths) {
			String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
			assertNoLegacyStateIdentifier(sourcePath, source);
			if (!sourcePath.equals(HTTP_RUNTIME_SOURCE)) {
				String lowerSource = source.toLowerCase(Locale.ROOT);
				Assertions.assertFalse(lowerSource.contains("mcp-session-id"),
						() -> "Legacy session header escaped its denylist: "
								+ sourcePath);
				Assertions.assertFalse(lowerSource.contains("last-event-id"),
						() -> "Legacy replay header escaped its denylist: "
								+ sourcePath);
			}
		}

		for (Path signatures : REVIEWED_API_SIGNATURES) {
			String api = Files.readString(signatures, StandardCharsets.UTF_8);
			assertNoLegacyStateIdentifier(signatures, api);
			String lowerApi = api.toLowerCase(Locale.ROOT);
			Assertions.assertFalse(lowerApi.contains("mcp-session-id"),
					() -> "Reviewed API exposes the legacy session header: " + signatures);
			Assertions.assertFalse(lowerApi.contains("last-event-id"),
					() -> "Reviewed API exposes the legacy replay header: " + signatures);
		}
	}

	@Test
	public void legacyHeaderNamesExistOnlyInTheSharedPolicyOutputDenylist()
			throws Exception {
		String runtime = Files.readString(HTTP_RUNTIME_SOURCE, StandardCharsets.UTF_8);
		String lowerRuntime = runtime.toLowerCase(Locale.ROOT);
		Assertions.assertEquals(1, occurrences(lowerRuntime, "\"mcp-session-id\""),
				"The legacy session header may exist only as a denylist literal.");
		Assertions.assertEquals(1, occurrences(lowerRuntime, "\"last-event-id\""),
				"The legacy replay header may exist only as a denylist literal.");

		String denylist = slice(runtime,
				"FORBIDDEN_LEGACY_MCP_POLICY_HEADERS", "MCP_HTTP_METHODS");
		Assertions.assertTrue(denylist.contains("\"mcp-session-id\""), denylist);
		Assertions.assertTrue(denylist.contains("\"last-event-id\""), denylist);

		String validator = slice(runtime,
				"private List<@NonNull Header> validatedPolicyHeaders(",
				"private boolean validHeaderName(");
		Assertions.assertTrue(validator.contains(
				"String lowerName = name.toLowerCase(Locale.ROOT)"), validator);
		Assertions.assertTrue(validator.contains(
				"FORBIDDEN_LEGACY_MCP_POLICY_HEADERS.contains(lowerName)"), validator);
		Assertions.assertEquals(2, occurrences(runtime,
				"validatedPolicyHeaders(rejection.headers())"),
				"Request and notification admission output must share the validator.");
	}

	private static List<Path> mcpProductionSourcePaths() throws IOException {
		try (var paths = Files.walk(PUBLIC_SOURCE_ROOT)) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.filter(path -> path.startsWith(INTERNAL_MCP_SOURCE_ROOT)
							|| path.getFileName().toString().startsWith("Mcp")
							|| path.getFileName().toString().startsWith("DefaultMcp"))
					.sorted()
					.toList();
		}
	}

	private static void assertNoLegacyStateIdentifier(Path path, String source) {
		Matcher matcher = LEGACY_STATE_IDENTIFIER.matcher(source);
		Assertions.assertFalse(matcher.find(), () -> "Legacy MCP session/replay state "
				+ "identifier '" + matcher.group() + "' found in " + path);
	}

	private static int occurrences(String input, String fragment) {
		int count = 0;
		for (int offset = 0; (offset = input.indexOf(fragment, offset)) >= 0;
				offset += fragment.length())
			count++;
		return count;
	}

	private static String slice(String source, String startMarker, String endMarker) {
		int start = source.indexOf(startMarker);
		int end = source.indexOf(endMarker, start + startMarker.length());
		Assertions.assertTrue(start >= 0 && end > start,
				() -> "Missing source inventory boundary: " + startMarker + " -> "
						+ endMarker);
		return source.substring(start, end);
	}
}

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Source-level convention checks for Soklet's MCP implementation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpSourceConventionsTests {
	private static final String AUTHOR_TAG =
			"@author <a href=\"https://www.revetkn.com\">Mark Allen</a>";
	private static final Path PUBLIC_SOURCE_ROOT =
			Path.of("src/main/java/com/soklet");
	private static final Path INTERNAL_SOURCE_ROOT =
			PUBLIC_SOURCE_ROOT.resolve("internal/mcp");

	@Test
	public void everyMcpProductionSourceUsesTheEstablishedAuthorTag()
			throws IOException {
		List<Path> sourcePaths = mcpProductionSourcePaths();
		List<Path> missingAuthorTags = new ArrayList<>();

		for (Path sourcePath : sourcePaths) {
			String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
			if (!source.contains(AUTHOR_TAG))
				missingAuthorTags.add(sourcePath);
		}

		Assertions.assertFalse(sourcePaths.isEmpty(),
				"No MCP production sources were found");
		Assertions.assertTrue(missingAuthorTags.isEmpty(), () ->
				"MCP production sources missing the established author tag:\n - "
						+ String.join("\n - ", missingAuthorTags.stream()
						.map(Path::toString).toList()));
	}

	private static List<Path> mcpProductionSourcePaths() throws IOException {
		try (var paths = Files.walk(PUBLIC_SOURCE_ROOT)) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.filter(path -> path.startsWith(INTERNAL_SOURCE_ROOT)
							|| path.getFileName().toString().startsWith("Mcp")
							|| path.getFileName().toString()
							.equals("DefaultMcpServer.java"))
					.sorted()
					.toList();
		}
	}
}

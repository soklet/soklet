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
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Completeness and single-owner checks for the reviewed MCP API inventories.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpPublicApiInventoryTests {
	private static final List<Path> INCLUDE_FILES = List.of(
			Path.of("api/mcp/phase-4.includes"),
			Path.of("api/mcp/phase-5.includes"),
			Path.of("api/mcp/phase-6.includes"),
			Path.of("api/mcp/provisional.includes"));
	private static final Set<String> SHARED_HOSTS = Set.of(
			"com.soklet.CorsAuthorizer",
			"com.soklet.LifecycleObserver",
			"com.soklet.LogEventType",
			"com.soklet.MetricsCollector",
			"com.soklet.MetricsCollector$Snapshot",
			"com.soklet.MetricsCollector$Snapshot$Builder",
			"com.soklet.SokletConfig",
			"com.soklet.SokletConfig$Builder",
			"com.soklet.SokletConfig$Copier");

	@Test
	public void everyExportedMcpTypeHasExactlyOneReviewedOwner() throws Exception {
		Map<String, Path> reviewedOwners = reviewedOwners();
		Set<String> discovered = discoverExportedMcpTypes();
		discovered.addAll(SHARED_HOSTS);

		Assertions.assertEquals(discovered, reviewedOwners.keySet(), () -> {
			Set<String> missing = new LinkedHashSet<>(discovered);
			missing.removeAll(reviewedOwners.keySet());
			Set<String> stale = new LinkedHashSet<>(reviewedOwners.keySet());
			stale.removeAll(discovered);
			return "Reviewed MCP API ownership differs from independent class discovery; "
					+ "missing=" + missing + ", stale=" + stale;
		});
	}

	@Test
	public void everyReviewedTypeExistsAndIsExported() throws Exception {
		for (String binaryName : reviewedOwners().keySet()) {
			Class<?> type = Class.forName(binaryName, false,
					Thread.currentThread().getContextClassLoader());
			int modifiers = type.getModifiers();
			Assertions.assertTrue(Modifier.isPublic(modifiers)
						|| Modifier.isProtected(modifiers),
					() -> "Reviewed MCP API type is not exported: " + binaryName);
			Assertions.assertFalse(type.isAnonymousClass() || type.isLocalClass()
						|| type.isSynthetic(),
					() -> "Reviewed MCP API type is compiler-generated: " + binaryName);
		}
	}

	private static Map<String, Path> reviewedOwners() throws IOException {
		Map<String, Path> owners = new LinkedHashMap<>();
		for (Path includeFile : INCLUDE_FILES) {
			List<String> entries = Files.readAllLines(includeFile, StandardCharsets.UTF_8)
					.stream().map(String::trim)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.toList();
			List<String> sorted = entries.stream().sorted().toList();
			Assertions.assertEquals(sorted, entries,
					() -> "MCP API include file must be sorted: " + includeFile);
			for (String entry : entries) {
				Path previous = owners.putIfAbsent(entry, includeFile);
				Assertions.assertNull(previous, () -> "MCP API type " + entry
						+ " appears in both " + previous + " and " + includeFile);
			}
		}
		return owners;
	}

	private static Set<String> discoverExportedMcpTypes() throws Exception {
		Path packageRoot = Path.of("target/classes/com/soklet");
		Assertions.assertTrue(Files.isDirectory(packageRoot),
				"Compiled Soklet classes are required for MCP API discovery");
		List<Path> classFiles;
		try (var paths = Files.walk(packageRoot)) {
			classFiles = paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".class"))
					.toList();
		}

		List<String> binaryNames = new ArrayList<>();
		for (Path classFile : classFiles) {
			String relative = packageRoot.getParent().getParent().relativize(classFile)
					.toString().replace(classFile.getFileSystem().getSeparator(), ".");
			binaryNames.add(relative.substring(0, relative.length() - ".class".length()));
		}

		Set<String> discovered = new LinkedHashSet<>();
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		for (String binaryName : binaryNames) {
			if (!binaryName.startsWith("com.soklet.Mcp")
					&& !binaryName.startsWith("com.soklet.annotation.Mcp"))
				continue;
			Class<?> type = Class.forName(binaryName, false, classLoader);
			int modifiers = type.getModifiers();
			if ((Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))
					&& !type.isAnonymousClass() && !type.isLocalClass() && !type.isSynthetic())
				discovered.add(binaryName);
		}
		return discovered;
	}
}

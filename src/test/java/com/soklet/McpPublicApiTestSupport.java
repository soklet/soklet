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

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Current compiled API discovery shared by convention tests; no release snapshots are inputs. */
final class McpPublicApiTestSupport {
	private McpPublicApiTestSupport() {}

	static List<Class<?>> publicMcpTypes() throws Exception {
		Path classes = Path.of(McpServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Assertions.assertTrue(Files.isDirectory(classes), "Compiled main classes are required for MCP API discovery");
		List<Path> classFiles;
		try (var paths = Files.walk(classes.resolve("com/soklet"))) {
			classFiles = paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".class"))
					.sorted().toList();
		}
		List<Class<?>> types = new ArrayList<>();
		ClassLoader loader = McpServer.class.getClassLoader();
		for (Path classFile : classFiles) {
			String relative = classes.relativize(classFile).toString()
					.replace(classFile.getFileSystem().getSeparator(), ".");
			String name = relative.substring(0, relative.length() - ".class".length());
			if (!name.startsWith("com.soklet.Mcp")
					&& !name.startsWith("com.soklet.annotation.Mcp")
					&& !name.equals("com.soklet.DefaultMcpServer"))
				continue;
			Class<?> type = Class.forName(name, false, loader);
			if (isExported(type))
				types.add(type);
		}
		types.sort(Comparator.comparing(Class::getName));
		Assertions.assertFalse(types.isEmpty(), "No exported MCP API types were discovered");
		return List.copyOf(types);
	}

	static boolean isExported(Class<?> type) {
		if (type.isAnonymousClass() || type.isLocalClass() || type.isSynthetic())
			return false;
		for (Class<?> owner = type; owner != null; owner = owner.getEnclosingClass()) {
			if (!Modifier.isPublic(owner.getModifiers()) && !Modifier.isProtected(owner.getModifiers()))
				return false;
		}
		return true;
	}

	static Set<String> documentedApiTypeNames() throws Exception {
		Set<String> names = new LinkedHashSet<>();
		for (Class<?> type : publicMcpTypes())
			names.add(type.getName());
		// These general-purpose hosts also expose MCP entry points.
		for (Class<?> type : List.of(CorsAuthorizer.class, LifecycleObserver.class,
				LogEventType.class, MetricsCollector.class, MetricsCollector.Snapshot.class,
				MetricsCollector.Snapshot.Builder.class, Simulator.class, SokletConfig.class,
				SokletConfig.Builder.class))
			names.add(type.getName());
		return names;
	}
}

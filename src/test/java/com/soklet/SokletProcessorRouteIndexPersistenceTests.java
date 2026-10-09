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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
public class SokletProcessorRouteIndexPersistenceTests {
	@Test
	void incrementalAmbiguityFailsBeforePublishingMetadata(@TempDir Path directory) throws Exception {
		Fixture fixture = new Fixture(directory, "sidecar");
		Files.writeString(fixture.alpha, """
				package example;
				import com.soklet.annotation.GET;
				import com.soklet.annotation.PathParameter;
				public class Alpha {
				  @GET("/item/{id}") public String get(@PathParameter String id) { return id; }
				}
				""", StandardCharsets.UTF_8);
		fixture.compile(fixture.alpha);
		String index = Files.readString(fixture.index());
		String sidecar = Files.readString(fixture.sidecar());
		Files.writeString(fixture.beta, """
				package example;
				import com.soklet.annotation.GET;
				import com.soklet.annotation.PathParameter;
				public class Beta {
				  @GET("/item/{other}") public String get(@PathParameter String other) { return other; }
				}
				""", StandardCharsets.UTF_8);
		assertFalse(fixture.tryCompile("sidecar", List.of(fixture.beta), List.of(), List.of()));
		for (String context : List.of("HTTP GET", "overlaps", "example.Alpha#get(java.lang.String)",
				"example.Beta#get(java.lang.String)", "/item/{id}", "/item/{other}"))
			assertTrue(fixture.diagnostics.contains(context), fixture.diagnostics);
		assertEquals(index, Files.readString(fixture.index()));
		assertEquals(sidecar, Files.readString(fixture.sidecar()));
	}


	@Test
	void deletedOwnerIsPrunedWhileUntouchedOwnerRemains(@TempDir Path directory) throws Exception {
		for (String mode : List.of("none", "sidecar", "persistent")) {
			Fixture fixture = new Fixture(directory.resolve(mode), mode);
			fixture.initialCompile();
			Files.delete(fixture.beta);
			Files.delete(fixture.classes.resolve("example/Beta.class"));
			fixture.compile(fixture.plain);
			fixture.assertSnapshots(List.of("example.Alpha"));
			fixture.verifyPackagedRoutes("/alpha");
		}
	}

	@Test
	void renamedOwnerReplacesOldRows(@TempDir Path directory) throws Exception {
		for (String mode : List.of("none", "sidecar", "persistent")) {
			Fixture fixture = new Fixture(directory.resolve(mode), mode);
			fixture.initialCompile();
			Files.delete(fixture.beta);
			Files.delete(fixture.classes.resolve("example/Beta.class"));
			Path gamma = fixture.writeResource("Gamma", "/gamma");
			fixture.compile(gamma);
			fixture.assertSnapshots(List.of("example.Alpha", "example.Gamma"));
			fixture.verifyPackagedRoutes("/alpha", "/gamma");
		}
	}

	@Test
	void cleanClassOutputDoesNotRestoreDeletedOwnersFromCaches(@TempDir Path directory) throws Exception {
		for (String mode : List.of("sidecar", "persistent")) {
			Fixture fixture = new Fixture(directory.resolve(mode), mode);
			fixture.initialCompile();
			Files.delete(fixture.beta);
			deleteTree(fixture.classes);
			Files.createDirectories(fixture.classes);
			if (mode.equals("persistent")) deleteTree(fixture.sidecar().getParent());
			fixture.compile(fixture.alpha);
			fixture.assertSnapshots(List.of("example.Alpha"));
			fixture.verifyPackagedRoutes("/alpha");
		}
	}

	@Test
	void currentOutputPreventsOlderCachesFromRestoringRemovedAnnotations(@TempDir Path directory) throws Exception {
		for (String mode : List.of("sidecar", "persistent")) {
			Fixture fixture = new Fixture(directory.resolve(mode), mode);
			fixture.initialCompile();
			Files.writeString(fixture.alpha, "package example; public class Alpha { public String get() { return \"old\"; } }", StandardCharsets.UTF_8);
			fixture.compileWith("none", List.of(fixture.alpha), List.of(), List.of());
			assertEquals(List.of("example.Beta"), owners(fixture.index()));
			assertEquals(List.of("example.Alpha", "example.Beta"), owners(fixture.sidecar()));
			fixture.compile(fixture.plain);
			fixture.assertSnapshots(List.of("example.Beta"));
		}
	}

	@Test
	void emptyCurrentOutputIsAnAuthoritativeSnapshot(@TempDir Path directory) throws Exception {
		Fixture fixture = new Fixture(directory, "persistent");
		fixture.initialCompile();
		Files.writeString(fixture.alpha, "package example; public class Alpha {}", StandardCharsets.UTF_8);
		Files.writeString(fixture.beta, "package example; public class Beta {}", StandardCharsets.UTF_8);
		fixture.compileWith("none", List.of(fixture.alpha, fixture.beta), List.of(), List.of());
		assertEquals(0, Files.size(fixture.index()));
		fixture.compile(fixture.plain);
		fixture.assertSnapshots(List.of());
	}

	@Test
	void sourceVisibleUntouchedOwnerSurvivesACleanOutput(@TempDir Path directory) throws Exception {
		Fixture fixture = new Fixture(directory, "sidecar");
		fixture.initialCompile();
		deleteTree(fixture.classes);
		Files.createDirectories(fixture.classes);
		fixture.compileWith("sidecar", List.of(fixture.alpha), List.of(), List.of("-sourcepath", fixture.sources.toString()));
		fixture.assertSnapshots(List.of("example.Alpha", "example.Beta"));
	}

	@Test
	void untouchedNestedOwnersWithDollarIdentifiersRemainVisible(@TempDir Path directory) throws Exception {
		Fixture fixture = new Fixture(directory, "sidecar");
		Files.writeString(fixture.beta, """
				package example;
				import com.soklet.annotation.GET;
				public class Beta {
				  public static class Inner$Part {
				    @GET("/nested") public String get() { return "/nested"; }
				    public static class Deeper {
				      @GET("/deep") public String get() { return "/deep"; }
				    }
				  }
				}
				""", StandardCharsets.UTF_8);
		fixture.initialCompile();
		fixture.compile(fixture.plain);
		fixture.assertSnapshots(List.of("example.Alpha", "example.Beta$Inner$Part", "example.Beta$Inner$Part$Deeper"));
		fixture.verifyPackagedRoutes("/alpha", "/nested", "/deep");
	}

	@Test
	void dependencyJarOwnersAndIndexesArePreserved(@TempDir Path directory) throws Exception {
		Fixture dependency = new Fixture(directory.resolve("dependency"), "none");
		dependency.compile(dependency.beta);
		Path jar = directory.resolve("dependency.jar");
		try (var output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(dependency.classes)) {
			for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				output.putNextEntry(new JarEntry(dependency.classes.relativize(file).toString().replace('\\', '/')));
				Files.copy(file, output);
				output.closeEntry();
			}
		}
		Fixture fixture = new Fixture(directory.resolve("application"), "sidecar");
		fixture.compile(fixture.alpha);
		// Exercise a retained dependency owner without a classfile in this module's output.
		Files.writeString(fixture.index(), Files.readString(fixture.index()) + Files.readString(dependency.index()));
		fixture.compileWith("sidecar", List.of(fixture.plain), List.of(jar), List.of());
		fixture.assertSnapshots(List.of("example.Alpha", "example.Beta"));
		fixture.verifyPackagedRoutes(List.of(jar), "/alpha", "/beta");
	}

	@Test
	void failedCompilationPreservesPreviousIndexAndCaches(@TempDir Path directory) throws Exception {
		Fixture fixture = new Fixture(directory, "persistent");
		fixture.initialCompile();
		String before = Files.readString(fixture.index());
		Files.writeString(fixture.alpha, "package example; public class Alpha { syntax error }", StandardCharsets.UTF_8);
		assertFalse(fixture.tryCompile("persistent", List.of(fixture.alpha), List.of(), List.of()));
		assertEquals(before, Files.readString(fixture.index()));
		fixture.assertSnapshots(List.of("example.Alpha", "example.Beta"));
	}

	@Test
	void invalidSelectedSnapshotFailsWithoutPublishingPartialMetadata(@TempDir Path directory) throws Exception {
		for (String selected : List.of("output", "sidecar", "persistent")) {
			Fixture fixture = new Fixture(directory.resolve(selected), "persistent");
			fixture.initialCompile();
			Path snapshot = switch (selected) {
				case "output" -> fixture.index();
				case "sidecar" -> fixture.sidecar();
				default -> fixture.persistentSnapshot();
			};
			if (!selected.equals("output")) Files.delete(fixture.index());
			if (selected.equals("persistent")) Files.delete(fixture.sidecar());
			String malformed = Files.readAllLines(snapshot).get(0) + "\nPRIVATE_INVALID_ROW\n";
			Files.writeString(snapshot, malformed, StandardCharsets.UTF_8);
			assertFalse(fixture.tryCompile("persistent", List.of(fixture.plain), List.of(), List.of()));
			assertTrue(fixture.diagnostics.contains("HTTP/SSE route index"), fixture.diagnostics);
			assertTrue(fixture.diagnostics.contains(snapshot.toString()), fixture.diagnostics);
			assertFalse(fixture.diagnostics.contains("PRIVATE_INVALID_ROW"), "Diagnostics must identify the snapshot without echoing its contents");
			assertEquals(malformed, Files.readString(snapshot));
			if (!selected.equals("output")) assertFalse(Files.exists(fixture.index()));
		}
	}

	@Test
	void invalidUnselectedCacheDoesNotReplaceCurrentOutput(@TempDir Path directory) throws Exception {
		Fixture fixture = new Fixture(directory, "persistent");
		fixture.initialCompile();
		Files.writeString(fixture.sidecar(), "malformed sidecar", StandardCharsets.UTF_8);
		Files.writeString(fixture.persistentSnapshot(), "malformed persistent cache", StandardCharsets.UTF_8);
		fixture.compile(fixture.plain);
		fixture.assertSnapshots(List.of("example.Alpha", "example.Beta"));
	}

	@Test
	void unmodifiableCacheTargetIsDiagnosedAndTemporaryFilesAreRemoved(@TempDir Path directory) throws Exception {
		Fixture fixture = new Fixture(directory, "sidecar");
		fixture.initialCompile();
		Files.delete(fixture.sidecar());
		Files.createDirectory(fixture.sidecar());
		Files.writeString(fixture.sidecar().resolve("blocker"), "retained", StandardCharsets.UTF_8);
		assertFalse(fixture.tryCompile("sidecar", List.of(fixture.plain), List.of(), List.of()));
		assertTrue(fixture.diagnostics.contains("Unable to update or invalidate"), fixture.diagnostics);
		try (var files = Files.list(fixture.sidecar().getParent())) {
			assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
		}
	}

	private static List<String> owners(Path index) throws IOException {
		List<String> owners = new ArrayList<>();
		for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
			if (line.isBlank()) continue;
			String[] fields = line.split("\\|", -1);
			assertEquals(6, fields.length);
			owners.add(new String(Base64.getDecoder().decode(fields[2]), StandardCharsets.UTF_8));
		}
		return owners.stream().distinct().sorted().toList();
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
		}
	}

	private static class Fixture {
		final Path directory, sources, classes, cache, alpha, beta, plain;
		final String mode;
		int invocation;
		String diagnostics = "";

		Fixture(Path directory, String mode) throws IOException {
			this.directory = directory;
			this.mode = mode;
			this.sources = Files.createDirectories(directory.resolve("sources"));
			this.classes = Files.createDirectories(directory.resolve("classes"));
			this.cache = Files.createDirectories(directory.resolve("cache"));
			this.alpha = writeResource("Alpha", "/alpha");
			this.beta = writeResource("Beta", "/beta");
			this.plain = sources.resolve("example/Plain.java");
			Files.writeString(plain, "package example; public class Plain {}", StandardCharsets.UTF_8);
		}

		Path writeResource(String name, String path) throws IOException {
			Path source = sources.resolve("example/" + name + ".java");
			Files.createDirectories(source.getParent());
			Files.writeString(source, """
					package example;
					import com.soklet.annotation.GET;
					import com.soklet.annotation.SseEventSource;
					import com.soklet.SseHandshakeResult;
					public class %s {
					  @GET("%s") public String get() { return "%s"; }
					  @SseEventSource("%s/events") public SseHandshakeResult events() { return SseHandshakeResult.accept(); }
					}
					""".formatted(name, path, path, path), StandardCharsets.UTF_8);
			return source;
		}

		void initialCompile() throws IOException { compile(alpha, beta); }
		void compile(Path... sources) throws IOException { compileWith(mode, List.of(sources), List.of(), List.of()); }
		void compileWith(String mode, List<Path> sources, List<Path> dependencies, List<String> options) throws IOException {
			assertTrue(tryCompile(mode, sources, dependencies, options), diagnostics);
		}

		boolean tryCompile(String mode, List<Path> sources, List<Path> dependencies, List<String> options) throws IOException {
			var compiler = ToolProvider.getSystemJavaCompiler();
			assertNotNull(compiler);
			var collector = new DiagnosticCollector<JavaFileObject>();
			Path generated = Files.createDirectories(directory.resolve("generated-" + invocation++));
			try (var manager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
				String classpath = classes + System.getProperty("path.separator") + System.getProperty("java.class.path");
				for (Path dependency : dependencies) classpath += System.getProperty("path.separator") + dependency;
				List<String> arguments = new ArrayList<>(List.of("--release", "17", "-parameters", "-classpath", classpath,
						"-d", classes.toString(), "-s", generated.toString(), "-Asoklet.cacheMode=" + mode, "-Asoklet.cacheDir=" + cache));
				arguments.addAll(options);
				var task = compiler.getTask(null, manager, collector, arguments, null, manager.getJavaFileObjectsFromPaths(sources));
				task.setProcessors(List.of(new SokletProcessor()));
				boolean successful = task.call();
				diagnostics = collector.getDiagnostics().toString();
				return successful;
			}
		}

		Path index() { return classes.resolve(SokletProcessor.RESOURCE_METHOD_LOOKUP_TABLE_PATH); }
		Path sidecar() { return directory.resolve("soklet/classes/resource-method-lookup-table"); }
		Path persistentSnapshot() throws IOException {
			try (var files = Files.walk(cache.resolve("resource-methods"))) {
				List<Path> snapshots = files.filter(Files::isRegularFile).toList();
				assertEquals(1, snapshots.size());
				return snapshots.get(0);
			}
		}
		void assertSnapshots(List<String> expected) throws IOException {
			assertEquals(expected, owners(index()));
			String current = Files.readString(index());
			if (!mode.equals("none")) assertEquals(current, Files.readString(sidecar()));
			if (mode.equals("persistent")) {
				assertEquals(current, Files.readString(persistentSnapshot()));
			}
		}

		void verifyPackagedRoutes(String... paths) throws Exception { verifyPackagedRoutes(List.of(), paths); }
		void verifyPackagedRoutes(List<Path> dependencies, String... paths) throws Exception {
			Path verification = sources.resolve("example/Verification.java");
			Files.writeString(verification, VERIFICATION, StandardCharsets.UTF_8);
			compileWith(mode, List.of(verification), dependencies, List.of());
			String classpath = classes + System.getProperty("path.separator") + System.getProperty("java.class.path");
			for (Path dependency : dependencies) classpath += System.getProperty("path.separator") + dependency;
			List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"-cp", classpath, "example.Verification", classes.toUri().toString()));
			for (Path dependency : dependencies) command.add(dependency.toUri().toString());
			command.add("--");
			command.addAll(List.of(paths));
			Path output = directory.resolve("verification.log");
			Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
			try {
				assertTrue(process.waitFor(10, TimeUnit.SECONDS));
				assertEquals(0, process.exitValue(), Files.readString(output));
			} finally {
				if (process.isAlive()) process.destroyForcibly();
			}
		}
	}

	private static final String VERIFICATION = """
			package example;
			import com.soklet.*;
			import java.net.*;
			import java.util.*;
			public class Verification {
			  public static void main(String[] arguments) throws Exception {
			    List<URL> roots = new ArrayList<>(); int separator = 0;
			    while (!arguments[separator].equals("--")) roots.add(URI.create(arguments[separator++]).toURL());
			    try (URLClassLoader loader = new URLClassLoader(roots.toArray(URL[]::new), Verification.class.getClassLoader()) {
			      @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
			        return name.equals("META-INF/soklet/resource-method-lookup-table") ? findResources(name) : super.getResources(name);
			      }
			    }) {
			      Thread.currentThread().setContextClassLoader(loader);
			      ResourceMethodResolver resolver = ResourceMethodResolver.fromClasspathIntrospection();
			      for (int i = separator + 1; i < arguments.length; i++) {
			        ResourceMethod method = resolver.resourceMethodForRequest(Request.withPath(HttpMethod.GET, arguments[i]).build(), ServerType.HTTP).orElseThrow();
			        Object owner = method.getMethod().getDeclaringClass().getConstructor().newInstance();
			        if (!arguments[i].equals(method.getMethod().invoke(owner))) throw new AssertionError("Wrong resource method");
			      }
			    }
			  }
			}
			""";
}

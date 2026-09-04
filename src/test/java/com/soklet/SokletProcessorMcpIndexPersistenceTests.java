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
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.concurrent.ThreadSafe;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SokletProcessorMcpIndexPersistenceTests {
	private static final String MCP_INDEX_PATH =
			"META-INF/soklet/mcp-endpoint-descriptor-providers";
	private static final String MCP_CACHE_FILENAME =
			"mcp-endpoint-descriptor-providers";

	@Test
	void cleanClassOutputRestoresUntouchedMcpEndpointRowsFromSidecar(
			@TempDir Path temporaryDirectory) throws IOException {
		Fixture fixture = createFixture(temporaryDirectory);
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint(),
				fixture.secondEndpoint()), "-Asoklet.cacheMode=sidecar"));

		Path sidecar = sidecarPath(fixture.classDirectory());
		Assertions.assertEquals(List.of("example.AEndpoint",
				"example.BEndpoint"), endpointNames(sidecar));

		deleteRecursively(fixture.classDirectory());
		Files.createDirectories(fixture.classDirectory());
		Path secondGeneratedDirectory =
				temporaryDirectory.resolve("generated-second");
		Files.createDirectories(secondGeneratedDirectory);
		Assertions.assertTrue(compileWithSokletProcessor(
				fixture.classDirectory(), secondGeneratedDirectory,
				List.of(fixture.firstEndpoint()),
				List.of("-Asoklet.cacheMode=sidecar")));

		Assertions.assertEquals(List.of("example.AEndpoint",
				"example.BEndpoint"), endpointNames(classOutputIndex(
				fixture.classDirectory())));
		Assertions.assertEquals(List.of("example.AEndpoint",
				"example.BEndpoint"), endpointNames(sidecar));
	}

	@Test
	void staleFormatThreeSidecarRequiresCleanRegeneration(
			@TempDir Path temporaryDirectory) throws IOException {
		Fixture fixture = createFixture(temporaryDirectory);
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint()),
				"-Asoklet.cacheMode=sidecar"));
		Path classOutputIndex = classOutputIndex(fixture.classDirectory());
		Path sidecar = sidecarPath(fixture.classDirectory());
		assertIndexVersion(classOutputIndex, "4");
		assertIndexVersion(sidecar, "4");

		Files.delete(classOutputIndex);
		List<String> staleRows = Files.readAllLines(sidecar,
				StandardCharsets.UTF_8).stream()
				.map(row -> "3" + row.substring(1))
				.toList();
		Files.write(sidecar, staleRows, StandardCharsets.UTF_8);
		Path unrelated = fixture.firstEndpoint().getParent().resolve("Plain.java");
		Files.writeString(unrelated, """
				package example;
				public final class Plain {}
				""", StandardCharsets.UTF_8);

		Assertions.assertFalse(fixture.compile(List.of(unrelated),
				"-Asoklet.cacheMode=sidecar"));
		assertIndexVersion(sidecar, "3");
	}

	@Test
	void touchedEndpointWithoutAnnotationIsRemovedFromClassOutputAndSidecar(
			@TempDir Path temporaryDirectory) throws IOException {
		Fixture fixture = createFixture(temporaryDirectory);
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint(),
				fixture.secondEndpoint()), "-Asoklet.cacheMode=sidecar"));

		Files.writeString(fixture.firstEndpoint(), """
				package example;
				public final class AEndpoint {}
				""", StandardCharsets.UTF_8);
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint()),
				"-Asoklet.cacheMode=sidecar"));

		Assertions.assertEquals(List.of("example.BEndpoint"), endpointNames(
				classOutputIndex(fixture.classDirectory())));
		Assertions.assertEquals(List.of("example.BEndpoint"), endpointNames(
				sidecarPath(fixture.classDirectory())));
	}

	@Test
	void currentClassOutputPreventsStaleSidecarFromResurrectingRemovedEndpoint(
			@TempDir Path temporaryDirectory) throws IOException {
		Fixture fixture = createFixture(temporaryDirectory);
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint(),
				fixture.secondEndpoint()), "-Asoklet.cacheMode=sidecar"));
		Path sidecar = sidecarPath(fixture.classDirectory());

		Files.writeString(fixture.firstEndpoint(), """
				package example;
				public final class AEndpoint {}
				""", StandardCharsets.UTF_8);
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint()),
				"-Asoklet.cacheMode=none"));
		Assertions.assertEquals(List.of("example.BEndpoint"), endpointNames(
				classOutputIndex(fixture.classDirectory())));
		Assertions.assertEquals(List.of("example.AEndpoint",
				"example.BEndpoint"), endpointNames(sidecar));

		Path unrelated = fixture.firstEndpoint().getParent().resolve("Plain.java");
		Files.writeString(unrelated, """
				package example;
				public final class Plain {}
				""", StandardCharsets.UTF_8);
		Assertions.assertTrue(fixture.compile(List.of(unrelated),
				"-Asoklet.cacheMode=sidecar"));

		Assertions.assertEquals(List.of("example.BEndpoint"), endpointNames(
				classOutputIndex(fixture.classDirectory())));
		Assertions.assertEquals(List.of("example.BEndpoint"), endpointNames(
				sidecar));
	}

	@Test
	void pruneDeletedRemovesMissingEndpointFromClassOutputAndSidecar(
			@TempDir Path temporaryDirectory) throws IOException {
		Fixture fixture = createFixture(temporaryDirectory);
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint(),
				fixture.secondEndpoint()), "-Asoklet.cacheMode=sidecar"));

		Files.delete(fixture.classDirectory().resolve(
				"example/BEndpoint.class"));
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint()),
				"-Asoklet.cacheMode=sidecar",
				"-Asoklet.pruneDeleted=true"));

		Assertions.assertEquals(List.of("example.AEndpoint"), endpointNames(
				classOutputIndex(fixture.classDirectory())));
		Assertions.assertEquals(List.of("example.AEndpoint"), endpointNames(
				sidecarPath(fixture.classDirectory())));
	}

	@Test
	void pruneDeletedRemovesEndpointWhoseGeneratedProviderIsMissing(
			@TempDir Path temporaryDirectory) throws IOException {
		Fixture fixture = createFixture(temporaryDirectory);
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint(),
				fixture.secondEndpoint()), "-Asoklet.cacheMode=sidecar"));

		Path classOutputIndex = classOutputIndex(fixture.classDirectory());
		String provider = providerName(classOutputIndex, "example.BEndpoint");
		Files.delete(fixture.classDirectory().resolve(
				provider.replace('.', '/') + ".class"));
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint()),
				"-Asoklet.cacheMode=sidecar",
				"-Asoklet.pruneDeleted=true"));

		Assertions.assertEquals(List.of("example.AEndpoint"), endpointNames(
				classOutputIndex(fixture.classDirectory())));
		Assertions.assertEquals(List.of("example.AEndpoint"), endpointNames(
				sidecarPath(fixture.classDirectory())));
	}

	@Test
	void persistentCacheRestoresRowsWhenClassOutputAndSidecarAreClean(
			@TempDir Path temporaryDirectory) throws IOException {
		Fixture fixture = createFixture(temporaryDirectory);
		Path cacheDirectory = temporaryDirectory.resolve("cache");
		String cacheOption = "-Asoklet.cacheDir=" + cacheDirectory;
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint(),
				fixture.secondEndpoint()), "-Asoklet.cacheMode=persistent",
				cacheOption));

		deleteRecursively(fixture.classDirectory());
		Files.createDirectories(fixture.classDirectory());
		Files.delete(sidecarPath(fixture.classDirectory()));
		Path secondGeneratedDirectory =
				temporaryDirectory.resolve("generated-second");
		Files.createDirectories(secondGeneratedDirectory);
		Assertions.assertTrue(compileWithSokletProcessor(
				fixture.classDirectory(), secondGeneratedDirectory,
				List.of(fixture.firstEndpoint()), List.of(
						"-Asoklet.cacheMode=persistent", cacheOption)));

		Assertions.assertEquals(List.of("example.AEndpoint",
				"example.BEndpoint"), endpointNames(classOutputIndex(
				fixture.classDirectory())));
	}

	@Test
	void duplicatePathAcrossSeparateCompilationsFailsAndPreservesIndex(
			@TempDir Path temporaryDirectory) throws IOException {
		Fixture fixture = createFixture(temporaryDirectory);
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint()),
				"-Asoklet.cacheMode=sidecar"));
		Files.writeString(fixture.secondEndpoint(),
				endpointSource("BEndpoint", "//a/"), StandardCharsets.UTF_8);

		Assertions.assertFalse(fixture.compile(List.of(fixture.secondEndpoint()),
				"-Asoklet.cacheMode=sidecar"));

		Assertions.assertEquals(List.of("example.AEndpoint"), endpointNames(
				classOutputIndex(fixture.classDirectory())));
		Assertions.assertEquals(List.of("example.AEndpoint"), endpointNames(
				sidecarPath(fixture.classDirectory())));
		Assertions.assertEquals(List.of("/a"), endpointPaths(
				classOutputIndex(fixture.classDirectory())));
	}

	@Test
	void pruneDeletedAllowsNewEndpointToReuseRemovedEndpointPath(
			@TempDir Path temporaryDirectory) throws IOException {
		Fixture fixture = createFixture(temporaryDirectory);
		Assertions.assertTrue(fixture.compile(List.of(fixture.firstEndpoint()),
				"-Asoklet.cacheMode=sidecar"));
		Files.delete(fixture.classDirectory().resolve(
				"example/AEndpoint.class"));
		Files.writeString(fixture.secondEndpoint(),
				endpointSource("BEndpoint", "/a"), StandardCharsets.UTF_8);

		Assertions.assertTrue(fixture.compile(List.of(fixture.secondEndpoint()),
				"-Asoklet.cacheMode=sidecar",
				"-Asoklet.pruneDeleted=true"));

		Assertions.assertEquals(List.of("example.BEndpoint"), endpointNames(
				classOutputIndex(fixture.classDirectory())));
		Assertions.assertEquals(List.of("/a"), endpointPaths(
				classOutputIndex(fixture.classDirectory())));
	}

	@Test
	void compilingDollarPrefixedTopLevelDoesNotRemoveDollarNamedEndpoint(
			@TempDir Path temporaryDirectory) throws IOException {
		Path sourceDirectory = temporaryDirectory.resolve("src/example");
		Path classDirectory = temporaryDirectory.resolve("classes");
		Path generatedDirectory = temporaryDirectory.resolve("generated");
		Files.createDirectories(sourceDirectory);
		Files.createDirectories(classDirectory);
		Files.createDirectories(generatedDirectory);
		Path ordinaryType = sourceDirectory.resolve("Foo.java");
		Path dollarNamedEndpoint = sourceDirectory.resolve("Foo$Bar.java");
		Files.writeString(ordinaryType, """
				package example;
				public final class Foo {}
				""", StandardCharsets.UTF_8);
		Files.writeString(dollarNamedEndpoint,
				endpointSource("Foo$Bar", "/dollar"), StandardCharsets.UTF_8);

		Assertions.assertTrue(compileWithSokletProcessor(classDirectory,
				generatedDirectory, List.of(ordinaryType, dollarNamedEndpoint),
				List.of("-Asoklet.cacheMode=sidecar")));
		Assertions.assertTrue(compileWithSokletProcessor(classDirectory,
				generatedDirectory, List.of(ordinaryType),
				List.of("-Asoklet.cacheMode=sidecar")));

		Path index = classOutputIndex(classDirectory);
		Assertions.assertEquals(List.of("example.Foo$Bar"),
				endpointNames(index));
		Assertions.assertEquals(List.of("example.Foo$Bar"),
				topLevelNames(index));
	}

	private static Fixture createFixture(Path temporaryDirectory)
			throws IOException {
		Path sourceDirectory = temporaryDirectory.resolve("src/example");
		Path classDirectory = temporaryDirectory.resolve("classes");
		Path generatedDirectory = temporaryDirectory.resolve("generated");
		Files.createDirectories(sourceDirectory);
		Files.createDirectories(classDirectory);
		Files.createDirectories(generatedDirectory);
		Path firstEndpoint = sourceDirectory.resolve("AEndpoint.java");
		Path secondEndpoint = sourceDirectory.resolve("BEndpoint.java");
		Files.writeString(firstEndpoint, endpointSource("AEndpoint", "/a"),
				StandardCharsets.UTF_8);
		Files.writeString(secondEndpoint, endpointSource("BEndpoint", "/b"),
				StandardCharsets.UTF_8);
		return new Fixture(classDirectory, generatedDirectory, firstEndpoint,
				secondEndpoint);
	}

	private static String endpointSource(String className, String path) {
		return """
				package example;
				import com.soklet.annotation.McpServerEndpoint;
				@McpServerEndpoint(path="%s", name="%s", version="1")
				public final class %s {}
				""".formatted(path, className, className);
	}

	private static Path classOutputIndex(Path classDirectory) {
		return classDirectory.resolve(MCP_INDEX_PATH);
	}

	private static Path sidecarPath(Path classDirectory) {
		return classDirectory.getParent().resolve("soklet")
				.resolve(classDirectory.getFileName())
				.resolve(MCP_CACHE_FILENAME);
	}

	private static List<String> endpointNames(Path index) throws IOException {
		List<String> names = new ArrayList<>();
		for (String row : Files.readAllLines(index, StandardCharsets.UTF_8)) {
			String[] fields = row.split("\\|", -1);
			Assertions.assertEquals(5, fields.length);
			names.add(new String(Base64.getDecoder().decode(fields[1]),
					StandardCharsets.UTF_8));
		}
		return names;
	}

	private static void assertIndexVersion(Path index, String expectedVersion)
			throws IOException {
		for (String row : Files.readAllLines(index, StandardCharsets.UTF_8)) {
			String[] fields = row.split("\\|", -1);
			Assertions.assertEquals(5, fields.length);
			Assertions.assertEquals(expectedVersion, fields[0]);
		}
	}

	private static List<String> topLevelNames(Path index) throws IOException {
		List<String> names = new ArrayList<>();
		for (String row : Files.readAllLines(index, StandardCharsets.UTF_8)) {
			String[] fields = row.split("\\|", -1);
			Assertions.assertEquals(5, fields.length);
			names.add(new String(Base64.getDecoder().decode(fields[3]),
					StandardCharsets.UTF_8));
		}
		return names;
	}

	private static List<String> endpointPaths(Path index) throws IOException {
		List<String> paths = new ArrayList<>();
		for (String row : Files.readAllLines(index, StandardCharsets.UTF_8)) {
			String[] fields = row.split("\\|", -1);
			Assertions.assertEquals(5, fields.length);
			paths.add(new String(Base64.getDecoder().decode(fields[4]),
					StandardCharsets.UTF_8));
		}
		return paths;
	}

	private static String providerName(Path index, String endpointName)
			throws IOException {
		for (String row : Files.readAllLines(index, StandardCharsets.UTF_8)) {
			String[] fields = row.split("\\|", -1);
			Assertions.assertEquals(5, fields.length);
			String endpoint = new String(Base64.getDecoder().decode(fields[1]),
					StandardCharsets.UTF_8);
			if (endpoint.equals(endpointName))
				return new String(Base64.getDecoder().decode(fields[2]),
						StandardCharsets.UTF_8);
		}
		throw new AssertionError("No provider indexed for " + endpointName);
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root))
			return;
		List<Path> paths;
		try (var stream = Files.walk(root)) {
			paths = stream.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path path : paths)
			Files.delete(path);
	}

	private static boolean compileWithSokletProcessor(Path classes,
			Path generated, List<Path> sources, List<String> processorOptions)
			throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler);
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
				null, null, StandardCharsets.UTF_8)) {
			String classpath = classes + System.getProperty("path.separator")
					+ System.getProperty("java.class.path");
			List<String> options = new ArrayList<>(List.of("--release", "17",
					"-parameters", "-classpath", classpath, "-d",
					classes.toString(), "-s", generated.toString()));
			options.addAll(processorOptions);
			JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager,
					null, options, null,
					fileManager.getJavaFileObjectsFromPaths(sources));
			task.setProcessors(List.of(new SokletProcessor()));
			return task.call();
		}
	}

	private record Fixture(Path classDirectory, Path generatedDirectory,
			Path firstEndpoint, Path secondEndpoint) {
		private boolean compile(List<Path> sources, String... processorOptions)
				throws IOException {
			return compileWithSokletProcessor(classDirectory,
					generatedDirectory, sources, List.of(processorOptions));
		}
	}
}

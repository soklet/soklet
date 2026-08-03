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

package com.soklet.internal.mcp.schema;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class JsonSchemaTestSuitePinTests {
	private static final Path SUITE_ROOT = Path.of(
			"src", "test", "resources", "com", "soklet", "internal", "mcp", "schema",
			"json-schema-test-suite");
	private static final Path PIN_PATH = SUITE_ROOT.resolve("upstream-pin.json");
	private static final Path MANIFEST_PATH = SUITE_ROOT.resolve("manifest.sha256");
	private static final String EXPECTED_REPOSITORY =
			"https://github.com/json-schema-org/JSON-Schema-Test-Suite";
	private static final String EXPECTED_COMMIT =
			"0c7b65dc16dd8eaa7bd83e21099c76610c3b246a";
	private static final String EXPECTED_ARCHIVE_SHA256 =
			"405fa34d133c5a5dd3280399e0dafa379bcbf5adb17d180bd7b1b1aaa5afaa1b";
	private static final String EXPECTED_MANIFEST_SHA256 =
			"70be2fa92b362ee738144c4d581bd6cf45b9f47ef4276a942a49eacf2bbbfa88";
	private static final String EXPECTED_LICENSE_SHA256 =
			"837402bd25fad9b704265801ca3f92566a98157c1f9a7acd6f446299ba1c305a";
	private static final int EXPECTED_IMPORTED_FILE_COUNT = 104;
	private static final int EXPECTED_REQUIRED_TEST_FILE_COUNT = 46;
	private static final int EXPECTED_OPTIONAL_TEST_FILE_COUNT = 34;
	private static final int EXPECTED_REMOTE_FILE_COUNT = 23;
	private static final Pattern MANIFEST_LINE_PATTERN = Pattern.compile(
			"([0-9a-f]{64})  ([A-Za-z0-9._/-]+)");
	private static final Pattern EXPECTED_PIN_PATTERN = Pattern.compile(
			"\\A\\s*\\{\\s*"
					+ "\"importFormat\"\\s*:\\s*1\\s*,\\s*"
					+ "\"repository\"\\s*:\\s*" + jsonString(EXPECTED_REPOSITORY) + "\\s*,\\s*"
					+ "\"commit\"\\s*:\\s*" + jsonString(EXPECTED_COMMIT) + "\\s*,\\s*"
					+ "\"archiveSha256\"\\s*:\\s*" + jsonString(EXPECTED_ARCHIVE_SHA256) + "\\s*,\\s*"
					+ "\"importedRoots\"\\s*:\\s*\\[\\s*"
					+ jsonString("LICENSE.upstream") + "\\s*,\\s*"
					+ jsonString("remotes/draft2019-09/ignore-prefixItems.json") + "\\s*,\\s*"
					+ jsonString("remotes/draft2020-12") + "\\s*,\\s*"
					+ jsonString("tests/draft2020-12") + "\\s*\\]\\s*,\\s*"
					+ "\"importedFileCount\"\\s*:\\s*" + EXPECTED_IMPORTED_FILE_COUNT + "\\s*,\\s*"
					+ "\"manifestSha256\"\\s*:\\s*" + jsonString(EXPECTED_MANIFEST_SHA256) + "\\s*,\\s*"
					+ "\"license\"\\s*:\\s*" + jsonString("MIT") + "\\s*,\\s*"
					+ "\"licenseSha256\"\\s*:\\s*" + jsonString(EXPECTED_LICENSE_SHA256)
					+ "\\s*\\}\\s*\\z");

	@Test
	public void upstreamPinIdentifiesTheExactApprovedSnapshot()
			throws IOException, NoSuchAlgorithmException {
		String pin = Files.readString(PIN_PATH, StandardCharsets.UTF_8);

		Assertions.assertTrue(EXPECTED_PIN_PATTERN.matcher(pin).matches(),
				"upstream-pin.json does not identify the approved JSON Schema Test Suite snapshot");
		Assertions.assertEquals(EXPECTED_MANIFEST_SHA256, sha256(MANIFEST_PATH),
				"manifest.sha256 is not the manifest authenticated by upstream-pin.json");
	}

	@Test
	public void manifestAuthenticatesTheExactImportedResourceTree()
			throws IOException, NoSuchAlgorithmException {
		ResourceTree resourceTree = scanResourceTree();
		Map<String, String> manifest = readManifest();

		Assertions.assertEquals(EXPECTED_IMPORTED_FILE_COUNT, manifest.size());
		Assertions.assertEquals(EXPECTED_LICENSE_SHA256, manifest.get("LICENSE.upstream"));

		for (Map.Entry<String, String> entry : manifest.entrySet()) {
			Path resource = SUITE_ROOT.resolve(entry.getKey());

			Assertions.assertTrue(Files.isRegularFile(resource, LinkOption.NOFOLLOW_LINKS),
					entry.getKey());
			Assertions.assertEquals(entry.getValue(), sha256(resource), entry.getKey());
		}

		Set<String> expectedFiles = new LinkedHashSet<>(manifest.keySet());
		expectedFiles.add("manifest.sha256");
		expectedFiles.add("upstream-pin.json");
		Assertions.assertEquals(expectedFiles, resourceTree.files(),
				"vendored suite contains missing or unmanifested files");
		Assertions.assertEquals(expectedDirectories(expectedFiles), resourceTree.directories(),
				"vendored suite contains missing or unexpected directories");
		Assertions.assertEquals(Set.of(), resourceTree.otherEntries(),
				"vendored suite contains non-file, non-directory entries");

		long requiredTestFiles = manifest.keySet().stream()
				.filter(JsonSchemaTestSuitePinTests::isRequiredTestFile)
				.count();
		long optionalTestFiles = manifest.keySet().stream()
				.filter(path -> path.startsWith("tests/draft2020-12/optional/"))
				.count();
		long remoteFiles = manifest.keySet().stream()
				.filter(path -> path.startsWith("remotes/"))
				.count();

		Assertions.assertEquals(EXPECTED_REQUIRED_TEST_FILE_COUNT, requiredTestFiles);
		Assertions.assertEquals(EXPECTED_OPTIONAL_TEST_FILE_COUNT, optionalTestFiles);
		Assertions.assertEquals(EXPECTED_REMOTE_FILE_COUNT, remoteFiles);
		Assertions.assertEquals(
				EXPECTED_IMPORTED_FILE_COUNT,
				1 + requiredTestFiles + optionalTestFiles + remoteFiles,
				"every imported file must be the license, a required test, an optional test, or a remote");
	}

	private static Map<String, String> readManifest() throws IOException {
		List<String> lines = Files.readAllLines(MANIFEST_PATH, StandardCharsets.US_ASCII);
		Map<String, String> manifest = new LinkedHashMap<>();

		Assertions.assertEquals(EXPECTED_IMPORTED_FILE_COUNT, lines.size());

		for (String line : lines) {
			var matcher = MANIFEST_LINE_PATTERN.matcher(line);
			Assertions.assertTrue(matcher.matches(), () -> "Malformed manifest record: " + line);
			String relativePath = matcher.group(2);
			assertSafeRelativePath(relativePath);
			Assertions.assertNull(manifest.put(relativePath, matcher.group(1)),
					() -> "Duplicate manifest path: " + relativePath);
		}

		return manifest;
	}

	private static ResourceTree scanResourceTree() throws IOException {
		Assertions.assertTrue(Files.isDirectory(SUITE_ROOT, LinkOption.NOFOLLOW_LINKS),
				"vendored JSON Schema Test Suite root is missing");
		Set<String> files = new LinkedHashSet<>();
		Set<String> directories = new TreeSet<>();
		Set<String> otherEntries = new TreeSet<>();

		try (var paths = Files.walk(SUITE_ROOT)) {
			for (Path path : paths.toList()) {
				Assertions.assertFalse(Files.isSymbolicLink(path),
						() -> "Symbolic links are forbidden in the vendored suite: " + path);

				if (path.equals(SUITE_ROOT))
					continue;

				String relativePath = portableRelativePath(path);

				if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					files.add(relativePath);
				else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					directories.add(relativePath);
				else
					otherEntries.add(relativePath);
			}
		}

		return new ResourceTree(files, directories, otherEntries);
	}

	private static Set<String> expectedDirectories(Set<String> expectedFiles) {
		Set<String> expectedDirectories = new TreeSet<>();

		for (String expectedFile : expectedFiles) {
			Path parent = Path.of(expectedFile).getParent();

			while (parent != null) {
				expectedDirectories.add(portablePath(parent));
				parent = parent.getParent();
			}
		}

		return expectedDirectories;
	}

	private static void assertSafeRelativePath(String relativePath) {
		Path path = Path.of(relativePath);

		Assertions.assertFalse(path.isAbsolute(), relativePath);
		Assertions.assertFalse(relativePath.startsWith("/"), relativePath);
		Assertions.assertFalse(relativePath.contains("\\"), relativePath);
		Assertions.assertFalse(relativePath.contains("//"), relativePath);
		Assertions.assertEquals(relativePath, portablePath(path.normalize()), relativePath);
		Assertions.assertTrue(SUITE_ROOT.resolve(path).normalize().startsWith(SUITE_ROOT),
				relativePath);
	}

	private static boolean isRequiredTestFile(String path) {
		return path.startsWith("tests/draft2020-12/")
				&& !path.startsWith("tests/draft2020-12/optional/");
	}

	private static String portableRelativePath(Path path) {
		return portablePath(SUITE_ROOT.relativize(path));
	}

	private static String portablePath(Path path) {
		List<String> elements = new ArrayList<>();
		path.forEach(element -> elements.add(element.toString()));
		return String.join("/", elements);
	}

	private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
		MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
		return HexFormat.of().formatHex(sha256.digest(Files.readAllBytes(path)));
	}

	private static String jsonString(String value) {
		return "\"" + Pattern.quote(value) + "\"";
	}

	private record ResourceTree(
			Set<String> files,
			Set<String> directories,
			Set<String> otherEntries) {
	}
}

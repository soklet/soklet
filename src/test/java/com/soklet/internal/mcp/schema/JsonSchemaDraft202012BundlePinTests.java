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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class JsonSchemaDraft202012BundlePinTests {
	private static final String RESOURCE_ROOT =
			"com/soklet/internal/mcp/schema/draft-2020-12/";
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(32_768, 64, 16_384, 16_384,
					1_024, 100_000, 20_000, 32_768));
	private static final Map<String, String> FILE_HASHES = fileHashes();

	@Test
	public void authenticatesEveryPackagedBundleFileIndependently()
			throws IOException {
		Assertions.assertEquals(
				"3c7a6495a01028e007b0afe3841e0523871bc3afd4d7d788c95c9f30633b200c",
				sha256(read("manifest.sha256")));
		Assertions.assertEquals(
				"17669be20eb59aad1b4a953c99501b55cb8332d6f0ebf7fb4746177081d6632b",
				sha256(read("upstream-pin.json")));

		String manifest = new String(read("manifest.sha256"), StandardCharsets.US_ASCII);
		StringBuilder expectedManifest = new StringBuilder();
		for (Map.Entry<String, String> entry : FILE_HASHES.entrySet()) {
			byte[] bytes = read(entry.getKey());
			Assertions.assertEquals(entry.getValue(), sha256(bytes), entry.getKey());
			expectedManifest.append(entry.getValue()).append("  ")
					.append(entry.getKey()).append('\n');
			if (entry.getKey().endsWith(".json"))
				JSON_CODEC.parse(bytes);
		}
		Assertions.assertEquals(expectedManifest.toString(), manifest);

		McpJsonValue pin = JSON_CODEC.parse(read("upstream-pin.json"));
		Assertions.assertInstanceOf(McpJsonObject.class, pin);
	}

	@Test
	public void loadsAllNineOfficialSchemaResources() {
		Assertions.assertEquals(9, McpSchemaDraft202012Bundle.documents().size());
		Assertions.assertEquals(9, McpSchemaDraft202012Bundle.documents().stream()
				.map(McpSchemaDocument::retrievalUri).distinct().count());
		Assertions.assertTrue(McpSchemaDraft202012Bundle.documents().stream()
				.anyMatch(document -> document.retrievalUri().toString()
						.endsWith("/meta/format-assertion")));
	}

	private static byte[] read(String path) throws IOException {
		String resourceName = RESOURCE_ROOT + path;
		try (InputStream input = JsonSchemaDraft202012BundlePinTests.class
				.getClassLoader().getResourceAsStream(resourceName)) {
			Assertions.assertNotNull(input, resourceName);
			return input.readAllBytes();
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Map<String, String> fileHashes() {
		Map<String, String> hashes = new LinkedHashMap<>();
		hashes.put("LICENSE.upstream",
				"909b25a80d4945b21d3adb2fb17f90bf592e0274bdf117c069c088a8e44dc7b6");
		hashes.put("README.upstream.md",
				"54848e8b5b5932577091349eef76cc567be48d31f760c184bb364be0c758477b");
		hashes.put("meta/applicator.json",
				"bf273b26f9f735b93ece78f2b61b36676e1d122ce78ab37ad5a2e45dfa1ca2b1");
		hashes.put("meta/content.json",
				"a10456605b2b5bb12a1b4dcfc0300f02f54d3e8bb3646bed7724583866627682");
		hashes.put("meta/core.json",
				"21f79d143fab1f180245c331e5657057045b36794d41fe151e6e4fed65035299");
		hashes.put("meta/format-annotation.json",
				"5c79404f831dd905c0f40fefac7c6f3e51bf3729b4a876a5c2020178d97f3bcc");
		hashes.put("meta/format-assertion.json",
				"6a5a8e13c605e3eff51f9bf8da18078880d81ff1634e391760ccc2e16ee2146f");
		hashes.put("meta/meta-data.json",
				"c664d438a84d58889c8edecd248ce2f945a4bc0e3b087323b11303dc136abfbe");
		hashes.put("meta/unevaluated.json",
				"fc99f32188da41689a9382af174dd42e8b255e4374965c157b8286556b4ab2bc");
		hashes.put("meta/validation.json",
				"e921c5b79264d3689af01c1af1ffdf692e09f1c45df90a0f08eb7288c9acdeab");
		hashes.put("schema.json",
				"41da76f5afb7ce062d248f762463a92f7ca47e4e0f905b224ba6afeef91ded0f");
		return Collections.unmodifiableMap(hashes);
	}
}

/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpSkillRegistrationTests {
	private static final URI ROOT_URI = URI.create("skill://test-skill/SKILL.md");
	private static final byte[] ROOT = "---\nname: test-skill\ndescription: Description\n---\nBody\n".getBytes(StandardCharsets.UTF_8);

	@Test
	void defaultsKeepOriginalUriAndBundleAndCacheTheImmutableResourceViews() {
		McpSkillBundle bundle = bundle();
		McpSkillRegistration registration = McpSkillRegistration.withUriAndSkillBundle(ROOT_URI, bundle).build();
		assertSame(ROOT_URI, registration.getUri());
		assertSame(ROOT_URI, registration.getResources().get(0).getUri());
		assertSame(bundle, registration.getSkillBundle());
		assertTrue(registration.getLocale().isEmpty());
		assertSame(McpCachePolicy.privateNoCacheInstance(), registration.getCachePolicy());
		assertSame(registration.getResources(), registration.getResources());
		assertThrows(UnsupportedOperationException.class, () -> registration.getResources().clear());
	}

	@Test
	void builderChangesDoNotMutatePublishedValuesAndNullSettersAreFailAtomic() {
		McpSkillRegistration.Builder builder = McpSkillRegistration.withUriAndSkillBundle(ROOT_URI, bundle());
		McpCachePolicy privatePolicy = McpCachePolicy.fromPrivateTimeToLive(Duration.ofSeconds(5));
		McpSkillRegistration first = builder.locale(Locale.CANADA_FRENCH).cachePolicy(privatePolicy).build();
		assertThrows(NullPointerException.class, () -> builder.locale(null));
		assertThrows(NullPointerException.class, () -> builder.cachePolicy(null));
		assertEquals(first, builder.build());
		McpSkillRegistration second = builder.locale(Locale.JAPANESE)
				.cachePolicy(McpCachePolicy.privateNoCacheInstance()).build();
		assertEquals(Locale.CANADA_FRENCH, first.getLocale().orElseThrow());
		assertSame(privatePolicy, first.getCachePolicy());
		assertEquals(Locale.JAPANESE, second.getLocale().orElseThrow());
		assertNotEquals(first, second);
	}

	@Test
	void generatedResourcesUseEncodedUrisAndExactRawByteDigestsAndSizes() throws Exception {
		String path = "refs/café space+#?.bin";
		byte[] binary = {0, (byte) 0xff, 2};
		McpSkillBundle bundle = McpSkillBundle.fromFiles(Map.of("SKILL.md", ROOT, path, binary));
		McpSkillRegistration registration = McpSkillRegistration.withUriAndSkillBundle(ROOT_URI, bundle).build();
		assertEquals(URI.create("skill://test-skill/refs/caf%C3%A9%20space%2B%23%3F.bin"),
				registration.getResources().get(1).getUri());
		for (int index = 0; index < 2; ++index) {
			byte[] bytes = index == 0 ? ROOT : binary;
			McpSkillRegistration.Resource resource = registration.getResources().get(index);
			assertEquals(Long.valueOf(bytes.length), resource.getSizeInBytes());
			assertEquals("sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
					resource.getDigest());
		}
	}

	@Test
	void registrationAndResourceEqualityFollowUriIdentityAndStructuralValues() {
		McpSkillRegistration first = McpSkillRegistration.withUriAndSkillBundle(
				URI.create("SKILL://HOST.invalid/test-skill/SKILL.md"), bundle()).build();
		McpSkillRegistration equal = McpSkillRegistration.withUriAndSkillBundle(
				URI.create("skill://host.invalid/test-skill/SKILL.md"), bundle()).build();
		assertEquals(first, equal);
		assertEquals(first.hashCode(), equal.hashCode());
		assertEquals(first.getResources(), equal.getResources());
		assertEquals(first.getResources().get(0).hashCode(), equal.getResources().get(0).hashCode());
		assertNotEquals(first, McpSkillRegistration.withUriAndSkillBundle(ROOT_URI, bundle()).build());
		assertNotEquals(first, McpSkillRegistration.withUriAndSkillBundle(first.getUri(), bundle()).locale(Locale.ROOT).build());
		assertNotEquals(first, McpSkillRegistration.withUriAndSkillBundle(first.getUri(), bundle())
				.cachePolicy(McpCachePolicy.fromPrivateTimeToLive(Duration.ofSeconds(1))).build());
		McpSkillBundle changed = McpSkillBundle.fromFiles(Map.of("SKILL.md", ROOT, "extra", new byte[0]));
		assertNotEquals(first, McpSkillRegistration.withUriAndSkillBundle(first.getUri(), changed).build());
		assertNotEquals(first, null);
		assertNotEquals(first.getResources().get(0), "resource");
	}

	@Test
	void invalidRootsAndNullRequiredArgumentsFailWithoutExposingInput() {
		McpSkillBundle bundle = bundle();
		assertThrows(NullPointerException.class, () -> McpSkillRegistration.withUriAndSkillBundle(null, bundle));
		assertThrows(NullPointerException.class, () -> McpSkillRegistration.withUriAndSkillBundle(ROOT_URI, null));
		for (String uri : List.of("skill://private-canary/SKILL.md", "skill://test-skill/wrong.md",
				"skill://test-skill/SKILL.md?private-canary")) {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
					() -> McpSkillRegistration.withUriAndSkillBundle(URI.create(uri), bundle).build());
			assertFalse(failure.getMessage().contains("private-canary"));
			assertNull(failure.getCause());
		}
	}

	@Test
	void diagnosticsNeverExposeRegistrationOrResourceContent() {
		McpSkillRegistration registration = McpSkillRegistration.withUriAndSkillBundle(ROOT_URI, bundle()).build();
		assertEquals("McpSkillRegistration[redacted]", registration.toString());
		assertEquals("McpSkillRegistration.Resource[redacted]", registration.getResources().get(0).toString());
	}

	private static McpSkillBundle bundle() { return McpSkillBundle.fromFiles(Map.of("SKILL.md", ROOT)); }
}

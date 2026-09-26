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

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RFC 6750 and RFC 9728 challenge output and admission integration. */
class BearerAuthenticationChallengeTests {
	private static final URI METADATA_URI = URI.create(
			"https://api.example.com/.well-known/oauth-protected-resource/mcp");

	@Test
	void missingCredentialsCanGiveInitialScopeGuidanceWithoutAnError() {
		BearerAuthenticationChallenge challenge =
				BearerAuthenticationChallenge.withResourceMetadataUri(METADATA_URI)
						.requiredScopes(List.of("mcp:discover", "mcp:discover"))
						.build();

		assertEquals(401, challenge.getRecommendedStatusCode());
		assertTrue(challenge.getError().isEmpty());
		assertEquals(List.of("mcp:discover"), challenge.getRequiredScopes());
		assertEquals("Bearer scope=\"mcp:discover\", resource_metadata=\""
				+ METADATA_URI + "\"", challenge.getHeaderValue());
		assertThrows(UnsupportedOperationException.class,
				() -> challenge.getRequiredScopes().add("later"));
	}

	@Test
	void insufficientScopeUsesOneSpaceSeparatedScopeValueAndA403() {
		BearerAuthenticationChallenge challenge =
				BearerAuthenticationChallenge.withResourceMetadataUri(METADATA_URI)
						.error(BearerAuthenticationError.INSUFFICIENT_SCOPE)
						.requiredScopes(List.of("mcp:tasks:listen",
								"mcp:resources:read"))
						.build();
		assertEquals(403, challenge.getRecommendedStatusCode());
		assertEquals("Bearer error=\"insufficient_scope\", "
				+ "scope=\"mcp:tasks:listen mcp:resources:read\", "
				+ "resource_metadata=\"" + METADATA_URI + "\"",
				challenge.getHeaderValue());
		McpAdmissionRejection rejection = McpAdmissionRejection
				.withBearerAuthenticationChallengeAndError(challenge,
						McpJsonRpcError.fromApplication(-31903,
								"Operation not permitted"))
				.build();
		assertEquals(403, rejection.getStatusCode());
		assertEquals(Map.of("WWW-Authenticate",
				List.of(challenge.getHeaderValue())), rejection.getHeaders());
	}

	@Test
	void invalidTokenAndRealmUseValidQuotedParameters() {
		BearerAuthenticationChallenge challenge =
				BearerAuthenticationChallenge.withResourceMetadataUri(METADATA_URI)
						.error(BearerAuthenticationError.INVALID_TOKEN)
						.realm("catalog \"main\"")
						.errorDescription("Token expired")
						.build();
		assertEquals(401, challenge.getRecommendedStatusCode());
		assertTrue(challenge.getHeaderValue().startsWith(
				"Bearer realm=\"catalog \\\"main\\\"\", "
						+ "error=\"invalid_token\""));
		assertTrue(challenge.getHeaderValue().endsWith(
				"error_description=\"Token expired\""));
	}

	@Test
	void rejectsInvalidCharactersAndContradictoryErrorFields() {
		assertThrows(IllegalArgumentException.class,
				() -> BearerAuthenticationChallenge.withResourceMetadataUri(
						URI.create("relative-metadata")));
		assertThrows(IllegalArgumentException.class,
				() -> BearerAuthenticationChallenge.withResourceMetadataUri(
						URI.create(METADATA_URI + "#fragment")));
		assertThrows(IllegalArgumentException.class,
				() -> BearerAuthenticationChallenge.withResourceMetadataUri(METADATA_URI)
						.requiredScopes(List.of("scope with space")));
		assertThrows(IllegalArgumentException.class,
				() -> BearerAuthenticationChallenge.withResourceMetadataUri(METADATA_URI)
						.error(BearerAuthenticationError.INSUFFICIENT_SCOPE)
						.build());
		assertThrows(IllegalArgumentException.class,
				() -> BearerAuthenticationChallenge.withResourceMetadataUri(METADATA_URI)
						.errorDescription("No error")
						.build());
		assertThrows(IllegalArgumentException.class,
				() -> BearerAuthenticationChallenge.withResourceMetadataUri(METADATA_URI)
						.error(BearerAuthenticationError.INVALID_TOKEN)
						.errorDescription("bad\r\nheader"));
		assertThrows(IllegalArgumentException.class,
				() -> BearerAuthenticationChallenge.withResourceMetadataUri(METADATA_URI)
						.error(BearerAuthenticationError.INVALID_TOKEN)
						.errorUri(URI.create("relative-error")));
	}
}

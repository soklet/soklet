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

package com.soklet.internal.mcp.protocol;

import com.soklet.HttpMethod;
import com.soklet.Request;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Canary coverage for protocol-runtime diagnostic carriers.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpPrivacyBoundaryInternalTests {
	private static final String SECRET = "privacy-canary-b63fc114";

	@Test
	void runtimeObservationInputDiagnosticDoesNotRenderExactRequestValues() {
		Request request = Request.withRawUrl(HttpMethod.POST,
				"/" + SECRET + "?query=" + SECRET)
				.id(SECRET)
				.headers(Map.of("Authorization", Set.of("Bearer " + SECRET)))
				.build();
		McpJsonObject secretJson = new McpJsonObject(
				Map.of(SECRET, new McpJsonString(SECRET)));
		McpRuntimeRequestInput input = new McpRuntimeRequestInput(request,
				Map.of(SECRET, SECRET), SECRET,
				Optional.of(new McpJsonRpcId.StringId(SECRET)), "2026-07-28",
				Optional.of(SECRET), Optional.of(
						McpImplementationMetadata.withNameAndVersion(SECRET, SECRET)),
				secretJson, secretJson, secretJson, Optional.empty(),
				List.of(SECRET), McpAdmissionIdentity.anonymousInstance());

		String diagnostic = input.toString();
		assertFalse(diagnostic.contains(SECRET), diagnostic);
		assertEquals(SECRET, input.requestId()
				.map(McpJsonRpcId.StringId.class::cast)
				.map(McpJsonRpcId.StringId::value)
				.orElseThrow());
		assertEquals(SECRET, input.request().getPath().substring(1));
	}
}

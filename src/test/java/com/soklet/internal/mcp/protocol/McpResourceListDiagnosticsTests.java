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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class McpResourceListDiagnosticsTests {
	@Test
	public void routeDiagnosticsIdentifyUriAndEndpointPath() {
		Assertions.assertEquals(
				"A resource-list page for endpoint '/catalog' contains a duplicate URI "
						+ "'test://resource/one'.",
				McpHttpServerRuntime.resourceListRouteDiagnostic(true,
						"test://resource/one", "/catalog"));
		Assertions.assertEquals(
				"A resource-list page for endpoint '/catalog' contains an unreadable URI "
						+ "'test://resource/two'.",
				McpHttpServerRuntime.resourceListRouteDiagnostic(false,
						"test://resource/two", "/catalog"));
	}

	@Test
	public void routeDiagnosticValuesAreBounded() {
		String endpointPath = "/" + "p".repeat(1_000);
		String uri = "test:///" + "u".repeat(1_000);
		String boundedEndpointPath = endpointPath.substring(0, 253) + "...";
		String boundedUri = uri.substring(0, 253) + "...";

		Assertions.assertEquals(
				"A resource-list page for endpoint '" + boundedEndpointPath
						+ "' contains an unreadable URI '" + boundedUri + "'.",
				McpHttpServerRuntime.resourceListRouteDiagnostic(false, uri,
						endpointPath));
	}
}

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

import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestObservationInput;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.Map;
import java.util.Optional;

@NotThreadSafe
public class McpRequestStateContextPlumbingTests {
	@Test
	public void observation_request_state_projects_into_the_public_context() {
		McpApplicationRequestState requestState =
				new McpApplicationRequestState("opaque-state");
		RequestObservationInput input = new RequestObservationInput(
				request(), endpoint(), Map.of(), "tools/call",
				Optional.of(McpRequestId.fromString("request")), "2026-07-28",
				Optional.of("lookup"), Optional.empty(),
				McpJsonObject.emptyInstance(), McpJsonObject.emptyInstance(),
				McpInputResponses.emptyInstance(), Optional.of(requestState),
				McpAdmissionIdentity.anonymousInstance());

		DefaultMcpRequestContext context = new DefaultMcpRequestContext(input);

		Assertions.assertSame(requestState,
				context.getRequestState().orElseThrow());
	}

	@Test
	public void compatibility_observation_constructor_defaults_request_state_to_absent() {
		RequestObservationInput input = new RequestObservationInput(
				request(), endpoint(), Map.of(), "tools/call",
				Optional.of(McpRequestId.fromString("request")), "2026-07-28",
				Optional.of("lookup"), Optional.empty(),
				McpJsonObject.emptyInstance(), McpJsonObject.emptyInstance(),
				McpInputResponses.emptyInstance(),
				McpAdmissionIdentity.anonymousInstance());

		DefaultMcpRequestContext context = new DefaultMcpRequestContext(input);

		Assertions.assertTrue(context.getRequestState().isEmpty());
	}

	private static Request request() {
		return Request.withPath(HttpMethod.POST, "/mcp").build();
	}

	private static McpEndpoint endpoint() {
		return McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation
						.withNameAndVersion("test-server", "1")
						.build())
				.build();
	}
}

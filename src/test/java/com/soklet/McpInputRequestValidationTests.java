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
import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contract coverage for input-request parameter and response correlation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpInputRequestValidationTests {
	@Test
	public void matchesTheDeclaredResponseUnionBranchForEveryRequestType() {
		McpInputRequest form = request(
				McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.REQUIRED), formParams());
		McpInputRequest url = request(
				McpInputRequestDeclaration.fromElicitationUrl(
						McpInputRequirement.CONDITIONAL), urlParams());
		McpInputRequest sampling = request(
				McpInputRequestDeclaration.fromSampling(Set.of(
						McpClientCapability.SAMPLING_CONTEXT,
						McpClientCapability.SAMPLING_TOOLS),
						McpInputRequirement.CONDITIONAL), samplingParams());
		McpInputRequest roots = request(
				McpInputRequestDeclaration.fromRoots(
						McpInputRequirement.REQUIRED),
				McpJsonObject.emptyInstance());

		McpJsonObject elicitationResponse = McpJsonObject.builder()
				.put("action", "accept")
				.put("content", McpJsonObject.builder().put("answer", "yes")
						.build())
				.put("com.example/future", true)
				.build();
		McpJsonObject samplingResponse = McpJsonObject.builder()
				.put("model", "test-model")
				.put("role", "assistant")
				.put("content", McpJsonObject.builder()
						.put("type", "text")
						.put("text", "done")
						.put("com.example/future", true)
						.build())
				.put("com.example/future", true)
				.build();
		McpJsonObject rootsResponse = McpJsonObject.builder()
				.put("roots", McpJsonArray.builder()
						.add(McpJsonObject.builder()
								.put("uri", "file:///tmp/project")
								.put("com.example/future", true)
								.build())
						.build())
				.put("com.example/future", true)
				.build();

		Assertions.assertTrue(form.matchesInputResponse(elicitationResponse));
		Assertions.assertTrue(url.matchesInputResponse(elicitationResponse));
		Assertions.assertTrue(sampling.matchesInputResponse(samplingResponse));
		Assertions.assertTrue(roots.matchesInputResponse(rootsResponse));
		Assertions.assertFalse(form.matchesInputResponse(rootsResponse));
		Assertions.assertFalse(url.matchesInputResponse(samplingResponse));
		Assertions.assertFalse(sampling.matchesInputResponse(rootsResponse));
		Assertions.assertFalse(roots.matchesInputResponse(elicitationResponse));

		for (McpInputRequest inputRequest
				: List.of(form, url, sampling, roots)) {
			Assertions.assertFalse(inputRequest.matchesInputResponse(
					McpJsonObject.emptyInstance()));
			Assertions.assertFalse(inputRequest.matchesInputResponse(
					McpJsonString.fromValue("not-an-input-response")));
			Assertions.assertThrows(NullPointerException.class,
					() -> inputRequest.matchesInputResponse(null));
		}
	}

	@Test
	public void methodSpecificParamsAreValidatedAtTheProtocolBoundarySeam() {
		List<McpInputRequest> valid = List.of(
				request(McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.REQUIRED), formParams()),
				request(McpInputRequestDeclaration.fromElicitationUrl(
						McpInputRequirement.REQUIRED), urlParams()),
				request(McpInputRequestDeclaration.fromSampling(Set.of(
						McpClientCapability.SAMPLING_CONTEXT,
						McpClientCapability.SAMPLING_TOOLS),
						McpInputRequirement.REQUIRED), samplingParams()),
				request(McpInputRequestDeclaration.fromRoots(
						McpInputRequirement.REQUIRED),
						McpJsonObject.emptyInstance()));
		valid.forEach(inputRequest -> Assertions.assertDoesNotThrow(
				inputRequest::requireValidParams));

		List<McpInputRequest> invalid = List.of(
				request(McpInputRequestDeclaration.fromElicitationForm(
						McpInputRequirement.REQUIRED),
						McpJsonObject.builder().put("message", "Missing schema")
								.build()),
				request(McpInputRequestDeclaration.fromElicitationUrl(
						McpInputRequirement.REQUIRED),
						McpJsonObject.builder()
								.put("mode", "url")
								.put("message", "Relative URL")
								.put("url", "relative")
								.build()),
				request(McpInputRequestDeclaration.fromSampling(Set.of(),
						McpInputRequirement.REQUIRED),
						samplingParamsWithoutMaximumTokens()),
				request(McpInputRequestDeclaration.fromRoots(
						McpInputRequirement.REQUIRED),
						McpJsonObject.builder().put("_meta", "not-an-object")
								.build()));
		for (McpInputRequest inputRequest : invalid) {
			IllegalArgumentException exception = Assertions.assertThrows(
					IllegalArgumentException.class,
					inputRequest::requireValidParams);
			Assertions.assertEquals(
					"Embedded MCP input-request parameters are invalid.",
					exception.getMessage());
		}
	}

	@NonNull
	private static McpInputRequest request(
			@NonNull McpInputRequestDeclaration declaration,
			@NonNull McpJsonObject params) {
		return McpInputRequest.fromDeclaration(declaration, params);
	}

	@NonNull
	private static McpJsonObject formParams() {
		return McpJsonObject.builder()
				.put("mode", "form")
				.put("message", "Choose an answer")
				.put("requestedSchema", McpJsonObject.builder()
						.put("type", "object")
						.put("properties", McpJsonObject.builder()
								.put("answer", McpJsonObject.builder()
										.put("type", "string")
										.build())
								.build())
						.build())
				.build();
	}

	@NonNull
	private static McpJsonObject urlParams() {
		return McpJsonObject.builder()
				.put("mode", "url")
				.put("message", "Authorize access")
				.put("url", "https://example.com/authorize")
				.build();
	}

	@NonNull
	private static McpJsonObject samplingParams() {
		return McpJsonObject.fromMembers(Map.of(
				"messages", messages(),
				"maxTokens", McpJsonNumber.fromValue(BigDecimal.valueOf(16)),
				"includeContext", McpJsonString.fromValue("allServers"),
				"tools", McpJsonArray.builder()
						.add(McpJsonObject.builder()
								.put("name", "lookup")
								.put("inputSchema", McpJsonObject.builder()
										.put("type", "object")
										.build())
								.build())
						.build()));
	}

	@NonNull
	private static McpJsonObject samplingParamsWithoutMaximumTokens() {
		return McpJsonObject.builder().put("messages", messages()).build();
	}

	@NonNull
	private static McpJsonArray messages() {
		return McpJsonArray.builder()
				.add(McpJsonObject.builder()
						.put("role", "user")
						.put("content", McpJsonObject.builder()
								.put("type", "text")
								.put("text", "Hello")
								.build())
						.build())
				.build();
	}
}

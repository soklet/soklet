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

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the public staged MCP prompt-registration surface.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpPromptRegistrationTests {
	@Test
	void promptTextFactoriesWrapTextWithTheRequestedRole() {
		McpPromptMessage user = McpPromptMessage.fromUserText("user text");
		McpPromptMessage assistant =
				McpPromptMessage.fromAssistantText("assistant text");

		assertEquals(McpRole.USER, user.getRole());
		assertEquals("user text", assertInstanceOf(McpTextContent.class,
				user.getContent()).getText());
		assertEquals(McpRole.ASSISTANT, assistant.getRole());
		assertEquals("assistant text", assertInstanceOf(McpTextContent.class,
				assistant.getContent()).getText());
		assertThrows(NullPointerException.class,
				() -> McpPromptMessage.fromUserText(null));
		assertThrows(NullPointerException.class,
				() -> McpPromptMessage.fromAssistantText(null));
	}

	@Test
	void promptMessagesExposeValueSemanticsWithoutRenderingContent() {
		String secret = "prompt-content-secret";
		McpTextContent content = McpTextContent.fromText(secret);
		McpPromptMessage user = McpPromptMessage.fromUserContent(content);
		McpPromptMessage equalUser = McpPromptMessage.fromUserContent(content);
		McpPromptMessage assistant =
				McpPromptMessage.fromAssistantContent(content);

		assertEquals(McpRole.USER, user.getRole());
		assertSame(content, user.getContent());
		assertEquals(user, equalUser);
		assertEquals(user.hashCode(), equalUser.hashCode());
		assertFalse(user.equals(assistant));
		assertEquals("McpPromptMessage{role=USER, content=<redacted>}",
				user.toString());
		assertFalse(user.toString().contains(secret));
		assertThrows(NullPointerException.class,
				() -> McpPromptMessage.fromUserContent(null));
		assertThrows(NullPointerException.class,
				() -> McpPromptMessage.fromAssistantContent(null));
	}

	@Test
	void registrationPreservesDescriptorAndExactStringArguments()
			throws Exception {
		McpPromptArgumentDeclaration required = McpPromptArgumentDeclaration
				.withName("audience")
				.title("Audience")
				.description("Intended audience")
				.required(true)
				.build();
		McpPromptArgumentDeclaration optional = McpPromptArgumentDeclaration
				.withName("tone")
				.required(false)
				.build();
		McpIcon icon = McpIcon.withSource(
				URI.create("https://example.com/prompt.png")).build();
		AtomicReference<McpPromptGetContext> observed = new AtomicReference<>();
		McpPromptRegistration registration = McpPromptRegistration
				.withName("catalog.recommend")
				.handler((request, prompt, features) -> {
					observed.set(prompt);
					return McpCompleteResult.fromPromptOutput(
							McpPromptOutput.fromMessages(
									McpPromptMessage.fromUserContent(
											McpTextContent.fromText(
													prompt.findArgument("audience")
															.orElseThrow()))));
				})
				.title("Recommend")
				.description("Builds a recommendation prompt")
				.addIcon(icon)
				.addArgument(required)
				.addArgument(optional)
				.metadata(McpJsonObject.builder().put("owner", "catalog").build())
				.build();
		McpJsonObject input = McpJsonObject.builder()
				.put("audience", "  Amélie  ")
				.put("tone", "")
				.build();

		McpCompleteResult result = assertInstanceOf(McpCompleteResult.class,
				registration.invoke(requestContext(), input,
						McpInvocationFeatures.fromFeatures(Map.of())));
		McpPromptOutput output = assertInstanceOf(McpPromptOutput.class,
				result.getPayload());

		assertEquals("catalog.recommend", registration.getName());
		assertEquals("Recommend", registration.getTitle().orElseThrow());
		assertEquals("Builds a recommendation prompt",
				registration.getDescription().orElseThrow());
		assertEquals(List.of(icon), registration.getIcons());
		assertEquals(List.of(required, optional), registration.getArguments());
		assertEquals("catalog", ((McpJsonString) registration.getMetadata()
				.find("owner").orElseThrow()).getValue());
		assertEquals("Audience", required.getTitle().orElseThrow());
		assertEquals("Intended audience",
				required.getDescription().orElseThrow());
		assertTrue(required.isRequired());
		assertFalse(optional.isRequired());
		assertEquals("  Amélie  ",
				observed.get().findArgument("audience").orElseThrow());
		assertEquals("", observed.get().findArgument("tone").orElseThrow());
		assertEquals(input.getMembers().keySet(),
				observed.get().getArguments().keySet());
		assertThrows(UnsupportedOperationException.class,
				() -> observed.get().getArguments().clear());
		assertEquals("  Amélie  ", ((McpTextContent) output.getMessages()
				.get(0).getContent()).getText());
	}

	@Test
	void validatesRequiredDeclaredStringArgumentsBeforeHandlerInvocation() {
		AtomicInteger invocations = new AtomicInteger();
		McpPromptRegistration registration = McpPromptRegistration
				.withName("validate")
				.handler((request, prompt, features) -> {
					invocations.incrementAndGet();
					return McpCompleteResult.fromPromptOutput(
							McpPromptOutput.fromMessages());
				})
				.addArgument(McpPromptArgumentDeclaration.withName("required")
						.required(true).build())
				.addArgument(McpPromptArgumentDeclaration.withName("optional")
						.build())
				.build();

		assertInvalid(registration, McpJsonObject.emptyInstance());
		assertInvalid(registration,
				McpJsonObject.builder().put("required", 42).build());
		assertInvalid(registration, McpJsonObject.builder()
				.put("required", "present").put("undeclared", "typo").build());
		assertEquals(0, invocations.get());
	}

	@Test
	void distinguishesHandlerFailuresAndRejectsInvalidDefinitions()
			throws Exception {
		assertThrows(IllegalArgumentException.class,
				() -> McpPromptRegistration.withName(" "));
		assertThrows(IllegalArgumentException.class,
				() -> McpPromptArgumentDeclaration.withName(""));
		assertThrows(NullPointerException.class,
				() -> McpPromptArgumentDeclaration.withName("argument")
						.required(null));

		McpPromptArgumentDeclaration duplicate =
				McpPromptArgumentDeclaration.withName("same").build();
		assertThrows(IllegalStateException.class, () -> McpPromptRegistration
				.withName("duplicates")
				.handler((request, prompt, features) ->
						McpCompleteResult.fromPromptOutput(
								McpPromptOutput.fromMessages()))
				.addArgument(duplicate)
				.addArgument(duplicate)
				.build());

		IllegalArgumentException applicationFailure =
				new IllegalArgumentException("application failure");
		McpPromptRegistration failing = McpPromptRegistration
				.withName("failure")
				.handler((request, prompt, features) -> {
					throw applicationFailure;
				})
				.build();
		assertSame(applicationFailure, assertThrows(IllegalArgumentException.class,
				() -> failing.invoke(requestContext(), McpJsonObject.emptyInstance(),
						McpInvocationFeatures.fromFeatures(Map.of()))));

		McpPromptRegistration nullResult = McpPromptRegistration
				.withName("null-result")
				.handler((request, prompt, features) -> null)
				.build();
		assertThrows(NullPointerException.class, () -> nullResult.invoke(
				requestContext(), McpJsonObject.emptyInstance(),
				McpInvocationFeatures.fromFeatures(Map.of())));
	}

	@Test
	void endpointDefensivelyCopiesAndRejectsDuplicatePromptNames() {
		McpPromptRegistration prompt = McpPromptRegistration.withName("one")
				.handler((request, context, features) ->
						McpCompleteResult.fromPromptOutput(
								McpPromptOutput.fromMessages()))
				.build();
		McpEndpoint endpoint = endpointBuilder().addPrompt(prompt).build();

		assertEquals(List.of(prompt), endpoint.getPrompts());
		assertThrows(UnsupportedOperationException.class,
				() -> endpoint.getPrompts().clear());
		assertThrows(IllegalStateException.class, () -> endpointBuilder()
				.addPrompt(prompt)
				.addPrompt(McpPromptRegistration.withName("one")
						.handler((request, context, features) ->
								McpCompleteResult.fromPromptOutput(
										McpPromptOutput.fromMessages()))
						.build())
				.build());
	}

	private static McpEndpoint.Builder endpointBuilder() {
		return McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion(
						"prompt-tests", "4.0.0").build());
	}

	private static void assertInvalid(McpPromptRegistration registration,
			McpJsonObject arguments) {
		assertThrows(McpInvalidPromptArgumentsException.class,
				() -> registration.invoke(requestContext(), arguments,
						McpInvocationFeatures.fromFeatures(Map.of())));
	}

	private static McpRequestContext requestContext() {
		return new McpRequestContext() {
			@Override public Request getRequest() {
				throw new UnsupportedOperationException();
			}
			@Override public McpEndpoint getEndpoint() {
				throw new UnsupportedOperationException();
			}
			@Override public Map<String, String> getEndpointPathParameters() {
				return Map.of();
			}
			@Override public String getJsonRpcMethod() {
				return "prompts/get";
			}
			@Override public Optional<McpRequestId> getRequestId() {
				return Optional.of(McpRequestId.fromString("test"));
			}
			@Override public String getProtocolVersion() {
				return "2026-07-28";
			}
			@Override public Optional<String> getOperationName() {
				return Optional.empty();
			}
			@Override public Optional<McpImplementation> getClientInfo() {
				return Optional.empty();
			}
			@Override public McpClientCapabilities getClientCapabilities() {
				throw new UnsupportedOperationException();
			}
			@Override public McpJsonObject getRequestMetadata() {
				return McpJsonObject.emptyInstance();
			}
			@Override public McpInputResponses getInputResponses() {
				return McpInputResponses.emptyInstance();
			}
			@Override public Optional<McpJsonValue> getFrameworkRequestState() {
				return Optional.empty();
			}
			@Override public Optional<String> getApplicationRequestState() {
				return Optional.empty();
			}
			@Override
			public Optional<McpLogLevel> getLogLevel() {
				return Optional.empty();
			}
			@Override public Optional<TraceContext> getTraceContext() {
				return Optional.empty();
			}
			@Override public Map<String, String> getBaggage() {
				return Map.of();
			}
			@Override public McpAdmissionIdentity getAdmissionIdentity() {
				return McpAdmissionIdentity.anonymousInstance();
			}
		};
	}
}

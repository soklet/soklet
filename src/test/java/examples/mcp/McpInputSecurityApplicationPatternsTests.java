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

package examples.mcp;

import com.soklet.McpInputResponses;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonString;
import com.soklet.McpJsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Public-API-only examples for application-owned MCP input security policy.
 *
 * <p>The framework validates the open wire union, capabilities, protected
 * request state, and structural sampling flow. These examples deliberately
 * cover the decisions that remain application-owned: semantic form policy,
 * response-to-request correlation, user binding, URL construction, sampling
 * data classification and loop bounds, and filesystem containment.
 */
public class McpInputSecurityApplicationPatternsTests {
	private static final String RESPONSE_KEY = "profile";
	private static final Set<String> SECRET_FIELD_FRAGMENTS = Set.of(
			"password", "passcode", "secret", "token", "apikey",
			"privatekey", "creditcard", "paymentcard", "cvv");

	@Test
	void formPolicyHandlesEveryActionAndValidatesAcceptedContent() {
		PendingForm pending = new PendingForm("tenant-7:user-42",
				Set.of("displayName"));

		assertEquals(new FormResult(FormAction.RE_REQUEST, Optional.empty()),
				evaluateForm(pending, "tenant-7:user-42",
						McpInputResponses.emptyInstance()));
		assertEquals(new FormResult(FormAction.DECLINE, Optional.empty()),
				evaluateForm(pending, "tenant-7:user-42",
						responses(elicitation("decline", null))));
		assertEquals(new FormResult(FormAction.CANCEL, Optional.empty()),
				evaluateForm(pending, "tenant-7:user-42",
						responses(elicitation("cancel", McpJsonObject.builder()
								.put("untrusted", "ignored")
								.build()))));
		assertEquals(new FormResult(FormAction.ACCEPT,
				Optional.of("Ada Lovelace")), evaluateForm(pending,
				"tenant-7:user-42", responses(elicitation("accept",
						McpJsonObject.builder()
								.put("displayName", "Ada Lovelace")
								.build()))));

		assertThrows(IllegalArgumentException.class, () -> evaluateForm(pending,
				"tenant-7:user-99", responses(elicitation("accept",
						McpJsonObject.builder()
								.put("displayName", "Ada")
								.build()))));
		for (McpJsonObject content : List.of(
				McpJsonObject.emptyInstance(),
				McpJsonObject.builder().put("displayName", "").build(),
				McpJsonObject.builder().put("displayName", "Ada\nAdmin").build(),
				McpJsonObject.builder().put("displayName", "Ada")
						.put("role", "administrator").build()))
			assertThrows(IllegalArgumentException.class, () -> evaluateForm(
					pending, "tenant-7:user-42",
					responses(elicitation("accept", content))));
	}

	@Test
	void formConstructionRejectsSecretBearingFieldNames() {
		assertSafeFormFields(Set.of("displayName", "timezone", "newsletter"));

		for (String field : List.of("password", "apiKey", "access_token",
				"payment-card", "CVV"))
			assertThrows(IllegalArgumentException.class,
					() -> assertSafeFormFields(Set.of(field)));
	}

	@Test
	void urlModeUsesServerOwnedHttpsAndBindsOpaqueStateToTheUser() {
		PendingUrl pending = beginUrl(
				URI.create("https://account.example/mcp/complete/"),
				"tenant-7:user-42",
				"A3xzWzE4Vq0nYpK6sD2mQw");

		assertEquals("https", pending.destination().getScheme());
		assertEquals("account.example", pending.destination().getHost());
		assertTrue(pending.destination().getPath().endsWith(
				"/A3xzWzE4Vq0nYpK6sD2mQw"));
		assertNull(pending.destination().getUserInfo());
		assertNull(pending.destination().getQuery());
		assertNull(pending.destination().getFragment());
		assertFalse(pending.destination().toString().contains("tenant-7"));
		assertTrue(completeUrl(pending, "tenant-7:user-42",
				"A3xzWzE4Vq0nYpK6sD2mQw"));

		assertThrows(IllegalArgumentException.class, () -> beginUrl(
				URI.create("http://account.example/mcp/complete/"),
				"tenant-7:user-42", "A3xzWzE4Vq0nYpK6sD2mQw"));
		assertThrows(IllegalArgumentException.class, () -> beginUrl(
				URI.create("https://bearer@account.example/mcp/complete/"),
				"tenant-7:user-42", "A3xzWzE4Vq0nYpK6sD2mQw"));
		assertThrows(IllegalArgumentException.class, () -> completeUrl(pending,
				"tenant-7:user-99", "A3xzWzE4Vq0nYpK6sD2mQw"));
		assertThrows(IllegalArgumentException.class, () -> completeUrl(pending,
				"tenant-7:user-42", "A3xzWzE4Vq0nYpK6sD2mQx"));
	}

	@Test
	void samplingPolicyClassifiesContentAndAppliesAFixedLoopBudget() {
		Predicate<McpJsonValue> dataPolicy = value ->
				!(value instanceof McpJsonString string)
						|| !string.value().contains("SECRET-CANARY");
		SamplingPolicy policy = new SamplingPolicy(4, dataPolicy);

		policy.validateRound(0, List.of(new McpJsonString("public context")));
		policy.validateRound(3, List.of(new McpJsonString("final round")));
		assertThrows(IllegalArgumentException.class, () -> policy.validateRound(
				4, List.of(new McpJsonString("one round too many"))));
		assertThrows(IllegalArgumentException.class, () -> policy.validateRound(
				0, List.of(new McpJsonString("SECRET-CANARY"))));
	}

	@Test
	void rootsAreResolvedToRealPathsInsideAnApplicationBoundary(
			@TempDir Path temporaryDirectory) throws IOException {
		Path allowed = Files.createDirectory(temporaryDirectory.resolve("allowed"));
		Path inside = Files.writeString(allowed.resolve("project.txt"), "ok");
		Path outside = Files.writeString(
				temporaryDirectory.resolve("outside.txt"), "not allowed");

		assertEquals(inside.toRealPath(), resolveRoot(inside.toUri(), allowed));
		assertThrows(IllegalArgumentException.class,
				() -> resolveRoot(outside.toUri(), allowed));
		assertThrows(IllegalArgumentException.class,
				() -> resolveRoot(URI.create("https://example.com/project"), allowed));
	}

	private static McpInputResponses responses(McpJsonObject response) {
		return McpInputResponses.fromResponses(Map.of(RESPONSE_KEY, response));
	}

	private static McpJsonObject elicitation(String action,
			McpJsonObject content) {
		McpJsonObject.Builder builder = McpJsonObject.builder().put("action", action);
		if (content != null)
			builder.put("content", content);
		return builder.build();
	}

	private static FormResult evaluateForm(PendingForm pending,
			String currentPrincipal, McpInputResponses responses) {
		requireNonNull(pending);
		requireNonNull(currentPrincipal);
		requireNonNull(responses);
		McpJsonValue raw = responses.find(RESPONSE_KEY).orElse(null);
		if (raw == null)
			return new FormResult(FormAction.RE_REQUEST, Optional.empty());
		if (!pending.principal().equals(currentPrincipal))
			throw invalid();
		if (!(raw instanceof McpJsonObject response))
			throw invalid();
		McpJsonValue rawAction = response.getMembers().get("action");
		if (!(rawAction instanceof McpJsonString action))
			throw invalid();
		return switch (action.value()) {
			case "decline" -> new FormResult(FormAction.DECLINE, Optional.empty());
			case "cancel" -> new FormResult(FormAction.CANCEL, Optional.empty());
			case "accept" -> acceptForm(pending, response);
			default -> throw invalid();
		};
	}

	private static FormResult acceptForm(PendingForm pending,
			McpJsonObject response) {
		McpJsonValue rawContent = response.getMembers().get("content");
		if (!(rawContent instanceof McpJsonObject content)
				|| !content.getMembers().keySet().equals(pending.fields()))
			throw invalid();
		McpJsonValue rawDisplayName = content.getMembers().get("displayName");
		if (!(rawDisplayName instanceof McpJsonString displayName))
			throw invalid();
		String value = displayName.value();
		int length = value.codePointCount(0, value.length());
		if (length < 1 || length > 80
				|| value.codePoints().anyMatch(Character::isISOControl))
			throw invalid();
		return new FormResult(FormAction.ACCEPT, Optional.of(value));
	}

	private static void assertSafeFormFields(Set<String> fields) {
		for (String field : Set.copyOf(requireNonNull(fields))) {
			String normalized = requireNonNull(field).toLowerCase(Locale.ROOT)
					.replaceAll("[^a-z0-9]", "");
			if (SECRET_FIELD_FRAGMENTS.stream().anyMatch(normalized::contains))
				throw invalid();
		}
	}

	private static PendingUrl beginUrl(URI base, String principal,
			String opaqueHandle) {
		requireNonNull(base);
		requireNonNull(principal);
		requireNonNull(opaqueHandle);
		if (!base.isAbsolute() || !"https".equals(base.getScheme())
				|| base.getHost() == null || base.getUserInfo() != null
				|| base.getQuery() != null || base.getFragment() != null
				|| !opaqueHandle.matches("[A-Za-z0-9_-]{22,64}"))
			throw invalid();
		URI destination = base.resolve("./" + opaqueHandle);
		return new PendingUrl(principal, opaqueHandle, destination);
	}

	private static boolean completeUrl(PendingUrl pending,
			String currentPrincipal, String opaqueHandle) {
		if (!requireNonNull(pending).principal().equals(
				requireNonNull(currentPrincipal))
				|| !pending.opaqueHandle().equals(requireNonNull(opaqueHandle)))
			throw invalid();
		return true;
	}

	private static Path resolveRoot(URI root, Path allowedDirectory)
			throws IOException {
		requireNonNull(root);
		Path boundary = requireNonNull(allowedDirectory).toRealPath();
		if (!"file".equals(root.getScheme()) || root.getAuthority() != null
				|| root.getQuery() != null || root.getFragment() != null)
			throw invalid();
		Path resolved = Path.of(root).toRealPath();
		if (!resolved.startsWith(boundary))
			throw invalid();
		return resolved;
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Application MCP input policy rejected the value.");
	}

	private enum FormAction {
		ACCEPT,
		DECLINE,
		CANCEL,
		RE_REQUEST
	}

	private record PendingForm(String principal, Set<String> fields) {
		private PendingForm {
			requireNonNull(principal);
			fields = Set.copyOf(requireNonNull(fields));
			assertSafeFormFields(fields);
		}
	}

	private record FormResult(FormAction action, Optional<String> displayName) {
		private FormResult {
			requireNonNull(action);
			requireNonNull(displayName);
		}
	}

	private record PendingUrl(String principal, String opaqueHandle,
			URI destination) {
		private PendingUrl {
			requireNonNull(principal);
			requireNonNull(opaqueHandle);
			requireNonNull(destination);
		}
	}

	private record SamplingPolicy(int maximumRounds,
			Predicate<McpJsonValue> dataPolicy) {
		private SamplingPolicy {
			if (maximumRounds < 1 || maximumRounds > 32)
				throw new IllegalArgumentException("Sampling round bound is invalid.");
			requireNonNull(dataPolicy);
		}

		private void validateRound(int completedRounds,
				List<? extends McpJsonValue> content) {
			if (completedRounds < 0 || completedRounds >= this.maximumRounds)
				throw invalid();
			for (McpJsonValue value : List.copyOf(requireNonNull(content)))
				if (!this.dataPolicy.test(requireNonNull(value)))
					throw invalid();
		}
	}
}

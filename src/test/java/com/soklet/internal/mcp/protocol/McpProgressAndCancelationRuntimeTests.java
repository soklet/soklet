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

import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@NotThreadSafe
public class McpProgressAndCancelationRuntimeTests {
	@Test
	public void cancelation_token_fixes_first_reason_and_contains_callbacks()
			throws Exception {
		McpApplicationCancellationState cancellation =
				new McpApplicationCancellationState();
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				cancellation.cancel(StreamTerminationReason.COMPLETED));
		AtomicInteger callbacks = new AtomicInteger();
		AutoCloseable removed = cancellation.onCancel(() ->
				Assertions.fail("A removed callback must not run."));
		removed.close();
		cancellation.onCancel(() -> {
			throw new IllegalStateException("contained");
		});
		cancellation.onCancel(callbacks::incrementAndGet);

		Assertions.assertTrue(cancellation.cancel(
				StreamTerminationReason.CLIENT_DISCONNECTED));
		Assertions.assertFalse(cancellation.cancel(
				StreamTerminationReason.INTERNAL_ERROR));
		Assertions.assertTrue(cancellation.isCanceled());
		Assertions.assertEquals(
				Optional.of(StreamTerminationReason.CLIENT_DISCONNECTED),
				cancellation.getCancelationReason());
		Assertions.assertTrue(cancellation.getCancelationCause().isEmpty());
		Assertions.assertEquals(1, callbacks.get(),
				"One callback failure must not prevent later callbacks.");

		cancellation.onCancel(callbacks::incrementAndGet);
		Assertions.assertEquals(2, callbacks.get(),
				"Callbacks registered after cancellation run immediately.");
	}

	@Test
	public void normal_completion_releases_callbacks_without_canceling_the_token()
			throws Exception {
		McpApplicationCancellationState cancellation =
				new McpApplicationCancellationState();
		AtomicInteger callbacks = new AtomicInteger();
		cancellation.onCancel(callbacks::incrementAndGet);

		Assertions.assertTrue(cancellation.isActive());
		cancellation.complete();
		cancellation.complete();

		Assertions.assertFalse(cancellation.isActive());
		Assertions.assertFalse(cancellation.isCanceled());
		Assertions.assertTrue(cancellation.getCancelationReason().isEmpty());
		Assertions.assertEquals(0, callbacks.get());
		AutoCloseable lateRegistration = cancellation.onCancel(
				callbacks::incrementAndGet);
		lateRegistration.close();
		Assertions.assertEquals(0, callbacks.get());
		Assertions.assertFalse(cancellation.cancel(
				StreamTerminationReason.CLIENT_DISCONNECTED));
		Assertions.assertEquals(0, callbacks.get());
	}

	@Test
	public void progress_emitter_preserves_opaque_token_and_optional_fields()
			throws Exception {
		BigInteger token = new BigInteger(
				"1234567890123456789012345678901234567890");
		AtomicReference<McpJsonRpcMessage.Notification> notification =
				new AtomicReference<>();
		McpApplicationInvocation invocation = invocation(
				Optional.of(new McpProgressToken.IntegerToken(token)),
				McpClientCapabilities.empty(), value -> {
					notification.set(value);
					return true;
				});
		McpServerRuntimeBridge.ProgressEmitter emitter =
				McpServerRuntimeBridge.progressEmitterFor(invocation,
						McpInputRequestPlan.empty()).orElseThrow();

		Assertions.assertTrue(emitter.emit(2.5d, Optional.of(10.0d),
				Optional.of("working")));
		McpJsonRpcMessage.Notification emitted = notification.get();
		Assertions.assertNotNull(emitted);
		Assertions.assertEquals("notifications/progress", emitted.method());
		McpJsonObject params = emitted.params().orElseThrow();
		Assertions.assertEquals(new McpJsonNumber(new BigDecimal(token)),
				params.members().get("progressToken"));
		Assertions.assertEquals(new McpJsonNumber(BigDecimal.valueOf(2.5d)),
				params.members().get("progress"));
		Assertions.assertEquals(new McpJsonNumber(BigDecimal.valueOf(10L)),
				params.members().get("total"));
		Assertions.assertEquals(new McpJsonString("working"),
				params.members().get("message"));
		Assertions.assertEquals(List.of(
				"progressToken", "progress", "total", "message"),
				new ArrayList<>(params.members().keySet()));
	}

	@Test
	public void every_application_operation_path_preserves_its_progress_token()
			throws Exception {
		List<String> methods = List.of(
				"tools/call", "prompts/get", "resources/read", "resources/list");
		for (int index = 0; index < methods.size(); index++) {
			String method = methods.get(index);
			String token = "operation-token-" + index;
			AtomicReference<McpJsonRpcMessage.Notification> notification =
					new AtomicReference<>();
			McpApplicationInvocation invocation = invocation(method,
					Optional.of(new McpProgressToken.StringToken(token)),
					McpClientCapabilities.empty(), value -> {
						notification.set(value);
						return true;
					});

			McpServerRuntimeBridge.ProgressEmitter emitter =
					McpServerRuntimeBridge.progressEmitterFor(invocation,
							McpInputRequestPlan.empty()).orElseThrow();
			Assertions.assertTrue(emitter.emit(index + 1.0d,
					Optional.empty(), Optional.empty()), method);
			McpJsonRpcMessage.Notification emitted = notification.get();
			Assertions.assertNotNull(emitted, method);
			Assertions.assertEquals("notifications/progress", emitted.method(),
					method);
			McpJsonObject params = emitted.params().orElseThrow();
			Assertions.assertEquals(new McpJsonString(token),
					params.members().get("progressToken"), method);
			Assertions.assertEquals(
					new McpJsonNumber(BigDecimal.valueOf(index + 1L)),
					params.members().get("progress"), method);
		}
	}

	@Test
	public void progress_is_absent_without_token_or_during_conditional_hold() {
		McpApplicationInvocation withoutToken = invocation(Optional.empty(),
				McpClientCapabilities.empty(), ignored -> true);
		Assertions.assertTrue(McpServerRuntimeBridge.progressEmitterFor(
				withoutToken, McpInputRequestPlan.empty()).isEmpty());

		McpApplicationInvocation held = invocation(
				Optional.of(new McpProgressToken.StringToken("held")),
				McpClientCapabilities.empty(), ignored -> true);
		McpInputRequestPlan conditionalPlan = new McpInputRequestPlan(List.of(
				McpInputRequestDeclaration.roots(McpInputRequirement.CONDITIONAL)));
		Assertions.assertTrue(McpServerRuntimeBridge.progressEmitterFor(
				held, conditionalPlan).isEmpty(),
				"Conditional-capability holds suppress progress without replay.");
	}

	@Test
	public void progress_is_available_when_conditional_capability_is_present()
			throws Exception {
		AtomicInteger writes = new AtomicInteger();
		McpApplicationInvocation invocation = invocation(
				Optional.of(new McpProgressToken.StringToken("opaque")),
				McpClientCapabilities.fromRequirements(
						java.util.Set.of(McpCoreClientCapability.ROOTS)),
				ignored -> {
					writes.incrementAndGet();
					return false;
				});
		McpInputRequestPlan conditionalPlan = new McpInputRequestPlan(List.of(
				McpInputRequestDeclaration.roots(McpInputRequirement.CONDITIONAL)));
		McpServerRuntimeBridge.ProgressEmitter emitter =
				McpServerRuntimeBridge.progressEmitterFor(invocation,
						conditionalPlan).orElseThrow();

		Assertions.assertFalse(emitter.emit(1.0d, Optional.empty(),
				Optional.empty()));
		Assertions.assertEquals(1, writes.get());
	}

	private static McpApplicationInvocation invocation(
			Optional<McpProgressToken> progressToken,
			McpClientCapabilities clientCapabilities,
			McpApplicationNotificationWriter notificationWriter) {
		return invocation("tools/call", progressToken, clientCapabilities,
				notificationWriter);
	}

	private static McpApplicationInvocation invocation(String method,
			Optional<McpProgressToken> progressToken,
			McpClientCapabilities clientCapabilities,
			McpApplicationNotificationWriter notificationWriter) {
		McpRequestMetadata metadata = new McpRequestMetadata(
				McpProtocolVersion.CURRENT, clientCapabilities, Optional.empty(),
				Optional.empty(), progressToken, McpJsonObject.empty());
		McpJsonRpcMessage.Request request = new McpJsonRpcMessage.Request(
				new McpJsonRpcId.StringId("request"), method,
				new McpRequestParameters(metadata, McpJsonObject.empty()),
				McpJsonObject.empty());
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata.withNameAndVersion(
						"progress-test", "3.6.0-SNAPSHOT"))
				.build();
		McpEffectiveAdmissionIdentity identity =
				McpEffectiveAdmissionIdentity.resolve(endpoint, "/mcp",
						McpAdmissionIdentity.anonymousInstance());
		return new McpApplicationInvocation(null, null, request, identity,
				new McpApplicationCancellationState(), notificationWriter, () -> {});
	}
}

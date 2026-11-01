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

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live self-wait guards for state protection, Skills policy, and source cleanup.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Timeout(60)
class McpRemainingSpiLifecycleExecutionTests {
	private static final Set<McpProtocolVersion> VERSIONS = Set.of(McpProtocolVersion.V2026_07_28);

	@TestFactory
	Stream<DynamicTest> stateSkillsAndRegistrationCallbacksCannotJoinTheirOwnShutdown() {
		return Stream.of("protector-seal", "protector-open", "skill-access", "skill-discovery", "skill-selector",
				"subscription-source-close", "task-source-close")
				.map(callback -> DynamicTest.dynamicTest(callback, () -> assertTimeoutPreemptively(Duration.ofSeconds(60),
						() -> assertCloseIsGuarded(callback))));
	}

	private static void assertCloseIsGuarded(String callback) throws Exception {
		AtomicReference<Soklet> ownerReference = new AtomicReference<>();
		AtomicReference<Throwable> closeFailure = new AtomicReference<>();
		AtomicBoolean attempted = new AtomicBoolean();
		AtomicBoolean marked = new AtomicBoolean();
		AtomicBoolean subscribeMarked = new AtomicBoolean();
		AtomicInteger registrationCloses = new AtomicInteger();
		CountDownLatch callbackReturned = new CountDownLatch(1);
		Runnable attemptClose = () -> {
			if (!attempted.compareAndSet(false, true)) return;
			Soklet owner = ownerReference.get();
			marked.set(isMarked(owner));
			try {
				owner.close();
			} catch (Throwable failure) {
				closeFailure.set(failure);
			} finally {
				callbackReturned.countDown();
			}
		};
		var endpoint = McpEndpoint.withPath("/mcp",
				McpImplementation.withNameAndVersion("remaining-spi-lifecycle", "1").build(), VERSIONS);
		var builder = McpServer.withPort(0).host("127.0.0.1").allowedHosts(Set.of("127.0.0.1"));
		if (callback.startsWith("protector-")) {
			AtomicReference<byte[]> plaintext = new AtomicReference<>();
			McpRequestStateProtector protector = new McpRequestStateProtector() {
				@Override public String seal(McpRequestStateProtectionContext context, byte[] value) {
					plaintext.set(value.clone());
					if (callback.equals("protector-seal")) attemptClose.run();
					return "sealed-state";
				}
				@Override public byte[] open(McpRequestStateProtectionContext context, String value) {
					if (callback.equals("protector-open")) attemptClose.run();
					return plaintext.get().clone();
				}
			};
			endpoint.toolRegistrations(List.of(McpToolRegistration.withName("work", VERSIONS).jsonObjectArguments()
					.handler((request, arguments, features) -> request.getFrameworkRequestState().isPresent()
							? McpCompleteResult.fromToolText("done")
							: McpInputRequiredResult.withFrameworkRequestState(McpJsonObject.emptyInstance()).build())
					.requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED).build()));
			builder.protectionConfig(McpProtectionConfig.withRequestStateProtector(protector).build())
					.toolRateLimiter(context -> McpRateLimitDecision.allowed());
		} else if (callback.startsWith("skill-")) {
			McpSkillBundle bundle = McpSkillBundle.fromFiles(Map.of("SKILL.md",
					"---\nname: example\ndescription: Example skill\n---\nInstructions\n".getBytes(StandardCharsets.UTF_8)));
			McpSkillRegistration registration = McpSkillRegistration.withUriAndSkillBundle(
					URI.create("skill://lifecycle/example/SKILL.md"), bundle, VERSIONS).build();
			if (callback.equals("skill-selector"))
				endpoint.skillGroups(List.of(McpSkillGroup.fromKeyAndSkillRegistrations("variants", List.of(registration))));
			else endpoint.skillRegistrations(List.of(registration));
			builder.skillAccessPolicy(McpSkillAccessPolicy.fromEvaluators((request, skill, features) -> {
				if (callback.equals("skill-access")) attemptClose.run();
				return true;
			}, (request, skill, features) -> {
				if (callback.equals("skill-discovery")) attemptClose.run();
				return true;
			}));
			if (callback.equals("skill-selector")) builder.skillVariantSelector((request, context, features) -> {
				attemptClose.run();
				return Optional.of(context.getSkillRegistrations().getFirst());
			});
		} else {
			Runnable closeRegistration = () -> { registrationCloses.incrementAndGet(); attemptClose.run(); };
			endpoint.subscriptionProtocolVersions(VERSIONS);
			builder.subscriptionAuthorizer((context, features) ->
					McpSubscriptionAuthorization.Allowed.fromValidUntil(Instant.now().plusSeconds(60)));
			if (callback.equals("task-source-close")) {
				McpTaskEventPublisher publisher = new McpTaskEventPublisher() {
					@Override public McpSubscriptionEventRegistration subscribe(McpTaskEventListener listener) {
						subscribeMarked.set(isMarked(ownerReference.get()));
						return closeRegistration::run;
					}
					@Override public void publishTaskChanged(String taskId) {}
				};
				endpoint.taskProtocolVersions(VERSIONS);
				builder.taskManager(new McpTaskManager() {
					@Override public Optional<McpTaskEventPublisher> getTaskEventPublisher() { return Optional.of(publisher); }
					@Override public Optional<McpTask> findTask(McpTaskRequestContext context) { return Optional.empty(); }
					@Override public void updateTask(McpTaskUpdateContext context) {}
					@Override public void requestTaskCancelation(McpTaskRequestContext context) {}
				});
			} else {
				URI uri = URI.create("test://lifecycle/item");
				endpoint.resourceRegistrations(List.of(McpResourceRegistration.withUriAndName(uri, "Item", VERSIONS)
						.handler((request, context, features) -> McpCompleteResult.fromResourceOutput(
								McpResourceOutput.fromContent(McpTextResourceContents.withUriAndText(uri, "item").build())))
						.build()));
				McpSubscriptionEventPublisher publisher = new McpSubscriptionEventPublisher() {
					@Override public McpSubscriptionEventRegistration subscribe(McpSubscriptionEventListener listener) {
						subscribeMarked.set(isMarked(ownerReference.get()));
						return closeRegistration::run;
					}
					@Override public void publish(McpSubscriptionEvent event) {}
				};
				endpoint.subscriptionConfig(McpSubscriptionConfig.withEventPublisherAndNotificationTypes(publisher,
						Set.of(McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)).build());
			}
		}
		McpServer server = builder.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint.build()))).build();
		// The SPI callbacks must inspect the exact owner token and verify owner-scoped self-wait guards.
		// The SPI callbacks must inspect the exact owner token and verify owner-scoped self-wait guards.
		try (SokletTestScope ownerScope = SokletTestScope.of(Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build()));
				HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
			Soklet owner = ownerScope.soklet();
			ownerReference.set(owner);
			owner.start();
			if (callback.endsWith("source-close")) {
				assertTrue(subscribeMarked.get(), "Startup source registration must retain the existing exact-owner scope.");
				owner.shutdown();
			} else {
				int port = server.getDiagnostics().getBoundAddress().orElseThrow().getPort();
				if (callback.equals("protector-open")) {
					HttpResponse<String> initial = client.send(request(port, "initial", "tools/call", "work",
							"\"name\":\"work\",\"arguments\":{}"), HttpResponse.BodyHandlers.ofString());
					assertEquals(200, initial.statusCode(), initial.body());
					assertTrue(initial.body().contains("\"requestState\":\"sealed-state\""), initial.body());
				}
				boolean protector = callback.startsWith("protector-");
				String fields = protector ? "\"name\":\"work\",\"arguments\":{}"
						+ (callback.equals("protector-open") ? ",\"requestState\":\"sealed-state\"" : "") : "";
				client.sendAsync(request(port, "retry", protector ? "tools/call" : "skills/list", protector ? "work" : null, fields),
						HttpResponse.BodyHandlers.discarding());
			}
			assertTrue(callbackReturned.await(5, TimeUnit.SECONDS), "The selected callback was not invoked");
			assertTrue(marked.get(), "Framework-owned callbacks must carry the exact lifecycle owner");
			IllegalStateException failure = assertInstanceOf(IllegalStateException.class, closeFailure.get());
			assertTrue(failure.getMessage().contains("tracked lifecycle execution"), failure.toString());
			ShutdownResult result = owner.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
			assertTrue(result.isComplete(), result.toString());
			assertTrue(result.getShutdownComponentResults().stream().allMatch(component -> component
					.getResidualActivityEvidence().map(evidence -> evidence.getResidualActivityTypes().isEmpty()).orElse(true)),
					"Guarded self-wait must not freeze callback or executor residuals");
			if (callback.endsWith("source-close")) assertEquals(1, registrationCloses.get());
		}
	}

	private static boolean isMarked(Soklet owner) {
		return LifecycleExecutionContext.isMarked(owner.getDirectLifecycle().executionOwnerToken());
	}

	private static HttpRequest request(int port, String id, String method, String name, String fields) {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method + "\",\"params\":{" + fields
				+ (fields.isEmpty() ? "" : ",") + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
				.timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", method);
		if (name != null) builder.header("Mcp-Name", name);
		return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
	}
}

/*
 * Copyright 2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet.interop.skills;

import com.soklet.CorsAuthorizer;
import com.soklet.LifecycleObserver;
import com.soklet.LogEvent;
import com.soklet.McpAdmissionDecision;
import com.soklet.McpAdmissionIdentity;
import com.soklet.McpAdmissionRejection;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpJsonObject;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcException;
import com.soklet.McpServer;
import com.soklet.McpServerStatus;
import com.soklet.McpSkillAccessPolicy;
import com.soklet.McpSkillBundle;
import com.soklet.McpSkillGroup;
import com.soklet.McpSkillPage;
import com.soklet.McpSkillRegistration;
import com.soklet.ResourceMethodResolver;
import com.soklet.ShutdownComponentDisposition;
import com.soklet.ShutdownComponentType;
import com.soklet.ShutdownResult;
import com.soklet.Soklet;
import com.soklet.SokletConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disposable public-API Skills host fixture with bounded, caller-bound pages. */
public final class SkillsMatrixFixture {
	private static final String HOST = "127.0.0.1";
	private static final String PATH = "/mcp";
	private static final String EN_TOKEN = "matrix-en-credential";
	private static final String FR_TOKEN = "matrix-fr-credential";
	private static final String DENIED_TOKEN = "matrix-denied-credential";
	private static final URI COMMON_URI = URI.create("skill://matrix/common/overview/SKILL.md");
	private static final URI PARENT_URI = URI.create("skill://matrix/shared/parent/SKILL.md");
	private static final URI CHILD_URI = URI.create("skill://matrix/shared/parent/child/SKILL.md");
	private static final URI HIDDEN_URI = URI.create("skill://matrix/hidden/reference/SKILL.md");
	private static final URI EN_URI = URI.create("skill://matrix/en/guide/SKILL.md");
	private static final URI FR_URI = URI.create("skill://matrix/fr/guide/SKILL.md");
	private static final byte[] CHILD_DOCUMENT = document("child", "Nested child");
	private static final byte[] SHARED_BINARY = {0, 1, (byte) 0xff, 3};
	private static final LifecycleObserver QUIET = new LifecycleObserver() {
		@Override
		public void didReceiveLogEvent(LogEvent event) {
			// The control stream contains only fixed structural events.
		}
	};
	private final AtomicBoolean revoked = new AtomicBoolean();
	private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
	private final McpSkillRegistration common = registration(COMMON_URI, "overview", "Shared overview", null);
	private final McpSkillRegistration parent = McpSkillRegistration.withUriAndSkillBundle(
			PARENT_URI, McpSkillBundle.fromFiles(Map.of(
				"SKILL.md", document("parent", "Parent with shared child"),
				"child/SKILL.md", CHILD_DOCUMENT,
				"child/assets/shared.bin", SHARED_BINARY))).build();
	private final McpSkillRegistration child = McpSkillRegistration.withUriAndSkillBundle(
			CHILD_URI, McpSkillBundle.fromFiles(Map.of(
				"SKILL.md", CHILD_DOCUMENT,
				"assets/shared.bin", SHARED_BINARY))).build();
	private final McpSkillRegistration hidden = registration(HIDDEN_URI, "reference", "Unlisted reference", null);
	private final McpSkillRegistration english = registration(EN_URI, "guide", "English guide", Locale.ENGLISH);
	private final McpSkillRegistration french = registration(FR_URI, "guide", "Guide français", Locale.FRENCH);

	private record Caller(String subject, String language, boolean allowed) { }
	private record Snapshot(String subject, List<McpSkillRegistration> remaining) { }

	private SkillsMatrixFixture() { }

	public static void main(String[] arguments) throws Exception {
		if (arguments.length != 0) throw new IllegalArgumentException("No arguments expected.");
		SkillsMatrixFixture fixture = new SkillsMatrixFixture();
		McpServer server = fixture.server();
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(QUIET).build();
		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			InetSocketAddress address = server.getDiagnostics().getBoundAddress().orElseThrow();
			if (server.getDiagnostics().getStatus() != McpServerStatus.RUNNING
					|| !HOST.equals(address.getAddress().getHostAddress()) || address.getPort() < 1)
				throw new IllegalStateException("Fixture did not bind loopback.");
			control("{\"event\":\"ready\",\"host\":\"127.0.0.1\",\"port\":" + address.getPort()
					+ ",\"path\":\"/mcp\"}");
			BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
			String command;
			while ((command = input.readLine()) != null) {
				if (!command.equals("revoke") || fixture.revoked.getAndSet(true))
					throw new IllegalArgumentException("Unexpected fixture control.");
				control("{\"event\":\"revoked\"}");
			}
			ShutdownResult result = soklet.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
			if (server.getDiagnostics().getStatus() != McpServerStatus.TERMINATED
					|| result.getShutdownComponentResult(ShutdownComponentType.MCP).orElseThrow()
							.getShutdownComponentDisposition() != ShutdownComponentDisposition.GRACEFUL_TERMINATION)
				throw new IllegalStateException("Fixture did not stop cleanly.");
		}
		control("{\"event\":\"stopped\",\"clean\":true}");
	}

	private McpServer server() {
		McpEndpoint endpoint = McpEndpoint.withPath(PATH,
				McpImplementation.withNameAndVersion("soklet-skills-matrix", "fixture-v1").build())
				.skillRegistrations(List.of(this.common, this.parent, this.child, this.hidden))
				.skillGroups(List.of(McpSkillGroup.fromKeyAndSkillRegistrations("guide",
						List.of(this.english, this.french))))
				.skillListHandler((requestContext, skillListContext, invocationFeatures) -> {
					Caller caller = caller(requestContext);
					if (skillListContext.getInitialSkillRegistrations().isPresent()) {
						List<McpSkillRegistration> initial = skillListContext.getInitialSkillRegistrations().orElseThrow();
						if (initial.isEmpty()) return McpSkillPage.builder().build();
						if (initial.size() != 4 || initial.get(0) != this.common
								|| initial.get(1) != this.parent || initial.get(2) != this.child)
							throw new IllegalStateException("Unexpected initial Skills selection.");
						if (this.snapshots.size() >= 32)
							throw new IllegalStateException("Fixture page bound reached.");
						String cursor = UUID.randomUUID().toString();
						this.snapshots.put(cursor, new Snapshot(caller.subject(), initial.subList(1, 4)));
						return McpSkillPage.builder().skillRegistrations(List.of(initial.get(0)))
								.nextCursor(cursor).build();
					}
					String cursor = skillListContext.getCursor().orElseThrow();
					Snapshot snapshot = this.snapshots.get(cursor);
					if (snapshot == null || !snapshot.subject().equals(caller.subject()))
						throw new McpJsonRpcException(McpJsonRpcError.fromInvalidParameters(
								"Invalid Skills cursor.", McpJsonObject.emptyInstance()));
					this.snapshots.remove(cursor, snapshot);
					return McpSkillPage.builder().skillRegistrations(snapshot.remaining()).build();
				})
				.build();
		return McpServer.withPort(0).host(HOST).allowedHosts(Set.of(HOST))
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> {
					String authorization = context.getRequest().getHeader("Authorization").orElse("");
					Caller caller = switch (authorization) {
						case "Bearer " + EN_TOKEN -> this.revoked.get() ? null : new Caller("en", "en", true);
						case "Bearer " + FR_TOKEN -> new Caller("fr", "fr", true);
						case "Bearer " + DENIED_TOKEN -> new Caller("denied", "en", false);
						default -> null;
					};
					if (caller == null) return McpAdmissionDecision.rejected(
							McpAdmissionRejection.withStatusCodeAndError(401,
									McpJsonRpcError.fromApplication(-31901, "Authentication required."))
									.addHeader("WWW-Authenticate", "Bearer").build());
					return McpAdmissionDecision.accepted(McpAdmissionIdentity
							.withRateLimitPartitionKey(caller.subject()).authorizationPartitionKey(caller.subject())
							.principal(caller).build());
				})
				.skillAccessPolicy(McpSkillAccessPolicy.fromEvaluators(
						(requestContext, registration, invocationFeatures) -> {
							Caller caller = caller(requestContext);
							return caller.allowed() && (registration == this.common
									|| registration == this.parent || registration == this.child
									|| registration == this.hidden
									|| registration == this.english && caller.language().equals("en")
									|| registration == this.french && caller.language().equals("fr"));
						},
						(requestContext, registration, invocationFeatures) -> registration != this.hidden))
				.skillVariantSelector((requestContext, selectionContext, invocationFeatures) ->
						java.util.Optional.of(selectionContext.getSkillRegistrations().get(0)))
				.build();
	}

	private static McpSkillRegistration registration(URI uri, String name, String description, Locale locale) {
		McpSkillRegistration.Builder builder = McpSkillRegistration.withUriAndSkillBundle(uri,
				McpSkillBundle.fromFiles(Map.of("SKILL.md", document(name, description))));
		if (locale != null) builder.locale(locale);
		return builder.build();
	}

	private static byte[] document(String name, String description) {
		return ("---\nname: " + name + "\ndescription: " + description
				+ "\n---\nSynthetic instructions.\n").getBytes(StandardCharsets.UTF_8);
	}

	private static Caller caller(com.soklet.McpRequestContext requestContext) {
		return (Caller) requestContext.getAdmissionIdentity().getPrincipal().orElseThrow();
	}

	private static void control(String json) {
		System.out.println(json);
		System.out.flush();
		if (System.out.checkError()) throw new IllegalStateException("Fixture control failed.");
	}
}

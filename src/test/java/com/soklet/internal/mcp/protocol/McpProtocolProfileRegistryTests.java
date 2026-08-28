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

import com.soklet.CorsAuthorizer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class McpProtocolProfileRegistryTests {
	private static final String CURRENT = "2026-07-28";
	private static final String FAKE = "2099-01-01";

	@Test
	public void constructionRejectsEmptyBlankAndDuplicateProfiles() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpProtocolProfileRegistry(List.of()));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpProtocolProfileRegistry(List.of(profile(" "))));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpProtocolProfileRegistry(List.of(
						profile(CURRENT), profile(CURRENT))));
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpProtocolProfileRegistry(null));
		List<McpProtocolProfile> containingNull = new ArrayList<>();
		containingNull.add(profile(CURRENT));
		containingNull.add(null);
		Assertions.assertThrows(NullPointerException.class,
				() -> new McpProtocolProfileRegistry(containingNull));
	}

	@Test
	public void registryOrderIsImmutableDiagnosticRenderingNotPreference() {
		McpProtocolProfile first = profile("2099-01-02");
		McpProtocolProfile second = profile("2099-01-01");
		McpProtocolProfileRegistry registry =
				new McpProtocolProfileRegistry(List.of(first, second));

		Assertions.assertEquals(List.of("2099-01-02", "2099-01-01"),
				registry.revisions());
		Assertions.assertSame(first, registry.resolve("2099-01-02").orElseThrow());
		Assertions.assertSame(second, registry.resolve("2099-01-01").orElseThrow());
		Assertions.assertTrue(registry.resolve("absent").isEmpty());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> registry.revisions().add("latest"));

		McpJsonRpcError error = McpJsonRpcError.unsupportedProtocolVersion(
				"absent", registry.revisions());
		Assertions.assertEquals("{\"code\":-32022,"
					+ "\"message\":\"Unsupported protocol version\","
					+ "\"data\":{\"supported\":[\"2099-01-02\","
					+ "\"2099-01-01\"],\"requested\":\"absent\"}}",
				new McpJsonCodec(McpJsonLimits.productionDefaults())
						.toJson(error.toJsonObject()));
	}

	@Test
	public void productionRegistryAndEvidenceIndexHaveExactOneToOneParity()
			throws Exception {
		McpProtocolProfileRegistry production =
				McpProductionProtocolProfiles.REGISTRY;
		Assertions.assertEquals(List.of(CURRENT), production.revisions());
		Assertions.assertSame(Mcp20260728ProtocolProfile.INSTANCE,
				production.resolve(CURRENT).orElseThrow());

		Path indexPath = Path.of("conformance", "official",
				"protocol-profile-evidence.json");
		McpJsonObject index = Assertions.assertInstanceOf(McpJsonObject.class,
				new McpJsonCodec(McpJsonLimits.productionDefaults())
						.parse(Files.readAllBytes(indexPath)));
		McpJsonArray profiles = Assertions.assertInstanceOf(McpJsonArray.class,
				index.members().get("profiles"));
		List<String> indexedRevisions = profiles.values().stream()
				.map(value -> Assertions.assertInstanceOf(McpJsonObject.class, value))
				.map(value -> value.members().get("revision"))
				.map(value -> Assertions.assertInstanceOf(McpJsonString.class, value))
				.map(McpJsonString::value)
				.toList();
		Assertions.assertEquals(production.revisions(), indexedRevisions);
		Assertions.assertFalse(Files.readString(indexPath).contains(FAKE));
	}

	@Test
	@Timeout(120)
	public void fakeProfileEntersOnlyThroughTheExplicitRuntimeTestSeam()
			throws Exception {
		AtomicInteger fakeMappings = new AtomicInteger();
		McpProtocolProfile fake = new McpProtocolProfile() {
			@Override
			@NonNull
			public String revision() {
				return FAKE;
			}

			@Override
			public McpJsonRpcMessage.@NonNull Request mapRequest(
					@NonNull McpRequestWireMapper mapper,
					McpJsonRpcEnvelope.@NonNull Request request) {
				fakeMappings.incrementAndGet();
				return mapper.map(request);
			}

			@Override
			@NonNull
			public McpNotificationMetadataValidation validateNotificationMetadata(
					McpJsonRpcEnvelope.@NonNull Notification notification) {
				return Mcp20260728ProtocolProfile.INSTANCE
						.validateNotificationMetadata(notification);
			}

			@Override
			@NonNull
			public McpWireResult renderFrameworkResult(
					@NonNull McpProfileFrameworkResultKind kind,
					@NonNull McpWireResult canonicalResult) {
				return canonicalResult;
			}

			@Override
			@NonNull
			public McpWireResult renderApplicationResult(
					@NonNull McpProfileApplicationResultKind kind,
					@NonNull McpWireResult canonicalResult) {
				return canonicalResult;
			}

			@Override
			public McpJsonRpcMessage.@NonNull Notification
					renderFrameworkNotification(
							@NonNull McpProfileFrameworkNotificationKind kind,
							McpJsonRpcMessage.@NonNull Notification notification) {
				return notification;
			}

			@Override
			@NonNull
			public McpJsonRpcError renderFrameworkError(
					@NonNull McpProfileErrorKind kind,
					@NonNull McpJsonRpcError canonicalError) {
				return canonicalError;
			}
		};
		McpProtocolProfileRegistry fakeRegistry =
				new McpProtocolProfileRegistry(List.of(
						Mcp20260728ProtocolProfile.INSTANCE, fake));

		try (McpHttpServerRuntime production = productionRuntime();
				McpHttpServerRuntime injected = injectedRuntime(fakeRegistry)) {
			var protocolProfiles = McpHttpServerRuntime.class
					.getDeclaredField("protocolProfiles");
			protocolProfiles.setAccessible(true);
			Assertions.assertSame(McpProductionProtocolProfiles.REGISTRY,
					protocolProfiles.get(production),
					"The production runtime must retain the sole production registry holder.");

			int productionPort = production.start().getPort();
			String productionBody = discover(productionPort, CURRENT);
			Assertions.assertTrue(productionBody.contains(
					"\"supportedVersions\":[\"" + CURRENT + "\"]"),
					productionBody);
			Assertions.assertFalse(productionBody.contains(FAKE), productionBody);

			int injectedPort = injected.start().getPort();
			String unsupportedBody = discoverError(injectedPort, "2099-12-31");
			Assertions.assertTrue(unsupportedBody.contains("\"code\":-32022"),
					unsupportedBody);
			Assertions.assertEquals(0, fakeMappings.get(),
					"An unsupported selector must not invoke any profile mapper.");
			String injectedProductionBody = discover(injectedPort, CURRENT);
			Assertions.assertTrue(injectedProductionBody.contains(
					"\"supportedVersions\":[\"" + CURRENT + "\",\"" + FAKE
							+ "\"]"), injectedProductionBody);
			Assertions.assertEquals(0, fakeMappings.get(),
					"The production selector must retain the production profile.");
			String injectedBody = discover(injectedPort, FAKE);
			Assertions.assertTrue(injectedBody.contains(
					"\"supportedVersions\":[\"" + CURRENT + "\",\"" + FAKE
							+ "\"]"),
					injectedBody);
			Assertions.assertEquals(1, fakeMappings.get());
		}

		Assertions.assertEquals(List.of(CURRENT),
				McpProductionProtocolProfiles.REGISTRY.revisions());
	}

	@Test
	public void formerSupportedConstantHasNoProductionConsumer() throws Exception {
		Path productionRoot = Path.of("src", "main", "java", "com", "soklet");
		try (var files = Files.walk(productionRoot)) {
			List<Path> offenders = files.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".java"))
					.filter(path -> {
						try {
							return Files.readString(path).contains(
									"McpProtocolVersion.SUPPORTED");
						} catch (Exception exception) {
							throw new RuntimeException(exception);
						}
					})
					.toList();
			Assertions.assertEquals(List.of(), offenders);
		}
		String runtime = Files.readString(productionRoot.resolve(Path.of(
				"internal", "mcp", "protocol", "McpHttpServerRuntime.java")));
		Assertions.assertTrue(runtime.contains(
				"this.protocolProfiles.resolve(headerProtocolVersion)"));
		Assertions.assertTrue(runtime.contains(
				"this.protocolProfiles.resolve(protocolVersion)"));
		Assertions.assertTrue(runtime.contains(
				"this.protocolProfiles.revisions().stream()"));
		String capabilities = Files.readString(productionRoot.resolve(Path.of(
				"internal", "mcp", "protocol", "McpServerCapabilityRegistry.java")));
		Assertions.assertTrue(capabilities.contains("protocolProfiles.revisions()"));
		String errors = Files.readString(productionRoot.resolve(Path.of(
				"internal", "mcp", "protocol", "McpJsonRpcError.java")));
		Assertions.assertTrue(errors.contains("supportedProtocolVersions"));
		Assertions.assertFalse(errors.contains("McpProtocolVersion"));
	}

	private static McpHttpServerRuntime productionRuntime() {
		McpNormalizedEndpoint endpoint = endpoint();
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				policy(), endpoint);
	}

	private static McpProtocolProfile profile(String revision) {
		return new McpProtocolProfile() {
			@Override
			public @NonNull String revision() {
				return revision;
			}

			@Override
			public McpJsonRpcMessage.@NonNull Request mapRequest(
					@NonNull McpRequestWireMapper mapper,
					McpJsonRpcEnvelope.@NonNull Request request) {
				return mapper.map(request);
			}

			@Override
			public @NonNull McpNotificationMetadataValidation
					validateNotificationMetadata(
							McpJsonRpcEnvelope.@NonNull Notification notification) {
				return Mcp20260728ProtocolProfile.INSTANCE
						.validateNotificationMetadata(notification);
			}

			@Override
			public @NonNull McpWireResult renderFrameworkResult(
					@NonNull McpProfileFrameworkResultKind kind,
					@NonNull McpWireResult canonicalResult) {
				return canonicalResult;
			}

			@Override
			public @NonNull McpWireResult renderApplicationResult(
					@NonNull McpProfileApplicationResultKind kind,
					@NonNull McpWireResult canonicalResult) {
				return canonicalResult;
			}

			@Override
			public McpJsonRpcMessage.@NonNull Notification
					renderFrameworkNotification(
							@NonNull McpProfileFrameworkNotificationKind kind,
							McpJsonRpcMessage.@NonNull Notification notification) {
				return notification;
			}

			@Override
			public @NonNull McpJsonRpcError renderFrameworkError(
					@NonNull McpProfileErrorKind kind,
					@NonNull McpJsonRpcError canonicalError) {
				return canonicalError;
			}
		};
	}

	private static McpHttpServerRuntime injectedRuntime(
			McpProtocolProfileRegistry registry) {
		McpNormalizedEndpoint endpoint = endpoint();
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(
				policy(), endpoint, McpApplicationRequestRouter.empty());
		return new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				List.of(binding), McpJsonLimits.productionDefaults(),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(),
				ignored -> {}, ignored -> {}, Optional.empty(),
				McpFrameworkRequestStateRuntime.disabledInstance(),
				McpSubscriptionRuntimeConfiguration.productionDefaults(),
				McpApplicationExecutionObserver.disabledInstance(), registry);
	}

	private static McpHttpEndpointPolicy policy() {
		return McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				ignored -> McpAdmissionDecision.acceptedAnonymous());
	}

	private static McpNormalizedEndpoint endpoint() {
		return McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion(
						"profile-registry-test", "4.0.0-SNAPSHOT"))
				.build();
	}

	private static String discover(int port, String revision) throws Exception {
		return discover(port, revision, 200);
	}

	private static String discoverError(int port, String revision) throws Exception {
		return discover(port, revision, 400);
	}

	private static String discover(int port, String revision, int expectedStatus)
			throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"profile\","
				+ "\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"" + revision
				+ "\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcpMessage(
				port, body, List.of(
						new McpChunkedHttpClient.RequestHeader(
								"MCP-Protocol-Version", revision),
						new McpChunkedHttpClient.RequestHeader(
								"Mcp-Method", "server/discover")))) {
			McpChunkedHttpClient.HttpResponseHead head = client.readHead();
			Assertions.assertEquals(expectedStatus, head.status(), head.raw());
			return client.readFixedBody(head);
		}
	}
}

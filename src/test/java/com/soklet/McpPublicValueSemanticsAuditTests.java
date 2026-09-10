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
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression coverage for the bounded audit of public immutable MCP classes.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpPublicValueSemanticsAuditTests {
	@Test
	void formerRecordValuesAndTheirWrappersAgreeStructurally() {
		URI uri = URI.create("catalog://resource/1");
		McpJsonObject metadata = metadata("revision", "one");
		McpJsonObject equalMetadata = metadata("revision", "one");
		McpTextResourceContents text = McpTextResourceContents
				.withUriAndText(uri, "contents")
				.mimeType("text/plain")
				.metadata(metadata)
				.build();
		McpTextResourceContents equalText = McpTextResourceContents
				.withUriAndText(uri, "contents")
				.mimeType("text/plain")
				.metadata(equalMetadata)
				.build();
		McpTextResourceContents differentText = McpTextResourceContents
				.withUriAndText(uri, "different")
				.mimeType("text/plain")
				.metadata(metadata)
				.build();
		assertValueSemantics(text, equalText, differentText);
		assertEquals(List.of(text), List.of(equalText));
		assertEquals(McpResourceOutput.fromContent(text),
				McpResourceOutput.fromContent(equalText));

		byte[] supplied = {1, 2, 3};
		byte[] equalSupplied = {1, 2, 3};
		McpBlobResourceContents blob = McpBlobResourceContents
				.withUriAndData(uri, supplied)
				.mimeType("application/octet-stream")
				.metadata(metadata)
				.build();
		McpBlobResourceContents equalBlob = McpBlobResourceContents
				.withUriAndData(uri, equalSupplied)
				.mimeType("application/octet-stream")
				.metadata(equalMetadata)
				.build();
		McpBlobResourceContents differentBlob = McpBlobResourceContents
				.withUriAndData(uri, new byte[]{1, 2, 4})
				.mimeType("application/octet-stream")
				.metadata(metadata)
				.build();
		supplied[0] = 9;
		equalSupplied[0] = 8;
		assertValueSemantics(blob, equalBlob, differentBlob);
		assertEquals(List.of(blob), List.of(equalBlob));

		McpContentAnnotations annotations = annotations(0.75);
		McpContentAnnotations equalAnnotations = McpContentAnnotations.builder()
				.audience(McpRole.ASSISTANT, McpRole.USER)
				.priority(0.75)
				.lastModified(Instant.parse("2026-09-10T12:00:00Z"))
				.build();
		assertValueSemantics(annotations, equalAnnotations, annotations(0.5));
		McpIcon icon = icon("32x32");
		McpIcon equalIcon = icon("32x32");
		assertValueSemantics(icon, equalIcon, icon("64x64"));

		McpResourceDescriptor descriptor = descriptor(uri, icon, annotations,
				metadata);
		McpResourceDescriptor equalDescriptor = descriptor(uri, equalIcon,
				equalAnnotations, equalMetadata);
		McpResourceDescriptor differentDescriptor = descriptor(
				URI.create("catalog://resource/2"), icon, annotations, metadata);
		assertValueSemantics(descriptor, equalDescriptor, differentDescriptor);
		assertEquals(McpResourceLink.fromResourceDescriptor(descriptor),
				McpResourceLink.fromResourceDescriptor(equalDescriptor));

		McpJsonObject capabilitiesJson = McpJsonObject.builder()
				.put("sampling", McpJsonObject.emptyInstance())
				.put("extensions", McpJsonObject.builder()
						.put("example.test", metadata)
						.build())
				.build();
		McpJsonObject equalCapabilitiesJson = McpJsonObject.builder()
				.put("extensions", McpJsonObject.builder()
						.put("example.test", equalMetadata)
						.build())
				.put("sampling", McpJsonObject.emptyInstance())
				.build();
		assertValueSemantics(
				McpClientCapabilities.fromJson(capabilitiesJson),
				McpClientCapabilities.fromJson(equalCapabilitiesJson),
				McpClientCapabilities.fromJson(McpJsonObject.emptyInstance()));

		McpImplementation implementation = implementation("4.0.0");
		assertValueSemantics(implementation, implementation("4.0.0"),
				implementation("4.0.1"));
	}

	@Test
	void remainingDataOnlyImmutableTypesUseStructuralEquality() {
		McpToolAnnotations toolAnnotations = toolAnnotations("Safe tool");
		assertValueSemantics(toolAnnotations, toolAnnotations("Safe tool"),
				toolAnnotations("Different tool"));

		McpJsonObject schemaDocument = McpJsonObject.builder()
				.put("type", "object")
				.build();
		assertValueSemantics(new McpToolSchema(schemaDocument),
				new McpToolSchema(McpJsonObject.builder()
						.put("type", "object").build()),
				new McpToolSchema(McpJsonObject.builder()
						.put("type", "string").build()));
		assertValueSemantics(
				McpCachePolicy.fromPrivateTimeToLive(Duration.ofSeconds(5)),
				McpCachePolicy.fromPrivateTimeToLive(Duration.ofSeconds(5)),
				McpCachePolicy.fromPublicTimeToLive(Duration.ofSeconds(5)));

		McpResourceDescriptor resource = descriptor(
				URI.create("catalog://page/1"), icon("32x32"),
				annotations(0.75), metadata("page", "one"));
		McpResourcePage page = page(resource, "next");
		McpResourcePage equalPage = page(descriptor(
				URI.create("catalog://page/1"), icon("32x32"),
				annotations(0.75), metadata("page", "one")), "next");
		assertValueSemantics(page, equalPage, page(resource, "different"));

		assertValueSemantics(progress("working"), progress("working"),
				progress("different"));
		assertValueSemantics(promptArgument("query"), promptArgument("query"),
				promptArgument("filter"));
		assertValueSemantics(tokenBucket(20), tokenBucket(20), tokenBucket(21));

		McpProtectionKeyringSnapshot snapshot = keyringSnapshot("fingerprint");
		assertValueSemantics(snapshot, keyringSnapshot("fingerprint"),
				keyringSnapshot("different"));
		assertValueSemantics(localizationCatalog("Instructions"),
				localizationCatalog("Instructions"),
				localizationCatalog("Different instructions"));

		McpInputResponses inputResponses = inputResponses("accepted");
		assertValueSemantics(inputResponses, inputResponses("accepted"),
				inputResponses("declined"));
		assertValueSemantics(inputRequired("state"), inputRequired("state"),
				inputRequired("different"));

		McpRequestStateProtectionContext protectionContext = protectionContext(
				new byte[]{1, 2, 3});
		McpRequestStateProtectionContext equalProtectionContext =
				protectionContext(new byte[]{1, 2, 3});
		assertValueSemantics(protectionContext, equalProtectionContext,
				protectionContext(new byte[]{1, 2, 4}));
		byte[] returnedAssociatedData = protectionContext.getAssociatedData();
		returnedAssociatedData[0] = 9;
		assertEquals(protectionContext, equalProtectionContext);

		assertValueSemantics(admissionRejection("challenge"),
				admissionRejection("challenge"),
				admissionRejection("different"));
	}

	@Test
	void capabilityAndInvocationCarriersRetainReferenceIdentity() {
		McpSubscriptionEventPublisher publisher =
				McpSubscriptionEventPublisher.fromInMemoryDefaults();
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher,
						Set.of(McpSubscriptionNotificationType.RESOURCE_UPDATED))
				.build();
		McpSubscriptionConfig equalLookingSubscriptions = McpSubscriptionConfig
				.withEventPublisher(publisher,
						Set.of(McpSubscriptionNotificationType.RESOURCE_UPDATED))
				.build();
		assertNotEquals(subscriptions, equalLookingSubscriptions);

		McpRequestContext requestContext = inertRequestContext();
		McpTaskRequestContext taskRequest = McpTaskRequestContext.fromComponents(
				requestContext, "task-1");
		McpTaskRequestContext equalLookingTaskRequest =
				McpTaskRequestContext.fromComponents(requestContext, "task-1");
		assertNotEquals(taskRequest, equalLookingTaskRequest);
		McpTaskUpdateContext taskUpdate = McpTaskUpdateContext.fromComponents(
				requestContext, "task-1", inputResponses("accepted"));
		McpTaskUpdateContext equalLookingTaskUpdate =
				McpTaskUpdateContext.fromComponents(requestContext, "task-1",
						inputResponses("accepted"));
		assertNotEquals(taskUpdate, equalLookingTaskUpdate);

		Object principal = new Object();
		McpAdmissionIdentity identity = McpAdmissionIdentity
				.withRateLimitPartitionKey("rate")
				.authorizationPartitionKey("authorization")
				.principal(principal)
				.build();
		McpAdmissionIdentity equalLookingIdentity = McpAdmissionIdentity
				.withRateLimitPartitionKey("rate")
				.authorizationPartitionKey("authorization")
				.principal(principal)
				.build();
		assertNotEquals(identity, equalLookingIdentity);

		byte[] material = new byte[32];
		McpProtectionKey protectionKey = McpProtectionKey.fromIdAndBytes(
				"active", material);
		McpProtectionKey equalLookingProtectionKey =
				McpProtectionKey.fromIdAndBytes("active", material);
		assertNotEquals(protectionKey, equalLookingProtectionKey);
		assertNotSame(protectionKey, equalLookingProtectionKey);
	}

	@Test
	void auditPinsDeclaredValueAndIdentitySemantics() {
		for (Class<?> valueType : List.of(
				McpTextResourceContents.class,
				McpBlobResourceContents.class,
				McpResourceDescriptor.class,
				McpClientCapabilities.class,
				McpContentAnnotations.class,
				McpIcon.class,
				McpToolAnnotations.class,
				McpToolSchema.class,
				McpCachePolicy.class,
				McpResourcePage.class,
				McpProgressUpdate.class,
				McpImplementation.class,
				McpPromptArgumentDeclaration.class,
				McpTokenBucketConfig.class,
				McpProtectionKeyringSnapshot.class,
				McpLocalizationCatalog.class,
				McpInputResponses.class,
				McpInputRequiredResult.class,
				McpRequestStateProtectionContext.class,
				McpAdmissionRejection.class)) {
			try {
				valueType.getDeclaredMethod("equals", Object.class);
				valueType.getDeclaredMethod("hashCode");
			} catch (ReflectiveOperationException exception) {
				throw new AssertionError(valueType.getName(), exception);
			}
		}

		for (Class<?> identityType : List.of(
				McpSubscriptionConfig.class,
				McpTaskRequestContext.class,
				McpTaskUpdateContext.class,
				McpAdmissionIdentity.class,
				McpProtectionKey.class)) {
			assertThrows(NoSuchMethodException.class,
					() -> identityType.getDeclaredMethod("equals", Object.class),
					identityType.getName());
			assertThrows(NoSuchMethodException.class,
					() -> identityType.getDeclaredMethod("hashCode"),
					identityType.getName());
		}
	}

	private static McpContentAnnotations annotations(double priority) {
		return McpContentAnnotations.builder()
				.audience(McpRole.USER, McpRole.ASSISTANT)
				.priority(priority)
				.lastModified(Instant.parse("2026-09-10T12:00:00Z"))
				.build();
	}

	private static McpIcon icon(String size) {
		return McpIcon.withSource(URI.create("https://example.com/icon.png"))
				.mimeType("image/png")
				.sizes(size)
				.theme(McpIconTheme.DARK)
				.build();
	}

	private static McpResourceDescriptor descriptor(URI uri, McpIcon icon,
			McpContentAnnotations annotations, McpJsonObject metadata) {
		return McpResourceDescriptor.withUriAndName(uri, "resource")
				.title("Resource")
				.description("Description")
				.mimeType("text/plain")
				.addIcon(icon)
				.annotations(annotations)
				.sizeInBytes(7L)
				.metadata(metadata)
				.build();
	}

	private static McpImplementation implementation(String version) {
		return McpImplementation.withNameAndVersion("soklet", version)
				.title("Soklet")
				.description("MCP server")
				.websiteUrl(URI.create("https://example.com/soklet"))
				.build();
	}

	private static McpToolAnnotations toolAnnotations(String title) {
		return McpToolAnnotations.builder()
				.title(title)
				.readOnlyHint(true)
				.destructiveHint(false)
				.idempotentHint(true)
				.openWorldHint(false)
				.build();
	}

	private static McpResourcePage page(McpResourceDescriptor resource,
			String nextCursor) {
		return McpResourcePage.builder()
				.addResource(resource)
				.metadata(metadata("page", "metadata"))
				.nextCursor(nextCursor)
				.cacheTimeToLiveOverride(Duration.ofSeconds(3))
				.build();
	}

	private static McpProgressUpdate progress(String message) {
		return McpProgressUpdate.withProgress(1.5)
				.total(3.0)
				.message(message)
				.build();
	}

	private static McpPromptArgumentDeclaration promptArgument(String name) {
		return McpPromptArgumentDeclaration.withName(name)
				.title("Query")
				.description("Search query")
				.required(true)
				.build();
	}

	private static McpTokenBucketConfig tokenBucket(long capacity) {
		return McpTokenBucketConfig.withCapacity(capacity)
				.refillTokens(4L)
				.refillInterval(Duration.ofSeconds(2))
				.build();
	}

	private static McpProtectionKeyringSnapshot keyringSnapshot(
			String fingerprint) {
		return new McpProtectionKeyringSnapshot("active", Set.of("verification"),
				new McpProtectionKeyringFingerprint(fingerprint));
	}

	private static McpLocalizationCatalog localizationCatalog(
			String instructions) {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
				implementation("4.0.0"))
				.instructions(instructions)
				.build();
		return McpLocalizationCatalog.fromEndpointRegistry(
				McpEndpointRegistry.fromEndpoints(List.of(endpoint)));
	}

	private static McpInputResponses inputResponses(String action) {
		return McpInputResponses.fromResponses(Map.of("approval",
				McpJsonObject.builder().put("action", action).build()));
	}

	private static McpInputRequiredResult inputRequired(String state) {
		McpInputRequest inputRequest = McpInputRequest.fromDeclaration(
				McpInputRequestDeclaration.fromRoots(McpInputRequirement.CONDITIONAL),
				McpJsonObject.emptyInstance());
		return McpInputRequiredResult.withInputRequest("roots", inputRequest)
				.frameworkRequestState(McpJsonString.fromValue(state))
				.metadata(metadata("result", "metadata"))
				.build();
	}

	private static McpRequestStateProtectionContext protectionContext(
			byte[] associatedData) {
		return McpRequestStateProtectionContext.fromComponents("/mcp",
				"2026-07-28", "tools/call", associatedData);
	}

	private static McpAdmissionRejection admissionRejection(String challenge) {
		return McpAdmissionRejection.withStatusCodeAndError(401,
				McpJsonRpcError.fromApplication(1_000, "Unauthorized"))
				.addHeader("WWW-Authenticate", challenge)
				.build();
	}

	private static McpRequestContext inertRequestContext() {
		return (McpRequestContext) Proxy.newProxyInstance(
				McpRequestContext.class.getClassLoader(),
				new Class<?>[]{McpRequestContext.class},
				(proxy, method, arguments) -> {
					throw new AssertionError("The identity fixture must not invoke "
							+ method.getName());
				});
	}

	private static McpJsonObject metadata(String name, String value) {
		return McpJsonObject.builder().put(name, value).build();
	}

	private static <T> void assertValueSemantics(T first, T equal,
			T different) {
		assertNotSame(first, equal);
		assertEquals(first, equal);
		assertEquals(equal, first);
		assertEquals(first.hashCode(), equal.hashCode());
		assertNotEquals(first, different);
		assertNotEquals(first, null);
		assertNotEquals(first, new Object());
	}
}

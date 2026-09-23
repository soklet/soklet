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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpAppMetadataSupportTests {

	@Test
	void typedToolWireMetadataComposesCanonicalFieldsAndPreservesExtensions() {
		McpJsonObject raw = McpJsonObject.builder().put("vendor", "root")
				.put("ui", McpJsonObject.builder().put("vendor", "ui").build()).build();
		McpAppToolMetadata typed = McpAppToolMetadata.builder()
				.resourceUri(URI.create("ui://orders/dashboard"))
				.visibility(new LinkedHashSet<>(List.of(McpAppToolMetadata.Visibility.APP,
						McpAppToolMetadata.Visibility.MODEL))).build();
		McpJsonObject wire = McpAppMetadataSupport.toolMetadata(raw, typed, true);
		assertEquals(McpJsonString.fromValue("root"), wire.find("vendor").orElseThrow());
		assertEquals(McpJsonObject.builder().put("vendor", "ui")
				.put("resourceUri", "ui://orders/dashboard").put("visibility", array("model", "app")).build(),
				wire.find("ui").orElseThrow());
		assertEquals(McpJsonObject.builder().put("vendor", "ui").build(), raw.find("ui").orElseThrow());
		assertEquals(McpJsonObject.builder().put("visibility", array("model", "app")).build(),
				McpAppMetadataSupport.toolMetadata(McpJsonObject.emptyInstance(),
						McpAppToolMetadata.builder().build(), true).find("ui").orElseThrow());
		assertEquals(McpJsonObject.builder().put("visibility", array()).build(),
				McpAppMetadataSupport.toolMetadata(McpJsonObject.emptyInstance(),
						McpAppToolMetadata.builder().visibility(Set.of()).build(), true).find("ui").orElseThrow());
	}

	@Test
	void rawToolWireMetadataCanonicalizesOnlyPresentOwnedFields() {
		McpJsonObject raw = metadata(McpJsonObject.builder().put("visibility", array("app", "model"))
				.put("resourceUri", "ui://orders/dashboard").put("vendor", "preserved").build());
		McpJsonObject wireUi = (McpJsonObject) McpAppMetadataSupport.toolMetadata(raw, null, true)
				.find("ui").orElseThrow();
		assertEquals(List.of("vendor", "resourceUri", "visibility"), List.copyOf(wireUi.getMembers().keySet()));
		assertEquals(array("model", "app"), wireUi.find("visibility").orElseThrow());
		assertEquals(array("app", "model"), ((McpJsonObject) raw.find("ui").orElseThrow())
				.find("visibility").orElseThrow());
		McpJsonObject omitted = metadata(McpJsonObject.builder().put("resourceUri", "ui://orders/dashboard").build());
		assertEquals(omitted, McpAppMetadataSupport.toolMetadata(omitted, null, true));
	}

	@Test
	void nonAppsToolProjectionRemovesOnlyOwnedFieldsAndDoesNotChangeRaw() {
		McpJsonObject raw = McpJsonObject.builder().put("vendor-root", "root")
				.put("ui", McpJsonObject.builder().put("resourceUri", "ui://orders/dashboard")
						.put("visibility", array("model")).put("vendor-ui", "ui").build()).build();
		assertEquals(McpJsonObject.builder().put("vendor-root", "root")
				.put("ui", McpJsonObject.builder().put("vendor-ui", "ui").build()).build(),
				McpAppMetadataSupport.toolMetadata(raw, null, false));
		assertEquals(McpJsonObject.emptyInstance(), McpAppMetadataSupport.toolMetadata(
				metadata(McpJsonObject.builder().put("visibility", array("model")).build()), null, false));
		McpJsonObject unrelated = McpJsonObject.builder().put("vendor", true).build();
		assertEquals(unrelated, McpAppMetadataSupport.toolMetadata(unrelated,
				McpAppToolMetadata.builder().resourceUri(URI.create("ui://orders/dashboard")).build(), false));
		assertTrue(((McpJsonObject) raw.find("ui").orElseThrow()).find("resourceUri").isPresent());
		assertTrue(((McpJsonObject) raw.find("ui").orElseThrow()).find("visibility").isPresent());
	}

	@Test
	void typedResourceWireMetadataComposesCanonicalPolicyPermissionsAndOptionalHints() {
		McpJsonObject raw = McpJsonObject.builder().put("vendor-root", "root")
				.put("ui", McpJsonObject.builder().put("vendor-ui", "ui").build()).build();
		McpAppResourceMetadata typed = McpAppResourceMetadata.builder()
				.contentSecurityPolicy(McpAppResourceMetadata.ContentSecurityPolicy.builder()
						.connectDomains(new LinkedHashSet<>(List.of("wss://socket.example", "https://api.example")))
						.resourceDomains(new LinkedHashSet<>(List.of("https://z.example", "https://a.example"))).build())
				.permissions(new LinkedHashSet<>(List.of(McpAppResourceMetadata.Permission.CLIPBOARD_WRITE,
						McpAppResourceMetadata.Permission.MICROPHONE, McpAppResourceMetadata.Permission.CAMERA,
						McpAppResourceMetadata.Permission.GEOLOCATION)))
				.domain("app.example").prefersBorder(false).build();
		McpJsonObject wire = McpAppMetadataSupport.resourceMetadata(raw, typed);
		McpJsonObject ui = (McpJsonObject) wire.find("ui").orElseThrow();
		McpJsonObject csp = (McpJsonObject) ui.find("csp").orElseThrow();
		McpJsonObject permissions = (McpJsonObject) ui.find("permissions").orElseThrow();
		assertEquals(McpJsonString.fromValue("root"), wire.find("vendor-root").orElseThrow());
		assertEquals(McpJsonString.fromValue("ui"), ui.find("vendor-ui").orElseThrow());
		assertEquals(array("https://api.example", "wss://socket.example"), csp.find("connectDomains").orElseThrow());
		assertEquals(array("https://a.example", "https://z.example"), csp.find("resourceDomains").orElseThrow());
		assertEquals(array(), csp.find("frameDomains").orElseThrow());
		assertEquals(array(), csp.find("baseUriDomains").orElseThrow());
		assertEquals(List.of("camera", "microphone", "geolocation", "clipboardWrite"),
				List.copyOf(permissions.getMembers().keySet()));
		assertTrue(permissions.getMembers().values().stream().allMatch(McpJsonObject.emptyInstance()::equals));
		assertEquals(McpJsonString.fromValue("app.example"), ui.find("domain").orElseThrow());
		assertEquals(McpJsonBoolean.fromValue(false), ui.find("prefersBorder").orElseThrow());
		assertEquals(McpJsonObject.builder().put("vendor-ui", "ui").build(), raw.find("ui").orElseThrow());
	}

	@Test
	void rawResourceWireMetadataPreservesNestedExtensionsAndPermissionMarkerOptions() {
		McpJsonObject cameraMarker = McpJsonObject.builder().put("vendor-camera", true).build();
		McpJsonObject clipboardMarker = McpJsonObject.builder().put("vendor-clipboard", "value").build();
		McpJsonObject rawCsp = McpJsonObject.builder().put("resourceDomains", array("https://z.example", "https://a.example"))
				.put("vendor-policy", McpJsonArray.fromElements(List.of(McpJsonNull.INSTANCE)))
				.put("connectDomains", array("wss://socket.example", "https://api.example")).build();
		McpJsonObject rawPermissions = McpJsonObject.builder().put("clipboardWrite", clipboardMarker)
				.put("camera", cameraMarker).build();
		McpJsonObject raw = metadata(McpJsonObject.builder().put("permissions", rawPermissions)
				.put("csp", rawCsp).put("vendor-ui", "preserved").build());
		McpJsonObject ui = (McpJsonObject) McpAppMetadataSupport.resourceMetadata(raw, null).find("ui").orElseThrow();
		McpJsonObject csp = (McpJsonObject) ui.find("csp").orElseThrow();
		McpJsonObject permissions = (McpJsonObject) ui.find("permissions").orElseThrow();
		assertEquals(List.of("camera", "clipboardWrite"), List.copyOf(permissions.getMembers().keySet()));
		assertSame(cameraMarker, permissions.find("camera").orElseThrow());
		assertSame(clipboardMarker, permissions.find("clipboardWrite").orElseThrow());
		assertSame(rawCsp.find("vendor-policy").orElseThrow(), csp.find("vendor-policy").orElseThrow());
		assertEquals(array("https://a.example", "https://z.example"), csp.find("resourceDomains").orElseThrow());
		assertEquals(array("https://api.example", "wss://socket.example"), csp.find("connectDomains").orElseThrow());
		assertTrue(csp.find("frameDomains").isEmpty());
		assertTrue(csp.find("baseUriDomains").isEmpty());
		assertTrue(ui.find("domain").isEmpty());
		assertTrue(ui.find("prefersBorder").isEmpty());
		assertEquals(McpJsonString.fromValue("preserved"), ui.find("vendor-ui").orElseThrow());
		assertEquals(rawCsp, ((McpJsonObject) raw.find("ui").orElseThrow()).find("csp").orElseThrow());
		assertEquals(rawPermissions, ((McpJsonObject) raw.find("ui").orElseThrow()).find("permissions").orElseThrow());
	}

	@Test
	void wireCompositionRetainsExplicitEmptyResourceFieldsAndOmission() {
		assertEquals(metadata(McpJsonObject.emptyInstance()), McpAppMetadataSupport.resourceMetadata(
				McpJsonObject.emptyInstance(), McpAppResourceMetadata.builder().build()));
		for (String field : List.of("csp", "permissions")) {
			McpJsonObject raw = metadata(McpJsonObject.builder().put(field, McpJsonObject.emptyInstance()).build());
			assertEquals(raw, McpAppMetadataSupport.resourceMetadata(raw, null));
		}
		McpJsonObject raw = metadata(McpJsonObject.builder().put("prefersBorder", false).build());
		assertEquals(raw, McpAppMetadataSupport.resourceMetadata(raw, null));
	}

	@Test
	void defaultPolicySerializesLikeEmptyBuilderWhileOmittedPolicyStaysAbsent() {
		McpAppResourceMetadata factoryMetadata = McpAppResourceMetadata.builder()
				.contentSecurityPolicy(McpAppResourceMetadata.ContentSecurityPolicy.defaultInstance()).build();
		McpAppResourceMetadata builderMetadata = McpAppResourceMetadata.builder()
				.contentSecurityPolicy(McpAppResourceMetadata.ContentSecurityPolicy.builder().build())
				.permissions(Set.of()).build();
		McpJsonObject factoryWire = McpAppMetadataSupport.resourceMetadata(
				McpJsonObject.emptyInstance(), factoryMetadata);
		McpJsonObject builderWire = McpAppMetadataSupport.resourceMetadata(
				McpJsonObject.emptyInstance(), builderMetadata);
		McpJsonCodec codec = new McpJsonCodec(McpJsonLimits.productionDefaults());
		byte[] factoryBytes = codec.toUtf8Bytes(McpPublicJsonValueConverter.toInternal(factoryWire));
		assertArrayEquals(codec.toUtf8Bytes(McpPublicJsonValueConverter.toInternal(builderWire)), factoryBytes);
		assertEquals("{\"ui\":{\"csp\":{\"connectDomains\":[],\"resourceDomains\":[],\"frameDomains\":[],\"baseUriDomains\":[]}}}",
				new String(factoryBytes, StandardCharsets.UTF_8));

		for (McpAppResourceMetadata omitted : List.of(McpAppResourceMetadata.builder().build(),
				McpAppResourceMetadata.builder().permissions(Set.of()).build())) {
			McpJsonObject wire = McpAppMetadataSupport.resourceMetadata(McpJsonObject.emptyInstance(), omitted);
			assertEquals(metadata(McpJsonObject.emptyInstance()), wire);
			assertTrue(((McpJsonObject) wire.find("ui").orElseThrow()).find("csp").isEmpty());
		}
	}

	@Test
	void ordinaryMetadataPassesThroughWithoutCreatingPresentationFields() {
		for (McpJsonObject raw : List.of(McpJsonObject.emptyInstance(),
				McpJsonObject.builder().put("other", true).build(),
				metadata(McpJsonObject.emptyInstance()),
				metadata(McpJsonObject.builder().put("vendor", "preserved").build()))) {
			assertSame(raw, McpAppMetadataSupport.toolMetadata(raw, null, true));
			assertSame(raw, McpAppMetadataSupport.toolMetadata(raw, null, false));
			assertSame(raw, McpAppMetadataSupport.resourceMetadata(raw, null));
		}
	}

	@Test
	void wireCompositionRevalidatesCollisionsAndRawValuesBeforeProjection() {
		McpJsonObject toolRaw = metadata(McpJsonObject.builder().put("visibility", array("model")).build());
		McpJsonObject resourceRaw = metadata(McpJsonObject.builder().put("prefersBorder", false).build());
		for (boolean supportsApps : List.of(false, true)) {
			assertThrows(IllegalArgumentException.class, () -> McpAppMetadataSupport.toolMetadata(toolRaw,
					McpAppToolMetadata.builder().build(), supportsApps));
			assertThrows(IllegalArgumentException.class, () -> McpAppMetadataSupport.toolMetadata(
					metadata(McpJsonObject.builder().put("visibility", array("future")).build()), null, supportsApps));
		}
		assertThrows(IllegalArgumentException.class, () -> McpAppMetadataSupport.resourceMetadata(resourceRaw,
				McpAppResourceMetadata.builder().build()));
		assertThrows(IllegalArgumentException.class, () -> McpAppMetadataSupport.resourceMetadata(
				metadata(McpJsonObject.builder().put("permissions", McpJsonObject.builder()
						.put("future", McpJsonObject.emptyInstance()).build()).build()), null));
	}

	@Test
	void oversizedTypedCspOriginCollectionFailsBeforeWireComposition() {
		Set<String> origins = new LinkedHashSet<>();
		for (int index = 0; index < McpJsonLimits.productionDefaults().maximumNodeCount(); index++)
			origins.add("https://private-origin-" + index + ".example");
		McpAppResourceMetadata typed = McpAppResourceMetadata.builder()
				.contentSecurityPolicy(McpAppResourceMetadata.ContentSecurityPolicy.builder()
						.resourceDomains(origins).build()).build();
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> McpAppMetadataSupport.resourceMetadata(McpJsonObject.emptyInstance(), typed));
		assertFalse(error.toString().contains("private-origin"));
		assertNull(error.getCause());
	}

	@Test
	void differentlyOrderedInputsSerializeToIdenticalCanonicalAppsBytes() {
		McpJsonCodec codec = new McpJsonCodec(McpJsonLimits.productionDefaults());
		McpAppToolMetadata firstTool = McpAppToolMetadata.builder().visibility(new LinkedHashSet<>(List.of(
				McpAppToolMetadata.Visibility.APP, McpAppToolMetadata.Visibility.MODEL))).build();
		McpAppToolMetadata secondTool = McpAppToolMetadata.builder().visibility(new LinkedHashSet<>(List.of(
				McpAppToolMetadata.Visibility.MODEL, McpAppToolMetadata.Visibility.APP))).build();
		assertArrayEquals(codec.toUtf8Bytes(McpPublicJsonValueConverter.toInternal(
				McpAppMetadataSupport.toolMetadata(McpJsonObject.emptyInstance(), firstTool, true))),
				codec.toUtf8Bytes(McpPublicJsonValueConverter.toInternal(
						McpAppMetadataSupport.toolMetadata(McpJsonObject.emptyInstance(), secondTool, true))));
		McpJsonObject firstRaw = metadata(McpJsonObject.builder()
				.put("csp", McpJsonObject.builder().put("connectDomains", array("https://z.example", "https://a.example")).build())
				.put("permissions", McpJsonObject.builder().put("clipboardWrite", McpJsonObject.emptyInstance())
						.put("camera", McpJsonObject.emptyInstance()).build()).build());
		McpJsonObject secondRaw = metadata(McpJsonObject.builder()
				.put("permissions", McpJsonObject.builder().put("camera", McpJsonObject.emptyInstance())
						.put("clipboardWrite", McpJsonObject.emptyInstance()).build())
				.put("csp", McpJsonObject.builder().put("connectDomains", array("https://a.example", "https://z.example")).build()).build());
		assertArrayEquals(codec.toUtf8Bytes(McpPublicJsonValueConverter.toInternal(
				McpAppMetadataSupport.resourceMetadata(firstRaw, null))),
				codec.toUtf8Bytes(McpPublicJsonValueConverter.toInternal(
						McpAppMetadataSupport.resourceMetadata(secondRaw, null))));
	}

	@Test
	void emptyAndUnrelatedRawMetadataDoesNotCreateTypedMetadata() {
		for (McpJsonObject raw : List.of(McpJsonObject.emptyInstance(),
				McpJsonObject.builder().put("other", true).build(),
				metadata(McpJsonObject.emptyInstance()),
				metadata(McpJsonObject.builder().put("vendor", "preserved").build()))) {
			assertTrue(McpAppMetadataSupport.effectiveToolMetadata(raw, null).isEmpty());
			assertTrue(McpAppMetadataSupport.effectiveResourceMetadata(raw, null).isEmpty());
		}
	}

	@Test
	void rawVisibilityHasTypedDefaultsAndCanonicalOrderWithoutMutatingRaw() {
		McpJsonObject raw = metadata(McpJsonObject.builder()
				.put("resourceUri", "ui://orders/dashboard")
				.put("visibility", array("app", "model")).put("vendor", 9).build());
		McpAppToolMetadata effective = McpAppMetadataSupport.effectiveToolMetadata(raw, null).orElseThrow();
		assertEquals(URI.create("ui://orders/dashboard"), effective.getResourceUri().orElseThrow());
		assertEquals(List.of(McpAppToolMetadata.Visibility.MODEL, McpAppToolMetadata.Visibility.APP),
				List.copyOf(effective.getVisibility()));
		assertEquals(array("app", "model"), ((McpJsonObject) raw.find("ui").orElseThrow()).find("visibility").orElseThrow());
		assertEquals(McpAppToolMetadata.builder().build().getVisibility(),
				McpAppMetadataSupport.effectiveToolMetadata(metadata(McpJsonObject.builder()
						.put("resourceUri", "ui://orders/dashboard").build()), null).orElseThrow().getVisibility());
		assertTrue(McpAppMetadataSupport.effectiveToolMetadata(metadata(McpJsonObject.builder()
				.put("visibility", array()).build()), null).orElseThrow().getVisibility().isEmpty());
	}

	@Test
	void malformedContainersAndRecognizedToolFieldsFailClosed() {
		for (McpJsonValue invalid : invalidObjects()) {
			McpJsonObject raw = McpJsonObject.builder().put("ui", invalid).build();
			assertThrows(IllegalArgumentException.class, () -> McpAppMetadataSupport.effectiveToolMetadata(raw, null));
			assertThrows(IllegalArgumentException.class, () -> McpAppMetadataSupport.effectiveResourceMetadata(raw, null));
		}
		for (McpJsonValue invalid : List.of(McpJsonNull.INSTANCE, McpJsonBoolean.fromValue(true),
				McpJsonObject.emptyInstance(), McpJsonArray.emptyInstance(),
				McpJsonString.fromValue(""), McpJsonString.fromValue("ui:relative"),
				McpJsonString.fromValue("https://other.example"), McpJsonString.fromValue("ui://orders/a/../b"),
				McpJsonString.fromValue("ui://orders/☃"))) {
			McpJsonObject raw = metadata(McpJsonObject.builder().put("resourceUri", invalid).build());
			assertThrows(IllegalArgumentException.class, () -> McpAppMetadataSupport.effectiveToolMetadata(raw, null));
		}
		for (McpJsonValue invalid : List.of(McpJsonNull.INSTANCE, McpJsonObject.emptyInstance(),
				McpJsonString.fromValue("model"), array("app", "app"), array("MODEL"), array("future"),
				McpJsonArray.fromElements(List.of(McpJsonBoolean.fromValue(true))))) {
			McpJsonObject raw = metadata(McpJsonObject.builder().put("visibility", invalid).build());
			assertThrows(IllegalArgumentException.class, () -> McpAppMetadataSupport.effectiveToolMetadata(raw, null));
		}
	}

	@Test
	void rawResourceFieldsUseTypedValidationAndDeterministicSets() {
		McpJsonObject marker = McpJsonObject.builder().put("vendor-option", true).build();
		McpJsonObject permissions = McpJsonObject.builder().put("clipboardWrite", marker)
				.put("geolocation", marker).put("microphone", marker).put("camera", marker).build();
		McpJsonObject csp = McpJsonObject.builder().put("connectDomains", array("wss://socket.example", "https://api.example"))
				.put("resourceDomains", array("https://z.example", "https://a.example"))
				.put("frameDomains", array("https://frame.example"))
				.put("baseUriDomains", array("https://base.example"))
				.put("vendor-policy", "preserved").build();
		McpJsonObject raw = metadata(McpJsonObject.builder().put("permissions", permissions)
				.put("csp", csp).put("domain", "app.example").put("prefersBorder", false).build());
		McpAppResourceMetadata effective = McpAppMetadataSupport.effectiveResourceMetadata(raw, null).orElseThrow();
		assertEquals(List.of(McpAppResourceMetadata.Permission.values()), List.copyOf(effective.getPermissions()));
		assertEquals(false, effective.getPrefersBorder().orElseThrow());
		assertEquals("app.example", effective.getDomain().orElseThrow());
		var policy = effective.getContentSecurityPolicy().orElseThrow();
		assertEquals(List.of("https://api.example", "wss://socket.example"), List.copyOf(policy.getConnectDomains()));
		assertEquals(List.of("https://a.example", "https://z.example"), List.copyOf(policy.getResourceDomains()));
		assertEquals(Set.of("https://frame.example"), policy.getFrameDomains());
		assertEquals(Set.of("https://base.example"), policy.getBaseUriDomains());
		assertEquals(permissions, ((McpJsonObject) raw.find("ui").orElseThrow()).find("permissions").orElseThrow());
		assertEquals(csp, ((McpJsonObject) raw.find("ui").orElseThrow()).find("csp").orElseThrow());
	}

	@Test
	void rawEmptySecurityFieldsRemainPresentButOmittedHintsRemainAbsent() {
		for (String field : List.of("permissions", "csp")) {
			var value = McpAppMetadataSupport.effectiveResourceMetadata(metadata(McpJsonObject.builder()
					.put(field, McpJsonObject.emptyInstance()).build()), null).orElseThrow();
			assertTrue(value.getPermissions().isEmpty());
			assertTrue(value.getDomain().isEmpty());
			assertTrue(value.getPrefersBorder().isEmpty());
			assertEquals(field.equals("csp"), value.getContentSecurityPolicy().isPresent());
		}
	}

	@Test
	void resourcePermissionsAreClosedObjectMarkersNotTruthiness() {
		for (McpJsonValue invalid : invalidObjects()) {
			assertBadResourceField("permissions", invalid);
			assertBadResourceField("permissions", McpJsonObject.builder().put("camera", invalid).build());
		}
		for (String name : List.of("future", "CAMERA", "clipboard-write"))
			assertBadResourceField("permissions", McpJsonObject.builder().put(name, McpJsonObject.emptyInstance()).build());
	}

	@Test
	void everyRawCspFieldRejectsInvalidTypeDuplicateAndUnsafeOrigins() {
		for (McpJsonValue invalid : invalidObjects())
			assertBadResourceField("csp", invalid);
		for (String field : List.of("connectDomains", "resourceDomains", "frameDomains", "baseUriDomains")) {
			for (McpJsonValue invalid : List.of(McpJsonNull.INSTANCE, McpJsonObject.emptyInstance(),
					McpJsonString.fromValue("https://example.com"), array("https://a.example", "https://a.example"),
					array("https://example.com/"), array("'self'"), array("http://remote.example"),
					McpJsonArray.fromElements(List.of(McpJsonBoolean.fromValue(true)))))
				assertBadResourceField("csp", McpJsonObject.builder().put(field, invalid).build());
			if (!field.equals("connectDomains"))
				assertBadResourceField("csp", McpJsonObject.builder().put(field, array("wss://socket.example")).build());
		}
	}

	@Test
	void rawDomainAndBorderTypesAreStrict() {
		for (McpJsonValue invalid : List.of(McpJsonNull.INSTANCE, McpJsonBoolean.fromValue(true),
				McpJsonObject.emptyInstance(), array(), McpJsonString.fromValue("https://example.com"),
				McpJsonString.fromValue("Example.com"), McpJsonString.fromValue("example.com;other")))
			assertBadResourceField("domain", invalid);
		for (McpJsonValue invalid : List.of(McpJsonNull.INSTANCE, McpJsonString.fromValue("false"),
				McpJsonObject.emptyInstance(), array()))
			assertBadResourceField("prefersBorder", invalid);
	}

	@Test
	void validationErrorsDoNotRetainRawSecretsInMessagesOrCauses() {
		String canary = "private-tenant-secret";
		for (String uri : List.of("ui://" + canary + "/[", "ui://" + canary + "/a/../b", "https://" + canary)) {
			McpJsonObject raw = metadata(McpJsonObject.builder().put("resourceUri", uri).build());
			IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
					() -> McpAppMetadataSupport.effectiveToolMetadata(raw, null));
			assertFalse(error.toString().contains(canary));
			assertNull(error.getCause());
		}
		McpJsonObject badPermission = metadata(McpJsonObject.builder().put("permissions",
				McpJsonObject.builder().put(canary, McpJsonObject.emptyInstance()).build()).build());
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> McpAppMetadataSupport.effectiveResourceMetadata(badPermission, null));
		assertFalse(error.toString().contains(canary));
		assertNull(error.getCause());
	}

	private static void assertBadResourceField(String name, McpJsonValue value) {
		McpJsonObject raw = metadata(McpJsonObject.builder().put(name, value).build());
		assertThrows(IllegalArgumentException.class, () -> McpAppMetadataSupport.effectiveResourceMetadata(raw, null));
	}

	private static List<McpJsonValue> invalidObjects() {
		return List.of(McpJsonNull.INSTANCE, McpJsonBoolean.fromValue(true), McpJsonBoolean.fromValue(false),
				McpJsonString.fromValue("false"), McpJsonArray.emptyInstance());
	}

	private static McpJsonObject metadata(McpJsonObject ui) {
		return McpJsonObject.builder().put("ui", ui).build();
	}

	private static McpJsonArray array(String... values) {
		return McpJsonArray.fromElements(java.util.Arrays.stream(values).map(McpJsonString::fromValue).toList());
	}
}

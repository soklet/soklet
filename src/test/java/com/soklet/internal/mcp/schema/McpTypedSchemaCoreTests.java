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

package com.soklet.internal.mcp.schema;

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTypedSchemaCoreTests {
	@Test
	void scalarTableRendersTheExactClosedProfileShapes() {
		FakeTypeModel model = new FakeTypeModel();
		for (McpTypedSchemaScalar scalar : McpTypedSchemaScalar.values())
			model.add(scalar.name(), new McpTypedTypeDescriptor.Scalar<>(scalar));
		McpTypedSchemaResolver<String> resolver = resolver(model, generousLimits());
		McpTypedSchemaRenderer renderer = renderer(generousLimits());

		for (McpTypedSchemaScalar scalar : McpTypedSchemaScalar.values()) {
			McpJsonObject schema = renderer.render(
					resolver.resolveSchema(scalar.name()));
			assertEquals(new McpJsonString(scalar.jsonType()),
					schema.members().get("type"));
			if (scalar.minimum().isPresent()) {
				assertEquals(new McpJsonNumber(scalar.minimum().orElseThrow()),
						schema.members().get("minimum"));
				assertEquals(new McpJsonNumber(scalar.maximum().orElseThrow()),
						schema.members().get("maximum"));
				assertEquals(List.of("type", "minimum", "maximum"),
						List.copyOf(schema.members().keySet()));
			} else {
				assertEquals(List.of("type"),
						List.copyOf(schema.members().keySet()));
			}
		}

		assertEquals(BigDecimal.valueOf(Byte.MIN_VALUE),
				McpTypedSchemaScalar.BYTE.minimum().orElseThrow());
		assertEquals(BigDecimal.valueOf(Long.MAX_VALUE),
				McpTypedSchemaScalar.LONG.maximum().orElseThrow());
	}

	@Test
	void oneResolverNormalizesRecordsCollectionsOptionalsAndMetadata() {
		FakeTypeModel model = standardModel();
		model.add("arguments", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Arguments", List.of(
				new McpTypedTypeDescriptor.RecordComponent<>("query", "string",
						Optional.of("Query"), Optional.of("Search text"),
						Optional.of("Query")),
				component("limit", "optionalInt"),
				component("tags", "listString"),
				component("states", "mapStatus"))));

		McpTypedSchemaShape shape = resolver(model, generousLimits())
				.resolveToolInput("arguments");
		McpJsonObject document = renderer(generousLimits()).render(shape);

		assertEquals(List.of("type", "properties", "required",
				"additionalProperties"), List.copyOf(document.members().keySet()));
		assertEquals(new McpJsonString("object"), document.members().get("type"));
		assertEquals(McpJsonBoolean.FALSE,
				document.members().get("additionalProperties"));

		McpJsonObject properties = assertInstanceOf(McpJsonObject.class,
				document.members().get("properties"));
		assertEquals(List.of("query", "limit", "tags", "states"),
				List.copyOf(properties.members().keySet()));
		McpJsonObject query = assertInstanceOf(McpJsonObject.class,
				properties.members().get("query"));
		assertEquals(List.of("type", "title", "description", "x-mcp-header"),
				List.copyOf(query.members().keySet()));
		assertEquals(new McpJsonString("Query"),
				query.members().get("x-mcp-header"));

		McpJsonArray required = assertInstanceOf(McpJsonArray.class,
				document.members().get("required"));
		assertEquals(List.of(new McpJsonString("query"),
				new McpJsonString("tags"), new McpJsonString("states")),
				required.values());

		McpJsonObject states = assertInstanceOf(McpJsonObject.class,
				properties.members().get("states"));
		McpJsonObject stateValues = assertInstanceOf(McpJsonObject.class,
				states.members().get("additionalProperties"));
		assertEquals(new McpJsonArray(List.of(new McpJsonString("NEW"),
				new McpJsonString("DONE"))), stateValues.members().get("enum"));

		McpToolSchemaProfileProgram program =
				new McpToolSchemaProfileCompiler(generousLimits()).compile(document);
		new McpSchemaUseValidator().validateToolInput(program);
		assertEquals(document, program.document());
	}

	@Test
	void annotatedToolArgumentsUseTheSamePropertyBoundaryResolver() {
		McpTypedSchemaResolver<String> resolver = resolver(standardModel(),
				generousLimits());
		McpTypedSchemaShape.RecordValue shape =
				resolver.resolveToolInputProperties(List.of(
						component("query", "string"),
						component("limit", "optionalInt")));
		McpJsonObject document = renderer(generousLimits()).render(shape);
		McpJsonArray required = assertInstanceOf(McpJsonArray.class,
				document.members().get("required"));

		assertEquals(List.of(new McpJsonString("query")), required.values());
		McpTypedSchemaShape.RecordValue empty =
				resolver.resolveToolInputProperties(List.of());
		assertTrue(empty.properties().isEmpty());
		new McpSchemaUseValidator().validateToolInput(
				new McpToolSchemaProfileCompiler(generousLimits()).compile(document));
	}

	@Test
	void toolRootPoliciesAreSeparateFromGeneralSchemaDerivation() {
		FakeTypeModel model = standardModel();
		model.add("arrayString", new McpTypedTypeDescriptor.ArrayValue<>(
				"string"));
		McpTypedSchemaResolver<String> resolver = resolver(model, generousLimits());

		assertInstanceOf(McpTypedSchemaShape.Scalar.class,
				resolver.resolveSchema("string"));
		McpTypedSchemaException stringOutput = assertThrows(
				McpTypedSchemaException.class,
				() -> resolver.resolveToolOutput("string"));
		assertEquals(McpTypedSchemaException.Reason.AMBIGUOUS_OUTPUT_STRING,
				stringOutput.reason());

		McpTypedSchemaException listInput = assertThrows(
				McpTypedSchemaException.class,
				() -> resolver.resolveToolInput("listString"));
		assertEquals(McpTypedSchemaException.Reason.INPUT_ROOT_NOT_OBJECT,
				listInput.reason());
		assertInstanceOf(McpTypedSchemaShape.MapValue.class,
				resolver.resolveToolInput("mapStatus"));
		assertEquals(resolver.resolveSchema("arrayString"),
				resolver.resolveSchema("listString"));
		assertInstanceOf(McpTypedSchemaShape.ArrayValue.class,
				resolver.resolveToolOutput("arrayString"));
	}

	@Test
	void emptyJavaEnumsRenderAsTheProfilePermittedEmptyEnum() {
		FakeTypeModel model = standardModel();
		model.add("emptyEnum", new McpTypedTypeDescriptor.Enumeration<>(
				"example.Empty", List.of()));
		McpJsonObject document = renderer(generousLimits()).render(
				resolver(model, generousLimits()).resolveSchema("emptyEnum"));

		assertEquals(new McpJsonArray(List.of()), document.members().get("enum"));
		new McpToolSchemaProfileCompiler(generousLimits()).compile(document);
	}

	@Test
	void optionalIsOmissionOnlyAtARecordPropertyBoundary() {
		FakeTypeModel model = standardModel();
		model.add("listOptional", new McpTypedTypeDescriptor.ListValue<>(
				"optionalInt"));
		model.add("optionalOptional",
				new McpTypedTypeDescriptor.OptionalValue<>("optionalInt"));
		model.add("nestedOptionalRecord", new McpTypedTypeDescriptor.RecordValue<>(
				"example.NestedOptional", List.of(
				component("value", "optionalOptional"))));
		McpTypedSchemaResolver<String> resolver = resolver(model, generousLimits());

		McpTypedSchemaException root = assertThrows(McpTypedSchemaException.class,
				() -> resolver.resolveSchema("optionalInt"));
		assertEquals(McpTypedSchemaException.Reason.OPTIONAL_OUTSIDE_PROPERTY,
				root.reason());
		assertEquals("$", root.path().toString());

		McpTypedSchemaException list = assertThrows(McpTypedSchemaException.class,
				() -> resolver.resolveSchema("listOptional"));
		assertEquals(McpTypedSchemaException.Reason.OPTIONAL_OUTSIDE_PROPERTY,
				list.reason());
		assertEquals("$/items", list.path().toString());

		McpTypedSchemaException nested = assertThrows(
				McpTypedSchemaException.class,
				() -> resolver.resolveSchema("nestedOptionalRecord"));
		assertEquals(McpTypedSchemaException.Reason.OPTIONAL_OUTSIDE_PROPERTY,
				nested.reason());
		assertEquals("$/properties/value/optional", nested.path().toString());
	}

	@Test
	void recordDeclarationIdentityStopsExpandingAndMutualCycles() {
		FakeTypeModel model = standardModel();
		model.add("expanding1", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Expanding", List.of(component("next", "expanding2"))));
		model.add("expanding2", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Expanding", List.of(component("next", "expanding3"))));
		model.add("expanding3", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Expanding", List.of(component("value", "string"))));

		McpTypedSchemaException cycle = assertThrows(McpTypedSchemaException.class,
				() -> resolver(model, generousLimits()).resolveSchema("expanding1"));
		assertEquals(McpTypedSchemaException.Reason.RECURSIVE_TYPE,
				cycle.reason());
		assertEquals("$/properties/next", cycle.path().toString());

		model.add("child", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Child", List.of(component("value", "string"))));
		model.add("siblings", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Siblings", List.of(component("left", "child"),
				component("right", "child"))));
		McpTypedSchemaShape.RecordValue siblings = assertInstanceOf(
				McpTypedSchemaShape.RecordValue.class,
				resolver(model, generousLimits()).resolveSchema("siblings"));
		assertEquals(2, siblings.properties().size());
	}

	@Test
	void repeatedGenericRecordsRequireStrictlyDecreasingComplexity() {
		FakeTypeModel model = standardModel();
		model.add("shrinkingOuter", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Shrinking", List.of(
						component("next", "shrinkingInner")), 2));
		model.add("shrinkingInner", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Shrinking", List.of(component("value", "string")), 1));
		model.add("equalOuter", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Equal", List.of(component("next", "equalInner")), 1));
		model.add("equalInner", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Equal", List.of(component("value", "string")), 1));
		model.add("increasingOuter", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Increasing",
				List.of(component("next", "increasingInner")), 1));
		model.add("increasingInner", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Increasing", List.of(component("value", "string")), 2));
		McpTypedSchemaResolver<String> resolver = resolver(model,
				generousLimits());

		McpTypedSchemaShape.RecordValue shrinking = assertInstanceOf(
				McpTypedSchemaShape.RecordValue.class,
				resolver.resolveSchema("shrinkingOuter"));
		assertInstanceOf(McpTypedSchemaShape.RecordValue.class,
				shrinking.properties().get(0).shape());

		for (String root : List.of("equalOuter", "increasingOuter")) {
			McpTypedSchemaException failure = assertThrows(
					McpTypedSchemaException.class,
					() -> resolver.resolveSchema(root));
			assertEquals(McpTypedSchemaException.Reason.RECURSIVE_TYPE,
					failure.reason());
			assertEquals("$/properties/next", failure.path().toString());
		}
	}

	@Test
	void unusedGenericArgumentsAreScreenedByTheSharedPolicyAndLimits() {
		FakeTypeModel model = standardModel();
		model.add("wildcard", new McpTypedTypeDescriptor.Unsupported<>(
				McpTypedSchemaException.Reason.WILDCARD));
		model.add("phantom", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Phantom", List.of(component("value", "string")), 1,
				List.of("wildcard")));
		model.add("tooComplex", new McpTypedTypeDescriptor.RecordValue<>(
				"example.TooComplex", List.of(), 3));

		McpTypedSchemaException screening = assertThrows(
				McpTypedSchemaException.class,
				() -> resolver(model, generousLimits()).resolveSchema("phantom"));
		assertEquals(McpTypedSchemaException.Reason.WILDCARD,
				screening.reason());
		assertEquals("$/genericArguments/0", screening.path().toString());

		assertLimit(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
				() -> resolver(model, limits(2, 10, 100, 10, 10))
						.resolveSchema("tooComplex"));
		assertThrows(IllegalArgumentException.class,
				() -> new McpTypedTypeDescriptor.RecordValue<>("example.Bad",
						List.of(), -1));
	}

	@Test
	void mapKeysAndUnsupportedClassificationsFailWithStableReasons() {
		FakeTypeModel model = standardModel();
		model.add("badMap", new McpTypedTypeDescriptor.MapValue<>("int",
				"string"));
		McpTypedSchemaException map = assertThrows(McpTypedSchemaException.class,
				() -> resolver(model, generousLimits()).resolveSchema("badMap"));
		assertEquals(McpTypedSchemaException.Reason.MAP_KEY_NOT_STRING,
				map.reason());

		for (McpTypedSchemaException.Reason reason : List.of(
				McpTypedSchemaException.Reason.RAW_GENERIC,
				McpTypedSchemaException.Reason.WILDCARD,
				McpTypedSchemaException.Reason.UNRESOLVED_TYPE_VARIABLE,
				McpTypedSchemaException.Reason.UNRESOLVED_GENERIC_ARRAY_COMPONENT,
				McpTypedSchemaException.Reason.OBJECT_TYPE,
				McpTypedSchemaException.Reason.CHAR_SEQUENCE_TYPE,
				McpTypedSchemaException.Reason.FRAMEWORK_TYPE,
				McpTypedSchemaException.Reason.UNSUPPORTED_TYPE)) {
			String type = "unsupported-" + reason;
			model.add(type, new McpTypedTypeDescriptor.Unsupported<>(reason));
			McpTypedSchemaException exception = assertThrows(
					McpTypedSchemaException.class,
					() -> resolver(model, generousLimits()).resolveSchema(type));
			assertEquals(reason, exception.reason());
			assertEquals("$", exception.path().toString());
		}
	}

	@Test
	void resolverAndRendererEnforceCompilationLimitsAtExactBoundaries() {
		FakeTypeModel model = standardModel();
		model.add("pair", new McpTypedTypeDescriptor.RecordValue<>("example.Pair",
				List.of(component("aa", "string"), component("b", "string"))));
		model.add("one", new McpTypedTypeDescriptor.RecordValue<>("example.One",
				List.of(component("a", "string"))));

		assertInstanceOf(McpTypedSchemaShape.RecordValue.class,
				resolver(model, limits(2, 2, 100, 1, 1))
						.resolveSchema("one"));
		assertLimit(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
				() -> resolver(model, limits(1, 2, 100, 1, 1))
						.resolveSchema("one"));
		assertLimit(McpSchemaCompilationException.Limit.SCHEMA_DEPTH,
				() -> resolver(model, limits(2, 1, 100, 1, 1))
						.resolveSchema("one"));
		assertLimit(McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
				() -> resolver(model, limits(10, 10, 100, 1, 1))
						.resolveSchema("pair"));
		assertLimit(McpSchemaCompilationException.Limit.NAME_LENGTH,
				() -> resolver(model, limits(10, 10, 100, 2, 1))
						.resolveSchema("pair"));

		McpTypedSchemaShape boundedInteger = new McpTypedSchemaShape.Scalar(
				McpTypedSchemaScalar.INT);
		renderer(limits(10, 10, 3, 10, 10)).render(boundedInteger);
		assertLimit(McpSchemaCompilationException.Limit.KEYWORD_COUNT,
				() -> renderer(limits(10, 10, 2, 10, 10))
						.render(boundedInteger));
	}

	@Test
	void renderingIsDeterministicAndEmptyRecordsRemainClosedObjects() {
		McpTypedSchemaShape empty = new McpTypedSchemaShape.RecordValue(List.of());
		McpTypedSchemaRenderer renderer = renderer(generousLimits());
		McpJsonObject first = renderer.render(empty);
		McpJsonObject second = renderer.render(empty);

		assertEquals(first, second);
		assertEquals(new McpJsonObject(Map.of()),
				first.members().get("properties"));
		assertEquals(new McpJsonArray(List.of()), first.members().get("required"));
		assertEquals(McpJsonBoolean.FALSE,
				first.members().get("additionalProperties"));
		McpToolSchemaProfileProgram program =
				new McpToolSchemaProfileCompiler(generousLimits()).compile(first);
		new McpSchemaUseValidator().validateToolInput(program);
	}

	@Test
	void descriptorFailuresAreContainedAndPathsUseStablePointerEscaping() {
		McpTypedSchemaResolver<String> nullResolver = resolver(type -> null,
				generousLimits());
		McpTypedSchemaException missing = assertThrows(
				McpTypedSchemaException.class,
				() -> nullResolver.resolveSchema("missing"));
		assertEquals(McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
				missing.reason());

		McpTypedSchemaResolver<String> throwingResolver = resolver(type -> {
			throw new IllegalStateException("adapter detail");
		}, generousLimits());
		McpTypedSchemaException throwing = assertThrows(
				McpTypedSchemaException.class,
				() -> throwingResolver.resolveSchema("throwing"));
		assertEquals(McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
				throwing.reason());
		assertFalse(throwing.getMessage().contains("adapter detail"));

		McpTypedSchemaResolver<String> linkageResolver = resolver(type -> {
			throw new NoClassDefFoundError("application detail");
		}, generousLimits());
		McpTypedSchemaException linkage = assertThrows(
				McpTypedSchemaException.class,
				() -> linkageResolver.resolveSchema("linkage"));
		assertEquals(McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
				linkage.reason());
		assertFalse(linkage.getMessage().contains("application detail"));

		assertEquals("$/properties/a~1b~0c/items",
				McpTypedSchemaPath.root().property("a/b~c").arrayElement()
						.toString());

		String unsafeName = "line\ncolumn\t"
				+ (char) 0x0000 + (char) 0x007F + (char) 0x0085
				+ (char) 0x2028 + (char) 0x2029 + (char) 0x202E
				+ (char) 0x2066 + (char) 0x2069 + (char) 0x061C
				+ (char) 0x200E + (char) 0x200F + "/~\uD83D\uDE00";
		String safePath = McpTypedSchemaPath.root().property(unsafeName)
				.toString();
		assertEquals(
				"$/properties/line\\u000Acolumn\\u0009\\u0000\\u007F"
						+ "\\u0085\\u2028\\u2029\\u202E\\u2066\\u2069"
						+ "\\u061C\\u200E\\u200F~1~0\uD83D\uDE00",
				safePath);
		assertFalse(safePath.contains("\n"));
		assertFalse(safePath.contains("\t"));
		assertFalse(safePath.indexOf((char) 0x202E) >= 0);

		String boundedPath = McpTypedSchemaPath.root()
				.property("x".repeat(10_000)).toString();
		assertTrue(boundedPath.endsWith("..."), boundedPath);
		assertTrue(boundedPath.length() <= 272, boundedPath);
	}

	private static FakeTypeModel standardModel() {
		return new FakeTypeModel()
				.add("string", new McpTypedTypeDescriptor.Scalar<>(
						McpTypedSchemaScalar.STRING))
				.add("int", new McpTypedTypeDescriptor.Scalar<>(
						McpTypedSchemaScalar.INT))
				.add("status", new McpTypedTypeDescriptor.Enumeration<>(
						"example.Status", List.of("NEW", "DONE")))
				.add("optionalInt", new McpTypedTypeDescriptor.OptionalValue<>(
						"int"))
				.add("listString", new McpTypedTypeDescriptor.ListValue<>(
						"string"))
				.add("mapStatus", new McpTypedTypeDescriptor.MapValue<>(
						"string", "status"));
	}

	private static McpTypedTypeDescriptor.RecordComponent<String> component(
			String name, String type) {
		return McpTypedTypeDescriptor.RecordComponent.fromNameAndType(name, type);
	}

	private static McpTypedSchemaResolver<String> resolver(
			McpTypedTypeModel<String> model, McpSchemaCompilationLimits limits) {
		return new McpTypedSchemaResolver<>(model, limits);
	}

	private static McpTypedSchemaRenderer renderer(
			McpSchemaCompilationLimits limits) {
		return new McpTypedSchemaRenderer(limits);
	}

	private static McpSchemaCompilationLimits generousLimits() {
		return limits(10_000, 256, 100_000, 10_000, 4_096);
	}

	private static McpSchemaCompilationLimits limits(int nodes, int depth,
			int keywords, int collectionEntries, int nameLength) {
		return new McpSchemaCompilationLimits(nodes, depth, keywords,
				1_000, 1_000, 4_096, 4_096, 256, collectionEntries,
				nameLength, 4_096);
	}

	private static void assertLimit(McpSchemaCompilationException.Limit limit,
			Runnable invocation) {
		McpTypedSchemaException exception = assertThrows(
				McpTypedSchemaException.class, invocation::run);
		assertEquals(McpTypedSchemaException.Reason.LIMIT_EXCEEDED,
				exception.reason());
		assertEquals(Optional.of(limit), exception.limit());
		assertTrue(exception.getMessage().contains("limit"));
	}

	private static final class FakeTypeModel
			implements McpTypedTypeModel<String> {
		private final Map<String, McpTypedTypeDescriptor<String>> descriptors =
				new LinkedHashMap<>();

		private FakeTypeModel add(String type,
				McpTypedTypeDescriptor<String> descriptor) {
			descriptors.put(type, descriptor);
			return this;
		}

		@Override
		public McpTypedTypeDescriptor<String> describe(String type) {
			return descriptors.get(type);
		}
	}
}

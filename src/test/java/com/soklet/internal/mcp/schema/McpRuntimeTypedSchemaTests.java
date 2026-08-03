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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.CharBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class McpRuntimeTypedSchemaTests {
	private static final McpSchemaCompilationLimits LIMITS =
			McpSchemaCompilationLimits.productionDefaults();
	private static final McpRuntimeTypedTypeModel TYPE_MODEL =
			new McpRuntimeTypedTypeModel(LIMITS);
	private static final McpTypedSchemaResolver<Type> RESOLVER =
			new McpTypedSchemaResolver<>(TYPE_MODEL, LIMITS);
	private static final McpTypedSchemaRenderer RENDERER =
			new McpTypedSchemaRenderer(LIMITS);
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(1_000_000, 128, 1_000_000, 1_000_000,
					1_024, 10_000, 100_000, 4_000_000));

	@Test
	void derivesEverySupportedScalarWithExactBounds() {
		Map<Type, String> expected = Map.ofEntries(
				Map.entry(boolean.class, "{\"type\":\"boolean\"}"),
				Map.entry(Boolean.class, "{\"type\":\"boolean\"}"),
				Map.entry(byte.class,
						"{\"type\":\"integer\",\"minimum\":-128,\"maximum\":127}"),
				Map.entry(Byte.class,
						"{\"type\":\"integer\",\"minimum\":-128,\"maximum\":127}"),
				Map.entry(short.class,
						"{\"type\":\"integer\",\"minimum\":-32768,\"maximum\":32767}"),
				Map.entry(Short.class,
						"{\"type\":\"integer\",\"minimum\":-32768,\"maximum\":32767}"),
				Map.entry(int.class,
						"{\"type\":\"integer\",\"minimum\":-2147483648,\"maximum\":2147483647}"),
				Map.entry(Integer.class,
						"{\"type\":\"integer\",\"minimum\":-2147483648,\"maximum\":2147483647}"),
				Map.entry(long.class,
						"{\"type\":\"integer\",\"minimum\":-9223372036854775808,\"maximum\":9223372036854775807}"),
				Map.entry(Long.class,
						"{\"type\":\"integer\",\"minimum\":-9223372036854775808,\"maximum\":9223372036854775807}"),
				Map.entry(BigInteger.class, "{\"type\":\"integer\"}"),
				Map.entry(float.class, "{\"type\":\"number\"}"),
				Map.entry(Float.class, "{\"type\":\"number\"}"),
				Map.entry(double.class, "{\"type\":\"number\"}"),
				Map.entry(Double.class, "{\"type\":\"number\"}"),
				Map.entry(BigDecimal.class, "{\"type\":\"number\"}"),
				Map.entry(String.class, "{\"type\":\"string\"}"));

		for (Map.Entry<Type, String> entry : expected.entrySet())
			Assertions.assertEquals(entry.getValue(), schema(entry.getKey()),
					() -> "Unexpected schema for " + entry.getKey());
	}

	@Test
	void derivesEnumsArraysListsMapsAndParameterizedRecordsDeterministically()
			throws ReflectiveOperationException {
		Assertions.assertEquals(
				"{\"type\":\"string\",\"enum\":[\"WEST\",\"EAST\",\"NORTH\"]}",
				schema(Direction.class));
		Assertions.assertEquals(
				"{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"minimum\":-2147483648,\"maximum\":2147483647}}",
				schema(int[].class));
		Assertions.assertEquals(
				"{\"type\":\"array\",\"items\":{\"type\":\"string\"}}",
				schema(fieldType("strings")));
		Assertions.assertEquals(
				"{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\",\"minimum\":-9223372036854775808,\"maximum\":9223372036854775807}}",
				schema(fieldType("longsByName")));

		String boxSchema = schema(fieldType("stringBox"));
		Assertions.assertEquals(
				"{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"},\"history\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"value\"],\"additionalProperties\":false}",
				boxSchema);
		Assertions.assertEquals(boxSchema, schema(fieldType("stringBox")));
		Assertions.assertEquals(
				"{\"type\":\"array\",\"items\":" + boxSchema + "}",
					schema(fieldType("stringBoxes")));
	}

	@Test
	void allowsFiniteRepeatedGenericRecordsWhenComplexityStrictlyDecreases()
			throws ReflectiveOperationException {
		McpJsonObject document = RENDERER.render(
				RESOLVER.resolveSchema(fieldType("nestedBox")));

		new McpToolSchemaProfileCompiler(LIMITS).compile(document);
		Assertions.assertEquals(new McpJsonString("object"),
				document.members().get("type"));
	}

	@Test
	void screensUnusedGenericArgumentsThroughTheSharedNestedPolicy()
			throws ReflectiveOperationException {
		assertSchemaFailure(fieldType("phantomWildcard"),
				McpTypedSchemaException.Reason.WILDCARD,
				"$/genericArguments/0");
		assertSchemaFailure(fieldType("phantomNestedWildcard"),
				McpTypedSchemaException.Reason.WILDCARD,
				"$/genericArguments/0/items");
		assertSchemaFailure(fieldType("phantomObject"),
				McpTypedSchemaException.Reason.OBJECT_TYPE,
				"$/genericArguments/0");
		assertSchemaFailure(fieldType("phantomOptional"),
				McpTypedSchemaException.Reason.OPTIONAL_OUTSIDE_PROPERTY,
				"$/genericArguments/0");
		assertSchemaFailure(fieldType("phantomFramework"),
				McpTypedSchemaException.Reason.FRAMEWORK_TYPE,
				"$/genericArguments/0");

		Assertions.assertEquals(
				"{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"integer\",\"minimum\":-2147483648,\"maximum\":2147483647}},\"required\":[\"value\"],\"additionalProperties\":false}",
				schema(fieldType("phantomString")));
		Assertions.assertEquals(
				"{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[],\"additionalProperties\":false}",
				schema(fieldType("usedOptional")));
	}

	@Test
	void rejectsFrameworkMarkerSubtypesAtRootAndNestedPaths() {
		McpRuntimeTypedTypeModel model = new McpRuntimeTypedTypeModel(LIMITS,
				Set.of(FrameworkMarker.class.getName()));
		McpTypedSchemaResolver<Type> resolver =
				new McpTypedSchemaResolver<>(model, LIMITS);

		assertSchemaFailure(resolver, FrameworkSubtype.class,
				McpTypedSchemaException.Reason.FRAMEWORK_TYPE, "$");
		assertSchemaFailure(resolver, FrameworkHolder.class,
				McpTypedSchemaException.Reason.FRAMEWORK_TYPE,
				"$/properties/value");
		assertSchemaFailure(McpJsonObject.class,
				McpTypedSchemaException.Reason.FRAMEWORK_TYPE, "$");
	}

	@Test
	void rejectsIdentityCyclicCustomGenericMetadataWithoutRecursing() {
		GenericArrayType cyclicType = new GenericArrayType() {
			@Override
			public Type getGenericComponentType() {
				return this;
			}
		};

		assertSchemaFailure(cyclicType,
				McpTypedSchemaException.Reason.INVALID_DESCRIPTOR, "$");
	}

	@Test
	void cachesOneImmutableEnumDescriptorPerRuntimeModel() {
		McpRuntimeTypedTypeModel model = new McpRuntimeTypedTypeModel(LIMITS);

		Assertions.assertSame(model.describe(Direction.class),
				model.describe(Direction.class));
		Assertions.assertNotSame(model.describe(Direction.class),
				new McpRuntimeTypedTypeModel(LIMITS).describe(Direction.class));
	}

	@Test
	void derivedInputAndOutputDocumentsPassProfileCompilationAndUseValidation()
			throws ReflectiveOperationException {
		for (Type input : List.of(SimpleInput.class, fieldType("longsByName"))) {
			McpJsonObject document = RENDERER.render(RESOLVER.resolveToolInput(input));
			McpToolSchemaProfileProgram program =
					new McpToolSchemaProfileCompiler(LIMITS).compile(document);
			new McpSchemaUseValidator().validateToolInput(program);
			Assertions.assertEquals(document, program.document());
		}

		for (Type output : List.of(Integer.class, Direction.class, int[].class,
				fieldType("strings"), SimpleInput.class)) {
			McpJsonObject document = RENDERER.render(
					RESOLVER.resolveToolOutput(output));
			McpToolSchemaProfileProgram program =
					new McpToolSchemaProfileCompiler(LIMITS).compile(document);
			new McpSchemaUseValidator().validateToolOutput(program);
		}
	}

	@Test
	void enforcesInputAndOutputRootPolicies() throws ReflectiveOperationException {
		Assertions.assertInstanceOf(McpTypedSchemaShape.Scalar.class,
				RESOLVER.resolveSchema(String.class));
		assertFailure(Integer.class,
				McpTypedSchemaException.Reason.INPUT_ROOT_NOT_OBJECT, "$", true);
		assertFailure(fieldType("strings"),
				McpTypedSchemaException.Reason.INPUT_ROOT_NOT_OBJECT, "$", true);
		assertFailure(String.class,
				McpTypedSchemaException.Reason.AMBIGUOUS_OUTPUT_STRING, "$", false);
		assertFailure(fieldType("optionalString"),
				McpTypedSchemaException.Reason.OPTIONAL_OUTSIDE_PROPERTY, "$", false);
	}

	@Test
	void rejectsEveryUnsupportedReflectionShapeSynchronously()
			throws ReflectiveOperationException {
		assertSchemaFailure(Object.class, McpTypedSchemaException.Reason.OBJECT_TYPE,
				"$");
		assertSchemaFailure(char.class,
				McpTypedSchemaException.Reason.UNSUPPORTED_TYPE, "$");
		assertSchemaFailure(Character.class,
				McpTypedSchemaException.Reason.UNSUPPORTED_TYPE, "$");
		assertSchemaFailure(CharBuffer.class,
				McpTypedSchemaException.Reason.CHAR_SEQUENCE_TYPE, "$");
		assertSchemaFailure(ArbitraryBean.class,
				McpTypedSchemaException.Reason.UNSUPPORTED_TYPE, "$");
		assertSchemaFailure(List.class, McpTypedSchemaException.Reason.RAW_GENERIC,
				"$");
		assertSchemaFailure(Box.class, McpTypedSchemaException.Reason.RAW_GENERIC,
				"$");
		assertSchemaFailure(fieldType("wildcardStrings"),
				McpTypedSchemaException.Reason.WILDCARD, "$/items");
		assertSchemaFailure(fieldType("integerKeyed"),
				McpTypedSchemaException.Reason.MAP_KEY_NOT_STRING, "$");
		assertSchemaFailure(fieldType("optionalStrings"),
				McpTypedSchemaException.Reason.OPTIONAL_OUTSIDE_PROPERTY, "$/items");
		assertSchemaFailure(fieldType("unresolved"),
				McpTypedSchemaException.Reason.UNRESOLVED_TYPE_VARIABLE, "$");
		assertSchemaFailure(fieldType("unresolvedArray"),
				McpTypedSchemaException.Reason.UNRESOLVED_GENERIC_ARRAY_COMPONENT,
				"$");
		assertSchemaFailure(McpJsonValue.class,
				McpTypedSchemaException.Reason.FRAMEWORK_TYPE, "$");
	}

	@Test
	void rejectsDirectMutualAndExpandingGenericRecordCycles() {
		assertSchemaFailure(Recursive.class,
				McpTypedSchemaException.Reason.RECURSIVE_TYPE,
				"$/properties/next");
		assertSchemaFailure(Left.class,
				McpTypedSchemaException.Reason.RECURSIVE_TYPE,
				"$/properties/right/properties/left");
		assertSchemaFailure(fieldTypeUnchecked("expanding"),
				McpTypedSchemaException.Reason.RECURSIVE_TYPE,
				"$/properties/next");
	}

	@Test
	void adapterLimitsAreRepathedAtTheNestedTypeUse() {
		McpSchemaCompilationLimits narrow = new McpSchemaCompilationLimits(
				100, 20, 1_000, 10, 10, 100, 100, 20, 2, 100, 100);
		McpTypedSchemaResolver<Type> resolver = new McpTypedSchemaResolver<>(
				new McpRuntimeTypedTypeModel(narrow), narrow);

		McpTypedSchemaException exception = Assertions.assertThrows(
				McpTypedSchemaException.class,
				() -> resolver.resolveSchema(EnumHolder.class));
		Assertions.assertEquals(McpTypedSchemaException.Reason.LIMIT_EXCEEDED,
				exception.reason());
		Assertions.assertEquals(Optional.of(
				McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT),
				exception.limit());
		Assertions.assertEquals("$/properties/direction",
				exception.path().toString());
	}

	private static String schema(Type type) {
		return JSON_CODEC.toJson(RENDERER.render(RESOLVER.resolveSchema(type)));
	}

	private static void assertFailure(Type type,
			McpTypedSchemaException.Reason reason, String path, boolean input) {
		McpTypedSchemaException exception = Assertions.assertThrows(
				McpTypedSchemaException.class,
				() -> {
					if (input)
						RESOLVER.resolveToolInput(type);
					else
						RESOLVER.resolveToolOutput(type);
				});
		Assertions.assertEquals(reason, exception.reason());
		Assertions.assertEquals(path, exception.path().toString());
	}

	private static void assertSchemaFailure(Type type,
			McpTypedSchemaException.Reason reason, String path) {
		assertSchemaFailure(RESOLVER, type, reason, path);
	}

	private static void assertSchemaFailure(
			McpTypedSchemaResolver<Type> resolver, Type type,
			McpTypedSchemaException.Reason reason, String path) {
		McpTypedSchemaException exception = Assertions.assertThrows(
				McpTypedSchemaException.class,
				() -> resolver.resolveSchema(type));
		Assertions.assertEquals(reason, exception.reason());
		Assertions.assertEquals(path, exception.path().toString());
	}

	private static Type fieldType(String name) throws ReflectiveOperationException {
		return TypeFixtures.class.getDeclaredField(name).getGenericType();
	}

	private static Type fieldTypeUnchecked(String name) {
		try {
			return TypeFixtures.class.getDeclaredField(name).getGenericType();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private record SimpleInput(int count, Optional<String> query) {
	}

	private record Box<T>(T value, Optional<List<T>> history) {
	}

	private record Phantom<T>(int value) {
	}

	private record Used<T>(T value) {
	}

	private record Recursive(Recursive next) {
	}

	private record Left(Right right) {
	}

	private record Right(Left left) {
	}

	private record Expanding<T>(Expanding<List<T>> next) {
	}

	private record EnumHolder(Direction direction) {
	}

	private interface FrameworkMarker {
	}

	private record FrameworkSubtype(int value) implements FrameworkMarker {
	}

	private record FrameworkHolder(FrameworkSubtype value) {
	}

	private enum Direction {
		WEST,
		EAST,
		NORTH
	}

	private static final class ArbitraryBean {
	}

	@SuppressWarnings("rawtypes")
	private static final class TypeFixtures<T> {
		private List<String> strings;
		private Map<String, Long> longsByName;
		private Box<String> stringBox;
		private Box<String>[] stringBoxes;
		private Box<Box<String>> nestedBox;
		private Optional<String> optionalString;
		private List<?> wildcardStrings;
		private Map<Integer, String> integerKeyed;
		private List<Optional<String>> optionalStrings;
		private T unresolved;
		private T[] unresolvedArray;
		private Expanding<String> expanding;
		private Phantom<?> phantomWildcard;
		private Phantom<List<?>> phantomNestedWildcard;
		private Phantom<Object> phantomObject;
		private Phantom<Optional<String>> phantomOptional;
		private Phantom<McpJsonObject> phantomFramework;
		private Phantom<String> phantomString;
		private Used<Optional<String>> usedOptional;
	}
}

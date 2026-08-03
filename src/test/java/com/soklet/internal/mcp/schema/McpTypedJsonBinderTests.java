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
import com.soklet.internal.mcp.protocol.McpJsonNull;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class McpTypedJsonBinderTests {
	private static final McpSchemaCompilationLimits LIMITS =
			McpSchemaCompilationLimits.productionDefaults();
	private static final McpTypedSchemaResolver<Type> RESOLVER =
			new McpTypedSchemaResolver<>(new McpRuntimeTypedTypeModel(LIMITS),
					LIMITS);
	private static final McpRuntimeTypedJsonBindingCompiler BINDING_COMPILER =
			new McpRuntimeTypedJsonBindingCompiler(LIMITS);
	private static final McpTypedJsonBinder BINDER = new McpTypedJsonBinder();

	@Test
	void recordRoundTripUsesExactStringsOptionalsAndImmutableCollections()
			throws ReflectiveOperationException {
		McpTypedJsonBinding<Payload> binding = binding(Payload.class);
		McpJsonObject input = object(
				"exact", new McpJsonString("  unchanged  "),
				"count", number("1.0"),
				"labels", array(new McpJsonString("z"),
						new McpJsonString("a")),
				"scores", object("z", number("2"), "a", number("1")),
				"mode", new McpJsonString("SECOND"),
				"samples", array(number("3"), number("4")));

		Payload payload = BINDER.fromJson(input, binding);
		assertEquals("  unchanged  ", payload.exact());
		assertEquals(1, payload.count());
		assertEquals(Optional.empty(), payload.note());
		assertEquals(List.of("z", "a"), payload.labels());
		assertEquals(List.of("z", "a"),
				new ArrayList<>(payload.scores().keySet()));
		assertEquals(Mode.SECOND, payload.mode());
		assertArrayEquals(new int[] { 3, 4 }, payload.samples());
		assertThrows(UnsupportedOperationException.class,
				() -> payload.labels().add("blocked"));
		assertThrows(UnsupportedOperationException.class,
				() -> payload.scores().put("blocked", 3));

		McpJsonObject output = assertInstanceOf(McpJsonObject.class,
				BINDER.toJson(payload, binding));
		assertEquals(List.of("exact", "count", "labels", "scores", "mode",
				"samples"), new ArrayList<>(output.members().keySet()));
		McpJsonObject sortedScores = assertInstanceOf(McpJsonObject.class,
				output.members().get("scores"));
		assertEquals(List.of("a", "z"),
				new ArrayList<>(sortedScores.members().keySet()));
		assertValid(binding.shape(), output);
	}

	@Test
	void numericConversionsAreExactAndFiniteWithDocumentedUnderflow() {
		McpTypedJsonBinding<Byte> byteBinding = binding(Byte.class);
		assertEquals((byte) -128, BINDER.fromJson(number("-128"), byteBinding));
		assertEquals((byte) 127, BINDER.fromJson(number("127.0"), byteBinding));
		assertFailure(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.NUMBER_OUT_OF_RANGE,
				() -> BINDER.fromJson(number("128"), byteBinding));
		assertFailure(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.NUMBER_OUT_OF_RANGE,
				() -> BINDER.fromJson(number("-129"), byteBinding));
		assertFailure(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.NON_INTEGER_NUMBER,
				() -> BINDER.fromJson(number("1.5"), byteBinding));

		McpTypedJsonBinding<Integer> integerBinding = binding(int.class);
		assertEquals(1, BINDER.fromJson(number("1e0"), integerBinding));
		McpTypedJsonBinding<Long> longBinding = binding(long.class);
		assertEquals(Long.MAX_VALUE,
				BINDER.fromJson(number(Long.toString(Long.MAX_VALUE)), longBinding));

		McpTypedJsonBinding<BigInteger> bigIntegerBinding =
				binding(BigInteger.class);
		BigInteger huge = new BigInteger("9".repeat(200));
		assertEquals(huge, BINDER.fromJson(
				new McpJsonNumber(new BigDecimal(huge)), bigIntegerBinding));
		McpTypedJsonBinding<BigDecimal> decimalBinding = binding(BigDecimal.class);
		BigDecimal exponent = new BigDecimal("1.2300E+100");
		assertEquals(exponent,
				BINDER.fromJson(new McpJsonNumber(exponent), decimalBinding));

		McpTypedJsonBinding<Float> floatBinding = binding(float.class);
		McpTypedJsonBinding<Double> doubleBinding = binding(double.class);
		assertEquals(0.0f, BINDER.fromJson(number("1e-1000"), floatBinding));
		assertEquals(0.0d, BINDER.fromJson(number("1e-10000"), doubleBinding));
		assertEquals(number("0.1"), BINDER.toJson(0.1f, floatBinding));
		assertEquals(number("0.1"), BINDER.toJson(0.1d, doubleBinding));
		assertFailure(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.NUMBER_OUT_OF_RANGE,
				() -> BINDER.fromJson(number("1e10000"), doubleBinding));
		for (double nonFinite : List.of(Double.NaN, Double.NEGATIVE_INFINITY,
				Double.POSITIVE_INFINITY))
			assertFailure(McpTypedJsonBindingException.Operation.TO_JSON,
					McpTypedJsonBindingException.Reason.NON_FINITE_NUMBER,
					() -> BINDER.toJson(nonFinite, doubleBinding));
	}

	@Test
	void enumsUseExactNamesAndNeverToString() {
		McpTypedJsonBinding<Mode> binding = binding(Mode.class);
		assertEquals(Mode.SECOND,
				BINDER.fromJson(new McpJsonString("SECOND"), binding));
		assertEquals(new McpJsonString("SECOND"),
				BINDER.toJson(Mode.SECOND, binding));
		assertFalse(Mode.SECOND.toString().equals("SECOND"));
		assertFailure(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.ENUM_CONSTANT_MISMATCH,
				() -> BINDER.fromJson(new McpJsonString("second"), binding));

		McpTypedJsonBinding<EmptyMode> emptyBinding = binding(EmptyMode.class);
		assertFailure(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.ENUM_CONSTANT_MISMATCH,
				() -> BINDER.fromJson(new McpJsonString("ANY"), emptyBinding));
	}

	@Test
	void primitiveAndResolvedGenericArraysAndRecordsRoundTrip()
			throws ReflectiveOperationException {
		McpTypedJsonBinding<int[]> primitiveBinding = binding(int[].class);
		int[] primitives = BINDER.fromJson(array(number("1"), number("2")),
				primitiveBinding);
		assertArrayEquals(new int[] { 1, 2 }, primitives);
		assertEquals(array(number("1"), number("2")),
				BINDER.toJson(primitives, primitiveBinding));

		McpTypedJsonBinding<Box<String>> boxBinding = binding(
				fieldType("stringBox"));
		Box<String> box = BINDER.fromJson(object(
				"value", new McpJsonString("value"),
				"history", array(new McpJsonString("old"))), boxBinding);
		assertEquals(new Box<>("value", Optional.of(List.of("old"))), box);
		assertEquals(object("value", new McpJsonString("value"), "history",
				array(new McpJsonString("old"))), BINDER.toJson(box, boxBinding));

		McpTypedJsonBinding<Box<String>[]> arrayBinding = binding(
				fieldType("stringBoxes"));
		Box<String>[] boxes = BINDER.fromJson(array(object(
				"value", new McpJsonString("only"))), arrayBinding);
		assertEquals(1, boxes.length);
		assertEquals(new Box<>("only", Optional.empty()), boxes[0]);
	}

	@Test
	void nullUnknownMissingAndWrongRuntimeTypesFailClosed()
			throws ReflectiveOperationException {
		McpTypedJsonBinding<NullableRecord> binding =
				binding(NullableRecord.class);
		assertEquals(new NullableRecord("ok", Optional.empty()),
				BINDER.fromJson(object("required", new McpJsonString("ok")),
						binding));
		assertFailureAt(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.NULL_VALUE,
				"$/properties/optional", () -> BINDER.fromJson(object(
						"required", new McpJsonString("ok"), "optional",
						McpJsonNull.INSTANCE), binding));
		assertFailureAt(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.REQUIRED_PROPERTY_MISSING,
				"$/properties/required", () -> BINDER.fromJson(object(), binding));
		assertFailureAt(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.UNKNOWN_PROPERTY, "$",
				() -> BINDER.fromJson(object(
						"required", new McpJsonString("ok"),
						"notDeclared", McpJsonBoolean.TRUE), binding));
		assertFailure(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.NULL_VALUE,
				() -> BINDER.fromJson(McpJsonNull.INSTANCE, binding));
		assertFailureAt(McpTypedJsonBindingException.Operation.TO_JSON,
				McpTypedJsonBindingException.Reason.NULL_VALUE,
				"$/properties/required", () -> BINDER.toJson(
						new NullableRecord(null, Optional.empty()), binding));
		assertFailureAt(McpTypedJsonBindingException.Operation.TO_JSON,
				McpTypedJsonBindingException.Reason.NULL_VALUE,
				"$/properties/optional", () -> BINDER.toJson(
						new NullableRecord("ok", null), binding));

		McpTypedJsonBinding<List<String>> listBinding = binding(
				fieldType("strings"));
		assertFailureAt(McpTypedJsonBindingException.Operation.TO_JSON,
				McpTypedJsonBindingException.Reason.NULL_VALUE, "$/items",
				() -> BINDER.toJson(Arrays.asList("ok", null), listBinding));
		assertFailure(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.JSON_TYPE_MISMATCH,
				() -> BINDER.fromJson(new McpJsonString("not-an-array"),
						listBinding));

		McpTypedJsonBinding<Map<String, Integer>> mapBinding = binding(
				fieldType("integers"));
		Map<String, Integer> nullValue = new LinkedHashMap<>();
		nullValue.put("key", null);
		assertFailureAt(McpTypedJsonBindingException.Operation.TO_JSON,
				McpTypedJsonBindingException.Reason.NULL_VALUE,
				"$/additionalProperties",
				() -> BINDER.toJson(nullValue, mapBinding));
		assertWrongMapKeyRejected(mapBinding);
	}

	@Test
	void applicationFailuresAreSanitizedAndRetainNoCauseOrValue() {
		McpTypedJsonBinding<RejectingRecord> rejecting =
				binding(RejectingRecord.class);
		McpTypedJsonBindingException construction = assertThrows(
				McpTypedJsonBindingException.class,
				() -> BINDER.fromJson(object("secret",
						new McpJsonString("sensitive-constructor-value")), rejecting));
		assertEquals(McpTypedJsonBindingException.Reason.RECORD_CONSTRUCTION_FAILED,
				construction.reason());
		assertFalse(construction.getMessage().contains("sensitive"));
		assertNull(construction.getCause());

		McpTypedJsonBinding<ThrowingAccessorRecord> throwing =
				binding(ThrowingAccessorRecord.class);
		McpTypedJsonBindingException accessor = assertThrows(
				McpTypedJsonBindingException.class,
				() -> BINDER.toJson(new ThrowingAccessorRecord(
						"sensitive-accessor-value"), throwing));
		assertEquals(McpTypedJsonBindingException.Reason.RECORD_ACCESSOR_FAILED,
				accessor.reason());
		assertEquals("$/properties/secret", accessor.path().toString());
		assertFalse(accessor.getMessage().contains("sensitive"));
		assertNull(accessor.getCause());

		McpTypedJsonBinding<List<String>> listBinding;
		try {
			listBinding = binding(fieldType("strings"));
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
		List<String> hostile = new AbstractList<>() {
			@Override
			public String get(int index) {
				throw new IllegalStateException("sensitive-container-value");
			}

			@Override
			public int size() {
				throw new IllegalStateException("sensitive-container-value");
			}
		};
		McpTypedJsonBindingException container = assertThrows(
				McpTypedJsonBindingException.class,
				() -> BINDER.toJson(hostile, listBinding));
		assertEquals(McpTypedJsonBindingException.Reason.CONTAINER_ACCESS_FAILED,
				container.reason());
		assertFalse(container.getMessage().contains("sensitive"));
		assertNull(container.getCause());
	}

	@Test
	void bindingCompilerFailsSanitarilyWhenTypeAndShapeDisagree() {
		McpTypedJsonBindingException exception = assertThrows(
				McpTypedJsonBindingException.class,
				() -> BINDING_COMPILER.compile(String.class,
						new McpTypedSchemaShape.Scalar(McpTypedSchemaScalar.INT)));
		assertEquals(McpTypedJsonBindingException.Operation.COMPILE,
				exception.operation());
		assertEquals(McpTypedJsonBindingException.Reason.SHAPE_MISMATCH,
				exception.reason());
		assertEquals("$", exception.path().toString());
		assertNull(exception.getCause());
	}

	@Test
	void binderAndDerivedSchemaAgreeOnScalarAndClosedRecordCases() {
		McpTypedJsonBinding<Byte> byteBinding = binding(Byte.class);
		for (McpJsonValue candidate : List.of(number("-129"), number("-128"),
				number("0"), number("127.0"), number("128"), number("1.5"),
				new McpJsonString("1"), McpJsonNull.INSTANCE))
			assertEquals(schemaAccepts(byteBinding.shape(), candidate),
					bindingAccepts(byteBinding, candidate));

		McpTypedJsonBinding<ContractRecord> recordBinding =
				binding(ContractRecord.class);
		for (McpJsonValue candidate : List.of(
				object("required", number("1")),
				object("required", number("1"), "optional",
						new McpJsonString("present")),
				object(),
				object("required", number("1.5")),
				object("required", number("1"), "optional",
						McpJsonNull.INSTANCE),
				object("required", number("1"), "extra",
						McpJsonBoolean.TRUE)))
			assertEquals(schemaAccepts(recordBinding.shape(), candidate),
					bindingAccepts(recordBinding, candidate));
	}

	@Test
	void bindingLimitsHaveExactNodeDepthAndContainerBoundaries()
			throws ReflectiveOperationException {
		McpTypedJsonBinding<List<String>> listBinding = binding(
				fieldType("strings"));
		McpJsonArray twoValues = array(new McpJsonString("a"),
				new McpJsonString("b"));
		McpTypedJsonBinder exact = new McpTypedJsonBinder(
				new McpTypedJsonBindingLimits(3, 2, 2));
		assertEquals(List.of("a", "b"),
				exact.fromJson(twoValues, listBinding));
		assertEquals(twoValues, exact.toJson(List.of("a", "b"), listBinding));

		McpTypedJsonBinder nodeOneUnder = new McpTypedJsonBinder(
				new McpTypedJsonBindingLimits(2, 2, 2));
		assertLimit(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Limit.NODE_COUNT, "$",
				() -> nodeOneUnder.fromJson(twoValues, listBinding));
		McpTypedJsonBinder containerOneUnder = new McpTypedJsonBinder(
				new McpTypedJsonBindingLimits(10, 2, 1));
		assertLimit(McpTypedJsonBindingException.Operation.TO_JSON,
				McpTypedJsonBindingException.Limit.CONTAINER_ENTRY_COUNT, "$",
				() -> containerOneUnder.toJson(List.of("a", "b"),
						listBinding));

		McpTypedJsonBinding<List<List<String>>> nestedBinding = binding(
				fieldType("nestedStrings"));
		List<List<String>> nested = List.of(List.of("value"));
		McpJsonValue nestedJson = array(array(new McpJsonString("value")));
		McpTypedJsonBinder exactDepth = new McpTypedJsonBinder(
				new McpTypedJsonBindingLimits(3, 3, 1));
		assertEquals(nested, exactDepth.fromJson(nestedJson, nestedBinding));
		McpTypedJsonBinder depthOneUnder = new McpTypedJsonBinder(
				new McpTypedJsonBindingLimits(3, 2, 1));
		assertLimit(McpTypedJsonBindingException.Operation.TO_JSON,
				McpTypedJsonBindingException.Limit.NESTING_DEPTH,
				"$/items/items",
				() -> depthOneUnder.toJson(nested, nestedBinding));

		assertThrows(IllegalArgumentException.class,
				() -> new McpTypedJsonBindingLimits(0, 1, 1));
		assertThrows(IllegalArgumentException.class,
				() -> new McpTypedJsonBindingLimits(1, 0, 1));
		assertThrows(IllegalArgumentException.class,
				() -> new McpTypedJsonBindingLimits(1, 1, 0));
		assertThrows(IllegalArgumentException.class,
				() -> new McpTypedJsonBindingLimits(1_000_001, 1, 1));
		assertThrows(IllegalArgumentException.class,
				() -> new McpTypedJsonBindingLimits(1, 257, 1));
		assertThrows(IllegalArgumentException.class,
				() -> new McpTypedJsonBindingLimits(1, 1, 1_000_001));
	}

	@Test
	void cyclesMutationAndImpossibleContainerSizesFailBeforeUnboundedWork()
			throws ReflectiveOperationException {
		McpTypedJsonBinding<List<List<String>>> nestedBinding = binding(
				fieldType("nestedStrings"));
		assertCyclicListRejected(nestedBinding);

		McpTypedJsonBinding<List<String>> listBinding = binding(
				fieldType("strings"));
		List<String> truncated = new AbstractList<>() {
			@Override
			public String get(int index) {
				throw new AssertionError("get must not be used");
			}

			@Override
			public int size() {
				return 1;
			}

			@Override
			public Iterator<String> iterator() {
				return Collections.emptyIterator();
			}
		};
		assertFailureAt(McpTypedJsonBindingException.Operation.TO_JSON,
				McpTypedJsonBindingException.Reason.CONTAINER_MUTATED, "$",
				() -> BINDER.toJson(truncated, listBinding));

		List<String> impossible = new AbstractList<>() {
			@Override
			public String get(int index) {
				throw new AssertionError("get must not be used");
			}

			@Override
			public int size() {
				return Integer.MAX_VALUE;
			}
		};
		assertLimit(McpTypedJsonBindingException.Operation.TO_JSON,
				McpTypedJsonBindingException.Limit.CONTAINER_ENTRY_COUNT, "$",
				() -> BINDER.toJson(impossible, listBinding));

		McpTypedJsonBinder oneEntry = new McpTypedJsonBinder(
				new McpTypedJsonBindingLimits(10, 2, 1));
		assertLimit(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Limit.CONTAINER_ENTRY_COUNT, "$",
				() -> oneEntry.fromJson(array(new McpJsonString("a"),
						new McpJsonString("b")), listBinding));
	}

	@Test
	void repeatedEnumsReuseApprovedShapeAndGenericSubstitutionIsBounded()
			throws ReflectiveOperationException {
		McpTypedJsonBinding<RepeatedModes> repeatedBinding =
				binding(RepeatedModes.class);
		RepeatedModes repeated = new RepeatedModes(Mode.FIRST, Mode.SECOND,
				List.of(Mode.SECOND, Mode.FIRST));
		McpJsonValue repeatedJson = BINDER.toJson(repeated, repeatedBinding);
		assertEquals(repeated, BINDER.fromJson(repeatedJson, repeatedBinding));

		Type deepType = fieldType("deepGeneric");
		McpTypedSchemaShape deepShape = RESOLVER.resolveSchema(deepType);
		new McpRuntimeTypedJsonBindingCompiler(
				bindingCompilationLimits(3, 3, 10)).compile(deepType, deepShape);
		McpTypedJsonBindingException nodeLimit = assertThrows(
				McpTypedJsonBindingException.class,
				() -> new McpRuntimeTypedJsonBindingCompiler(
						bindingCompilationLimits(2, 3, 10))
						.compile(deepType, deepShape));
		assertEquals(McpTypedJsonBindingException.Operation.COMPILE,
				nodeLimit.operation());
		assertEquals(Optional.of(McpTypedJsonBindingException.Limit.NODE_COUNT),
				nodeLimit.limit());
		assertEquals("$/properties/value", nodeLimit.path().toString());

		McpTypedJsonBindingException depthLimit = assertThrows(
				McpTypedJsonBindingException.class,
				() -> new McpRuntimeTypedJsonBindingCompiler(
						bindingCompilationLimits(3, 2, 10))
						.compile(deepType, deepShape));
		assertEquals(Optional.of(
				McpTypedJsonBindingException.Limit.NESTING_DEPTH),
				depthLimit.limit());
		assertEquals("$/properties/value", depthLimit.path().toString());
	}

	private static <T> McpTypedJsonBinding<T> binding(Type type) {
		McpTypedSchemaShape shape = RESOLVER.resolveSchema(type);
		return BINDING_COMPILER.compile(type, shape);
	}

	private static boolean bindingAccepts(McpTypedJsonBinding<?> binding,
			McpJsonValue value) {
		try {
			BINDER.fromJson(value, binding);
			return true;
		} catch (McpTypedJsonBindingException exception) {
			return false;
		}
	}

	private static boolean schemaAccepts(McpTypedSchemaShape shape,
			McpJsonValue value) {
		McpJsonObject schema = new McpTypedSchemaRenderer(LIMITS).render(shape);
		McpToolSchemaProfileProgram program =
				new McpToolSchemaProfileCompiler(LIMITS).compile(schema);
		return new McpToolSchemaProfileEvaluator().evaluate(program, value,
				McpSchemaEvaluationLimits.productionDefaults())
				instanceof McpSchemaValidationOutcome.Valid;
	}

	private static void assertValid(McpTypedSchemaShape shape,
			McpJsonValue value) {
		assertTrue(schemaAccepts(shape, value));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void assertWrongMapKeyRejected(
			McpTypedJsonBinding<Map<String, Integer>> binding) {
		Map malformed = new LinkedHashMap();
		malformed.put(1, 1);
		assertFailure(McpTypedJsonBindingException.Operation.TO_JSON,
				McpTypedJsonBindingException.Reason.JAVA_TYPE_MISMATCH,
				() -> BINDER.toJson(malformed, (McpTypedJsonBinding) binding));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void assertCyclicListRejected(
			McpTypedJsonBinding<List<List<String>>> binding) {
		List cyclic = new ArrayList();
		cyclic.add(cyclic);
		McpTypedJsonBindingException exception = assertThrows(
				McpTypedJsonBindingException.class,
				() -> BINDER.toJson(cyclic, (McpTypedJsonBinding) binding));
		assertEquals(McpTypedJsonBindingException.Operation.TO_JSON,
				exception.operation());
		assertEquals(McpTypedJsonBindingException.Reason.CYCLIC_VALUE,
				exception.reason());
		assertEquals("$/items", exception.path().toString());
		assertNull(exception.getCause());
	}

	private static void assertFailure(
			McpTypedJsonBindingException.Operation operation,
			McpTypedJsonBindingException.Reason reason, Runnable invocation) {
		McpTypedJsonBindingException exception = assertThrows(
				McpTypedJsonBindingException.class, invocation::run);
		assertEquals(operation, exception.operation());
		assertEquals(reason, exception.reason());
	}

	private static void assertFailureAt(
			McpTypedJsonBindingException.Operation operation,
			McpTypedJsonBindingException.Reason reason, String path,
			Runnable invocation) {
		McpTypedJsonBindingException exception = assertThrows(
				McpTypedJsonBindingException.class, invocation::run);
		assertEquals(operation, exception.operation());
		assertEquals(reason, exception.reason());
		assertEquals(path, exception.path().toString());
	}

	private static void assertLimit(
			McpTypedJsonBindingException.Operation operation,
			McpTypedJsonBindingException.Limit limit, String path,
			Runnable invocation) {
		McpTypedJsonBindingException exception = assertThrows(
				McpTypedJsonBindingException.class, invocation::run);
		assertEquals(operation, exception.operation());
		assertEquals(McpTypedJsonBindingException.Reason.LIMIT_EXCEEDED,
				exception.reason());
		assertEquals(Optional.of(limit), exception.limit());
		assertEquals(path, exception.path().toString());
		assertNull(exception.getCause());
	}

	private static McpSchemaCompilationLimits bindingCompilationLimits(
			int nodes, int depth, int entries) {
		return new McpSchemaCompilationLimits(nodes, depth, 100, 10, 10,
				100, 100, 10, entries, 100, 100);
	}

	private static McpJsonNumber number(String value) {
		return new McpJsonNumber(new BigDecimal(value));
	}

	private static McpJsonArray array(McpJsonValue... values) {
		return new McpJsonArray(List.of(values));
	}

	private static McpJsonObject object(Object... namesAndValues) {
		if (namesAndValues.length % 2 != 0)
			throw new IllegalArgumentException("Expected name/value pairs.");
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		for (int index = 0; index < namesAndValues.length; index += 2)
			members.put((String) namesAndValues[index],
					(McpJsonValue) namesAndValues[index + 1]);
		return new McpJsonObject(members);
	}

	private static Type fieldType(String name)
			throws ReflectiveOperationException {
		return TypeFixtures.class.getDeclaredField(name).getGenericType();
	}

	private record Payload(String exact, int count, Optional<String> note,
			List<String> labels, Map<String, Integer> scores, Mode mode,
			int[] samples) {
	}

	private record NullableRecord(String required, Optional<String> optional) {
	}

	private record ContractRecord(int required, Optional<String> optional) {
	}

	private record RepeatedModes(Mode first, Mode second, List<Mode> history) {
	}

	private record Box<T>(T value, Optional<List<T>> history) {
	}

	private record DeepGeneric<T>(List<List<T>> value) {
	}

	private record RejectingRecord(String secret) {
		private RejectingRecord {
			throw new IllegalArgumentException("do not expose " + secret);
		}
	}

	private record ThrowingAccessorRecord(String secret) {
		@Override
		public String secret() {
			throw new IllegalStateException("do not expose " + secret);
		}
	}

	private enum Mode {
		FIRST,
		SECOND;

		@Override
		public String toString() {
			return "not-the-wire-name";
		}
	}

	private enum EmptyMode {
	}

	private static final class TypeFixtures {
		private List<String> strings;
		private List<List<String>> nestedStrings;
		private Map<String, Integer> integers;
		private Box<String> stringBox;
		private Box<String>[] stringBoxes;
		private DeepGeneric<String> deepGeneric;
	}
}

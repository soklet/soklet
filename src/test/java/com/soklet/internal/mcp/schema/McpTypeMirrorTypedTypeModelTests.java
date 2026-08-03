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

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class McpTypeMirrorTypedTypeModelTests {
	private static final McpSchemaCompilationLimits LIMITS =
			McpSchemaCompilationLimits.productionDefaults();

	@Test
	void derivesExactScalarTable() {
		Inspection inspection = inspect(standardFixture());
		Map<String, McpTypedSchemaScalar> expected = new LinkedHashMap<>();
		expected.put("primitiveBoolean", McpTypedSchemaScalar.BOOLEAN);
		expected.put("boxedBoolean", McpTypedSchemaScalar.BOOLEAN);
		expected.put("primitiveByte", McpTypedSchemaScalar.BYTE);
		expected.put("boxedByte", McpTypedSchemaScalar.BYTE);
		expected.put("primitiveShort", McpTypedSchemaScalar.SHORT);
		expected.put("boxedShort", McpTypedSchemaScalar.SHORT);
		expected.put("primitiveInt", McpTypedSchemaScalar.INT);
		expected.put("boxedInt", McpTypedSchemaScalar.INT);
		expected.put("primitiveLong", McpTypedSchemaScalar.LONG);
		expected.put("boxedLong", McpTypedSchemaScalar.LONG);
		expected.put("bigInteger", McpTypedSchemaScalar.BIG_INTEGER);
		expected.put("primitiveFloat", McpTypedSchemaScalar.FLOAT);
		expected.put("boxedFloat", McpTypedSchemaScalar.FLOAT);
		expected.put("primitiveDouble", McpTypedSchemaScalar.DOUBLE);
		expected.put("boxedDouble", McpTypedSchemaScalar.DOUBLE);
		expected.put("bigDecimal", McpTypedSchemaScalar.BIG_DECIMAL);
		expected.put("string", McpTypedSchemaScalar.STRING);

		for (Map.Entry<String, McpTypedSchemaScalar> entry : expected.entrySet()) {
			McpTypedSchemaShape.Scalar scalar = assertInstanceOf(
					McpTypedSchemaShape.Scalar.class,
					inspection.shapes.get(entry.getKey()), entry.getKey());
			assertEquals(entry.getValue(), scalar.scalar(), entry.getKey());
		}
	}

	@Test
	void preservesEnumOrderAndSubstitutesGenericRecordComponents() {
		Inspection inspection = inspect(standardFixture());
		McpTypedSchemaShape string = scalar(McpTypedSchemaScalar.STRING);
		McpTypedSchemaShape expectedBox = new McpTypedSchemaShape.RecordValue(
				List.of(
						property("value", string, true),
						property("values",
								new McpTypedSchemaShape.ArrayValue(string), true),
						property("maybe", string, false)));

		assertEquals(expectedBox, inspection.shapes.get("box"));
		assertEquals(new McpTypedSchemaShape.ArrayValue(expectedBox),
				inspection.shapes.get("boxArray"));
		assertEquals(new McpTypedSchemaShape.Enumeration(
				List.of("SECOND", "FIRST", "THIRD")),
				inspection.shapes.get("status"));
		assertEquals("fixtures.Box", inspection.declarationIdentities.get("box"));
		assertEquals("fixtures.Status",
				inspection.declarationIdentities.get("status"));
	}

	@Test
	void derivesOnlyExactSupportedCollectionShapes() {
		Inspection inspection = inspect(standardFixture());
		McpTypedSchemaShape string = scalar(McpTypedSchemaScalar.STRING);
		McpTypedSchemaShape integer = scalar(McpTypedSchemaScalar.INT);
		McpTypedSchemaShape status = new McpTypedSchemaShape.Enumeration(
				List.of("SECOND", "FIRST", "THIRD"));

		assertEquals(new McpTypedSchemaShape.ArrayValue(string),
				inspection.shapes.get("strings"));
		assertEquals(new McpTypedSchemaShape.ArrayValue(string),
				inspection.shapes.get("stringList"));
		assertEquals(new McpTypedSchemaShape.ArrayValue(
				new McpTypedSchemaShape.ArrayValue(string)),
				inspection.shapes.get("genericListArray"));
		assertEquals(new McpTypedSchemaShape.MapValue(integer),
				inspection.shapes.get("integerMap"));
		assertEquals(new McpTypedSchemaShape.MapValue(status),
				inspection.shapes.get("statusMap"));
	}

	@Test
	void rejectsUnsupportedFormsWithStableReasons() {
		Inspection inspection = inspect(standardFixture());
		Map<String, McpTypedSchemaException.Reason> expected = Map.ofEntries(
				Map.entry("rawList", McpTypedSchemaException.Reason.RAW_GENERIC),
				Map.entry("rawBox", McpTypedSchemaException.Reason.RAW_GENERIC),
				Map.entry("wildcardList", McpTypedSchemaException.Reason.WILDCARD),
				Map.entry("variable", McpTypedSchemaException.Reason
						.UNRESOLVED_TYPE_VARIABLE),
				Map.entry("genericArray", McpTypedSchemaException.Reason
						.UNRESOLVED_GENERIC_ARRAY_COMPONENT),
				Map.entry("object", McpTypedSchemaException.Reason.OBJECT_TYPE),
				Map.entry("characters", McpTypedSchemaException.Reason
						.CHAR_SEQUENCE_TYPE),
				Map.entry("framework", McpTypedSchemaException.Reason.FRAMEWORK_TYPE),
				Map.entry("uuid", McpTypedSchemaException.Reason.UNSUPPORTED_TYPE),
				Map.entry("hashMap", McpTypedSchemaException.Reason.UNSUPPORTED_TYPE),
				Map.entry("primitiveChar", McpTypedSchemaException.Reason
						.UNSUPPORTED_TYPE),
				Map.entry("boxedChar", McpTypedSchemaException.Reason
						.UNSUPPORTED_TYPE),
				Map.entry("optionalRoot", McpTypedSchemaException.Reason
						.OPTIONAL_OUTSIDE_PROPERTY),
				Map.entry("nestedOptional", McpTypedSchemaException.Reason
						.OPTIONAL_OUTSIDE_PROPERTY),
				Map.entry("badMap", McpTypedSchemaException.Reason.MAP_KEY_NOT_STRING));

		for (Map.Entry<String, McpTypedSchemaException.Reason> entry
				: expected.entrySet())
			assertEquals(entry.getValue(),
					inspection.failures.get(entry.getKey()).reason(), entry.getKey());
	}

	@Test
	void preflightsEnumNamesAndConstantCountsAgainstProductionLimits() {
		String constants = IntStream.rangeClosed(0,
				LIMITS.maximumCollectionEntryCount())
				.mapToObj(index -> "C" + index)
				.collect(Collectors.joining(","));
		String longName = "A".repeat(
				LIMITS.maximumNameLengthInCharacters() + 1);
		Inspection inspection = inspect("""
				package fixtures;
				class Fixture {
				  TooWide tooWide;
				  TooLong tooLong;
				  WideWrapper wideWrapper;
				  LongRecord longRecord;
				}
				enum TooWide { %s }
				enum TooLong { %s }
				record WideWrapper(TooWide values) {}
				record LongRecord(String %s) {}
				""".formatted(constants, longName, longName));

		assertEquals(McpTypedSchemaException.Reason.LIMIT_EXCEEDED,
				inspection.failures.get("tooWide").reason());
		assertEquals(McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
				inspection.failures.get("tooWide").limit().orElseThrow());
		assertEquals(McpTypedSchemaException.Reason.LIMIT_EXCEEDED,
				inspection.failures.get("tooLong").reason());
		assertEquals(McpSchemaCompilationException.Limit.NAME_LENGTH,
				inspection.failures.get("tooLong").limit().orElseThrow());
		assertEquals(McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
				inspection.failures.get("wideWrapper").limit().orElseThrow());
		assertEquals("$/properties/values",
				inspection.failures.get("wideWrapper").path().toString());
		assertEquals(McpSchemaCompilationException.Limit.NAME_LENGTH,
				inspection.failures.get("longRecord").limit().orElseThrow());
	}

	private static Inspection inspect(String source) {
		Inspection inspection = new Inspection();
		JavaFileObject fixture = JavaFileObjects.forSourceString(
				"fixtures.Fixture", source);
		Compilation compilation = Compiler.javac()
				.withOptions("--release", "17")
				.withProcessors(inspection)
				.compile(fixture);
		assertThat(compilation).succeeded();
		return inspection;
	}

	private static String standardFixture() {
		return """
				package fixtures;

				import com.soklet.internal.mcp.protocol.McpJsonValue;
				import java.math.BigDecimal;
				import java.math.BigInteger;
				import java.util.HashMap;
				import java.util.List;
				import java.util.Map;
				import java.util.Optional;
				import java.util.UUID;

				class Fixture<T> {
				  boolean primitiveBoolean;
				  Boolean boxedBoolean;
				  byte primitiveByte;
				  Byte boxedByte;
				  short primitiveShort;
				  Short boxedShort;
				  int primitiveInt;
				  Integer boxedInt;
				  long primitiveLong;
				  Long boxedLong;
				  BigInteger bigInteger;
				  float primitiveFloat;
				  Float boxedFloat;
				  double primitiveDouble;
				  Double boxedDouble;
				  BigDecimal bigDecimal;
				  String string;
				  Status status;
				  String[] strings;
				  List<String> stringList;
				  List<String>[] genericListArray;
				  Map<String, Integer> integerMap;
				  Map<String, Status> statusMap;
				  Box<String> box;
				  Box<String>[] boxArray;
				  List rawList;
				  Box rawBox;
				  List<?> wildcardList;
				  T variable;
				  T[] genericArray;
				  Object object;
				  StringBuilder characters;
				  McpJsonValue framework;
				  UUID uuid;
				  HashMap<String, String> hashMap;
				  char primitiveChar;
				  Character boxedChar;
				  Optional<String> optionalRoot;
				  List<Optional<String>> nestedOptional;
				  Map<Integer, String> badMap;
				}

				record Box<T>(T value, List<T> values, Optional<T> maybe) {}
				enum Status { SECOND, FIRST, THIRD }
				""";
	}

	private static McpTypedSchemaShape scalar(McpTypedSchemaScalar scalar) {
		return new McpTypedSchemaShape.Scalar(scalar);
	}

	private static McpTypedSchemaShape.Property property(String name,
			McpTypedSchemaShape shape, boolean required) {
		return McpTypedSchemaShape.Property.fromNameAndShape(name, shape,
				required);
	}

	private static final class Inspection extends AbstractProcessor {
		private final Map<String, McpTypedSchemaShape> shapes =
				new LinkedHashMap<>();
		private final Map<String, McpTypedSchemaException> failures =
				new LinkedHashMap<>();
		private final Map<String, String> declarationIdentities =
				new LinkedHashMap<>();
		private boolean complete;

		@Override
		public Set<String> getSupportedAnnotationTypes() {
			return Set.of("*");
		}

		@Override
		public SourceVersion getSupportedSourceVersion() {
			return SourceVersion.RELEASE_17;
		}

		@Override
		public boolean process(Set<? extends TypeElement> annotations,
				RoundEnvironment roundEnvironment) {
			if (complete || roundEnvironment.processingOver())
				return false;
			TypeElement fixture = processingEnv.getElementUtils()
					.getTypeElement("fixtures.Fixture");
			if (fixture == null)
				return false;

			McpTypeMirrorTypedTypeModel model = new McpTypeMirrorTypedTypeModel(
					processingEnv.getTypeUtils(), processingEnv.getElementUtils(),
					LIMITS);
			McpTypedSchemaResolver<TypeMirror> resolver =
					new McpTypedSchemaResolver<>(model, LIMITS);
			for (Element element : fixture.getEnclosedElements()) {
				if (element.getKind() != ElementKind.FIELD)
					continue;
				String name = element.getSimpleName().toString();
				try {
					shapes.put(name, resolver.resolveSchema(element.asType()));
					McpTypedTypeDescriptor<TypeMirror> descriptor =
							model.describe(element.asType());
					if (descriptor instanceof McpTypedTypeDescriptor.RecordValue<TypeMirror> record)
						declarationIdentities.put(name,
								record.declarationIdentity());
					if (descriptor instanceof McpTypedTypeDescriptor.Enumeration<TypeMirror> enumeration)
						declarationIdentities.put(name,
								enumeration.declarationIdentity());
				} catch (McpTypedSchemaException exception) {
					failures.put(name, exception);
				}
			}
			complete = true;
			return false;
		}
	}
}

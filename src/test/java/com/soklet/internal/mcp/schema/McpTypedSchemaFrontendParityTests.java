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
import com.soklet.annotation.McpToolArgument;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpTypedSchemaFrontendParityTests {
	private static final McpSchemaCompilationLimits LIMITS =
			McpSchemaCompilationLimits.productionDefaults();
	private static final McpTypedSchemaResolver<Type> RUNTIME_RESOLVER =
			new McpTypedSchemaResolver<>(new McpRuntimeTypedTypeModel(LIMITS,
					Set.of(ParityFrameworkRoot.class.getName())),
					LIMITS);
	private static final McpTypedSchemaRenderer RENDERER =
			new McpTypedSchemaRenderer(LIMITS);
	private static final McpToolSchemaProfileCompiler PROFILE_COMPILER =
			new McpToolSchemaProfileCompiler(LIMITS);
	private static final McpSchemaUseValidator USE_VALIDATOR =
			new McpSchemaUseValidator();
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(1_000_000, 128, 1_000_000, 1_000_000,
					1_024, 10_000, 100_000, 4_000_000));
	private static final List<String> SUPPORTED_FIELDS = List.of(
			"primitiveBoolean", "boxedBoolean", "primitiveByte", "boxedByte",
			"primitiveShort", "boxedShort", "primitiveInt", "boxedInt",
			"primitiveLong", "boxedLong", "bigInteger", "primitiveFloat",
			"boxedFloat", "primitiveDouble", "boxedDouble", "bigDecimal",
			"string", "status", "primitiveInts", "strings", "stringList",
			"longsByName", "box", "nestedBox", "input", "annotated");
	private static final Set<String> INPUT_FIELDS = Set.of("longsByName",
			"input");
	private static final Map<String, ExpectedFailure> EXPECTED_FAILURES =
			Map.ofEntries(
					Map.entry("rawList", failure(
							McpTypedSchemaException.Reason.RAW_GENERIC, "$")),
					Map.entry("wildcardList", failure(
							McpTypedSchemaException.Reason.WILDCARD, "$/items")),
					Map.entry("badMap", failure(
							McpTypedSchemaException.Reason.MAP_KEY_NOT_STRING, "$")),
					Map.entry("optionalRoot", failure(
							McpTypedSchemaException.Reason.OPTIONAL_OUTSIDE_PROPERTY,
							"$")),
					Map.entry("nestedOptional", failure(
							McpTypedSchemaException.Reason.OPTIONAL_OUTSIDE_PROPERTY,
							"$/items")),
					Map.entry("variable", failure(
							McpTypedSchemaException.Reason.UNRESOLVED_TYPE_VARIABLE,
							"$")),
					Map.entry("genericArray", failure(
							McpTypedSchemaException.Reason
									.UNRESOLVED_GENERIC_ARRAY_COMPONENT,
							"$")),
					Map.entry("object", failure(
							McpTypedSchemaException.Reason.OBJECT_TYPE, "$")),
					Map.entry("characters", failure(
							McpTypedSchemaException.Reason.CHAR_SEQUENCE_TYPE, "$")),
					Map.entry("framework", failure(
							McpTypedSchemaException.Reason.FRAMEWORK_TYPE, "$")),
					Map.entry("frameworkSubtype", failure(
							McpTypedSchemaException.Reason.FRAMEWORK_TYPE, "$")),
					Map.entry("expanding", failure(
							McpTypedSchemaException.Reason.RECURSIVE_TYPE,
							"$/properties/next")),
					Map.entry("phantomInvalid", failure(
							McpTypedSchemaException.Reason.OBJECT_TYPE,
							"$/genericArguments/0")),
					Map.entry("unsupported", failure(
							McpTypedSchemaException.Reason.UNSUPPORTED_TYPE, "$")));

	@Test
	void runtimeAndTypeMirrorFrontendsProduceIdenticalSchemas()
			throws ReflectiveOperationException {
		Inspection inspection = inspect();

		for (String fieldName : SUPPORTED_FIELDS) {
			McpTypedSchemaShape runtimeShape = RUNTIME_RESOLVER.resolveSchema(
					runtimeFieldType(fieldName));
			McpTypedSchemaShape mirrorShape = inspection.shapes.get(fieldName);
			assertNotNull(mirrorShape, fieldName);
			assertEquals(runtimeShape, mirrorShape, fieldName);

			byte[] runtimeJson = JSON_CODEC.toUtf8Bytes(
					RENDERER.render(runtimeShape));
			byte[] mirrorJson = JSON_CODEC.toUtf8Bytes(
					RENDERER.render(mirrorShape));
			assertArrayEquals(runtimeJson, mirrorJson, fieldName);
			compileAndValidate(runtimeJson, INPUT_FIELDS.contains(fieldName));
			compileAndValidate(mirrorJson, INPUT_FIELDS.contains(fieldName));
		}
	}

	@Test
	void runtimeAndTypeMirrorFrontendsRejectWithIdenticalReasonsAndPaths()
			throws ReflectiveOperationException {
		Inspection inspection = inspect();

		for (Map.Entry<String, ExpectedFailure> entry
				: EXPECTED_FAILURES.entrySet()) {
			String fieldName = entry.getKey();
			McpTypedSchemaException runtimeFailure = assertThrows(
					McpTypedSchemaException.class,
					() -> RUNTIME_RESOLVER.resolveSchema(
							runtimeFieldType(fieldName)), fieldName);
			McpTypedSchemaException mirrorFailure =
					inspection.failures.get(fieldName);
			assertNotNull(mirrorFailure, fieldName);

			assertEquals(entry.getValue().reason(), runtimeFailure.reason(),
					fieldName);
			assertEquals(entry.getValue().path(),
					runtimeFailure.path().toString(), fieldName);
			assertEquals(runtimeFailure.reason(), mirrorFailure.reason(),
					fieldName);
			assertEquals(runtimeFailure.path().toString(),
					mirrorFailure.path().toString(), fieldName);
		}
	}

	@Test
	void recordComponentMetadataUsesPublishedNamesAndOmitsBlankText() {
		Inspection inspection = inspect();
		McpTypedSchemaShape.RecordValue record = assertInstanceOf(
				McpTypedSchemaShape.RecordValue.class,
				inspection.shapes.get("annotated"));

		assertEquals("externalName", record.properties().get(0).name());
		assertEquals(Optional.of(" External title "),
				record.properties().get(0).title());
		assertEquals(Optional.of("External description"),
				record.properties().get(0).description());
		assertEquals("ordinary", record.properties().get(1).name());
		assertEquals(Optional.empty(), record.properties().get(1).title());
		assertEquals(Optional.empty(),
				record.properties().get(1).description());
	}

	private static void compileAndValidate(byte[] json, boolean toolInput) {
		McpJsonObject document = assertInstanceOf(McpJsonObject.class,
				JSON_CODEC.parse(json));
		McpToolSchemaProfileProgram program = PROFILE_COMPILER.compile(document);
		if (toolInput)
			USE_VALIDATOR.validateToolInput(program);
		else
			USE_VALIDATOR.validateToolOutput(program);
		assertEquals(document, program.document());
	}

	private static Type runtimeFieldType(String fieldName)
			throws ReflectiveOperationException {
		return RuntimeFixture.class.getDeclaredField(fieldName).getGenericType();
	}

	private static Inspection inspect() {
		Inspection inspection = new Inspection();
		JavaFileObject fixture = JavaFileObjects.forSourceString(
				"parity.Fixture", compileTimeFixture());
		Compilation compilation = Compiler.javac()
				.withOptions("--release", "17")
				.withProcessors(inspection)
				.compile(fixture);
		assertThat(compilation).succeeded();
		return inspection;
	}

	private static String compileTimeFixture() {
		return """
				package parity;

				import com.soklet.internal.mcp.protocol.McpJsonValue;
				import com.soklet.annotation.McpToolArgument;
				import java.math.BigDecimal;
				import java.math.BigInteger;
				import java.util.List;
				import java.util.Map;
				import java.util.Optional;
				import java.util.UUID;

				@SuppressWarnings("rawtypes")
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
				  ParityStatus status;
				  int[] primitiveInts;
				  String[] strings;
				  List<String> stringList;
				  Map<String, Long> longsByName;
				  ParityBox<String> box;
				  ParityBox<ParityBox<String>> nestedBox;
				  ParityInput input;
				  ParityAnnotated annotated;
				  List rawList;
				  List<?> wildcardList;
				  Map<Integer, String> badMap;
				  Optional<String> optionalRoot;
				  List<Optional<String>> nestedOptional;
				  T variable;
				  T[] genericArray;
				  Object object;
				  StringBuilder characters;
				  McpJsonValue framework;
				  FrameworkSubtype frameworkSubtype;
				  Expanding<String> expanding;
				  Phantom<Object> phantomInvalid;
				  UUID unsupported;
				}

				record ParityBox<T>(T value, List<T> values, Optional<T> maybe) {}
				record ParityInput(boolean enabled, int count, ParityStatus status,
				    String[] names, List<Long> totals,
				    Map<String, BigDecimal> prices, ParityBox<String> box,
				    Optional<Double> ratio) {}
				record ParityAnnotated(
				    @McpToolArgument(name = "externalName",
				        title = " External title ",
				        description = "External description") String internalName,
				    @McpToolArgument(title = "   ", description = "   ")
				        Optional<Integer> ordinary) {}
				record Expanding<T>(Expanding<List<T>> next) {}
				record Phantom<T>(String value) {}
				enum ParityStatus { SECOND, FIRST, THIRD }
				interface FrameworkRoot {}
				final class FrameworkSubtype implements FrameworkRoot {}
				""";
	}

	private static ExpectedFailure failure(McpTypedSchemaException.Reason reason,
			String path) {
		return new ExpectedFailure(reason, path);
	}

	private record ExpectedFailure(McpTypedSchemaException.Reason reason,
			String path) {
	}

	private record ParityBox<T>(T value, List<T> values, Optional<T> maybe) {
	}

	private record ParityInput(boolean enabled, int count, ParityStatus status,
			String[] names, List<Long> totals, Map<String, BigDecimal> prices,
			ParityBox<String> box, Optional<Double> ratio) {
	}

	private record ParityAnnotated(
			@McpToolArgument(name = "externalName", title = " External title ",
					description = "External description") String internalName,
			@McpToolArgument(title = "   ", description = "   ")
			Optional<Integer> ordinary) {
	}

	private record Expanding<T>(Expanding<List<T>> next) {
	}

	private record Phantom<T>(String value) {
	}

	private enum ParityStatus {
		SECOND,
		FIRST,
		THIRD
	}

	private static final class ArbitraryBean {
	}

	private interface ParityFrameworkRoot {
	}

	private static final class ParityFrameworkSubtype
			implements ParityFrameworkRoot {
	}

	@SuppressWarnings("rawtypes")
	private static final class RuntimeFixture<T> {
		private boolean primitiveBoolean;
		private Boolean boxedBoolean;
		private byte primitiveByte;
		private Byte boxedByte;
		private short primitiveShort;
		private Short boxedShort;
		private int primitiveInt;
		private Integer boxedInt;
		private long primitiveLong;
		private Long boxedLong;
		private BigInteger bigInteger;
		private float primitiveFloat;
		private Float boxedFloat;
		private double primitiveDouble;
		private Double boxedDouble;
		private BigDecimal bigDecimal;
		private String string;
		private ParityStatus status;
		private int[] primitiveInts;
		private String[] strings;
		private List<String> stringList;
		private Map<String, Long> longsByName;
		private ParityBox<String> box;
		private ParityBox<ParityBox<String>> nestedBox;
		private ParityInput input;
		private ParityAnnotated annotated;
		private List rawList;
		private List<?> wildcardList;
		private Map<Integer, String> badMap;
		private Optional<String> optionalRoot;
		private List<Optional<String>> nestedOptional;
		private T variable;
		private T[] genericArray;
		private Object object;
		private StringBuilder characters;
		private McpJsonValue framework;
		private ParityFrameworkSubtype frameworkSubtype;
		private Expanding<String> expanding;
		private Phantom<Object> phantomInvalid;
		private ArbitraryBean unsupported;
	}

	private static final class Inspection extends AbstractProcessor {
		private final Map<String, McpTypedSchemaShape> shapes =
				new LinkedHashMap<>();
		private final Map<String, McpTypedSchemaException> failures =
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
					.getTypeElement("parity.Fixture");
			if (fixture == null)
				return false;

			McpTypeMirrorTypedTypeModel model = new McpTypeMirrorTypedTypeModel(
					processingEnv.getTypeUtils(), processingEnv.getElementUtils(),
					LIMITS, Set.of("parity.FrameworkRoot"));
			McpTypedSchemaResolver<TypeMirror> resolver =
					new McpTypedSchemaResolver<>(model, LIMITS);
			for (Element element : fixture.getEnclosedElements()) {
				if (element.getKind() != ElementKind.FIELD)
					continue;
				String name = element.getSimpleName().toString();
				try {
					shapes.put(name, resolver.resolveSchema(element.asType()));
				} catch (McpTypedSchemaException exception) {
					failures.put(name, exception);
				}
			}
			complete = true;
			return false;
		}
	}
}

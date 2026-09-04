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
import com.soklet.annotation.McpHeader;
import com.soklet.annotation.McpToolProperty;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpTypeMirrorTypedSchemaBridgeTests {
	@Test
	void compilesSyntheticArgumentsAndOutputWithRuntimeParity() {
		Inspection inspection = inspect();
		McpTypeMirrorTypedSchemaBridge.CompiledSchemas compiled =
				assertInstanceOf(
						McpTypeMirrorTypedSchemaBridge.CompiledSchemas.class,
						inspection.results.get("valid"));
		McpRuntimeTypedSchemaCompiler runtimeCompiler = runtimeCompiler();
		McpCompiledRuntimeTypedSchema<RuntimeInput> runtimeInput =
				runtimeCompiler.compileToolInput(RuntimeInput.class);
		McpCompiledRuntimeTypedSchema<RuntimeOutput> runtimeOutput =
				runtimeCompiler.compileToolOutput(RuntimeOutput.class);

		assertArrayEquals(runtimeInput.schema().serializedDocument(),
				compiled.getInputSchemaBytes());
		assertArrayEquals(runtimeOutput.schema().serializedDocument(),
				compiled.getOutputSchemaBytes());
		assertEquals(compiled.getInputSchemaDocument(),
				new McpJsonCodec(McpJsonLimits.productionDefaults()).parse(
						compiled.getInputSchemaBytes()));
		assertEquals(compiled.getOutputSchemaDocument(),
				new McpJsonCodec(McpJsonLimits.productionDefaults()).parse(
						compiled.getOutputSchemaBytes()));
		assertEquals(
				"{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"title\":\"Query title\",\"description\":\"Query description\"},\"limit\":{\"type\":\"integer\",\"minimum\":-2147483648,\"maximum\":2147483647}},\"required\":[\"query\"],\"additionalProperties\":false}",
				new String(compiled.getInputSchemaBytes(), StandardCharsets.UTF_8));
	}

	@Test
	void mirroredHeaderRulesAndNestedRecordsMatchRuntimeCompiler() {
		Inspection inspection = inspect();
		McpTypeMirrorTypedSchemaBridge.CompiledSchemas compiled =
				assertInstanceOf(
						McpTypeMirrorTypedSchemaBridge.CompiledSchemas.class,
						inspection.results.get("validHeaders"));
		McpRuntimeTypedSchemaCompiler runtimeCompiler = runtimeCompiler();
		McpCompiledRuntimeTypedSchema<HeaderRuntimeInput> runtimeInput =
				runtimeCompiler.compileToolInput(HeaderRuntimeInput.class);
		McpCompiledRuntimeTypedSchema<HeaderRuntimeOutput> runtimeOutput =
				runtimeCompiler.compileToolOutput(HeaderRuntimeOutput.class);

		assertArrayEquals(runtimeInput.schema().serializedDocument(),
				compiled.getInputSchemaBytes());
		assertArrayEquals(runtimeOutput.schema().serializedDocument(),
				compiled.getOutputSchemaBytes());
		assertHeaderFailureParity(inspection, "invalidHeaderToken",
				() -> runtimeCompiler.compileToolInput(
						HeaderRuntimeInvalidToken.class));
		assertHeaderFailureParity(inspection, "duplicateHeaders",
				() -> runtimeCompiler.compileToolInput(
						HeaderRuntimeDuplicate.class));
		assertHeaderFailureParity(inspection, "invalidHeaderScalar",
				() -> runtimeCompiler.compileToolInput(
						HeaderRuntimeInvalidScalar.class));
		assertHeaderFailureParity(inspection, "outputHeader",
				() -> runtimeCompiler.compileToolOutput(
						HeaderRuntimeInvalidOutput.class));
	}

	@Test
	void returnsStableInputAndOutputDiagnosticsWithoutTypeValues() {
		Inspection inspection = inspect();
		McpTypeMirrorTypedSchemaBridge.Diagnostic input = diagnostic(
				inspection.results.get("badInput"));
		McpTypeMirrorTypedSchemaBridge.Diagnostic unsafePath = diagnostic(
				inspection.results.get("unsafePath"));
		McpTypeMirrorTypedSchemaBridge.Diagnostic output = diagnostic(
				inspection.results.get("badOutput"));

		assertEquals(McpTypeMirrorTypedSchemaBridge.Direction.TOOL_INPUT,
				input.direction());
		assertEquals(McpTypeMirrorTypedSchemaBridge.Reason.WILDCARD,
				input.reason());
		assertEquals("$/properties/published/items", input.path());
		assertFalse(input.toString().contains("secretParameter"));
		assertFalse(input.toString().contains("java.util"));

		assertEquals(
				"$/properties/line\\u000Aright\\u202E~1slash~0tab\\u0009/items",
				unsafePath.path());
		assertFalse(unsafePath.path().contains("\n"));
		assertFalse(unsafePath.path().contains("\t"));
		assertFalse(unsafePath.path().indexOf((char) 0x202E) >= 0);

		assertEquals(McpTypeMirrorTypedSchemaBridge.Direction.TOOL_OUTPUT,
				output.direction());
		assertEquals(
				McpTypeMirrorTypedSchemaBridge.Reason.AMBIGUOUS_OUTPUT_STRING,
				output.reason());
		assertEquals("$", output.path());
	}

	@Test
	void preservesPublishedOrderRejectsDuplicatesAndCopiesSchemaBytes() {
		Inspection inspection = inspect();
		McpTypeMirrorTypedSchemaBridge.Diagnostic duplicate = diagnostic(
				inspection.results.get("duplicate"));
		assertEquals(McpTypeMirrorTypedSchemaBridge.Reason.DUPLICATE_PROPERTY,
				duplicate.reason());
		assertEquals("$/properties/same", duplicate.path());

		McpTypeMirrorTypedSchemaBridge.CompiledSchemas compiled =
				assertInstanceOf(
						McpTypeMirrorTypedSchemaBridge.CompiledSchemas.class,
						inspection.results.get("valid"));
		byte[] first = compiled.getInputSchemaBytes();
		byte[] second = compiled.getInputSchemaBytes();
		assertNotSame(first, second);
		first[0] ^= 1;
		assertArrayEquals(second, compiled.getInputSchemaBytes());
	}

	private static McpTypeMirrorTypedSchemaBridge.Diagnostic diagnostic(
			McpTypeMirrorTypedSchemaBridge.Result result) {
		return assertInstanceOf(
				McpTypeMirrorTypedSchemaBridge.RejectedSchemas.class, result)
				.diagnostic();
	}

	private static void assertHeaderFailureParity(Inspection inspection,
			String methodName, Runnable runtimeCompilation) {
		McpSchemaCompilationException mirrorFailure = assertInstanceOf(
				McpSchemaCompilationException.class,
				inspection.schemaUseFailures.get(methodName));
		McpSchemaCompilationException runtimeFailure = assertThrows(
				McpSchemaCompilationException.class, runtimeCompilation::run);

		assertEquals(McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
				mirrorFailure.kind(), methodName);
		assertEquals(runtimeFailure.kind(), mirrorFailure.kind(), methodName);
		assertEquals(runtimeFailure.keyword(), mirrorFailure.keyword(), methodName);
		assertEquals(runtimeFailure.location(), mirrorFailure.location(), methodName);
	}

	private static Inspection inspect() {
		Inspection inspection = new Inspection();
		JavaFileObject fixture = JavaFileObjects.forSourceString(
				"bridge.Fixture", """
						package bridge;

						import com.soklet.annotation.McpHeader;
						import com.soklet.annotation.McpToolProperty;
						import java.util.List;
						import java.util.Optional;

						class Fixture {
						  CompileOutput valid(String query, Optional<Integer> limit) {
						    return null;
						  }
						  CompileOutput badInput(List<?> secretParameter) {
						    return null;
						  }
						  CompileOutput unsafePath(List<?> secretParameter) {
						    return null;
						  }
						  String badOutput(String query) { return query; }
						  CompileOutput duplicate(String first, int second) {
						    return null;
						  }
						  HeaderOutput validHeaders(
						      String tenant, HeaderRouting routing) {
						    return null;
						  }
						  HeaderOutput invalidHeaderToken(String value) {
						    return null;
						  }
						  HeaderOutput duplicateHeaders(String first, boolean second) {
						    return null;
						  }
						  HeaderOutput invalidHeaderScalar(double ratio) {
						    return null;
						  }
						  HeaderInvalidOutput outputHeader(String value) {
						    return null;
						  }
						}
						record CompileOutput(
						    @McpToolProperty(name = "items", title = "Items title",
						        description = "Items description")
						    List<CompileItem> javaItems) {}
						record CompileItem(String id, long score) {}
						record HeaderRouting(@McpHeader(name = "Shard") int shard) {}
						record HeaderOutput(String value) {}
						record HeaderInvalidOutput(
						    @McpHeader(name = "Output") String value) {}
						""");
		Compilation compilation = Compiler.javac()
				.withOptions("--release", "17")
				.withProcessors(inspection)
				.compile(fixture);
		assertThat(compilation).succeeded();
		return inspection;
	}

	private static McpRuntimeTypedSchemaCompiler runtimeCompiler() {
		return new McpRuntimeTypedSchemaCompiler(
				McpSchemaCompilationLimits.productionDefaults(),
				McpTypedJsonBindingLimits.productionDefaults(),
				new McpJsonCodec(McpJsonLimits.productionDefaults()));
	}

	private record RuntimeInput(
			@McpToolProperty(name = "query", title = "Query title",
					description = "Query description") String javaQuery,
			@McpToolProperty(title = " ", description = "\t")
			Optional<Integer> limit) {
	}

	private record RuntimeOutput(
			@McpToolProperty(name = "items", title = "Items title",
					description = "Items description")
			List<RuntimeItem> javaItems) {
	}

	private record RuntimeItem(String id, long score) {
	}

	private record HeaderRuntimeInput(
			@McpHeader(name = "Tenant") String tenant,
			HeaderRuntimeRouting routing) {
	}

	private record HeaderRuntimeRouting(@McpHeader(name = "Shard") int shard) {
	}

	private record HeaderRuntimeOutput(String value) {
	}

	private record HeaderRuntimeInvalidToken(
			@McpHeader(name = "bad name") String value) {
	}

	private record HeaderRuntimeDuplicate(
			@McpHeader(name = "Tenant") String first,
			@McpHeader(name = "tenant") boolean second) {
	}

	private record HeaderRuntimeInvalidScalar(
			@McpHeader(name = "Ratio") double ratio) {
	}

	private record HeaderRuntimeInvalidOutput(
			@McpHeader(name = "Output") String value) {
	}

	private static final class Inspection extends AbstractProcessor {
		private final Map<String, McpTypeMirrorTypedSchemaBridge.Result> results =
				new LinkedHashMap<>();
		private final Map<String, McpSchemaCompilationException> schemaUseFailures =
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
					.getTypeElement("bridge.Fixture");
			if (fixture == null)
				return false;

			for (Element element : fixture.getEnclosedElements()) {
				if (element.getKind() != ElementKind.METHOD)
					continue;
				ExecutableElement method = (ExecutableElement) element;
				List<McpTypeMirrorTypedSchemaBridge.ToolArgument> arguments =
						switch (method.getSimpleName().toString()) {
						case "valid" -> List.of(
								argument("query", method, 0,
										"Query title", "Query description"),
								argument("limit", method, 1, " ", "\t"));
						case "badInput" -> List.of(
								argument("published", method, 0));
						case "unsafePath" -> List.of(argument(
								"line\nright" + (char) 0x202E
										+ "/slash~tab\t", method, 0));
						case "badOutput" -> List.of(
								argument("query", method, 0));
						case "duplicate" -> List.of(
								argument("same", method, 0),
								argument("same", method, 1));
						case "validHeaders" -> List.of(
								headerArgument("tenant", method, 0, "Tenant"),
								argument("routing", method, 1));
						case "invalidHeaderToken" -> List.of(
								headerArgument("value", method, 0, "bad name"));
						case "duplicateHeaders" -> List.of(
								headerArgument("first", method, 0, "Tenant"),
								headerArgument("second", method, 1, "tenant"));
						case "invalidHeaderScalar" -> List.of(
								headerArgument("ratio", method, 0, "Ratio"));
						case "outputHeader" -> List.of(
								argument("value", method, 0));
						default -> throw new IllegalStateException(
								"Unexpected fixture method.");
					};
				String methodName = method.getSimpleName().toString();
				try {
					results.put(methodName,
							McpTypeMirrorTypedSchemaBridge.compileToolSchemas(
									processingEnv.getTypeUtils(),
									processingEnv.getElementUtils(), arguments,
									method.getReturnType()));
				} catch (McpSchemaCompilationException exception) {
					schemaUseFailures.put(methodName, exception);
				}
			}
			complete = true;
			return false;
		}

		private McpTypeMirrorTypedSchemaBridge.ToolArgument argument(
				String publishedName, ExecutableElement method, int index) {
			return new McpTypeMirrorTypedSchemaBridge.ToolArgument(publishedName,
					method.getParameters().get(index).asType());
		}

		private McpTypeMirrorTypedSchemaBridge.ToolArgument headerArgument(
				String publishedName, ExecutableElement method, int index,
				String headerName) {
			return new McpTypeMirrorTypedSchemaBridge.ToolArgument(publishedName,
					method.getParameters().get(index).asType(), "", "",
					Optional.of(headerName));
		}

		private McpTypeMirrorTypedSchemaBridge.ToolArgument argument(
				String publishedName, ExecutableElement method, int index,
				String title, String description) {
			return new McpTypeMirrorTypedSchemaBridge.ToolArgument(publishedName,
					method.getParameters().get(index).asType(), title, description);
		}
	}
}

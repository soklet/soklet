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

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.concurrent.ThreadSafe;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.google.testing.compile.CompilationSubject.assertThat;

/**
 * Focused validation and annotation-processing-round coverage for annotated
 * MCP endpoints.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class McpAnnotationProcessorValidationTests {
	@Test
	void rejectsSignatureTypesInaccessibleToGeneratedProvider() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InaccessibleEndpoint", """
						package example;

						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;
						import com.soklet.annotation.McpToolArgument;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class InaccessibleEndpoint {
						  @McpTool(name = "hidden")
						  public Hidden hidden(@McpToolArgument Hidden input) {
						    return input;
						  }

						  private record Hidden(String value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpTool return type must be accessible to the generated MCP endpoint provider")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"@McpTool argument type must be accessible to the generated MCP endpoint provider")
				.inFile(source);
	}

	@Test
	void rejectsThrownTypesOutsideGeneratedHandlerContract() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.ThrowableEndpoint", """
						package example;

						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class ThrowableEndpoint {
						  @McpTool(name = "invalid")
						  public Result invalid() throws Throwable {
						    return new Result("value");
						  }

						  public record Result(String value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpTool method throws types must extend Exception or Error")
				.inFile(source);
	}

	@Test
	void rejectsRecordConstructorParameterOnlyToolArgumentAnnotation() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.ParameterOnlyRecord", """
						package example;

						import com.soklet.annotation.McpToolArgument;

						public record ParameterOnlyRecord(String value) {
						  public ParameterOnlyRecord(@McpToolArgument String value) {
						    this.value = value;
						  }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpToolArgument parameters must belong to an @McpTool method")
				.inFile(source);
	}

	@Test
	void rejectsMcpHeaderParameterWithoutToolArgument() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.MisplacedHeaderEndpoint", """
						package example;

						import com.soklet.annotation.McpHeader;
						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class MisplacedHeaderEndpoint {
						  @McpTool(name = "invalid")
						  public Result invalid(@McpHeader("Tenant") String tenant) {
						    return new Result(tenant);
						  }

						  public record Result(String value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpHeader parameters must also declare @McpToolArgument")
				.inFile(source);
	}

	@Test
	void rejectsInvalidMirroredHeaderSchemas() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InvalidMirroredHeadersEndpoint", """
						package example;

						import com.soklet.annotation.McpHeader;
						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;
						import com.soklet.annotation.McpToolArgument;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class InvalidMirroredHeadersEndpoint {
						  @McpTool(name = "invalid-token")
						  public Result invalidToken(
						      @McpToolArgument @McpHeader("bad name") String value) {
						    return new Result(value);
						  }

						  @McpTool(name = "duplicate-headers")
						  public Result duplicateHeaders(
						      @McpToolArgument @McpHeader("Tenant") String first,
						      @McpToolArgument @McpHeader("tenant") boolean second) {
						    return new Result(first + second);
						  }

						  @McpTool(name = "invalid-scalar")
						  public Result invalidScalar(
						      @McpToolArgument @McpHeader("Ratio") double ratio) {
						    return new Result(Double.toString(ratio));
						  }

						  @McpTool(name = "output-placement")
						  public InvalidOutput outputPlacement(
						      @McpToolArgument String value) {
						    return new InvalidOutput(value);
						  }

						  public record Result(String value) {}
						  public record InvalidOutput(
						      @McpHeader("Output") String value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		for (String toolName : List.of("invalid-token", "duplicate-headers",
				"invalid-scalar", "output-placement"))
			assertThat(compilation).hadErrorContaining(
					"MCP tool '" + toolName + "'").inFile(source);
	}

	@Test
	void duplicateArgumentDiagnosticDoesNotRenderUnsafePublishedName() {
		String bidiEscape = String.format("\\u%04X", 0x202E);
		String unsafeLiteral = "\"duplicate\\n\\t" + bidiEscape + "\"";
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.DuplicateArgumentsEndpoint", """
						package example;

						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;
						import com.soklet.annotation.McpToolArgument;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class DuplicateArgumentsEndpoint {
						  @McpTool(name = "duplicate")
						  public Result duplicate(
						      @McpToolArgument(name = %s) String first,
						      @McpToolArgument(name = %s) String second) {
						    return new Result(first + second);
						  }

						  public record Result(String value) {}
						}
						""".formatted(unsafeLiteral, unsafeLiteral));

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"Duplicate MCP tool argument name").inFile(source);
		String diagnostics = compilation.errors().toString();
		Assertions.assertFalse(diagnostics.contains("\n\t"), diagnostics);
		Assertions.assertFalse(diagnostics.contains("\u202E"), diagnostics);
	}

	@Test
	void deeplyNestedRecordGraphProducesBoundedSchemaDiagnostic() {
		StringBuilder records = new StringBuilder();
		for (int index = 0; index < 200; ++index) {
			String componentType = index == 199 ? "String"
					: "Node" + (index + 1);
			records.append("  public record Node").append(index)
					.append('(').append(componentType)
					.append(" value) {}\n");
		}
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.DeepEndpoint", """
						package example;

						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class DeepEndpoint {
						  @McpTool(name = "deep")
						  public Node0 deep() { return null; }
						%s
						}
						""".formatted(records));

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("LIMIT_EXCEEDED")
				.inFile(source);
	}

	@Test
	void rejectsInvalidPromptSignaturesAndArgumentBindings() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InvalidPromptEndpoint", """
						package example;

						import com.soklet.McpRequestContext;
						import com.soklet.annotation.McpPrompt;
						import com.soklet.annotation.McpPromptArgument;
						import com.soklet.annotation.McpServerEndpoint;
						import java.util.Optional;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class InvalidPromptEndpoint {
						  @McpPrompt(name = "invalid")
						  public String invalid(
						      @McpPromptArgument Integer wrongType,
						      String missingAnnotation,
						      @McpPromptArgument McpRequestContext annotatedContext,
						      McpRequestContext duplicateContext) {
						    return "invalid";
						  }

						  public void stray(@McpPromptArgument Optional<String> value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpPrompt method return type must be McpPromptOutput or a subtype of McpOperationResult")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"@McpPromptArgument parameters must be String or Optional<String>")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Every non-context @McpPrompt parameter must be annotated")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Injectable MCP context parameters must not also be annotated with @McpPromptArgument")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"McpRequestContext at most once").inFile(source);
		assertThat(compilation).hadErrorContaining(
				"@McpPromptArgument parameters must belong to an @McpPrompt method")
				.inFile(source);
	}

	@Test
	void acceptsDirectInvocationFeatureInjectionAcrossAnnotatedHandlers() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InvocationFeatureEndpoint", """
						package example;

						import com.soklet.CancelationToken;
						import com.soklet.McpInvocationFeatures;
						import com.soklet.McpProgressReporter;
						import com.soklet.McpPromptOutput;
						import com.soklet.McpRequestContext;
						import com.soklet.McpResourceListContext;
						import com.soklet.McpResourceOutput;
						import com.soklet.McpResourcePage;
						import com.soklet.McpResourceReadContext;
						import com.soklet.annotation.McpListResources;
						import com.soklet.annotation.McpPrompt;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;
						import com.soklet.annotation.McpToolArgument;
						import java.util.Optional;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class InvocationFeatureEndpoint {
						  @McpTool(name = "tool")
						  public Result tool(
						      McpRequestContext request,
						      CancelationToken cancelationToken,
						      @McpToolArgument String value,
						      Optional<McpProgressReporter> progressReporter,
						      McpInvocationFeatures features) {
						    return new Result(value);
						  }

						  @McpPrompt(name = "prompt")
						  public McpPromptOutput prompt(
						      McpInvocationFeatures features,
						      Optional<McpProgressReporter> progressReporter,
						      CancelationToken cancelationToken,
						      McpRequestContext request) {
						    return McpPromptOutput.fromMessages();
						  }

						  @McpResource(uri = "test://resource", name = "resource")
						  public McpResourceOutput resource(
						      McpResourceReadContext resource,
						      CancelationToken cancelationToken,
						      McpRequestContext request,
						      Optional<McpProgressReporter> progressReporter,
						      McpInvocationFeatures features) {
						    return null;
						  }

						  @McpListResources
						  public McpResourcePage resources(
						      CancelationToken cancelationToken,
						      McpResourceListContext list,
						      Optional<McpProgressReporter> progressReporter,
						      McpInvocationFeatures features,
						      McpRequestContext request) {
						    return null;
						  }

						  public record Result(String value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).succeededWithoutWarnings();
	}

	@Test
	void rejectsInvalidDirectInvocationFeatureInjectionSignatures() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InvalidInvocationFeatureEndpoint", """
						package example;

						import com.soklet.CancelationToken;
						import com.soklet.McpProgressReporter;
						import com.soklet.McpPromptOutput;
						import com.soklet.McpResourceListContext;
						import com.soklet.McpResourceOutput;
						import com.soklet.McpResourcePage;
						import com.soklet.annotation.McpListResources;
						import com.soklet.annotation.McpPrompt;
						import com.soklet.annotation.McpPromptArgument;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpResourceUriParameter;
						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;
						import com.soklet.annotation.McpToolArgument;
						import java.util.Optional;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class InvalidInvocationFeatureEndpoint {
						  @McpTool(name = "tool")
						  public Result tool(
						      CancelationToken firstCancelation,
						      CancelationToken secondCancelation,
						      Optional<McpProgressReporter> firstProgress,
						      @McpToolArgument Optional<McpProgressReporter> secondProgress,
						      McpProgressReporter bareProgress,
						      Optional<? extends McpProgressReporter> wildcardProgress) {
						    return new Result("tool");
						  }

						  @McpPrompt(name = "prompt")
						  public McpPromptOutput prompt(
						      CancelationToken firstCancelation,
						      CancelationToken secondCancelation,
						      Optional<McpProgressReporter> firstProgress,
						      @McpPromptArgument Optional<McpProgressReporter> secondProgress,
						      McpProgressReporter bareProgress,
						      Optional rawProgress) {
						    return McpPromptOutput.fromMessages();
						  }

						  @McpResource(uri = "test://resource", name = "resource")
						  public McpResourceOutput resource(
						      CancelationToken firstCancelation,
						      CancelationToken secondCancelation,
						      Optional<McpProgressReporter> firstProgress,
						      @McpResourceUriParameter Optional<McpProgressReporter> secondProgress,
						      McpProgressReporter bareProgress,
						      Optional<CancelationToken> wrongProgress) {
						    return null;
						  }

						  @McpListResources
						  public McpResourcePage resources(
						      McpResourceListContext list,
						      CancelationToken firstCancelation,
						      CancelationToken secondCancelation,
						      Optional<McpProgressReporter> firstProgress,
						      Optional<McpProgressReporter> secondProgress,
						      McpProgressReporter bareProgress,
						      Optional<String> wrongProgress) {
						    return null;
						  }

						  public record Result(String value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		for (String operation : List.of("@McpTool", "@McpPrompt",
				"@McpResource", "@McpListResources")) {
			assertThat(compilation).hadErrorContaining(operation
					+ " method may inject CancelationToken at most once")
					.inFile(source);
			assertThat(compilation).hadErrorContaining(operation
					+ " method may inject Optional<McpProgressReporter> at most once")
					.inFile(source);
		}
		assertThat(compilation).hadErrorContaining(
				"McpProgressReporter must be injected as Optional<McpProgressReporter>")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Injectable MCP feature parameters must not also be annotated with @McpToolArgument")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Injectable MCP feature parameters must not also be annotated with @McpPromptArgument")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Injectable MCP feature parameters must not also be annotated with @McpResourceUriParameter")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Every non-context @McpTool parameter must be annotated")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Every non-context @McpPrompt parameter must be annotated")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Every non-context @McpResource parameter must be annotated")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"@McpListResources parameters must be McpRequestContext")
				.inFile(source);
	}

	@Test
	void rejectsDuplicatePromptContractsAndDualAnnotatedMethods() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.DuplicatePromptEndpoint", """
						package example;

						import com.soklet.McpPromptOutput;
						import com.soklet.annotation.McpPrompt;
						import com.soklet.annotation.McpPromptArgument;
						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class DuplicatePromptEndpoint {
						  @McpPrompt(name = "same")
						  public McpPromptOutput first(
						      @McpPromptArgument String value) {
						    return McpPromptOutput.fromMessages();
						  }

						  @McpPrompt(name = "same")
						  public McpPromptOutput second() {
						    return McpPromptOutput.fromMessages();
						  }

						  @McpPrompt(name = "duplicate-arguments")
						  public McpPromptOutput duplicateArguments(
						      @McpPromptArgument(name = "duplicate") String first,
						      @McpPromptArgument(name = "duplicate") String second) {
						    return McpPromptOutput.fromMessages();
						  }

						  @McpPrompt(name = "both")
						  @McpTool(name = "both")
						  public McpPromptOutput both() {
						    return McpPromptOutput.fromMessages();
						  }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"Duplicate MCP prompt argument name").inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Duplicate MCP prompt name 'same'").inFile(source);
		assertThat(compilation).hadErrorContaining(
				"must not declare both @McpTool and @McpPrompt").inFile(source);
	}

	@Test
	void rejectsInvalidResourceSignaturesAndUriVariableBindings() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InvalidResourceEndpoint", """
						package example;

						import com.soklet.McpInvocationFeatures;
						import com.soklet.McpResourceReadContext;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpResourceUriParameter;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class InvalidResourceEndpoint {
						  @McpResource(
						      uri = "test://items/{identifier}",
						      name = "invalid",
						      size = 1,
						      cacheTtlMs = -1)
						  public String invalid(
						      @McpResourceUriParameter Integer identifier,
						      String missingAnnotation,
						      @McpResourceUriParameter McpResourceReadContext annotatedContext,
						      McpResourceReadContext duplicateContext,
						      McpInvocationFeatures features) {
						    return "invalid";
						  }

						  @McpResource(uri = "test://exact", name = "exact")
						  public com.soklet.McpResourceOutput exact(
						      @McpResourceUriParameter String undeclared) {
						    return null;
						  }

						  public void stray(@McpResourceUriParameter String value) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpResource method return type must be McpResourceOutput or a subtype of McpOperationResult")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"URI template must not declare size").inFile(source);
		assertThat(compilation).hadErrorContaining(
				"resource cache TTL must not be negative").inFile(source);
		assertThat(compilation).hadErrorContaining(
				"@McpResourceUriParameter parameters must be String")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Every non-context @McpResource parameter must be annotated")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Injectable MCP context parameters must not also be annotated with @McpResourceUriParameter")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"McpResourceReadContext at most once").inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Every URI-template variable must be bound exactly once")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"exact @McpResource method must not declare URI-template parameters")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"@McpResourceUriParameter parameters must belong to an @McpResource method")
				.inFile(source);
	}

	@Test
	void rejectsInvalidResourceAddressesListHandlersAndOperationConflicts() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InvalidResourceContractsEndpoint", """
						package example;

						import com.soklet.McpResourceListContext;
						import com.soklet.McpResourceOutput;
						import com.soklet.McpResourcePage;
						import com.soklet.annotation.McpListResources;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpServerEndpoint;
						import com.soklet.annotation.McpTool;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class InvalidResourceContractsEndpoint {
						  @McpResource(uri = "relative", name = "relative")
						  public McpResourceOutput relative() { return null; }

						  @McpResource(uri = "test://items/{first}{second}", name = "adjacent")
						  public McpResourceOutput adjacent() { return null; }

						  @McpResource(uri = "test://duplicate", name = "first")
						  public McpResourceOutput first() { return null; }

						  @McpResource(uri = "test://duplicate", name = "second")
						  public McpResourceOutput second() { return null; }

						  @McpListResources
						  public McpResourcePage firstList(McpResourceListContext list) {
						    return null;
						  }

						  @McpListResources
						  public McpResourcePage secondList(McpResourceListContext list) {
						    return null;
						  }

						  @McpListResources
						  public String invalidList() { return "invalid"; }

						  @McpResource(uri = "test://both", name = "both")
						  @McpTool(name = "both")
						  public McpResourceOutput both() { return null; }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"@McpResource uri must be an absolute normalized URI")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"valid absolute RFC 6570 Level 1 URI template").inFile(source);
		assertThat(compilation).hadErrorContaining(
				"Duplicate MCP resource address").inFile(source);
		assertThat(compilation).hadErrorContaining(
				"at most one @McpListResources method").inFile(source);
		assertThat(compilation).hadErrorContaining(
				"@McpListResources method return type must be exactly McpResourcePage")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"must inject McpResourceListContext exactly once")
				.inFile(source);
		assertThat(compilation).hadErrorContaining(
				"must declare exactly one operation annotation").inFile(source);
	}

	@Test
	void exactResourceUrisRequireAsciiRfc3986WireForm() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.ExactResourceUriEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class ExactResourceUriEndpoint {
						  @McpResource(uri = "test://items/%FF", name = "opaque-octet")
						  public McpResourceOutput opaqueOctet() { return null; }

						  @McpResource(uri = "test://items/café", name = "unicode")
						  public McpResourceOutput unicode() { return null; }

						  @McpResource(uri = "test://items/%GG", name = "bad-percent")
						  public McpResourceOutput badPercent() { return null; }

						  @McpResource(uri = "test://items/a/../b", name = "not-normalized")
						  public McpResourceOutput notNormalized() { return null; }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		Assertions.assertEquals(3, compilation.errors().stream()
				.filter(diagnostic -> diagnostic.getMessage(Locale.ROOT).contains(
						"absolute normalized URI in ASCII RFC 3986 wire form"))
				.count());
	}

	@Test
	void rejectsSyntaxEquivalentExactResourceUrisWithoutConflatingTemplates() {
		JavaFileObject duplicateExact = JavaFileObjects.forSourceString(
				"example.EquivalentExactResourceUriEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class EquivalentExactResourceUriEndpoint {
						  @McpResource(uri = "CATALOG://ITEMS/a%2Fb", name = "first")
						  public McpResourceOutput first() { return null; }

						  @McpResource(uri = "catalog://items/a%2fb", name = "second")
						  public McpResourceOutput second() { return null; }
						}
						""");

		Compilation duplicateCompilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(duplicateExact);

		assertThat(duplicateCompilation).failed();
		assertThat(duplicateCompilation).hadErrorContaining(
				"Duplicate MCP resource address").inFile(duplicateExact);

		JavaFileObject distinctTemplates = JavaFileObjects.forSourceString(
				"example.CaseDistinctResourceTemplateEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpResourceUriParameter;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class CaseDistinctResourceTemplateEndpoint {
						  @McpResource(uri = "CATALOG://ITEMS/{id}", name = "upper")
						  public McpResourceOutput upper(
						      @McpResourceUriParameter String id) { return null; }

						  @McpResource(uri = "catalog://items/{slug}", name = "lower")
						  public McpResourceOutput lower(
						      @McpResourceUriParameter String slug) { return null; }
						}
						""");

		Compilation templateCompilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(distinctTemplates);

		assertThat(templateCompilation).succeededWithoutWarnings();
	}

	@Test
	void acceptsUnicodeTemplateLiteralsAndCanonicalizesThemForOverlap() {
		JavaFileObject accepted = JavaFileObjects.forSourceString(
				"example.UnicodeResourceTemplateEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpResourceUriParameter;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class UnicodeResourceTemplateEndpoint {
						  @McpResource(uri = "test://items/café/{id}", name = "unicode")
						  public McpResourceOutput unicode(
						      @McpResourceUriParameter String id) { return null; }
						}
						""");

		Compilation acceptedCompilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(accepted);

		assertThat(acceptedCompilation).succeededWithoutWarnings();

		JavaFileObject overlapping = JavaFileObjects.forSourceString(
				"example.CanonicalUnicodeResourceTemplateEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpResourceUriParameter;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class CanonicalUnicodeResourceTemplateEndpoint {
						  @McpResource(uri = "test://items/café/{id}", name = "raw")
						  public McpResourceOutput raw(
						      @McpResourceUriParameter String id) { return null; }

						  @McpResource(uri = "test://items/caf%c3%a9/{slug}", name = "encoded")
						  public McpResourceOutput encoded(
						      @McpResourceUriParameter String slug) { return null; }
						}
						""");

		Compilation overlappingCompilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(overlapping);

		assertThat(overlappingCompilation).failed();
		assertThat(overlappingCompilation).hadErrorContaining(
				"Potentially overlapping MCP resource URI templates")
				.inFile(overlapping);
	}

	@Test
	void rejectsCharactersExcludedFromRfc6570TemplateLiterals() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.InvalidResourceTemplateLiteralsEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpResourceUriParameter;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class InvalidResourceTemplateLiteralsEndpoint {
						  @McpResource(uri = "test://h/bad'/{value}", name = "apostrophe")
						  public McpResourceOutput apostrophe(@McpResourceUriParameter String value) { return null; }

						  @McpResource(uri = "test://h/bad path/{value}", name = "space")
						  public McpResourceOutput space(@McpResourceUriParameter String value) { return null; }

						  @McpResource(uri = "test://h/bad" + '"' + "/{value}", name = "quote")
						  public McpResourceOutput quote(@McpResourceUriParameter String value) { return null; }

						  @McpResource(uri = "test://h/bad</{value}", name = "left-angle")
						  public McpResourceOutput leftAngle(@McpResourceUriParameter String value) { return null; }

						  @McpResource(uri = "test://h/bad>/{value}", name = "right-angle")
						  public McpResourceOutput rightAngle(@McpResourceUriParameter String value) { return null; }

						  @McpResource(uri = "test://h/bad" + (char) 92 + "/{value}", name = "backslash")
						  public McpResourceOutput backslash(@McpResourceUriParameter String value) { return null; }

						  @McpResource(uri = "test://h/bad^/{value}", name = "caret")
						  public McpResourceOutput caret(@McpResourceUriParameter String value) { return null; }

						  @McpResource(uri = "test://h/bad`/{value}", name = "grave")
						  public McpResourceOutput grave(@McpResourceUriParameter String value) { return null; }

						  @McpResource(uri = "test://h/bad|/{value}", name = "pipe")
						  public McpResourceOutput pipe(@McpResourceUriParameter String value) { return null; }

						  @McpResource(uri = "test://h/bad" + (char) 9 + "/{value}", name = "control")
						  public McpResourceOutput control(@McpResourceUriParameter String value) { return null; }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		Assertions.assertEquals(10, compilation.errors().stream()
				.filter(diagnostic -> diagnostic.getMessage(Locale.ROOT).contains(
						"valid absolute RFC 6570 Level 1 URI template"))
				.count());
	}

	@Test
	void resourceTemplateOverlapRespectsLevelOneExpansionTokens() {
		JavaFileObject disjoint = JavaFileObjects.forSourceString(
				"example.DisjointResourceTemplateEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpResourceUriParameter;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class DisjointResourceTemplateEndpoint {
						  @McpResource(uri = "test://h/{value}", name = "parent")
						  public McpResourceOutput parent(
						      @McpResourceUriParameter String value) { return null; }

						  @McpResource(uri = "test://h/{child}/details", name = "child")
						  public McpResourceOutput child(
						      @McpResourceUriParameter String child) { return null; }
						}
						""");

		Compilation disjointCompilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(disjoint);

		assertThat(disjointCompilation).succeededWithoutWarnings();

		JavaFileObject overlapping = JavaFileObjects.forSourceString(
				"example.EncodedDelimiterResourceTemplateEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpResourceUriParameter;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class EncodedDelimiterResourceTemplateEndpoint {
						  @McpResource(uri = "test://h/{value}", name = "all")
						  public McpResourceOutput all(
						      @McpResourceUriParameter String value) { return null; }

						  @McpResource(uri = "test://h/{prefix}%2fdetails", name = "encoded")
						  public McpResourceOutput encoded(
						      @McpResourceUriParameter String prefix) { return null; }
						}
						""");

		Compilation overlappingCompilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(overlapping);

		assertThat(overlappingCompilation).failed();
		assertThat(overlappingCompilation).hadErrorContaining(
				"Potentially overlapping MCP resource URI templates")
				.inFile(overlapping);
	}

	@Test
	void resourceTemplatesPermitAtMostThirtyTwoVariableExpressions() {
		Compilation accepted = compileResourceTemplateWithVariableCount(32);
		assertThat(accepted).succeededWithoutWarnings();

		Compilation rejected = compileResourceTemplateWithVariableCount(33);
		assertThat(rejected).failed();
		assertThat(rejected).hadErrorContaining(
				"resource URI template may declare at most 32 variable expressions");
	}

	@Test
	void rejectsPotentiallyOverlappingResourceTemplatesButAllowsExactRoute() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.OverlappingResourceEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpResourceUriParameter;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class OverlappingResourceEndpoint {
						  @McpResource(uri = "test://items/{id}", name = "by-id")
						  public McpResourceOutput byId(
						      @McpResourceUriParameter String id) { return null; }

						  @McpResource(uri = "test://items/{slug}", name = "by-slug")
						  public McpResourceOutput bySlug(
						      @McpResourceUriParameter String slug) { return null; }

						  @McpResource(uri = "test://items/fixed", name = "fixed")
						  public McpResourceOutput fixed() { return null; }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining(
				"Potentially overlapping MCP resource URI templates")
				.inFile(source);
		Assertions.assertEquals(1, compilation.errors().stream()
				.filter(diagnostic -> diagnostic.getMessage(Locale.ROOT).contains(
						"Potentially overlapping MCP resource URI templates"))
				.count());
	}

	@Test
	void acceptsPercentEncodedLevelOneResourceVariableNames() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.PercentEncodedResourceEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpResourceUriParameter;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class PercentEncodedResourceEndpoint {
						  @McpResource(uri = "test://items/{%6Eame.part}", name = "encoded")
						  public McpResourceOutput read(
						      @McpResourceUriParameter("%6Eame.part") String value) {
						    return null;
						  }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(source);

		assertThat(compilation).succeededWithoutWarnings();
	}

	@Test
	void defersResourceEndpointUntilGeneratedThrownTypeResolves() {
		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.GeneratedResourceEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/resource", name = "test", version = "1")
						public final class GeneratedResourceEndpoint {
						  @McpResource(uri = "test://generated", name = "generated")
						  public McpResourceOutput read() throws GeneratedException {
						    return null;
						  }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor(), new GeneratedDtoProcessor())
				.compile(source);

		assertThat(compilation).succeededWithoutWarnings();
		Assertions.assertTrue(compilation.generatedSourceFiles().stream()
				.map(JavaFileObject::getName)
				.anyMatch(name -> name.contains("SokletMcpEndpointProvider_")),
				compilation.generatedSourceFiles().toString());
	}

	@Test
	void defersEndpointUntilGeneratedDtoGraphResolves(
			@TempDir Path temporaryDirectory) throws IOException {
		Path sourceDirectory = temporaryDirectory.resolve("src/example");
		Path classDirectory = temporaryDirectory.resolve("classes");
		Path generatedDirectory = temporaryDirectory.resolve("generated");
		Files.createDirectories(sourceDirectory);
		Files.createDirectories(classDirectory);
		Files.createDirectories(generatedDirectory);
		Path endpointSource = sourceDirectory.resolve("GeneratedEndpoint.java");
		Files.writeString(endpointSource, """
				package example;

				import com.soklet.annotation.McpServerEndpoint;
				import com.soklet.annotation.McpTool;
				import com.soklet.annotation.McpToolArgument;

				@McpServerEndpoint(path = "/generated", name = "test", version = "1")
				public final class GeneratedEndpoint {
				  @McpTool(name = "generated")
				  public Envelope generated(
				      @McpToolArgument GeneratedDto input)
				      throws GeneratedException {
				    return new Envelope(input);
				  }

				  public record Envelope(GeneratedDto value) {}
				}
				""", StandardCharsets.UTF_8);

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler);
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
				diagnostics, null, StandardCharsets.UTF_8)) {
			JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager,
					diagnostics, List.of("--release", "17", "-parameters",
						"-Asoklet.cacheMode=none", "-classpath",
						System.getProperty("java.class.path"), "-d",
						classDirectory.toString(), "-s",
						generatedDirectory.toString()), null,
					fileManager.getJavaFileObjects(endpointSource));
			task.setProcessors(List.of(new SokletProcessor(),
					new GeneratedDtoProcessor()));
			Assertions.assertTrue(Boolean.TRUE.equals(task.call()),
					diagnostics.getDiagnostics().toString());
		}

		Path index = classDirectory.resolve(
				"META-INF/soklet/mcp-endpoint-descriptor-providers");
		Assertions.assertTrue(Files.isRegularFile(index));
		Assertions.assertEquals(1,
				Files.readAllLines(index, StandardCharsets.UTF_8).size());
		try (var paths = Files.walk(generatedDirectory)) {
			Assertions.assertTrue(paths.anyMatch(path -> path.getFileName()
					.toString().startsWith("SokletMcpEndpointProvider_")));
		}
	}

	private static Compilation compileResourceTemplateWithVariableCount(
			int variableCount) {
		StringBuilder template = new StringBuilder("test://items");
		StringBuilder parameters = new StringBuilder();
		for (int index = 0; index < variableCount; ++index) {
			template.append("/{variable").append(index).append('}');
			if (index > 0)
				parameters.append(",\n      ");
			parameters.append("@McpResourceUriParameter String variable")
					.append(index);
		}

		JavaFileObject source = JavaFileObjects.forSourceString(
				"example.ResourceTemplateVariableCountEndpoint", """
						package example;

						import com.soklet.McpResourceOutput;
						import com.soklet.annotation.McpResource;
						import com.soklet.annotation.McpResourceUriParameter;
						import com.soklet.annotation.McpServerEndpoint;

						@McpServerEndpoint(path = "/mcp", name = "test", version = "1")
						public final class ResourceTemplateVariableCountEndpoint {
						  @McpResource(uri = "%s", name = "many")
						  public McpResourceOutput many(
						      %s) { return null; }
						}
						""".formatted(template, parameters));
		return Compiler.javac().withProcessors(new SokletProcessor())
				.compile(source);
	}

	private static final class GeneratedDtoProcessor extends AbstractProcessor {
		private boolean generated;

		@Override
		public Set<String> getSupportedAnnotationTypes() {
			return Set.of("*");
		}

		@Override
		public SourceVersion getSupportedSourceVersion() {
			return SourceVersion.latestSupported();
		}

		@Override
		public boolean process(Set<? extends TypeElement> annotations,
				@NonNull RoundEnvironment roundEnvironment) {
			if (generated || roundEnvironment.processingOver())
				return false;
			generated = true;
			try {
				try (Writer writer = processingEnv.getFiler()
						.createSourceFile("example.GeneratedDto").openWriter()) {
					writer.write("package example; public record GeneratedDto(String value) {}\n");
				}
				try (Writer writer = processingEnv.getFiler()
						.createSourceFile("example.GeneratedException")
						.openWriter()) {
					writer.write("package example; public final class GeneratedException extends Exception {}\n");
				}
			} catch (IOException exception) {
				throw new IllegalStateException(exception);
			}
			return false;
		}
	}
}

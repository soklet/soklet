package com.soklet;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.Set;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class Round11ProcessorValidationTests {
	@Test
	void routesWaitForParameterTypesGeneratedByAnotherProcessor() throws Exception {
		var source = JavaFileObjects.forSourceString("fixture.Routes", """
				package fixture;
				import com.soklet.annotation.*;
				public class Routes {
				  @POST("/dto") public String dto(@RequestBody GeneratedDto value) { return "ok"; }
				}
				""");
		var compilation = Compiler.javac().withProcessors(new SokletProcessor(), new DtoGenerator()).compile(source);
		assertThat(compilation).succeeded();
		String index = compilation.generatedFile(javax.tools.StandardLocation.CLASS_OUTPUT,
				SokletProcessor.RESOURCE_METHOD_LOOKUP_TABLE_PATH).orElseThrow().getCharContent(false).toString();
		String row = index.lines().filter(line -> line.startsWith("POST|")).findFirst().orElseThrow();
		assertEquals("fixture.GeneratedDto", new String(java.util.Base64.getDecoder().decode(row.split("\\|", -1)[4]),
				java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	void legacyToolRejectsNestedMirroredHeadersDuringCompilation() {
		var source = JavaFileObjects.forSourceString("fixture.Endpoint", """
				package fixture;
				import com.soklet.*;
				import com.soklet.annotation.*;
				@McpServerEndpoint(path="/mcp", name="test", version="1", protocolVersions=McpProtocolVersion.V2025_11_25)
				public class Endpoint {
				  public record Input(@McpHeader(name="Tenant") String tenant, int count) {}
				  public record Output(int count) {}
				  @McpTool(name="work", protocolVersions=McpProtocolVersion.V2025_11_25)
				  public Output work(@McpToolArgument Input value) { return new Output(value.count()); }
				}
				""");
		var compilation = Compiler.javac().withProcessors(new SokletProcessor()).compile(source);
		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("A 2025 @McpTool must not declare mirrored headers");
	}

	@Test
	void annotatedResourcesRejectMalformedMimeTypesDuringCompilation() {
		for (String mime : java.util.List.of("json", "not a mime", "text/plain; incomplete")) {
			var source = JavaFileObjects.forSourceString("fixture.Endpoint", """
					package fixture;
					import com.soklet.*;
					import com.soklet.annotation.*;
					@McpServerEndpoint(path="/mcp", name="test", version="1", protocolVersions=McpProtocolVersion.V2026_07_28)
					public class Endpoint {
					  @McpResource(name="item", uri="test://item", mimeType="%s", protocolVersions=McpProtocolVersion.V2026_07_28)
					  public McpResourceOutput item() { return null; }
					}
					""".formatted(mime));
			var compilation = Compiler.javac().withProcessors(new SokletProcessor()).compile(source);
			assertThat(compilation).failed();
			assertThat(compilation).hadErrorContaining("MCP resource MIME type must be a valid media type");
		}
	}

	private static final class DtoGenerator extends AbstractProcessor {
		private boolean generated;
		@Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
		@Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
		@Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
			if (!generated && !round.processingOver()) {
				generated = true;
				try (var writer = processingEnv.getFiler().createSourceFile("fixture.GeneratedDto").openWriter()) {
					writer.write("package fixture; public class GeneratedDto {}");
				} catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
			}
			return false;
		}
	}
}

package com.soklet;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.google.testing.compile.CompilationSubject.assertThat;

class McpProcessorCloseoutTests {
	@Test
	void schemeAndServerHostCaseCannotHideOverlappingTemplates() {
		for (List<String> pair : List.of(
				List.of("HTTP://Example.com/items/{id}", "http://example.com/items/{key}"),
				List.of("test://items/{id}", "test://ITEMS/{key}"),
				List.of("http://[ABCD::1]:{id}/a", "HTTP://[abcd::1]:{key}/a"))) {
			Compilation result = resources(pair);
			assertThat(result).failed();
			assertThat(result).hadErrorContaining("Potentially overlapping MCP resource URI templates");
		}
	}

	@Test
	void ipv6PortVariablesCompileLikeProgrammaticTemplates() {
		assertThat(resources(List.of("http://[::1]:{port}/a"))).succeeded();
	}

	@Test
	void pathAndRegistryAuthorityCaseStayDistinct() {
		for (List<String> pair : List.of(
				List.of("http://example.com/ITEMS/{id}", "http://example.com/items/{key}"),
				List.of("test://REG_ISTRY/{id}", "test://reg_istry/{key}"),
				List.of("http://User@example.com/{id}", "http://user@example.com/{key}")))
			assertThat(resources(pair)).succeeded();
	}

	@Test
	void malformedAnnotationMetadataFailsAtItsDeclaration() {
		for (String declaration : List.of(
				"@McpTool(name=\"work\", description=\"bad\\ud800\", protocolVersions=McpProtocolVersion.V2026_07_28) public Output work() { return new Output(1); }",
				"@McpPrompt(name=\"bad\\udc00\", protocolVersions=McpProtocolVersion.V2026_07_28) public McpPromptOutput prompt() { return null; }",
				"@McpResource(name=\"item\", title=\"bad\\ud800\", uri=\"test://item\", protocolVersions=McpProtocolVersion.V2026_07_28) public McpResourceOutput item() { return null; }",
				"@McpPrompt(name=\"prompt\", protocolVersions=McpProtocolVersion.V2026_07_28) public McpPromptOutput prompt(@McpPromptArgument(description=\"bad\\udc00\") String value) { return null; }")) {
			Compilation result = compile("", declaration);
			assertThat(result).failed();
			assertThat(result).hadErrorContaining("must not contain unpaired UTF-16 surrogates");
		}
		Compilation endpoint = compile(", description=\"bad\\ud800\"", "");
		assertThat(endpoint).failed();
		assertThat(endpoint).hadErrorContaining("@McpServerEndpoint description");
	}

	@Test
	void pairedSurrogatesRemainValidMetadata() {
		assertThat(compile(", description=\"valid \\ud83d\\ude00\"", """
				@McpResource(name="item", title="valid \\ud83d\\ude00", uri="test://item", protocolVersions=McpProtocolVersion.V2026_07_28)
				public McpResourceOutput item() { return null; }
				""")).succeeded();
	}

	private static Compilation resources(List<String> templates) {
		StringBuilder declarations = new StringBuilder();
		for (int index = 0; index < templates.size(); ++index) {
			String template = templates.get(index);
			String variable = template.substring(template.indexOf('{') + 1, template.indexOf('}'));
			declarations.append("""
					@McpResource(uri="%s", name="item%d", protocolVersions=McpProtocolVersion.V2026_07_28)
					public McpResourceOutput item%d(@McpResourceUriParameter(name="%s") String variable) { return null; }
					""".formatted(template, index, index, variable));
		}
		return compile("", declarations.toString());
	}

	private static Compilation compile(String endpointAttributes, String declarations) {
		return Compiler.javac().withProcessors(new SokletProcessor()).compile(JavaFileObjects.forSourceString(
				"fixture.Endpoint", """
						package fixture;
						import com.soklet.*;
						import com.soklet.annotation.*;
						@McpServerEndpoint(path="/mcp", name="test", version="1", protocolVersions=McpProtocolVersion.V2026_07_28%s)
						public class Endpoint {
						public record Output(int value) {}
						%s
						}
						""".formatted(endpointAttributes, declarations)));
	}
}

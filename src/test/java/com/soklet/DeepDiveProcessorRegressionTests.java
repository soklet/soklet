package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeepDiveProcessorRegressionTests {
	@TempDir Path temporary;

	@Test
	void namedPublicBaseClassSubclassesCompileWithoutJsr305() throws Exception {
		Path source = temporary.resolve("Subclasses.java");
		Files.writeString(source, """
				import com.soklet.converter.*;
				import java.util.Optional;
				class Converter extends FromStringValueConverter<Integer> {
				  public Optional<Integer> performConversion(String value) { return Optional.of(1); }
				}
				class AbstractConverter extends AbstractValueConverter<String, Integer> {
				  public Optional<Integer> performConversion(String value) { return Optional.of(1); }
				}
				class Reference extends TypeReference<String> {}
				class BadRequest extends com.soklet.exception.BadRequestException {
				  BadRequest() { super("bad"); }
				}
				class Canceled extends StreamingResponseCanceledException {
				  Canceled() { super(com.soklet.StreamTerminationReason.CLIENT_DISCONNECTED); }
				}
				""".replace("extends StreamingResponseCanceledException", "extends com.soklet.StreamingResponseCanceledException"));
		String coreOnly = Path.of(Soklet.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		compile(source, coreOnly, "UTF-8");
	}

	@Test
	void generatedMcpMetadataIsAsciiUnderLegacySourceEncodings() throws Exception {
		Path source = temporary.resolve("Endpoint.java");
		// Keep the input ASCII too: javac decodes these escapes after source decoding.
		String escaped = "Desc caf" + "\\u00e9 \\u2615 \\u65e5\\u672c";
		Files.writeString(source, """
				import com.soklet.annotation.*;
				@McpServerEndpoint(path="/mcp", name="test", version="1",
				  protocolVersions=com.soklet.McpProtocolVersion.V2026_07_28,
				  instructions="%s")
				public class Endpoint {}
				""".formatted(escaped), StandardCharsets.US_ASCII);
		compile(source, System.getProperty("java.class.path"), "ISO-8859-1");
		try (var generated = Files.walk(temporary.resolve("generated"))) {
			List<Path> sources = generated.filter(path -> path.toString().endsWith(".java")).toList();
			assertFalse(sources.isEmpty());
			boolean foundInstructions = false;
			for (Path path : sources) {
				String text = Files.readString(path, StandardCharsets.US_ASCII);
				assertTrue(text.chars().allMatch(character -> character < 128));
				foundInstructions |= text.contains("\\u2615") && text.contains("\\u65E5");
			}
			assertTrue(foundInstructions);
		}
	}

	private void compile(Path source, String classpath, String encoding) throws Exception {
		Path output = Files.createDirectories(temporary.resolve("classes"));
		Path generated = Files.createDirectories(temporary.resolve("generated"));
		var compiler = ToolProvider.getSystemJavaCompiler();
		var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
		try (var files = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.Charset.forName(encoding))) {
			var task = compiler.getTask(null, files, diagnostics, List.of("--release", "21", "-parameters",
					"-Asoklet.cacheMode=none", "-encoding", encoding, "-classpath", classpath,
					"-d", output.toString(), "-s", generated.toString()), null, files.getJavaFileObjects(source));
			task.setProcessors(List.of(new SokletProcessor()));
			assertTrue(task.call(), diagnostics.getDiagnostics().toString());
		}
	}

	@Test
	void corruptRouteIndexesFailWithSourceAndLine() throws Exception {
		Path index = temporary.resolve("routes");
		for (String row : List.of("GET|broken", "GET|||||maybe", "UNKNOWN|||||false")) {
			Files.writeString(index, "# header\n" + row + "\n");
			URL url = index.toUri().toURL();
			ClassLoader loader = new ClassLoader(null) {
				@Override public java.util.Enumeration<URL> getResources(String name) {
					return Collections.enumeration(List.of(url));
				}
			};
			RuntimeException error = assertThrows(RuntimeException.class,
					() -> DefaultResourceMethodResolver.ResourceMethodDeclarationLoader.loadAll(loader));
			assertTrue(error.getCause().getMessage().contains(url + ":2"), error.toString());
		}
	}
}

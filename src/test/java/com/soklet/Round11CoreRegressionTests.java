package com.soklet;

import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Round11CoreRegressionTests {
	@TempDir Path temporary;

	@Test
	void staticFileSegmentsHaveAByteBoundAndLoopsAreRejected() throws Exception {
		Files.writeString(temporary.resolve("x".repeat(255)), "boundary");
		Files.writeString(temporary.resolve("é".repeat(127) + "x"), "unicode boundary");
		Files.createSymbolicLink(temporary.resolve("loop1"), Path.of("loop2"));
		Files.createSymbolicLink(temporary.resolve("loop2"), Path.of("loop1"));
		for (boolean follow : List.of(false, true)) {
			StaticFiles files = StaticFiles.withRoot(temporary).trustedImmutableRoot(true).followSymlinks(follow).build();
			for (String rejected : List.of("x".repeat(256), "é".repeat(128), "x".repeat(300) + "/file", "loop1"))
				assertTrue(files.marshaledResponseFor(rejected, request()).isEmpty(), rejected);
			assertEquals("boundary", new String(files.marshaledResponseFor("x".repeat(255), request())
					.orElseThrow().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
			assertEquals("unicode boundary", new String(files.marshaledResponseFor("é".repeat(127) + "x", request())
					.orElseThrow().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
		}
	}

	@Test
	void followModeRejectsSymbolicLinkCycles() throws Exception {
		Files.createSymbolicLink(temporary.resolve("loop1"), Path.of("loop2"));
		Files.createSymbolicLink(temporary.resolve("loop2"), Path.of("loop1"));
		StaticFiles files = StaticFiles.withRoot(temporary).trustedImmutableRoot(true).followSymlinks(true).build();
		assertTrue(files.marshaledResponseFor("loop1", request()).isEmpty());
	}

	@Test
	void excessiveAcceptQualityIsRejectedBeforeArbitraryPrecisionNormalization() {
		assertTrue(MediaRange.fromHeaderRepresentation("text/plain;q=0.1" + "0".repeat(65_000)).isEmpty());
		assertTrue(MediaRange.fromHeaderRepresentation("text/plain;q=" + "9".repeat(65_000)).isEmpty());
		assertEquals(BigDecimal.ONE, MediaRange.fromHeaderRepresentation("text/plain;q=5").orElseThrow().getQuality());
		assertEquals(BigDecimal.ZERO, MediaRange.fromHeaderRepresentation("text/plain;q=-1").orElseThrow().getQuality());
		assertEquals(new BigDecimal("0.5"), MediaRange.fromHeaderRepresentation("text/plain;q=0.500").orElseThrow().getQuality());
		assertEquals(new BigDecimal("0.5"), MediaRange.fromHeaderRepresentation("text/plain;q=0.5;q=" + "0".repeat(65_000)).orElseThrow().getQuality());
	}

	@Test
	void veryLargeRangeNumbersPreserveExactOrderingAndClampOnlyAfterValidation() {
		String large = "9".repeat(65_000), smaller = "8".repeat(65_000);
		assertEquals(ByteRangeSelection.ByteRangeSelectionType.MALFORMED,
				ByteRangeSelection.fromHeaderValue("bytes=" + large + "-" + smaller, 10L).getType());
		assertEquals(ByteRangeSelection.ByteRangeSelectionType.UNSATISFIABLE,
				ByteRangeSelection.fromHeaderValue("bytes=" + smaller + "-" + large, 10L).getType());
		assertEquals(ByteRangeSelection.ByteRangeSelectionType.MALFORMED,
				ByteRangeSelection.fromHeaderValue("bytes=0-" + large + "x", 10L).getType());
		assertEquals(ByteRangeSelection.fromHeaderValue("bytes=2-9", 10L),
				ByteRangeSelection.fromHeaderValue("bytes=" + "0".repeat(65_000) + "2-" + large, 10L));
		assertEquals(ByteRangeSelection.fromHeaderValue("bytes=0-9", 10L),
				ByteRangeSelection.fromHeaderValue("bytes=-" + large, 10L));
		assertEquals(ByteRangeSelection.fromHeaderValue("bytes=0-9223372036854775806", Long.MAX_VALUE),
				ByteRangeSelection.fromHeaderValue("bytes=0-" + large, Long.MAX_VALUE));
	}

	@Test
	void decimalScaleAndAdjustedExponentAreBoundedWithoutBreakingOrdinaryPrecision() throws Exception {
		var registry = ValueConverterRegistry.fromDefaults();
		for (Class<?> type : List.of(BigDecimal.class, Number.class)) {
			var converter = registry.get(String.class, type).orElseThrow();
			for (String rejected : List.of("1e99999999", "1e-99999999", "1e4097", "1e-4097", "99e4096"))
				assertThrows(ValueConversionException.class, () -> converter.convert(rejected), type + ": " + rejected);
			for (String accepted : List.of("1e4096", "1e-4096", "9".repeat(4096), "19.95", "0", "-0.0001"))
				assertEquals(new BigDecimal(accepted), converter.convert(accepted).orElseThrow());
		}
	}

	@Test
	void formContentTypeAllowsHttpWhitespaceBeforeParameters() {
		for (String header : List.of("application/x-www-form-urlencoded\t; charset=UTF-8",
				"\tapplication/x-www-form-urlencoded\t", " application/x-www-form-urlencoded \t ; charset=UTF-8")) {
			Request request = Request.withPath(HttpMethod.POST, "/form").headers(Map.of("Content-Type", List.of(header)))
					.body("a=b".getBytes(StandardCharsets.UTF_8)).build();
			assertEquals(Map.of("a", List.of("b")), request.getFormParameters(), header);
		}
	}

	@Test
	void processorAndClasspathResolverRoundTripObjectAndPrimitiveArrays() throws Exception {
		Path output = compile("""
				package fixture;
				import com.soklet.annotation.GET;
				public class Routes {
				  public static class Item {}
				  @GET("/arrays") public String arrays(String[] text, Item[][] items, int[] numbers, int count) { return "ok"; }
				  @GET("/last") public String last(Item[] items) { return "ok"; }
				}
				""");
		try (var loader = loader(output)) {
			var declarations = DefaultResourceMethodResolver.ResourceMethodDeclarationLoader.loadAll(loader);
			var arrays = declarations.stream().filter(value -> value.methodName().equals("arrays")).findFirst().orElseThrow();
			assertArrayEquals(new String[]{"[Ljava.lang.String;", "[[Lfixture.Routes$Item;", "[I", "int"}, arrays.parameterTypes());
			var resolver = resolve(loader);
			assertEquals(2, resolver.getResourceMethods().size());
			var parser = SokletProcessor.class.getDeclaredMethod("parseIndexLine", String.class);
			parser.setAccessible(true);
			for (String line : Files.readAllLines(output.resolve(SokletProcessor.RESOURCE_METHOD_LOOKUP_TABLE_PATH))) {
				if (line.startsWith("#") || line.isBlank()) continue;
				ResourceMethodDeclaration reparsed = (ResourceMethodDeclaration) parser.invoke(new SokletProcessor(), line);
				assertNotNull(reparsed);
				assertArrayEquals(declarations.stream().filter(value -> value.methodName().equals(reparsed.methodName()))
						.findFirst().orElseThrow().parameterTypes(), reparsed.parameterTypes());
			}
		}
	}

	@Test
	void processorNormalizesPathsBeforeDeduplication() throws Exception {
		Path output = compile("""
				package fixture;
				import com.soklet.annotation.GET;
				public class Routes {
				  @GET(" /lead") @GET("/lead/") @GET("//lead") public String lead() { return "ok"; }
				}
				""");
		try (var loader = loader(output)) {
			var resolver = resolve(loader);
			assertEquals(1, resolver.getResourceMethods().size());
			assertTrue(resolver.resourceMethodForRequest(Request.fromPath(HttpMethod.GET, "/lead"), ServerType.HTTP).isPresent());
		}
	}

	private Path compile(String text) throws Exception {
		Path source = temporary.resolve("Routes.java");
		Files.writeString(source, text);
		Path output = Files.createDirectories(temporary.resolve("classes"));
		var compiler = ToolProvider.getSystemJavaCompiler();
		var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
		try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			var task = compiler.getTask(null, files, diagnostics, List.of("--release", "21", "-parameters", "-Asoklet.cacheMode=none",
					"-classpath", System.getProperty("java.class.path"), "-d", output.toString()), null, files.getJavaFileObjects(source));
			task.setProcessors(List.of(new SokletProcessor()));
			assertTrue(task.call(), diagnostics.getDiagnostics().toString());
		}
		return output;
	}

	private URLClassLoader loader(Path output) throws Exception {
		return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader()) {
			@Override public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
				if (name.equals(SokletProcessor.RESOURCE_METHOD_LOOKUP_TABLE_PATH))
					return Collections.enumeration(List.of(output.resolve(name).toUri().toURL()));
				return super.getResources(name);
			}
		};
	}

	private static ResourceMethodResolver resolve(ClassLoader loader) throws Exception {
		var method = DefaultResourceMethodResolver.class.getDeclaredMethod("fromClasspathIntrospection", ClassLoader.class);
		method.setAccessible(true);
		return (ResourceMethodResolver) method.invoke(null, loader);
	}

	private static Request request() { return Request.fromPath(HttpMethod.GET, "/asset"); }
}

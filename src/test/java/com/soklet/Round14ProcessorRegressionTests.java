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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class Round14ProcessorRegressionTests {
	@Test
	void repeatedAndExplicitContainerSseAnnotationsValidateReturnTypesBeforePublishing(@TempDir Path directory) throws Exception {
		List<String> annotations = List.of(
				"@SseEventSource(\"/one\") @SseEventSource(\"/two\")",
				"@SseEventSources({@SseEventSource(\"/one\"), @SseEventSource(\"/two\")})");
		for (int index = 0; index < annotations.size(); index++) {
			var fixture = new SokletProcessorRouteIndexPersistenceTests.Fixture(directory.resolve("case-" + index), "persistent");
			fixture.compile(fixture.alpha);
			var snapshots = fixture.snapshots();
			byte[] originalClass = Files.readAllBytes(fixture.classes.resolve("example/Alpha.class"));
			Files.writeString(fixture.alpha, """
					package example;
					import com.soklet.annotation.*;
					public class Alpha {
					  %s public String events() { return "not-a-handshake"; }
					}
					""".formatted(annotations.get(index)), StandardCharsets.UTF_8);
			assertFalse(fixture.tryCompile("persistent", List.of(fixture.alpha), List.of(), List.of()));
			assertTrue(fixture.diagnostics.contains("must specify a return type of SseHandshakeResult"), fixture.diagnostics);
			assertEquals(snapshots, fixture.snapshots());
			assertArrayEquals(originalClass, Files.readAllBytes(fixture.classes.resolve("example/Alpha.class")));
			fixture.verifyPackagedRoutes("/alpha");
		}
	}

	@Test
	void sseReturnTypeGeneratedInALaterRoundIsValidatedBeforePublishing(@TempDir Path directory) throws Exception {
		GeneratedTypeFixture fixture = new GeneratedTypeFixture(directory);
		fixture.compile(fixture.alpha);
		var snapshots = fixture.snapshots();
		Files.writeString(fixture.alpha, """
				package example;
				import com.soklet.annotation.SseEventSource;
				public class Alpha {
				  @SseEventSource("/generated") public GeneratedDto events() { return new GeneratedDto(); }
				}
				""", StandardCharsets.UTF_8);
		fixture.generateType = true;
		assertFalse(fixture.tryCompile("persistent", List.of(fixture.alpha), List.of(), List.of()));
		assertTrue(fixture.diagnostics.contains("must specify a return type of SseHandshakeResult"), fixture.diagnostics);
		assertFalse(fixture.diagnostics.contains("contains an unresolved parameter or return type"), fixture.diagnostics);
		assertEquals(snapshots, fixture.snapshots());
		fixture.verifyPackagedRoutes("/alpha");
	}

	@Test
	void aGeneratedParameterStillDefersAValidSseDeclaration(@TempDir Path directory) throws Exception {
		GeneratedTypeFixture fixture = new GeneratedTypeFixture(directory);
		Files.writeString(fixture.alpha, """
				package example;
				import com.soklet.*;
				import com.soklet.annotation.*;
				public class Alpha {
				  @SseEventSource("/generated")
				  public SseHandshakeResult.Accepted events(@RequestBody GeneratedDto body) { return SseHandshakeResult.accept(); }
				}
				""", StandardCharsets.UTF_8);
		fixture.generateType = true;
		fixture.compile(fixture.alpha);
		fixture.assertSnapshots(List.of("example.Alpha"));
		String row = Files.readAllLines(fixture.index()).getFirst();
		assertEquals("example.GeneratedDto", new String(Base64.getDecoder().decode(row.split("\\|", -1)[4]), StandardCharsets.UTF_8));
		fixture.verifyPackagedRoutes();
	}

	@Test
	void malformedUtf16HttpAndSseAnnotationsCannotChangeThePreviousSnapshots(@TempDir Path directory) throws Exception {
		for (String annotation : List.of("GET", "SseEventSource")) {
			for (String surrogate : List.of("\\uD800", "\\uDC00")) {
				var fixture = new SokletProcessorRouteIndexPersistenceTests.Fixture(
						directory.resolve(annotation + surrogate.substring(2)), "persistent");
				fixture.compile(fixture.alpha);
				var snapshots = fixture.snapshots();
				Files.writeString(fixture.alpha, """
						package example;
						import com.soklet.*;
						import com.soklet.annotation.*;
						public class Alpha {
						  @%s("/PRIVATE_%s") public %s route() { return %s; }
						}
						""".formatted(annotation, surrogate,
						annotation.equals("GET") ? "String" : "SseHandshakeResult",
						annotation.equals("GET") ? "\"ok\"" : "SseHandshakeResult.accept()"), StandardCharsets.UTF_8);
				assertFalse(fixture.tryCompile("persistent", List.of(fixture.alpha), List.of(), List.of()));
				assertTrue(fixture.diagnostics.contains("unpaired UTF-16 surrogates"), fixture.diagnostics);
				assertFalse(fixture.diagnosticMessages.contains("PRIVATE_"), fixture.diagnostics);
				assertEquals(snapshots, fixture.snapshots());
				fixture.verifyPackagedRoutes("/alpha");
			}
		}
	}

	@Test
	void supplementaryHttpAndRepeatedSseRoutesRoundTripTheIndexAndRuntimeDiscovery(@TempDir Path directory) throws Exception {
		var fixture = new SokletProcessorRouteIndexPersistenceTests.Fixture(directory, "persistent");
		Files.writeString(fixture.alpha, """
				package example;
				import com.soklet.*;
				import com.soklet.annotation.*;
				public class Alpha {
				  @GET("/unicode-😀") public String route() { return "/unicode-😀"; }
				  @SseEventSource("/events-😀") @SseEventSource("/other-events-😀")
				  public SseHandshakeResult.Accepted events() { return SseHandshakeResult.accept(); }
				}
				""", StandardCharsets.UTF_8);
		fixture.compile(fixture.alpha);
		fixture.assertSnapshots(List.of("example.Alpha"));
		Set<String> paths = Files.readAllLines(fixture.index()).stream().map(line ->
				new String(Base64.getDecoder().decode(line.split("\\|", -1)[1]), StandardCharsets.UTF_8)).collect(Collectors.toSet());
		assertEquals(Set.of("/unicode-😀", "/events-😀", "/other-events-😀"), paths);
		fixture.verifyPackagedRoutes("/unicode-😀");
	}

	@Test
	void aTypeVariableBoundGeneratedInALaterRoundUsesItsFinalBinaryName(@TempDir Path directory) throws Exception {
		GeneratedTypeFixture fixture = new GeneratedTypeFixture(directory);
		Files.writeString(fixture.alpha, """
				package example;
				import com.soklet.annotation.*;
				public class Alpha {
				  @POST("/generated") public <T extends GeneratedDto> String route(@RequestBody T body) { return "ok"; }
				}
				""", StandardCharsets.UTF_8);
		fixture.generateType = true;
		fixture.compile(fixture.alpha);
		String row = Files.readAllLines(fixture.index()).getFirst();
		assertEquals("example.GeneratedDto", new String(Base64.getDecoder().decode(row.split("\\|", -1)[4]), StandardCharsets.UTF_8));
		fixture.verifyPackagedRoutes();
	}

	private static final class GeneratedTypeFixture extends SokletProcessorRouteIndexPersistenceTests.Fixture {
		private boolean generateType;
		GeneratedTypeFixture(Path directory) throws IOException { super(directory, "persistent"); }
		@Override List<? extends Processor> processors() {
			return generateType ? List.of(new SokletProcessor(), new DtoGenerator()) : super.processors();
		}
	}

	private static final class DtoGenerator extends AbstractProcessor {
		private boolean generated;
		@Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
		@Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
		@Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
			if (!generated && !round.processingOver()) {
				generated = true;
				try (var writer = processingEnv.getFiler().createSourceFile("example.GeneratedDto").openWriter()) {
					writer.write("package example; public class GeneratedDto {}");
				} catch (IOException failure) { throw new UncheckedIOException(failure); }
			}
			return false;
		}
	}
}

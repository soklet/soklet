/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Compiles the published Markdown snippets themselves and exercises their resource lifetimes. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingDocumentationExamplesTests {

	@TempDir
	static Path compilationDirectory;
	private static URLClassLoader exampleClassLoader;
	private static Class<?> examplesType;
	private static Object examples;

	@BeforeAll
	static void compilePublishedExamples() throws Exception {
		String readme = Files.readString(Path.of("README.md"));
		String migration = Files.readString(Path.of("MIGRATING_TO_4_0.md"));
		String imports = """
				package examples.streamingdocs;
				import com.soklet.*;
				import com.soklet.annotation.*;
				import com.soklet.StreamingDocumentationExamplesTests.TokenService;
				import java.nio.charset.StandardCharsets;
				import java.time.Duration;
				import java.util.*;
				import org.junit.jupiter.api.Assertions;
				import org.junit.jupiter.api.Test;
				""";
		String handlers = imports + "public class PublishedStreamingExamples {\n"
				+ javaBlock(readme, "public MarshaledResponse tokens(TokenService tokenService)")
				+ "\npublic MarshaledResponse archive(byte[] reportBytes) {\n"
				+ javaBlock(readme, "new java.util.zip.ZipOutputStream(responseStream.asOutputStream())") + "\n}\n"
				+ "public HttpServer httpSettings() {\n"
				+ javaBlock(migration, "HttpServer httpServer = HttpServer.withPort(8080)\n    .streamingLifecycleCapacity")
				+ "\nreturn httpServer;\n}\n"
				+ "public SseServer sseSettings() {\n"
				+ javaBlock(migration, "SseServer sseServer = SseServer.withPort(8081)")
				+ "\nreturn sseServer;\n}\n}\n";

		String chat = javaBlock(readme, "public class ChatResource");
		int resourceStart = chat.indexOf("public class ChatResource");
		String simulatorTest = javaBlock(readme, "public void sseTest()");
		String simulation = imports + "public class PublishedSseTest {\n"
				+ "private final SokletConfig config = chatConfig();\n"
				+ "private SokletConfig chatConfig() {\n"
				+ javaBlock(readme, "ResourceMethodResolver.fromClasses(Set.of(ChatResource.class))")
				+ "\nreturn config;\n}\n"
				+ simulatorTest.substring(simulatorTest.indexOf("@Test")) + "\n}\n";
		Map<String, String> sources = Map.of(
				"PublishedStreamingExamples.java", handlers,
				"ChatMessage.java", imports + chat.substring(0, resourceStart),
				"ChatResource.java", imports + chat.substring(resourceStart),
				"PublishedSseTest.java", simulation);
		List<Path> sourcePaths = new ArrayList<>();
		for (Map.Entry<String, String> entry : sources.entrySet()) {
			Path sourcePath = compilationDirectory.resolve(entry.getKey());
			Files.writeString(sourcePath, entry.getValue());
			sourcePaths.add(sourcePath);
		}
		var compiler = ToolProvider.getSystemJavaCompiler();
		Assertions.assertNotNull(compiler, "Documentation examples require a full JDK");
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (var fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			boolean compiled = compiler.getTask(null, fileManager, diagnostics,
					List.of("--release", "17", "-proc:none", "-parameters", "-encoding", "UTF-8",
							"-classpath", System.getProperty("java.class.path"), "-d", compilationDirectory.toString()),
					null, fileManager.getJavaFileObjectsFromPaths(sourcePaths)).call();
			Assertions.assertTrue(compiled, () -> "Published streaming examples did not compile:\n" + diagnostics.getDiagnostics());
		}
		exampleClassLoader = new URLClassLoader(new java.net.URL[]{compilationDirectory.toUri().toURL()},
				StreamingDocumentationExamplesTests.class.getClassLoader());
		examplesType = exampleClassLoader.loadClass("examples.streamingdocs.PublishedStreamingExamples");
		examples = examplesType.getConstructor().newInstance();
	}

	@AfterAll
	static void closeExampleClassLoader() throws IOException {
		if (exampleClassLoader != null)
			exampleClassLoader.close();
	}

	@Test
	void publishedTokenWriterIsLazyAndWritesCheckedSynchronousCallbacks() throws Exception {
		AtomicInteger generations = new AtomicInteger();
		AtomicInteger stops = new AtomicInteger();
		Thread producerThread = Thread.currentThread();
		TokenService tokenService = new TokenService() {
			@Override public void generate(TokenConsumer tokenConsumer) throws Exception {
				Assertions.assertSame(producerThread, Thread.currentThread());
				generations.incrementAndGet();
				for (String token : List.of("Hello ", "π ", "🌱\n"))
					tokenConsumer.accept(token);
			}
			@Override public void stop() { stops.incrementAndGet(); }
		};
		MarshaledResponse response = (MarshaledResponse) examplesType.getMethod("tokens", TokenService.class)
				.invoke(examples, tokenService);
		Assertions.assertEquals(0, generations.get(), "Building a response must not begin generation");
		Assertions.assertEquals("Hello π 🌱\n", new String(simulate(response), StandardCharsets.UTF_8));
		Assertions.assertEquals(1, generations.get());
		Assertions.assertEquals(0, stops.get(), "Normal completion removes the cancelation registration");
	}

	@Test
	void publishedOwnedZipProducesACompleteArchive() throws Exception {
		byte[] reportBytes = "Report: π 🌱\n".getBytes(StandardCharsets.UTF_8);
		MarshaledResponse response = (MarshaledResponse) examplesType.getMethod("archive", byte[].class)
				.invoke(examples, (Object) reportBytes);
		Path archive = compilationDirectory.resolve("published-report.zip");
		Files.write(archive, simulate(response));
		// ZipFile requires the central directory emitted when managed ownership closes the encoder.
		try (ZipFile zipFile = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
			Assertions.assertEquals(1, zipFile.size());
			try (var inputStream = zipFile.getInputStream(zipFile.getEntry("report.txt"))) {
				Assertions.assertArrayEquals(reportBytes, inputStream.readAllBytes());
			}
		}
	}

	@Test
	void publishedChatSimulationAndServerSettingsExecute() throws Exception {
		Class<?> simulationType = exampleClassLoader.loadClass("examples.streamingdocs.PublishedSseTest");
		simulationType.getMethod("sseTest").invoke(simulationType.getConstructor().newInstance());
		Assertions.assertInstanceOf(HttpServer.class, examplesType.getMethod("httpSettings").invoke(examples));
		Assertions.assertInstanceOf(SseServer.class, examplesType.getMethod("sseSettings").invoke(examples));
	}

	private static byte[] simulate(MarshaledResponse response) {
		AtomicReference<byte[]> body = new AtomicReference<>();
		SokletSimulator.run(configuration(new ExampleResource(response)), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/documentation-body"));
			Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
			body.set(result.getMarshaledResponse().bodyBytesOrEmpty());
		});
		return body.get();
	}

	private static SimulatorConfig configuration(ExampleResource resource) {
		return SimulatorConfig.builder().httpServer().sseServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ExampleResource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == ExampleResource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
					}
				}).build();
	}

	private static String javaBlock(String markdown, String identifyingText) {
		var matcher = Pattern.compile("\\x60{3}java\\R(.*?)\\R\\x60{3}", Pattern.DOTALL).matcher(markdown);
		List<String> matches = new ArrayList<>();
		while (matcher.find())
			if (matcher.group(1).contains(identifyingText))
				matches.add(matcher.group(1));
		Assertions.assertEquals(1, matches.size(), "Expected one published snippet containing " + identifyingText);
		return matches.get(0);
	}

	/** Application-defined checked provider contract used by the README token example. */
	public interface TokenService {
		void generate(TokenConsumer tokenConsumer) throws Exception;
		void stop();
	}

	@FunctionalInterface
	public interface TokenConsumer {
		void accept(String token) throws Exception;
	}

	public static final class ExampleResource {
		private final MarshaledResponse response;
		private ExampleResource(MarshaledResponse response) {
			this.response = response;
		}
		@GET("/documentation-body") public MarshaledResponse body() { return this.response; }
	}
}

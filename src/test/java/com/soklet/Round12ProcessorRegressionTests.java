package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Round12ProcessorRegressionTests {
	@Test
	void httpRecordBodiesDoNotRequireTheirTransitiveComponentTypes(@TempDir Path temporary) throws Exception {
		Path sources = Files.createDirectories(temporary.resolve("sources/fixture"));
		Path output = Files.createDirectories(temporary.resolve("classes"));
		Path missing = sources.resolve("Missing.java");
		Path dto = sources.resolve("Dto.java");
		Files.writeString(missing, "package fixture; public class Missing {}");
		Files.writeString(dto, "package fixture; public record Dto(Missing value) {}");
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertEquals(0, compiler.run(null, null, null, "-proc:none", "-d", output.toString(), missing.toString(), dto.toString()));
		Files.delete(output.resolve("fixture/Missing.class"));
		Path routes = sources.resolve("Routes.java");
		Files.writeString(routes, """
				package fixture;
				import com.soklet.annotation.*;
				public class Routes {
				  @POST("/dto") public String dto(@RequestBody Dto input) { return "ok"; }
				  @GET("/other") public String other() { return "ok"; }
				}
				""");
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		try (var manager = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
			var task = compiler.getTask(null, manager, diagnostics, List.of("--release", "21", "-parameters",
					"-Asoklet.cacheMode=none", "-classpath", output + System.getProperty("path.separator") + System.getProperty("java.class.path"),
					"-d", output.toString()), null, manager.getJavaFileObjects(routes));
			task.setProcessors(List.of(new SokletProcessor()));
			assertTrue(task.call(), diagnostics.getDiagnostics().toString());
		}
		String index = Files.readString(output.resolve(SokletProcessor.RESOURCE_METHOD_LOOKUP_TABLE_PATH));
		assertEquals(2, index.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).count());
	}
}

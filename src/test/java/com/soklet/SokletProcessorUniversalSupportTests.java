/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class SokletProcessorUniversalSupportTests {
	@Test
	void processingLintPassesWhenAnotherProcessorClaimsAnnotations(@TempDir Path directory) throws Exception {
		ClaimingProcessor cooperating = new ClaimingProcessor();
		var fixture = new SokletProcessorRouteIndexPersistenceTests.Fixture(directory, "none") {
			@Override
			List<? extends Processor> processors() {
				return List.of(new SokletProcessor(), cooperating);
			}
		};
		Files.writeString(fixture.alpha, """
				package example;
				import com.soklet.annotation.GET;
				@interface Marker {}
				@Marker
				public class Alpha {
				  @GET("/hello") public String hello() { return "hello"; }
				}
				""", StandardCharsets.UTF_8);
		fixture.compileWith("none", List.of(fixture.alpha), List.of(),
				List.of("-Xlint:processing", "-Werror"));

		assertEquals(Set.of("com.soklet.annotation.GET", "example.Marker"), cooperating.claimed);
		fixture.assertSnapshots(List.of("example.Alpha"));
	}

	@Test
	void removingFinalHttpAnnotationPrunesOnlyTheTouchedOwner(@TempDir Path directory) throws Exception {
		for (String mode : List.of("none", "sidecar", "persistent")) {
			var fixture = new SokletProcessorRouteIndexPersistenceTests.Fixture(directory.resolve(mode), mode);
			fixture.initialCompile();
			Files.writeString(fixture.alpha, """
					package example;
					public class Alpha {
					  public String get() { return "plain"; }
					}
					""", StandardCharsets.UTF_8);
			fixture.compile(fixture.alpha);
			fixture.assertSnapshots(List.of("example.Beta"));
		}
	}

	private static final class ClaimingProcessor extends AbstractProcessor {
		private final Set<String> claimed = new LinkedHashSet<>();

		@Override
		public Set<String> getSupportedAnnotationTypes() {
			return Set.of("com.soklet.annotation.GET", "example.Marker");
		}

		@Override
		public SourceVersion getSupportedSourceVersion() {
			return SourceVersion.latestSupported();
		}

		@Override
		public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment environment) {
			for (TypeElement annotation : annotations)
				claimed.add(annotation.getQualifiedName().toString());
			return true;
		}
	}
}

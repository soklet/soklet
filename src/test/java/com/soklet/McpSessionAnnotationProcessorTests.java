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
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** Exact session revision selection is preserved and validated during generation. */
class McpSessionAnnotationProcessorTests {
	@Test
	void generatesOnlyTheExplicitSessionRevisionsAtAMixedEndpoint() throws IOException {
		Compilation compilation = compile("""
				@McpServerEndpoint(path = "/mcp", name = "catalog", version = "1",
				    protocolVersions = {McpProtocolVersion.V2025_06_18,
				        McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28},
				    sessionProtocolVersions = {McpProtocolVersion.V2025_06_18,
				        McpProtocolVersion.V2025_11_25})
				""");
		assertThat(compilation).succeeded();
		String generated = compilation.generatedSourceFiles().get(0).getCharContent(true).toString();
		assertTrue(generated.contains("endpointBuilder.sessionProtocolVersions(java.util.Set.of(com.soklet.McpProtocolVersion.V2025_06_18, com.soklet.McpProtocolVersion.V2025_11_25));"), generated);
	}

	@Test
	void defaultDoesNotEnableSessionsOrGenerateASetter() throws IOException {
		Compilation compilation = compile("""
				@McpServerEndpoint(path = "/mcp", name = "catalog", version = "1",
				    protocolVersions = McpProtocolVersion.V2025_11_25)
				""");
		assertThat(compilation).succeeded();
		assertFalse(compilation.generatedSourceFiles().get(0).getCharContent(true).toString()
				.contains("endpointBuilder.sessionProtocolVersions("));
	}

	@Test
	void rejectsModernAndUnimplementedSessionSelections() {
		for (McpProtocolVersion version : new McpProtocolVersion[]{
				McpProtocolVersion.V2026_07_28, McpProtocolVersion.V2025_03_26}) {
			Compilation compilation = compile("""
					@McpServerEndpoint(path = "/mcp", name = "catalog", version = "1",
					    protocolVersions = {McpProtocolVersion.V2025_06_18, McpProtocolVersion.%1$s},
					    sessionProtocolVersions = McpProtocolVersion.%1$s)
					""".formatted(version.name()));
			assertThat(compilation).failed();
			assertThat(compilation).hadErrorContaining("sessionProtocolVersions currently supports only MCP protocol revisions 2025-06-18 and 2025-11-25");
		}
	}

	@Test
	void rejectsAnEligibleRevisionThatTheEndpointDoesNotServe() {
		Compilation compilation = compile("""
				@McpServerEndpoint(path = "/mcp", name = "catalog", version = "1",
				    protocolVersions = McpProtocolVersion.V2025_06_18,
				    sessionProtocolVersions = McpProtocolVersion.V2025_11_25)
				""");
		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("sessionProtocolVersions must be a subset of @McpServerEndpoint protocolVersions");
	}

	@Test
	void generatesLegacyDeliverySelectionOnlyWithTheMatchingSessionRevisions() throws IOException {
		Compilation compilation = compile("""
				@McpServerEndpoint(path = "/mcp", name = "catalog", version = "1",
				    protocolVersions = {McpProtocolVersion.V2025_06_18,
				        McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28},
				    sessionProtocolVersions = {McpProtocolVersion.V2025_06_18,
				        McpProtocolVersion.V2025_11_25},
				    subscriptionProtocolVersions = {McpProtocolVersion.V2025_06_18,
				        McpProtocolVersion.V2025_11_25, McpProtocolVersion.V2026_07_28})
				""");
		assertThat(compilation).succeeded();
		String generated = compilation.generatedSourceFiles().get(0).getCharContent(true).toString();
		assertTrue(generated.contains("endpointBuilder.subscriptionProtocolVersions(java.util.Set.of(com.soklet.McpProtocolVersion.V2025_06_18, com.soklet.McpProtocolVersion.V2025_11_25, com.soklet.McpProtocolVersion.V2026_07_28));"), generated);
		assertTrue(generated.contains("endpointBuilder.sessionProtocolVersions(java.util.Set.of(com.soklet.McpProtocolVersion.V2025_06_18, com.soklet.McpProtocolVersion.V2025_11_25));"), generated);
	}

	@Test
	void rejectsLegacyDeliveryWithAbsentOrDifferentSessionSelection() {
		for (String sessions : new String[]{"", "sessionProtocolVersions = McpProtocolVersion.V2025_06_18,"}) {
			Compilation compilation = compile("""
					@McpServerEndpoint(path = "/mcp", name = "catalog", version = "1",
					    protocolVersions = {McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25},
					    %s
					    subscriptionProtocolVersions = McpProtocolVersion.V2025_11_25)
					""".formatted(sessions));
			assertThat(compilation).failed();
			assertThat(compilation).hadErrorContaining("legacy subscriptionProtocolVersions must be a subset of @McpServerEndpoint sessionProtocolVersions");
		}
	}

	@Test
	void modernSubscriptionsDoNotRequireSessionSelection() {
		Compilation compilation = compile("""
				@McpServerEndpoint(path = "/mcp", name = "catalog", version = "1",
				    protocolVersions = McpProtocolVersion.V2026_07_28,
				    subscriptionProtocolVersions = McpProtocolVersion.V2026_07_28)
				""");
		assertThat(compilation).succeeded();
	}

	private static Compilation compile(String annotation) {
		return Compiler.javac().withProcessors(new SokletProcessor()).compile(
				JavaFileObjects.forSourceString("example.SessionEndpoint", """
						package example;
						import com.soklet.*;
						import com.soklet.annotation.*;
						%s
						public final class SessionEndpoint {}
						""".formatted(annotation)));
	}
}

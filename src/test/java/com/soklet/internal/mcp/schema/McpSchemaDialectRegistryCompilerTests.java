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

package com.soklet.internal.mcp.schema;

import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class McpSchemaDialectRegistryCompilerTests {
	private static final String SUITE_ROOT =
			"com/soklet/internal/mcp/schema/json-schema-test-suite/";
	private static final URI NO_VALIDATION_URI = URI.create(
			"http://localhost:1234/draft2020-12/metaschema-no-validation.json");
	private static final URI OPTIONAL_VOCABULARY_URI = URI.create(
			"http://localhost:1234/draft2020-12/metaschema-optional-vocabulary.json");
	private static final URI UNKNOWN_VOCABULARY_URI = URI.create(
			"http://localhost:1234/draft/2020-12/vocab/custom");
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(2_000_000, 256, 1_000_000, 1_000_000,
					10_000, 100_000, 250_000, 2_000_000));
	private static final McpSchemaCompilationLimits LIMITS =
			limitsWithVocabularyCount(1_000);

	@Test
	public void extractsPinnedCustomDialectsWithExactVocabularySemantics()
			throws IOException {
		McpSchemaDialectRegistry registry = compiler(LIMITS).compile(List.of(
				pinnedMetaSchema("metaschema-no-validation.json"),
				pinnedMetaSchema("metaschema-optional-vocabulary.json")));

		McpSchemaDialect noValidation = registry.find(NO_VALIDATION_URI)
				.orElseThrow();
		Assertions.assertEquals(Map.of(
				McpSchemaVocabulary.APPLICATOR.uri(), true,
				McpSchemaVocabulary.CORE.uri(), true),
				noValidation.vocabularyRequirements());
		Assertions.assertTrue(noValidation.uses(McpSchemaVocabulary.CORE));
		Assertions.assertTrue(noValidation.uses(McpSchemaVocabulary.APPLICATOR));
		Assertions.assertFalse(noValidation.uses(McpSchemaVocabulary.VALIDATION));

		McpSchemaDialect optionalVocabulary = registry.find(
				OPTIONAL_VOCABULARY_URI).orElseThrow();
		Assertions.assertEquals(Boolean.FALSE,
				optionalVocabulary.vocabularyRequirements().get(
						UNKNOWN_VOCABULARY_URI));
		Assertions.assertTrue(optionalVocabulary.uses(McpSchemaVocabulary.CORE));
		Assertions.assertTrue(optionalVocabulary.uses(
				McpSchemaVocabulary.VALIDATION));
		Assertions.assertFalse(optionalVocabulary.uses(
				McpSchemaVocabulary.APPLICATOR));
	}

	@Test
	public void knownVocabularyRemainsActiveWhenDeclaredOptional() {
		URI dialectUri = URI.create("https://meta.example.test/known-optional");
		McpSchemaDialectRegistry registry = compile(metaSchema(dialectUri, """
				{
				  "https://json-schema.org/draft/2020-12/vocab/core": true,
				  "https://json-schema.org/draft/2020-12/vocab/validation": false
				}
				"""));

		McpSchemaDialect dialect = registry.find(dialectUri).orElseThrow();
		Assertions.assertTrue(dialect.uses(McpSchemaVocabulary.VALIDATION));
		Assertions.assertEquals(Boolean.FALSE,
				dialect.vocabularyRequirements().get(
						McpSchemaVocabulary.VALIDATION.uri()));
	}

	@Test
	public void unknownRequiredVocabularyFailsClosedButOptionalIsRetained() {
		URI requiredUri = URI.create("https://meta.example.test/unknown-required");
		McpSchemaCompilationException required = Assertions.assertThrows(
				McpSchemaCompilationException.class,
				() -> compile(metaSchema(requiredUri, """
						{
						  "https://json-schema.org/draft/2020-12/vocab/core": true,
						  "https://vocabulary.example.test/unknown": true
						}
						""")));
		Assertions.assertEquals(
				McpSchemaCompilationException.Kind.UNSUPPORTED_VOCABULARY,
				required.kind());
		Assertions.assertEquals("$vocabulary",
				required.keyword().orElseThrow());
		Assertions.assertEquals(requiredUri,
				required.location().orElseThrow().retrievalUri());

		URI optionalUri = URI.create("https://meta.example.test/unknown-optional");
		McpSchemaDialect optional = compile(metaSchema(optionalUri, """
				{
				  "https://json-schema.org/draft/2020-12/vocab/core": true,
				  "https://vocabulary.example.test/unknown": false
				}
				""")).find(optionalUri).orElseThrow();
		Assertions.assertEquals(Boolean.FALSE,
				optional.vocabularyRequirements().get(URI.create(
						"https://vocabulary.example.test/unknown")));
	}

	@Test
	public void malformedVocabularyDeclarationsAreTypedFailures() {
		List<String> invalidDeclarations = List.of(
				"true",
				"{}",
				"{\"https://json-schema.org/draft/2020-12/vocab/core\":false}",
				"{\"https://json-schema.org/draft/2020-12/vocab/core\":1}",
				"{\"relative-vocabulary\":false,"
						+ "\"https://json-schema.org/draft/2020-12/vocab/core\":true}",
				"{\"HTTPS://JSON-SCHEMA.ORG/draft/2020-12/vocab/core\":true}");

		for (int index = 0; index < invalidDeclarations.size(); ++index) {
			URI dialectUri = URI.create(
					"https://meta.example.test/invalid-" + index);
			String invalidDeclaration = invalidDeclarations.get(index);
			McpSchemaCompilationException exception = Assertions.assertThrows(
					McpSchemaCompilationException.class,
					() -> compile(metaSchema(dialectUri,
							invalidDeclaration)), invalidDeclaration);
			Assertions.assertEquals(
					McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
					exception.kind());
			Assertions.assertEquals("$vocabulary",
					exception.keyword().orElseThrow());
		}
	}

	@Test
	public void missingVocabularyDefaultsToCoreOnlyIncludingBooleanMetaSchemas() {
		URI objectUri = URI.create("https://meta.example.test/default-object");
		URI booleanUri = URI.create("https://meta.example.test/default-boolean");
		McpSchemaDocument objectMetaSchema = document(objectUri, """
				{
				  "$schema":"https://json-schema.org/draft/2020-12/schema",
				  "$id":"https://meta.example.test/default-object"
				}
				""");
		McpSchemaDocument booleanMetaSchema = document(booleanUri, "true");

		McpSchemaDialectRegistry registry = compile(objectMetaSchema,
				booleanMetaSchema);
		for (URI dialectUri : List.of(objectUri, booleanUri))
			Assertions.assertEquals(Map.of(
					McpSchemaVocabulary.CORE.uri(), true),
					registry.find(dialectUri).orElseThrow()
							.vocabularyRequirements());
	}

	@Test
	public void vocabularyDeclarationIsNeverInheritedThroughComposition() {
		List<String> compositions = List.of(
				"\"$ref\":\"https://json-schema.org/draft/2020-12/schema\"",
				"\"allOf\":[{\"$ref\":"
						+ "\"https://json-schema.org/draft/2020-12/schema\"}]");

		for (int index = 0; index < compositions.size(); ++index) {
			URI dialectUri = URI.create(
					"https://meta.example.test/non-inherited-" + index);
			McpSchemaDocument document = document(dialectUri, """
					{
					  "$schema":"https://json-schema.org/draft/2020-12/schema",
					  "$id":"%s",
					  %s
					}
					""".formatted(dialectUri, compositions.get(index)));

			McpSchemaDialect dialect = compile(document).find(dialectUri)
					.orElseThrow();
			Assertions.assertEquals(Map.of(
					McpSchemaVocabulary.CORE.uri(), true),
					dialect.vocabularyRequirements(), compositions.get(index));
		}
	}

	@Test
	public void vocabularyDeclarationIsRejectedInEveryDiscoveredSubschema() {
		List<String> nestedSchemas = List.of(
				"""
				{"$vocabulary":%s}
				""".formatted(coreVocabulary()),
				"""
				{
				  "$id":"https://meta.example.test/embedded",
				  "$vocabulary":%s
				}
				""".formatted(coreVocabulary()));

		for (int index = 0; index < nestedSchemas.size(); ++index) {
			URI dialectUri = URI.create(
					"https://meta.example.test/misplaced-" + index);
			McpSchemaDocument document = document(dialectUri, """
					{
					  "$schema":"https://json-schema.org/draft/2020-12/schema",
					  "$id":"%s",
					  "$vocabulary":%s,
					  "$defs":{"nested":%s}
					}
					""".formatted(dialectUri, coreVocabulary(),
						nestedSchemas.get(index)));

			McpSchemaCompilationException exception = Assertions.assertThrows(
					McpSchemaCompilationException.class,
					() -> compile(document), nestedSchemas.get(index));
			Assertions.assertEquals(
					McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
					exception.kind());
			Assertions.assertEquals("$vocabulary",
					exception.keyword().orElseThrow());
			Assertions.assertEquals("/$defs/nested",
					exception.location().orElseThrow().jsonPointer());
		}
	}

	@Test
	public void vocabularyDeclarationLimitIsCheckedAtTheExactBoundary() {
		URI dialectUri = URI.create("https://meta.example.test/bounded");
		McpSchemaDocument document = metaSchema(dialectUri, """
				{
				  "https://json-schema.org/draft/2020-12/vocab/core": true,
				  "https://vocabulary.example.test/optional": false
				}
				""");

		McpSchemaDialectRegistry exact = compiler(
				limitsWithVocabularyCount(2)).compile(List.of(document));
		Assertions.assertTrue(exact.find(dialectUri).isPresent());

		McpSchemaCompilationException oneOver = Assertions.assertThrows(
				McpSchemaCompilationException.class,
				() -> compiler(limitsWithVocabularyCount(1))
						.compile(List.of(document)));
		Assertions.assertEquals(
				McpSchemaCompilationException.Kind.LIMIT_EXCEEDED,
				oneOver.kind());
		Assertions.assertEquals(
				McpSchemaCompilationException.Limit.VOCABULARY_COUNT,
				oneOver.limit().orElseThrow());
		Assertions.assertEquals("$vocabulary",
				oneOver.keyword().orElseThrow());
	}

	@Test
	public void registryOrderAndOutputsAreImmutableAndInputOrderIndependent() {
		McpSchemaDocument first = metaSchema(
				URI.create("https://meta.example.test/a"), coreVocabulary());
		McpSchemaDocument second = metaSchema(
				URI.create("https://meta.example.test/z"), coreVocabulary());

		McpSchemaDialectRegistry forward = compiler(LIMITS).compile(
				List.of(first, second));
		McpSchemaDialectRegistry reverse = compiler(LIMITS).compile(
				List.of(second, first));
		Assertions.assertEquals(forward.dialects(), reverse.dialects());
		Assertions.assertEquals(new ArrayList<>(forward.dialects().keySet()),
				forward.dialects().keySet().stream()
						.sorted(java.util.Comparator.comparing(URI::toASCIIString))
						.toList());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> forward.dialects().clear());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> forward.find(first.retrievalUri()).orElseThrow()
						.vocabularyRequirements().clear());
	}

	@Test
	public void retrievalAndEmptyFragmentAliasesResolveToCanonicalDialect() {
		URI retrievalUri = URI.create(
				"https://meta.example.test/retrieved-dialect");
		URI canonicalUri = URI.create(
				"https://meta.example.test/canonical-dialect");
		McpSchemaDocument metaSchema = document(retrievalUri, """
				{
				  "$schema":"https://json-schema.org/draft/2020-12/schema",
				  "$id":"https://meta.example.test/canonical-dialect#",
				  "$vocabulary":%s
				}
				""".formatted(coreVocabulary()));
		McpSchemaDialectRegistry registry = compile(metaSchema);

		McpSchemaDialect canonical = registry.find(canonicalUri).orElseThrow();
		Assertions.assertEquals(canonical,
				registry.find(retrievalUri).orElseThrow());
		Assertions.assertEquals(canonical, registry.find(URI.create(
				"https://meta.example.test/canonical-dialect#")).orElseThrow());
		Assertions.assertEquals(Map.of(retrievalUri, canonicalUri),
				registry.aliases());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> registry.aliases().clear());

		for (String dialectIdentifier : List.of(retrievalUri.toASCIIString(),
				canonicalUri.toASCIIString() + "#")) {
			URI schemaUri = URI.create("https://schema.example.test/alias-"
					+ Math.abs(dialectIdentifier.hashCode()));
			McpSchemaResourceGraph graph = new McpSchemaResourceGraphCompiler(
					LIMITS, registry).compile(List.of(document(schemaUri, """
						{"$schema":"%s"}
						""".formatted(dialectIdentifier))));
			Assertions.assertEquals(canonicalUri,
					graph.resources().get(0).dialectUri());
		}
	}

	@Test
	public void customMetaSchemaListIsBoundedBeforeCopying() {
		McpSchemaDocument document = metaSchema(
				URI.create("https://meta.example.test/hostile-list"),
				coreVocabulary());
		AtomicInteger sizeCalls = new AtomicInteger();
		List<McpSchemaDocument> changesSizeAfterFirstRead = new AbstractList<>() {
			@Override
			public McpSchemaDocument get(int index) {
				if (index != 0)
					throw new AssertionError(
							"Only the captured in-limit size may be traversed.");
				return document;
			}

			@Override
			public int size() {
				return sizeCalls.getAndIncrement() == 0 ? 1 : Integer.MAX_VALUE;
			}
		};

		McpSchemaDialectRegistry registry = compiler(LIMITS).compile(
				changesSizeAfterFirstRead);
		Assertions.assertTrue(registry.find(document.retrievalUri()).isPresent());
		Assertions.assertEquals(1, sizeCalls.get());

		List<McpSchemaDocument> oversizedWithoutTraversal = new AbstractList<>() {
			@Override
			public McpSchemaDocument get(int index) {
				throw new AssertionError("An over-limit list must not be traversed.");
			}

			@Override
			public int size() {
				return LIMITS.maximumDocumentCount() + 1;
			}
		};
		McpSchemaCompilationException exception = Assertions.assertThrows(
				McpSchemaCompilationException.class,
				() -> compiler(LIMITS).compile(oversizedWithoutTraversal));
		Assertions.assertEquals(
				McpSchemaCompilationException.Limit.DOCUMENT_COUNT,
				exception.limit().orElseThrow());
	}

	@Test
	public void ordinarySchemaVocabularyKeywordDoesNotCreateOrAlterADialect() {
		URI retrievalUri = URI.create("https://schema.example.test/ordinary");
		McpSchemaResourceGraph graph = new McpSchemaResourceGraphCompiler(LIMITS)
				.compile(List.of(document(retrievalUri, """
						{
						  "$vocabulary": {
						    "https://vocabulary.example.test/unknown": true
						  },
						  "type":"number"
						}
						""")));
		McpSchemaValidationProgram program =
				new McpSchemaValidationProgramCompiler().compile(graph);
		McpSchemaValidationOutcome outcome = new McpSchemaEvaluator().evaluate(
				program, graph.documentRoots().get(retrievalUri),
				JSON_CODEC.parse("\"not a number\""),
				new McpSchemaEvaluationLimits(100, 10, 100, 10, 10, 10_000));

		Assertions.assertInstanceOf(McpSchemaValidationOutcome.Invalid.class,
				outcome);
		Assertions.assertEquals(McpSchemaDialectRegistry.DRAFT_2020_12_URI,
				graph.resources().get(0).dialectUri());
	}

	@Test
	public void validationAndApplicatorSemanticsFollowVocabularyPresence() {
		URI coreOnlyUri = URI.create("https://meta.example.test/core-runtime");
		URI validationUri = URI.create(
				"https://meta.example.test/validation-runtime");
		URI applicatorUri = URI.create(
				"https://meta.example.test/applicator-runtime");
		McpSchemaDialectRegistry registry = compile(
				metaSchema(coreOnlyUri, coreVocabulary()),
				metaSchema(validationUri, """
						{
						  "https://json-schema.org/draft/2020-12/vocab/core": true,
						  "https://json-schema.org/draft/2020-12/vocab/validation": false
						}
						"""),
				metaSchema(applicatorUri, """
						{
						  "https://json-schema.org/draft/2020-12/vocab/core": true,
						  "https://json-schema.org/draft/2020-12/vocab/applicator": true
						}
						"""));

		Assertions.assertInstanceOf(McpSchemaValidationOutcome.Valid.class,
				evaluate(registry, coreOnlyUri,
						"{\"type\":1,\"enum\":true,\"properties\":1}",
						"false"));
		Assertions.assertInstanceOf(McpSchemaValidationOutcome.Invalid.class,
				evaluate(registry, validationUri, "{\"type\":\"number\"}",
						"\"value\""));
		Assertions.assertInstanceOf(McpSchemaValidationOutcome.Invalid.class,
				evaluate(registry, applicatorUri,
						"{\"properties\":{\"blocked\":false},\"type\":1}",
						"{\"blocked\":null}"));
	}

	@Test
	public void builtInRegistryExactlyMatchesPinnedDefaultVocabularyMap() {
		McpJsonObject root = (McpJsonObject) McpSchemaDraft202012Bundle.documents()
				.stream()
				.filter(document -> document.retrievalUri().equals(
						McpSchemaDialectRegistry.DRAFT_2020_12_URI))
				.findFirst().orElseThrow().rootSchema();
		McpJsonObject declaration = (McpJsonObject) root.members()
				.get("$vocabulary");
		Map<URI, Boolean> pinned = new LinkedHashMap<>();
		for (Map.Entry<String, McpJsonValue> entry
				: declaration.members().entrySet())
			pinned.put(URI.create(entry.getKey()),
					entry.getValue() == McpJsonBoolean.TRUE);

		Assertions.assertEquals(pinned,
				McpSchemaDialectRegistry.draft202012().defaultDialect()
						.vocabularyRequirements());
	}

	private static McpSchemaDialectRegistry compile(
			McpSchemaDocument... metaSchemas) {
		return compiler(LIMITS).compile(List.of(metaSchemas));
	}

	private static McpSchemaDialectRegistryCompiler compiler(
			McpSchemaCompilationLimits limits) {
		return new McpSchemaDialectRegistryCompiler(limits);
	}

	private static McpSchemaDocument pinnedMetaSchema(String fileName)
			throws IOException {
		return new McpSchemaDocument(URI.create(
				"http://localhost:1234/draft2020-12/" + fileName),
				readJson("remotes/draft2020-12/" + fileName));
	}

	private static McpSchemaDocument metaSchema(URI uri,
			String vocabularyJson) {
		return document(uri, """
				{
				  "$schema":"https://json-schema.org/draft/2020-12/schema",
				  "$id":"%s",
				  "$vocabulary":%s
				}
				""".formatted(uri, vocabularyJson));
	}

	private static String coreVocabulary() {
		return """
				{
				  "https://json-schema.org/draft/2020-12/vocab/core": true
				}
				""";
	}

	private static McpSchemaDocument document(URI uri, String json) {
		return new McpSchemaDocument(uri, JSON_CODEC.parse(json));
	}

	private static McpSchemaValidationOutcome evaluate(
			McpSchemaDialectRegistry registry, URI dialectUri, String schema,
			String instance) {
		URI retrievalUri = URI.create("https://schema.example.test/runtime/"
				+ Math.abs(schema.hashCode()));
		McpJsonObject object = (McpJsonObject) JSON_CODEC.parse(schema);
		Map<String, McpJsonValue> members = new LinkedHashMap<>();
		members.put("$schema", new McpJsonString(dialectUri.toASCIIString()));
		members.putAll(object.members());
		McpSchemaResourceGraph graph = new McpSchemaResourceGraphCompiler(
				LIMITS, registry).compile(List.of(new McpSchemaDocument(
						retrievalUri, new McpJsonObject(members))));
		McpSchemaValidationProgram program =
				new McpSchemaValidationProgramCompiler(registry).compile(graph);
		return new McpSchemaEvaluator().evaluate(program,
				graph.documentRoots().get(retrievalUri), JSON_CODEC.parse(instance),
				new McpSchemaEvaluationLimits(10_000, 100, 1_000, 100,
						100, 100_000));
	}

	private static McpJsonValue readJson(String relativePath)
			throws IOException {
		String resourceName = SUITE_ROOT + relativePath;
		try (InputStream input = McpSchemaDialectRegistryCompilerTests.class
				.getClassLoader().getResourceAsStream(resourceName)) {
			Assertions.assertNotNull(input, resourceName);
			return JSON_CODEC.parse(input.readAllBytes());
		}
	}

	private static McpSchemaCompilationLimits limitsWithVocabularyCount(
			int maximumVocabularyDeclarationCount) {
		return new McpSchemaCompilationLimits(64, 20_000, 256, 100_000,
				2_000, 4_000, 4_000, 20_000, 8_192, 1_024,
				maximumVocabularyDeclarationCount);
	}
}

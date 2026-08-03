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

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.AbstractList;
import java.util.List;
import java.util.Optional;

public class McpSchemaCompilationLimitTests {
	private static final McpJsonCodec JSON_CODEC = new McpJsonCodec(
			new McpJsonLimits(4_096, 32, 1_024, 1_024, 128, 10_000,
					1_024, 4_096));
	private static final McpSchemaCompilationLimits GENEROUS_LIMITS =
			new McpSchemaCompilationLimits(64, 64, 64, 64, 64, 64, 64, 64,
					64, 64, 64);

	@Test
	public void compilationLimitsRequireEveryMaximumToBePositive() {
		McpSchemaCompilationLimits distinct = new McpSchemaCompilationLimits(
				1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
		Assertions.assertAll(
				() -> Assertions.assertEquals(1, distinct.maximumDocumentCount()),
				() -> Assertions.assertEquals(2, distinct.maximumSchemaNodeCount()),
				() -> Assertions.assertEquals(3, distinct.maximumSchemaDepth()),
				() -> Assertions.assertEquals(4, distinct.maximumKeywordCount()),
				() -> Assertions.assertEquals(5, distinct.maximumResourceCount()),
				() -> Assertions.assertEquals(6,
						distinct.maximumResourceIdentifierCount()),
				() -> Assertions.assertEquals(7, distinct.maximumAnchorCount()),
				() -> Assertions.assertEquals(8, distinct.maximumReferenceCount()),
				() -> Assertions.assertEquals(9,
						distinct.maximumUriLengthInCharacters()),
				() -> Assertions.assertEquals(10,
						distinct.maximumPointerSegmentCount()),
				() -> Assertions.assertEquals(11,
						distinct.maximumVocabularyDeclarationCount()));

		for (McpSchemaCompilationException.Limit limit
				: McpSchemaCompilationException.Limit.values()) {
			for (int nonPositive : List.of(0, -1)) {
				IllegalArgumentException exception = Assertions.assertThrows(
						IllegalArgumentException.class,
						() -> limitsWith(limit, nonPositive), limit.name());
				Assertions.assertTrue(
						exception.getMessage().contains(fieldName(limit)),
						exception::getMessage);
			}
		}
	}

	@Test
	public void recursiveSchemaDepthCannotBeConfiguredAboveTheSafeCeiling() {
		IllegalArgumentException exception = Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> new McpSchemaCompilationLimits(1, 1, 257, 1, 1, 1,
						1, 1, 1, 1, 1));

		Assertions.assertTrue(exception.getMessage().contains("maximumSchemaDepth"));
	}

	@Test
	public void documentCountAcceptsTheLimitAndRejectsOneOver() {
		McpSchemaDocument first = schema("urn:a", "true");
		McpSchemaDocument second = schema("urn:b", "false");

		McpSchemaResourceGraph graph = compile(
				limitsWith(McpSchemaCompilationException.Limit.DOCUMENT_COUNT, 2),
				first, second);
		Assertions.assertEquals(2, graph.documentRoots().size());

		McpSchemaCompilationException exception = compileFails(
				McpSchemaCompilationException.Limit.DOCUMENT_COUNT, 1,
				first, second);
		assertLimit(exception, McpSchemaCompilationException.Limit.DOCUMENT_COUNT,
				null, List.of(), null);

		List<McpSchemaDocument> oversizedWithoutSafeTraversal = new AbstractList<>() {
			@Override
			public McpSchemaDocument get(int index) {
				throw new AssertionError("An over-limit list must not be traversed.");
			}

			@Override
			public int size() {
				return 2;
			}
		};
		McpSchemaCompilationException preCopyFailure = Assertions.assertThrows(
				McpSchemaCompilationException.class,
				() -> new McpSchemaResourceGraphCompiler(limitsWith(
						McpSchemaCompilationException.Limit.DOCUMENT_COUNT, 1))
						.compile(oversizedWithoutSafeTraversal));
		assertLimit(preCopyFailure,
				McpSchemaCompilationException.Limit.DOCUMENT_COUNT,
				null, List.of(), null);
	}

	@Test
	public void schemaNodeCountAcceptsTheLimitAndRejectsOneOver() {
		McpSchemaDocument document = schema("urn:nodes", "{\"not\":true}");

		McpSchemaResourceGraph graph = compile(
				limitsWith(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT, 2),
				document);
		Assertions.assertEquals(2, graph.nodes().size());

		McpSchemaCompilationException exception = compileFails(
				McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT, 1, document);
		assertLimit(exception,
				McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
				URI.create("urn:nodes"), List.of("not"), null);
	}

	@Test
	public void schemaMapWidthIsPreflightedBeforeEntryCopyingAndSorting() {
		McpSchemaDocument document = schema("urn:wide-map",
				"{\"properties\":{\"z\":true,\"a\":true}}");

		McpSchemaResourceGraph exact = compile(
				limitsWith(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT, 3),
				document);
		Assertions.assertEquals(3, exact.nodes().size());

		McpSchemaCompilationException oneOver = compileFails(
				McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT, 2, document);
		assertLimit(oneOver,
				McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
				URI.create("urn:wide-map"), List.of(), "properties");
	}

	@Test
	public void schemaDepthAcceptsTheLimitAndRejectsOneOver() {
		McpSchemaDocument document = schema("urn:depth", "{\"not\":true}");

		McpSchemaResourceGraph graph = compile(
				limitsWith(McpSchemaCompilationException.Limit.SCHEMA_DEPTH, 2),
				document);
		Assertions.assertEquals(2, graph.nodes().size());

		McpSchemaCompilationException exception = compileFails(
				McpSchemaCompilationException.Limit.SCHEMA_DEPTH, 1, document);
		assertLimit(exception, McpSchemaCompilationException.Limit.SCHEMA_DEPTH,
				URI.create("urn:depth"), List.of("not"), null);
	}

	@Test
	public void keywordCountAcceptsTheLimitAndRejectsOneOver() {
		McpSchemaDocument document = schema("urn:keywords",
				"{\"title\":\"A\",\"description\":\"B\"}");

		McpSchemaResourceGraph graph = compile(
				limitsWith(McpSchemaCompilationException.Limit.KEYWORD_COUNT, 2),
				document);
		Assertions.assertEquals(1, graph.nodes().size());

		McpSchemaCompilationException exception = compileFails(
				McpSchemaCompilationException.Limit.KEYWORD_COUNT, 1, document);
		assertLimit(exception, McpSchemaCompilationException.Limit.KEYWORD_COUNT,
				URI.create("urn:keywords"), List.of(), null);
	}

	@Test
	public void resourceCountAcceptsTheLimitAndRejectsOneOver() {
		McpSchemaDocument document = schema("urn:resources",
				"{\"$defs\":{\"embedded\":{\"$id\":\"urn:embedded\"}}}");

		McpSchemaResourceGraph graph = compile(
				limitsWith(McpSchemaCompilationException.Limit.RESOURCE_COUNT, 2),
				document);
		Assertions.assertEquals(2, graph.resources().size());

		McpSchemaCompilationException exception = compileFails(
				McpSchemaCompilationException.Limit.RESOURCE_COUNT, 1, document);
		assertLimit(exception, McpSchemaCompilationException.Limit.RESOURCE_COUNT,
				URI.create("urn:resources"), List.of("$defs", "embedded"), "$id");
	}

	@Test
	public void resourceIdentifierCountAcceptsTheLimitAndRejectsOneOver() {
		McpSchemaDocument document = schema("urn:retrieval",
				"{\"$id\":\"urn:canonical\"}");

		McpSchemaResourceGraph graph = compile(limitsWith(
				McpSchemaCompilationException.Limit.RESOURCE_IDENTIFIER_COUNT, 2),
				document);
		Assertions.assertEquals(2, graph.resourceIdentifiers().size());

		McpSchemaCompilationException exception = compileFails(
				McpSchemaCompilationException.Limit.RESOURCE_IDENTIFIER_COUNT, 1,
				document);
		assertLimit(exception,
				McpSchemaCompilationException.Limit.RESOURCE_IDENTIFIER_COUNT,
				URI.create("urn:retrieval"), List.of(), "$id");
	}

	@Test
	public void anchorCountAcceptsTheLimitAndRejectsOneOver() {
		McpSchemaDocument document = schema("urn:anchors",
				"{\"$anchor\":\"plain\",\"$dynamicAnchor\":\"dynamic\"}");

		McpSchemaResourceGraph graph = compile(
				limitsWith(McpSchemaCompilationException.Limit.ANCHOR_COUNT, 2),
				document);
		Assertions.assertEquals(2, graph.resources().get(0).anchors().size());

		McpSchemaCompilationException exception = compileFails(
				McpSchemaCompilationException.Limit.ANCHOR_COUNT, 1, document);
		assertLimit(exception, McpSchemaCompilationException.Limit.ANCHOR_COUNT,
				URI.create("urn:anchors"), List.of(), "$dynamicAnchor");
	}

	@Test
	public void referenceCountAcceptsTheLimitAndRejectsOneOver() {
		McpSchemaDocument document = schema("urn:references",
				"{\"$ref\":\"#\",\"$dynamicRef\":\"#\"}");

		McpSchemaResourceGraph graph = compile(
				limitsWith(McpSchemaCompilationException.Limit.REFERENCE_COUNT, 2),
				document);
		McpCompiledSchemaNode root = graph.nodes().get(0);
		Assertions.assertTrue(root.reference().isPresent());
		Assertions.assertTrue(root.dynamicReference().isPresent());

		McpSchemaCompilationException exception = compileFails(
				McpSchemaCompilationException.Limit.REFERENCE_COUNT, 1, document);
		assertLimit(exception, McpSchemaCompilationException.Limit.REFERENCE_COUNT,
				URI.create("urn:references"), List.of(), "$dynamicRef");
	}

	@Test
	public void uriLengthAcceptsTheLimitAndRejectsOneOver() {
		McpSchemaDocument document = schema("urn:x", "{\"$id\":\"urn:xy\"}");

		McpSchemaResourceGraph graph = compile(
				limitsWith(McpSchemaCompilationException.Limit.URI_LENGTH, 6),
				document);
		Assertions.assertEquals(URI.create("urn:xy"),
				graph.resources().get(0).canonicalUri());

		McpSchemaCompilationException exception = compileFails(
				McpSchemaCompilationException.Limit.URI_LENGTH, 5, document);
		assertLimit(exception, McpSchemaCompilationException.Limit.URI_LENGTH,
				URI.create("urn:x"), List.of(), "$id");

		McpSchemaDocument anchored = schema("urn:a", "{\"$anchor\":\"x\"}");
		McpSchemaResourceGraph anchoredAtLimit = compile(
				limitsWith(McpSchemaCompilationException.Limit.URI_LENGTH, 7),
				anchored);
		Assertions.assertTrue(anchoredAtLimit.resources().get(0).anchors()
				.containsKey("x"));
		McpSchemaCompilationException composedAnchorFailure = compileFails(
				McpSchemaCompilationException.Limit.URI_LENGTH, 6, anchored);
		assertLimit(composedAnchorFailure,
				McpSchemaCompilationException.Limit.URI_LENGTH,
				URI.create("urn:a"), List.of(), "$anchor");
	}

	@Test
	public void pointerSegmentCountAcceptsTheLimitAndRejectsOneOver() {
		McpSchemaDocument document = schema("urn:pointer",
				"{\"properties\":{\"value\":true}}");

		McpSchemaResourceGraph graph = compile(limitsWith(
				McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT, 2),
				document);
		Assertions.assertEquals(List.of("properties", "value"),
				graph.nodes().get(1).location().pointerSegments());

		McpSchemaCompilationException exception = compileFails(
				McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT, 1,
				document);
		assertLimit(exception,
				McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT,
				URI.create("urn:pointer"), List.of("properties", "value"), null);
	}

	@Test
	public void referencePointerSegmentsAreBoundedBeforeSegmentAllocation() {
		McpSchemaDocument document = schema("urn:reference-pointer",
				"{\"$ref\":\"#/missing/nested\"}");

		McpSchemaCompilationException exception = compileFails(
				McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT, 1,
				document);
		assertLimit(exception,
				McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT,
				URI.create("urn:reference-pointer"), List.of(), "$ref");
	}

	private static McpSchemaDocument schema(String retrievalUri, String json) {
		return new McpSchemaDocument(URI.create(retrievalUri), JSON_CODEC.parse(json));
	}

	private static McpSchemaResourceGraph compile(
			McpSchemaCompilationLimits limits, McpSchemaDocument... documents) {
		return new McpSchemaResourceGraphCompiler(limits).compile(List.of(documents));
	}

	private static McpSchemaCompilationException compileFails(
			McpSchemaCompilationException.Limit limit, int maximum,
			McpSchemaDocument... documents) {
		return Assertions.assertThrows(McpSchemaCompilationException.class,
				() -> compile(limitsWith(limit, maximum), documents));
	}

	private static void assertLimit(McpSchemaCompilationException exception,
			McpSchemaCompilationException.Limit limit, URI retrievalUri,
			List<String> pointerSegments, String keyword) {
		Assertions.assertAll(
				() -> Assertions.assertEquals(
						McpSchemaCompilationException.Kind.LIMIT_EXCEEDED,
						exception.kind()),
				() -> Assertions.assertEquals(Optional.of(limit), exception.limit()),
				() -> Assertions.assertEquals(
						retrievalUri == null ? Optional.empty()
								: Optional.of(new McpSchemaLocation(retrievalUri,
										pointerSegments)),
						exception.location()),
				() -> Assertions.assertEquals(
						keyword == null ? Optional.empty() : Optional.of(keyword),
						exception.keyword()),
				() -> Assertions.assertFalse(exception.getMessage().isBlank()));
	}

	private static McpSchemaCompilationLimits limitsWith(
			McpSchemaCompilationException.Limit limit, int maximum) {
		return new McpSchemaCompilationLimits(
				select(limit, McpSchemaCompilationException.Limit.DOCUMENT_COUNT,
						maximum, GENEROUS_LIMITS.maximumDocumentCount()),
				select(limit, McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
						maximum, GENEROUS_LIMITS.maximumSchemaNodeCount()),
				select(limit, McpSchemaCompilationException.Limit.SCHEMA_DEPTH,
						maximum, GENEROUS_LIMITS.maximumSchemaDepth()),
				select(limit, McpSchemaCompilationException.Limit.KEYWORD_COUNT,
						maximum, GENEROUS_LIMITS.maximumKeywordCount()),
				select(limit, McpSchemaCompilationException.Limit.RESOURCE_COUNT,
						maximum, GENEROUS_LIMITS.maximumResourceCount()),
				select(limit,
						McpSchemaCompilationException.Limit.RESOURCE_IDENTIFIER_COUNT,
						maximum,
						GENEROUS_LIMITS.maximumResourceIdentifierCount()),
				select(limit, McpSchemaCompilationException.Limit.ANCHOR_COUNT,
						maximum, GENEROUS_LIMITS.maximumAnchorCount()),
				select(limit, McpSchemaCompilationException.Limit.REFERENCE_COUNT,
						maximum, GENEROUS_LIMITS.maximumReferenceCount()),
				select(limit, McpSchemaCompilationException.Limit.URI_LENGTH,
						maximum, GENEROUS_LIMITS.maximumUriLengthInCharacters()),
				select(limit,
						McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT,
						maximum, GENEROUS_LIMITS.maximumPointerSegmentCount()),
				select(limit, McpSchemaCompilationException.Limit.VOCABULARY_COUNT,
						maximum,
						GENEROUS_LIMITS.maximumVocabularyDeclarationCount()));
	}

	private static int select(McpSchemaCompilationException.Limit requested,
			McpSchemaCompilationException.Limit field, int selected,
			int fallback) {
		return requested == field ? selected : fallback;
	}

	private static String fieldName(McpSchemaCompilationException.Limit limit) {
		return switch (limit) {
			case DOCUMENT_COUNT -> "maximumDocumentCount";
			case SCHEMA_NODE_COUNT -> "maximumSchemaNodeCount";
			case SCHEMA_DEPTH -> "maximumSchemaDepth";
			case KEYWORD_COUNT -> "maximumKeywordCount";
			case RESOURCE_COUNT -> "maximumResourceCount";
			case RESOURCE_IDENTIFIER_COUNT -> "maximumResourceIdentifierCount";
			case ANCHOR_COUNT -> "maximumAnchorCount";
			case REFERENCE_COUNT -> "maximumReferenceCount";
			case URI_LENGTH -> "maximumUriLengthInCharacters";
			case POINTER_SEGMENT_COUNT -> "maximumPointerSegmentCount";
			case VOCABULARY_COUNT -> "maximumVocabularyDeclarationCount";
		};
	}
}

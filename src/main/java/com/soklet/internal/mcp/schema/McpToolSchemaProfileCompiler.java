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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Strict compiler for Soklet MCP Tool Schema Profile 1.
 *
 * <p>The compiler accepts one self-contained object document. It rejects every
 * keyword outside the closed profile and resolves only same-document JSON
 * Pointer and plain-name-anchor references.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpToolSchemaProfileCompiler {
	@NonNull
	static final String DRAFT_2020_12_URI =
			"https://json-schema.org/draft/2020-12/schema";
	@NonNull
	private static final Set<@NonNull String> SUPPORTED_KEYWORDS = Set.of(
			"$schema", "$defs", "$anchor", "$ref", "$comment",
			"properties", "additionalProperties", "items", "allOf",
			"anyOf", "if", "then", "else", "type", "enum", "const",
			"required", "minimum", "maximum", "title", "description",
			"default", "examples", "deprecated", "readOnly", "writeOnly",
			"format", "x-mcp-header");
	@NonNull
	private static final Set<@NonNull String> EXPLICITLY_REJECTED_KEYWORDS = Set.of(
			"$id", "$vocabulary", "$dynamicAnchor", "$dynamicRef", "oneOf",
			"not", "dependentRequired", "dependentSchemas", "prefixItems",
			"contains", "minContains", "maxContains", "pattern",
			"patternProperties", "propertyNames", "minLength", "maxLength",
			"minItems", "maxItems", "uniqueItems", "minProperties",
			"maxProperties", "multipleOf", "exclusiveMinimum",
			"exclusiveMaximum", "unevaluatedItems", "unevaluatedProperties",
			"contentEncoding", "contentMediaType", "contentSchema");

	@NonNull
	private final McpSchemaCompilationLimits limits;

	McpToolSchemaProfileCompiler(@NonNull McpSchemaCompilationLimits limits) {
		this.limits = requireNonNull(limits);
	}

	@NonNull
	McpToolSchemaProfileProgram compile(@NonNull McpJsonObject document) {
		return new Compilation(requireNonNull(document)).compile();
	}

	@NonNull
	static Set<@NonNull String> supportedKeywords() {
		return SUPPORTED_KEYWORDS;
	}

	@NonNull
	static Set<@NonNull String> explicitlyRejectedKeywords() {
		return EXPLICITLY_REJECTED_KEYWORDS;
	}

	@NotThreadSafe
	private final class Compilation {
		@NonNull
		private final McpJsonObject document;
		@NonNull
		private final List<@NonNull NodeBuilder> nodes;
		@NonNull
		private final Map<@NonNull String, @NonNull McpSchemaNodeId> nodesByPointer;
		@NonNull
		private final Map<@NonNull String, @NonNull McpSchemaNodeId> anchors;
		@NonNull
		private final List<@NonNull UnresolvedReference> references;
		@NonNull
		private final Map<@NonNull String, @NonNull String> headersByPointer;
		private int keywordCount;
		private int anchorCount;

		private Compilation(@NonNull McpJsonObject document) {
			this.document = document;
			this.nodes = new ArrayList<>();
			this.nodesByPointer = new LinkedHashMap<>();
			this.anchors = new LinkedHashMap<>();
			this.references = new ArrayList<>();
			this.headersByPointer = new LinkedHashMap<>();
		}

		@NonNull
		private McpToolSchemaProfileProgram compile() {
			McpSchemaNodeId root = compileSchema(document,
					McpSchemaLocation.root(), 1, true);
			resolveReferences();
			List<McpToolSchemaProfileNode> frozenNodes = new ArrayList<>(nodes.size());
			for (NodeBuilder node : nodes)
				frozenNodes.add(node.freeze());
			return new McpToolSchemaProfileProgram(document, root, frozenNodes,
					headersByPointer);
		}

		@NonNull
		private McpSchemaNodeId compileSchema(@NonNull McpJsonValue schema,
				@NonNull McpSchemaLocation location, int depth,
				boolean documentRoot) {
			checkNodeAndDepth(location, depth);
			McpSchemaNodeId id = new McpSchemaNodeId(nodes.size());
			NodeBuilder builder = new NodeBuilder(id, location);
			nodes.add(builder);
			if (nodesByPointer.put(location.jsonPointer(), id) != null)
				throw new IllegalStateException("A profile schema pointer is duplicated.");

			if (schema instanceof McpJsonBoolean booleanSchema) {
				if (documentRoot)
					throw failure(McpSchemaCompilationException.Kind.INVALID_SCHEMA,
							"A Profile 1 document root must be a JSON object.",
							location, null);
				builder.booleanSchema = Optional.of(booleanSchema);
				return id;
			}
			if (!(schema instanceof McpJsonObject object))
				throw failure(McpSchemaCompilationException.Kind.INVALID_SCHEMA,
						"A schema must be an object or boolean.", location, null);

			validateKeywordNames(object, location);
			validateDialect(object, location, documentRoot);
			validateAnnotations(object, location);
			registerAnchor(object, location, id);
			registerHeader(object, location);
			builder.acceptedTypes = readTypes(object, location);
			builder.directType = readDirectType(object);
			builder.constant = Optional.ofNullable(object.members().get("const"));
			builder.enumeration = readEnumeration(object, location);
			builder.requiredProperties = readRequired(object, location);
			builder.minimum = readNumber(object, "minimum", location);
			builder.maximum = readNumber(object, "maximum", location);

			compileMapChildren(object, "$defs", location, depth,
					builder.ignoredDefinitionSchemas);
			compileMapChildren(object, "properties", location, depth,
					builder.propertySchemas);
			builder.additionalPropertiesSchema = compileSingleChild(object,
					"additionalProperties", location, depth);
			builder.itemSchema = compileSingleChild(object, "items", location,
					depth);
			builder.allOfSchemas = compileArrayChildren(object, "allOf", location,
					depth);
			builder.anyOfSchemas = compileArrayChildren(object, "anyOf", location,
					depth);
			builder.ifSchema = compileSingleChild(object, "if", location, depth);
			builder.thenSchema = compileSingleChild(object, "then", location,
					depth);
			builder.elseSchema = compileSingleChild(object, "else", location,
					depth);
			collectReference(object, location, id);
			return id;
		}

		private void validateKeywordNames(@NonNull McpJsonObject schema,
				@NonNull McpSchemaLocation location) {
			if ((long) keywordCount + schema.members().size()
					> limits.maximumKeywordCount())
				throw limit(McpSchemaCompilationException.Limit.KEYWORD_COUNT,
						"Profile keyword count exceeds its configured limit.",
						location, null);
			keywordCount += schema.members().size();

			List<String> keywords = new ArrayList<>(schema.members().keySet());
			Collections.sort(keywords);
			for (String keyword : keywords) {
				if (!SUPPORTED_KEYWORDS.contains(keyword))
					throw failure(
							McpSchemaCompilationException.Kind.UNSUPPORTED_KEYWORD,
							"The schema uses a keyword outside Soklet MCP Tool Schema Profile 1.",
							location, keyword);
			}
		}

		private void validateDialect(@NonNull McpJsonObject schema,
				@NonNull McpSchemaLocation location, boolean documentRoot) {
			McpJsonValue value = schema.members().get("$schema");
			if (value == null)
				return;
			if (!documentRoot)
				throw failure(McpSchemaCompilationException.Kind.MISPLACED_DIALECT,
						"The Profile 1 dialect declaration is allowed only at the document root.",
						location, "$schema");
			if (!(value instanceof McpJsonString dialect)
					|| !DRAFT_2020_12_URI.equals(dialect.value()))
				throw failure(McpSchemaCompilationException.Kind.UNSUPPORTED_DIALECT,
						"Profile 1 accepts only the canonical Draft 2020-12 dialect URI.",
						location, "$schema");
		}

		private void validateAnnotations(@NonNull McpJsonObject schema,
				@NonNull McpSchemaLocation location) {
			for (String keyword : List.of("$comment", "title", "description",
					"format")) {
				McpJsonValue value = schema.members().get(keyword);
				if (value != null && !(value instanceof McpJsonString))
					throw invalidKeywordValue(location, keyword,
							"The annotation must contain a string.");
			}
			McpJsonValue examples = schema.members().get("examples");
			if (examples != null && !(examples instanceof McpJsonArray))
				throw invalidKeywordValue(location, "examples",
						"The examples annotation must contain an array.");
			for (String keyword : List.of("deprecated", "readOnly", "writeOnly")) {
				McpJsonValue value = schema.members().get(keyword);
				if (value != null && !(value instanceof McpJsonBoolean))
					throw invalidKeywordValue(location, keyword,
							"The annotation must contain a boolean.");
			}
		}

		private void registerAnchor(@NonNull McpJsonObject schema,
				@NonNull McpSchemaLocation location,
				@NonNull McpSchemaNodeId id) {
			McpJsonValue value = schema.members().get("$anchor");
			if (value == null)
				return;
			if (!(value instanceof McpJsonString anchor)
					|| !validAnchorName(anchor.value()))
				throw failure(McpSchemaCompilationException.Kind.INVALID_ANCHOR,
						"An anchor must use the Draft 2020-12 plain-name syntax.",
						location, "$anchor");
			if (anchor.value().length()
					> limits.maximumAnchorNameLengthInCharacters())
				throw limit(McpSchemaCompilationException.Limit.ANCHOR_NAME_LENGTH,
						"An anchor exceeds its configured character limit.",
						location, "$anchor");
			if (anchorCount >= limits.maximumAnchorCount())
				throw limit(McpSchemaCompilationException.Limit.ANCHOR_COUNT,
						"Profile anchor count exceeds its configured limit.",
						location, "$anchor");
			anchorCount++;
			if (anchors.putIfAbsent(anchor.value(), id) != null)
				throw failure(McpSchemaCompilationException.Kind.DUPLICATE_ANCHOR,
						"An anchor name is duplicated in the schema document.",
						location, "$anchor");
		}

		private void registerHeader(@NonNull McpJsonObject schema,
				@NonNull McpSchemaLocation location) {
			McpJsonValue value = schema.members().get("x-mcp-header");
			if (value == null)
				return;
			if (!(value instanceof McpJsonString header))
				throw invalidKeywordValue(location, "x-mcp-header",
						"x-mcp-header must contain a string.");
			checkNameLength(header.value(), location, "x-mcp-header");
			headersByPointer.put(location.jsonPointer(), header.value());
		}

		@NonNull
		private Optional<@NonNull McpSchemaType> readDirectType(
				@NonNull McpJsonObject schema) {
			McpJsonValue value = schema.members().get("type");
			if (!(value instanceof McpJsonString name))
				return Optional.empty();
			return McpSchemaType.fromSchemaName(name.value());
		}

		@NonNull
		private Set<@NonNull McpSchemaType> readTypes(
				@NonNull McpJsonObject schema,
				@NonNull McpSchemaLocation location) {
			McpJsonValue value = schema.members().get("type");
			if (value == null)
				return Set.of();
			EnumSet<McpSchemaType> types = EnumSet.noneOf(McpSchemaType.class);
			Set<String> names = new LinkedHashSet<>();
			if (value instanceof McpJsonString name) {
				addType(name, names, types, location);
			} else if (value instanceof McpJsonArray array
					&& !array.values().isEmpty()) {
				for (McpJsonValue element : array.values()) {
					if (!(element instanceof McpJsonString name))
						throw invalidType(location);
					addType(name, names, types, location);
				}
			} else {
				throw invalidType(location);
			}
			return types;
		}

		private void addType(@NonNull McpJsonString name,
				@NonNull Set<@NonNull String> names,
				@NonNull Set<@NonNull McpSchemaType> types,
				@NonNull McpSchemaLocation location) {
			McpSchemaType type = McpSchemaType.fromSchemaName(name.value())
					.orElseThrow(() -> invalidType(location));
			if (!names.add(name.value()))
				throw invalidType(location);
			types.add(type);
		}

		@NonNull
		private Optional<@NonNull List<@NonNull McpJsonValue>> readEnumeration(
				@NonNull McpJsonObject schema,
				@NonNull McpSchemaLocation location) {
			McpJsonValue value = schema.members().get("enum");
			if (value == null)
				return Optional.empty();
			if (!(value instanceof McpJsonArray array))
				throw invalidKeywordValue(location, "enum",
						"The enum keyword must contain an array.");
			checkCollectionWidth(array.values().size(), location, "enum");
			return Optional.of(array.values());
		}

		@NonNull
		private List<@NonNull String> readRequired(
				@NonNull McpJsonObject schema,
				@NonNull McpSchemaLocation location) {
			McpJsonValue value = schema.members().get("required");
			if (value == null)
				return List.of();
			if (!(value instanceof McpJsonArray array))
				throw invalidRequired(location);
			checkCollectionWidth(array.values().size(), location, "required");
			Set<String> names = new LinkedHashSet<>();
			for (McpJsonValue element : array.values()) {
				if (!(element instanceof McpJsonString name))
					throw invalidRequired(location);
				checkNameLength(name.value(), location, "required");
				if (!names.add(name.value()))
					throw invalidRequired(location);
			}
			List<String> sorted = new ArrayList<>(names);
			Collections.sort(sorted);
			return List.copyOf(sorted);
		}

		@NonNull
		private Optional<@NonNull BigDecimal> readNumber(
				@NonNull McpJsonObject schema, @NonNull String keyword,
				@NonNull McpSchemaLocation location) {
			McpJsonValue value = schema.members().get(keyword);
			if (value == null)
				return Optional.empty();
			if (!(value instanceof McpJsonNumber number))
				throw invalidKeywordValue(location, keyword,
						"The numeric bound must contain a JSON number.");
			return Optional.of(number.value());
		}

		private void compileMapChildren(@NonNull McpJsonObject schema,
				@NonNull String keyword, @NonNull McpSchemaLocation location,
				int depth,
				@NonNull Map<@NonNull String, @NonNull McpSchemaNodeId> destination) {
			McpJsonValue value = schema.members().get(keyword);
			if (value == null)
				return;
			if (!(value instanceof McpJsonObject map))
				throw invalidKeywordValue(location, keyword,
						"The keyword must contain an object of schemas.");
			checkCollectionWidth(map.members().size(), location, keyword);
			if (!map.members().isEmpty()) {
				checkImmediateChildDepth(depth, location, keyword);
				checkImmediatePointerCapacity(2, location, keyword);
				checkPointerSegmentLength(keyword, location, keyword);
				checkImmediateNodeCapacity(map.members().size(), location, keyword);
			}
			for (Map.Entry<String, McpJsonValue> entry
					: map.members().entrySet()) {
				checkNameLength(entry.getKey(), location, keyword);
				checkPointerSegmentLength(entry.getKey(), location, keyword);
				if (!isSchema(entry.getValue()))
					throw invalidKeywordValue(location, keyword,
							"Every member must be an object or boolean schema.");
			}
			List<String> names = new ArrayList<>(map.members().keySet());
			Collections.sort(names);
			for (String name : names) {
				McpJsonValue child = requireNonNull(map.members().get(name));
				destination.put(name, compileSchema(child,
						location.child(keyword, name), depth + 1, false));
			}
		}

		@NonNull
		private Optional<@NonNull McpSchemaNodeId> compileSingleChild(
				@NonNull McpJsonObject schema, @NonNull String keyword,
				@NonNull McpSchemaLocation location, int depth) {
			McpJsonValue value = schema.members().get(keyword);
			if (value == null)
				return Optional.empty();
			if (!isSchema(value))
				throw invalidKeywordValue(location, keyword,
						"The keyword must contain an object or boolean schema.");
			checkImmediateChildDepth(depth, location, keyword);
			checkImmediatePointerCapacity(1, location, keyword);
			checkPointerSegmentLength(keyword, location, keyword);
			checkImmediateNodeCapacity(1, location, keyword);
			return Optional.of(compileSchema(value, location.child(keyword),
					depth + 1, false));
		}

		@NonNull
		private List<@NonNull McpSchemaNodeId> compileArrayChildren(
				@NonNull McpJsonObject schema, @NonNull String keyword,
				@NonNull McpSchemaLocation location, int depth) {
			McpJsonValue value = schema.members().get(keyword);
			if (value == null)
				return List.of();
			if (!(value instanceof McpJsonArray array) || array.values().isEmpty())
				throw invalidKeywordValue(location, keyword,
						"The keyword must contain a non-empty array of schemas.");
			checkCollectionWidth(array.values().size(), location, keyword);
			checkImmediateChildDepth(depth, location, keyword);
			checkImmediatePointerCapacity(2, location, keyword);
			checkPointerSegmentLength(keyword, location, keyword);
			checkPointerSegmentLength(Integer.toString(array.values().size() - 1),
					location, keyword);
			checkImmediateNodeCapacity(array.values().size(), location, keyword);
			for (McpJsonValue child : array.values()) {
				if (!isSchema(child))
					throw invalidKeywordValue(location, keyword,
							"Every array member must be an object or boolean schema.");
			}
			List<McpSchemaNodeId> children = new ArrayList<>(array.values().size());
			for (int index = 0; index < array.values().size(); ++index) {
				McpJsonValue child = array.values().get(index);
				children.add(compileSchema(child,
						location.child(keyword, Integer.toString(index)), depth + 1,
						false));
			}
			return List.copyOf(children);
		}

		private void collectReference(@NonNull McpJsonObject schema,
				@NonNull McpSchemaLocation location,
				@NonNull McpSchemaNodeId source) {
			McpJsonValue value = schema.members().get("$ref");
			if (value == null)
				return;
			if (!(value instanceof McpJsonString reference)
					|| !reference.value().startsWith("#"))
				throw failure(McpSchemaCompilationException.Kind.INVALID_REFERENCE,
						"Profile 1 references must target the same schema document.",
						location, "$ref");
			if (reference.value().length()
					> limits.maximumReferenceLengthInCharacters())
				throw limit(McpSchemaCompilationException.Limit.REFERENCE_LENGTH,
						"A schema reference exceeds its configured character limit.",
						location, "$ref");
			validateRawFragmentSyntax(reference.value().substring(1), location);
			if (references.size() >= limits.maximumReferenceCount())
				throw limit(McpSchemaCompilationException.Limit.REFERENCE_COUNT,
						"Profile reference count exceeds its configured limit.",
						location, "$ref");
			references.add(new UnresolvedReference(source, reference.value(),
					location));
		}

		private void resolveReferences() {
			for (UnresolvedReference reference : references) {
				String fragment = decodeFragment(reference.value().substring(1),
						reference.location());
				McpSchemaNodeId target;
				if (fragment.isEmpty()) {
					target = nodesByPointer.get("");
				} else if (fragment.startsWith("/")) {
					target = nodesByPointer.get(decodePointer(fragment,
							reference.location()));
				} else {
					if (!validAnchorName(fragment))
						throw failure(
								McpSchemaCompilationException.Kind.INVALID_REFERENCE,
								"A plain-name reference contains an invalid anchor name.",
								reference.location(), "$ref");
					target = anchors.get(fragment);
				}
				if (target == null)
					throw failure(
							McpSchemaCompilationException.Kind.UNRESOLVED_REFERENCE,
							"A local schema reference does not resolve to a Profile 1 schema node.",
							reference.location(), "$ref");
				nodes.get(reference.source().value()).referenceTarget =
						Optional.of(target);
			}
		}

		@NonNull
		private String decodeFragment(@NonNull String rawFragment,
				@NonNull McpSchemaLocation location) {
			FragmentDecoder decoder = new FragmentDecoder(rawFragment.length(),
					location);
			for (int index = 0; index < rawFragment.length();) {
				char character = rawFragment.charAt(index);
				if (character != '%') {
					decoder.appendCodePoint(character);
					index++;
					continue;
				}
				PercentDecodedCodePoint codePoint = decodePercentEncodedCodePoint(
						rawFragment, index, location);
				decoder.appendCodePoint(codePoint.value());
				index = codePoint.nextIndex();
			}
			return decoder.value();
		}

		@NonNull
		private PercentDecodedCodePoint decodePercentEncodedCodePoint(
				@NonNull String rawFragment, int index,
				@NonNull McpSchemaLocation location) {
			int first = percentEncodedByte(rawFragment, index, 0, location);
			int byteCount;
			if (first <= 0x7F)
				byteCount = 1;
			else if (first >= 0xC2 && first <= 0xDF)
				byteCount = 2;
			else if (first >= 0xE0 && first <= 0xEF)
				byteCount = 3;
			else if (first >= 0xF0 && first <= 0xF4)
				byteCount = 4;
			else
				throw invalidReferenceEncoding(location);

			int second = byteCount >= 2
					? percentEncodedByte(rawFragment, index, 1, location) : 0;
			int third = byteCount >= 3
					? percentEncodedByte(rawFragment, index, 2, location) : 0;
			int fourth = byteCount == 4
					? percentEncodedByte(rawFragment, index, 3, location) : 0;
			if ((byteCount >= 2 && !utf8ContinuationByte(second))
					|| (byteCount >= 3 && !utf8ContinuationByte(third))
					|| (byteCount == 4 && !utf8ContinuationByte(fourth))
					|| (first == 0xE0 && second < 0xA0)
					|| (first == 0xED && second > 0x9F)
					|| (first == 0xF0 && second < 0x90)
					|| (first == 0xF4 && second > 0x8F))
				throw invalidReferenceEncoding(location);

			int value;
			if (byteCount == 1) {
				value = first;
			} else if (byteCount == 2) {
				value = ((first & 0x1F) << 6) | (second & 0x3F);
			} else if (byteCount == 3) {
				value = ((first & 0x0F) << 12) | ((second & 0x3F) << 6)
						| (third & 0x3F);
			} else {
				value = ((first & 0x07) << 18) | ((second & 0x3F) << 12)
						| ((third & 0x3F) << 6) | (fourth & 0x3F);
			}
			return new PercentDecodedCodePoint(value, index + byteCount * 3);
		}

		private int percentEncodedByte(@NonNull String rawFragment, int index,
				int byteIndex, @NonNull McpSchemaLocation location) {
			int relativeOffset = byteIndex * 3;
			if (rawFragment.length() - index < relativeOffset + 3)
				throw invalidReferenceEncoding(location);
			int offset = index + relativeOffset;
			if (rawFragment.charAt(offset) != '%')
				throw invalidReferenceEncoding(location);
			int high = Character.digit(rawFragment.charAt(offset + 1), 16);
			int low = Character.digit(rawFragment.charAt(offset + 2), 16);
			if (high < 0 || low < 0)
				throw invalidReferenceEncoding(location);
			return high * 16 + low;
		}

		private void validateRawFragmentSyntax(@NonNull String fragment,
				@NonNull McpSchemaLocation location) {
			for (int index = 0; index < fragment.length(); ++index) {
				char character = fragment.charAt(index);
				if (character == '%') {
					if (index + 2 >= fragment.length()
							|| !asciiHexDigit(fragment.charAt(index + 1))
							|| !asciiHexDigit(fragment.charAt(index + 2)))
						throw invalidReferenceEncoding(location);
					index += 2;
					continue;
				}
				if (!rfc3986FragmentCharacter(character))
					throw failure(
							McpSchemaCompilationException.Kind.INVALID_REFERENCE,
							"A local schema reference contains a character not permitted in an RFC 3986 fragment.",
							location, "$ref");
			}
		}

		@NonNull
		private String decodePointer(@NonNull String fragment,
				@NonNull McpSchemaLocation location) {
			int segmentCount = 1;
			for (int index = 1; index < fragment.length(); ++index) {
				if (fragment.charAt(index) == '/')
					segmentCount++;
			}
			if (segmentCount > limits.maximumPointerSegmentCount())
				throw limit(McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT,
						"A reference JSON Pointer exceeds its configured segment limit.",
						location, "$ref");

			List<String> segments = new ArrayList<>(segmentCount);
			int start = 1;
			for (int end = 1; end <= fragment.length(); ++end) {
				if (end < fragment.length() && fragment.charAt(end) != '/')
					continue;
				int decodedLength = decodedPointerSegmentLength(fragment, start,
						end, location);
				StringBuilder segment = new StringBuilder(decodedLength);
				for (int index = start; index < end; ++index) {
					char character = fragment.charAt(index);
					if (character != '~') {
						segment.append(character);
						continue;
					}
					if (index + 1 >= end)
						throw invalidPointer(location);
					char escaped = fragment.charAt(++index);
					if (escaped == '0')
						segment.append('~');
					else if (escaped == '1')
						segment.append('/');
					else
						throw invalidPointer(location);
				}
				segments.add(segment.toString());
				start = end + 1;
			}
			return new McpSchemaLocation(segments).jsonPointer();
		}

		private int decodedPointerSegmentLength(@NonNull String fragment,
				int start, int end, @NonNull McpSchemaLocation location) {
			int length = 0;
			for (int index = start; index < end; ++index) {
				if (fragment.charAt(index) == '~') {
					if (index + 1 >= end)
						throw invalidPointer(location);
					char escaped = fragment.charAt(++index);
					if (escaped != '0' && escaped != '1')
						throw invalidPointer(location);
				}
				if (length >= limits.maximumPointerSegmentLengthInCharacters())
					throw limit(
							McpSchemaCompilationException.Limit.POINTER_SEGMENT_LENGTH,
							"A reference JSON Pointer segment exceeds its configured character limit.",
							location, "$ref");
				length++;
			}
			return length;
		}

		@NotThreadSafe
		private final class FragmentDecoder {
			@NonNull
			private final McpSchemaLocation location;
			@NonNull
			private final StringBuilder decoded;
			private boolean firstCharacter = true;
			private boolean pointer;
			private int pointerSegmentCount;
			private long rawPointerSegmentLength;

			private FragmentDecoder(int rawLength,
					@NonNull McpSchemaLocation location) {
				this.location = location;
				this.decoded = new StringBuilder(Math.min(rawLength, 256));
			}

			private void appendCodePoint(int codePoint) {
				if (codePoint <= Character.MAX_VALUE) {
					appendCharacter((char) codePoint);
				} else {
					appendCharacter(Character.highSurrogate(codePoint));
					appendCharacter(Character.lowSurrogate(codePoint));
				}
			}

			private void appendCharacter(char character) {
				if (firstCharacter) {
					firstCharacter = false;
					if (character == '/') {
						pointer = true;
						pointerSegmentCount = 1;
						checkPointerSegmentCount();
					} else {
						checkAnchorReferenceLength();
					}
				} else if (pointer) {
					if (character == '/') {
						pointerSegmentCount++;
						rawPointerSegmentLength = 0;
						checkPointerSegmentCount();
					} else {
						long maximumRawLength = 2L
								* limits.maximumPointerSegmentLengthInCharacters();
						if (rawPointerSegmentLength >= maximumRawLength)
							throw pointerSegmentLengthLimit();
						rawPointerSegmentLength++;
					}
				} else {
					checkAnchorReferenceLength();
				}
				decoded.append(character);
			}

			private void checkAnchorReferenceLength() {
				if (decoded.length()
						>= limits.maximumAnchorNameLengthInCharacters())
					throw limit(
							McpSchemaCompilationException.Limit.ANCHOR_NAME_LENGTH,
							"A plain-name schema reference exceeds its configured character limit.",
							location, "$ref");
			}

			private void checkPointerSegmentCount() {
				if (pointerSegmentCount > limits.maximumPointerSegmentCount())
					throw limit(
							McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT,
							"A reference JSON Pointer exceeds its configured segment limit.",
							location, "$ref");
			}

			@NonNull
			private McpSchemaCompilationException pointerSegmentLengthLimit() {
				return limit(
						McpSchemaCompilationException.Limit.POINTER_SEGMENT_LENGTH,
						"A reference JSON Pointer segment exceeds its configured character limit.",
						location, "$ref");
			}

			@NonNull
			private String value() {
				return decoded.toString();
			}
		}

		private void checkNodeAndDepth(@NonNull McpSchemaLocation location,
				int depth) {
			if (nodes.size() >= limits.maximumSchemaNodeCount())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
						"Profile schema node count exceeds its configured limit.",
						location, null);
			if (depth > limits.maximumSchemaDepth())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_DEPTH,
						"Profile schema depth exceeds its configured limit.",
						location, null);
			if (location.pointerSegments().size()
					> limits.maximumPointerSegmentCount())
				throw limit(McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT,
						"A schema location exceeds its configured pointer segment limit.",
						location, null);
			for (String segment : location.pointerSegments()) {
				if (segment.length()
						> limits.maximumPointerSegmentLengthInCharacters())
					throw limit(
							McpSchemaCompilationException.Limit.POINTER_SEGMENT_LENGTH,
							"A schema location segment exceeds its configured character limit.",
							location, null);
			}
		}

		private void checkCollectionWidth(int size,
				@NonNull McpSchemaLocation location, @NonNull String keyword) {
			if (size > limits.maximumCollectionEntryCount())
				throw limit(
						McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
						"A Profile 1 schema collection exceeds its configured entry limit.",
						location, keyword);
		}

		private void checkImmediateNodeCapacity(int childCount,
				@NonNull McpSchemaLocation location, @NonNull String keyword) {
			if (childCount > limits.maximumSchemaNodeCount() - nodes.size())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
						"Profile schema node count exceeds its configured limit.",
						location, keyword);
		}

		private void checkImmediateChildDepth(int parentDepth,
				@NonNull McpSchemaLocation location, @NonNull String keyword) {
			if (parentDepth >= limits.maximumSchemaDepth())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_DEPTH,
						"Profile schema depth exceeds its configured limit.",
						location, keyword);
		}

		private void checkImmediatePointerCapacity(int additionalSegments,
				@NonNull McpSchemaLocation location, @NonNull String keyword) {
			if (additionalSegments > limits.maximumPointerSegmentCount()
					- location.pointerSegments().size())
				throw limit(
						McpSchemaCompilationException.Limit.POINTER_SEGMENT_COUNT,
						"A schema location exceeds its configured pointer segment limit.",
						location, keyword);
		}

		private void checkNameLength(@NonNull String name,
				@NonNull McpSchemaLocation location, @NonNull String keyword) {
			if (name.length() > limits.maximumNameLengthInCharacters())
				throw limit(McpSchemaCompilationException.Limit.NAME_LENGTH,
						"A Profile 1 name exceeds its configured character limit.",
						location, keyword);
		}

		private void checkPointerSegmentLength(@NonNull String segment,
				@NonNull McpSchemaLocation location, @NonNull String keyword) {
			if (segment.length()
					> limits.maximumPointerSegmentLengthInCharacters())
				throw limit(
						McpSchemaCompilationException.Limit.POINTER_SEGMENT_LENGTH,
						"A schema location segment exceeds its configured character limit.",
						location, keyword);
		}

		@NonNull
		private McpSchemaCompilationException invalidType(
				@NonNull McpSchemaLocation location) {
			return invalidKeywordValue(location, "type",
					"The type keyword must contain a valid unique type name or a non-empty array of unique type names.");
		}

		@NonNull
		private McpSchemaCompilationException invalidRequired(
				@NonNull McpSchemaLocation location) {
			return invalidKeywordValue(location, "required",
					"The required keyword must contain an array of unique strings.");
		}

		@NonNull
		private McpSchemaCompilationException invalidPointer(
				@NonNull McpSchemaLocation location) {
			return failure(McpSchemaCompilationException.Kind.INVALID_REFERENCE,
					"A local schema reference contains an invalid JSON Pointer escape.",
					location, "$ref");
		}

		@NonNull
		private McpSchemaCompilationException invalidReferenceEncoding(
				@NonNull McpSchemaLocation location) {
			return failure(McpSchemaCompilationException.Kind.INVALID_REFERENCE,
					"A local schema reference contains invalid percent-encoded UTF-8.",
					location, "$ref");
		}
	}

	private static boolean isSchema(@NonNull McpJsonValue value) {
		return value instanceof McpJsonObject || value instanceof McpJsonBoolean;
	}

	private static boolean validAnchorName(@NonNull String value) {
		if (value.isEmpty() || !anchorInitial(value.charAt(0)))
			return false;
		for (int index = 1; index < value.length(); ++index) {
			char character = value.charAt(index);
			if (!anchorInitial(character)
					&& !(character >= '0' && character <= '9')
					&& character != '-' && character != '.')
				return false;
		}
		return true;
	}

	private static boolean anchorInitial(char character) {
		return (character >= 'A' && character <= 'Z')
				|| (character >= 'a' && character <= 'z')
				|| character == '_';
	}

	private static boolean asciiHexDigit(char character) {
		return (character >= '0' && character <= '9')
				|| (character >= 'A' && character <= 'F')
				|| (character >= 'a' && character <= 'f');
	}

	private static boolean utf8ContinuationByte(int value) {
		return value >= 0x80 && value <= 0xBF;
	}

	private static boolean rfc3986FragmentCharacter(char character) {
		return (character >= 'A' && character <= 'Z')
				|| (character >= 'a' && character <= 'z')
				|| (character >= '0' && character <= '9')
				|| "-._~!$&'()*+,;=:@/?".indexOf(character) >= 0;
	}

	@NonNull
	private static McpSchemaCompilationException invalidKeywordValue(
			@NonNull McpSchemaLocation location, @NonNull String keyword,
			@NonNull String message) {
		return failure(McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
				message, location, keyword);
	}

	@NonNull
	private static McpSchemaCompilationException failure(
			McpSchemaCompilationException.@NonNull Kind kind,
			@NonNull String message,
			@Nullable McpSchemaLocation location, @Nullable String keyword) {
		return new McpSchemaCompilationException(kind, message, location, keyword);
	}

	@NonNull
	private static McpSchemaCompilationException limit(
			McpSchemaCompilationException.@NonNull Limit limit,
			@NonNull String message,
			@Nullable McpSchemaLocation location, @Nullable String keyword) {
		return new McpSchemaCompilationException(limit, message, location, keyword);
	}

	private record UnresolvedReference(@NonNull McpSchemaNodeId source,
			@NonNull String value, @NonNull McpSchemaLocation location) {
		private UnresolvedReference {
			requireNonNull(source);
			requireNonNull(value);
			requireNonNull(location);
		}
	}

	private record PercentDecodedCodePoint(int value, int nextIndex) {
	}

	@NotThreadSafe
	private static final class NodeBuilder {
		@NonNull
		private final McpSchemaNodeId id;
		@NonNull
		private final McpSchemaLocation location;
		@NonNull
		private Optional<@NonNull McpJsonBoolean> booleanSchema = Optional.empty();
		@NonNull
		private Set<@NonNull McpSchemaType> acceptedTypes = Set.of();
		@NonNull
		private Optional<@NonNull McpSchemaType> directType = Optional.empty();
		@NonNull
		private Optional<@NonNull McpJsonValue> constant = Optional.empty();
		@NonNull
		private Optional<@NonNull List<@NonNull McpJsonValue>> enumeration =
				Optional.empty();
		@NonNull
		private final Map<@NonNull String, @NonNull McpSchemaNodeId> propertySchemas =
				new LinkedHashMap<>();
		@NonNull
		private final Map<@NonNull String, @NonNull McpSchemaNodeId> ignoredDefinitionSchemas =
				new LinkedHashMap<>();
		@NonNull
		private List<@NonNull String> requiredProperties = List.of();
		@NonNull
		private Optional<@NonNull McpSchemaNodeId> additionalPropertiesSchema =
				Optional.empty();
		@NonNull
		private Optional<@NonNull McpSchemaNodeId> itemSchema = Optional.empty();
		@NonNull
		private List<@NonNull McpSchemaNodeId> allOfSchemas = List.of();
		@NonNull
		private List<@NonNull McpSchemaNodeId> anyOfSchemas = List.of();
		@NonNull
		private Optional<@NonNull McpSchemaNodeId> ifSchema = Optional.empty();
		@NonNull
		private Optional<@NonNull McpSchemaNodeId> thenSchema = Optional.empty();
		@NonNull
		private Optional<@NonNull McpSchemaNodeId> elseSchema = Optional.empty();
		@NonNull
		private Optional<@NonNull BigDecimal> minimum = Optional.empty();
		@NonNull
		private Optional<@NonNull BigDecimal> maximum = Optional.empty();
		@NonNull
		private Optional<@NonNull McpSchemaNodeId> referenceTarget = Optional.empty();

		private NodeBuilder(@NonNull McpSchemaNodeId id,
				@NonNull McpSchemaLocation location) {
			this.id = requireNonNull(id);
			this.location = requireNonNull(location);
		}

		@NonNull
		private McpToolSchemaProfileNode freeze() {
			return new McpToolSchemaProfileNode(id, location, booleanSchema,
					acceptedTypes, directType, constant, enumeration, propertySchemas,
					requiredProperties, additionalPropertiesSchema, itemSchema,
					allOfSchemas, anyOfSchemas, ifSchema, thenSchema, elseSchema,
					minimum, maximum, referenceTarget);
		}
	}
}

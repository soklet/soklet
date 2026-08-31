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

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable concrete-resource catalog descriptor.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpNormalizedResourceDescriptor(@NonNull String uri,
		@NonNull String name, @NonNull McpJsonObject descriptorFields,
		@NonNull McpJsonObject metadata,
		@NonNull McpResourceCachePolicy readCachePolicy) {
	@NonNull
	private static final Set<@NonNull String> RESERVED_DESCRIPTOR_FIELDS = Set.of(
			"uri", "name", "_meta");

	McpNormalizedResourceDescriptor {
		uri = McpLevelOneUriTemplate.requireValidAbsoluteUri(uri,
				"Resource URI");
		name = McpProtocolSupport.requireNonBlank(name, "Resource name");
		descriptorFields = McpProtocolSupport.requireExtensionFields(
				descriptorFields, RESERVED_DESCRIPTOR_FIELDS);
		metadata = McpProtocolSupport.requireApplicationMetadataFields(
				metadata, Set.of());
		requireNonNull(readCachePolicy);
	}

	@NonNull
	static McpNormalizedResourceDescriptor minimal(@NonNull String uri) {
		return new McpNormalizedResourceDescriptor(uri, uri,
				McpJsonObject.empty(), McpJsonObject.empty(),
				McpResourceCachePolicy.privateNoCache());
	}

	@NonNull
	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>();
		values.put("uri", new McpJsonString(uri));
		values.put("name", new McpJsonString(name));
		values.putAll(descriptorFields.members());
		if (!metadata.members().isEmpty())
			values.put("_meta", metadata);
		return new McpJsonObject(values);
	}
}

/**
 * Immutable resource-template catalog descriptor and its parsed Level-1 route.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpNormalizedResourceTemplateDescriptor(@NonNull String uriTemplate,
		@NonNull String name, @NonNull McpJsonObject descriptorFields,
		@NonNull McpJsonObject metadata,
		@NonNull McpResourceCachePolicy readCachePolicy,
		@NonNull McpLevelOneUriTemplate parsedTemplate) {
	@NonNull
	private static final Set<@NonNull String> RESERVED_DESCRIPTOR_FIELDS = Set.of(
			"uriTemplate", "name", "_meta");

	McpNormalizedResourceTemplateDescriptor(@NonNull String uriTemplate,
			@NonNull String name, @NonNull McpJsonObject descriptorFields,
			@NonNull McpJsonObject metadata,
			@NonNull McpResourceCachePolicy readCachePolicy) {
		this(uriTemplate, name, descriptorFields, metadata, readCachePolicy,
				McpLevelOneUriTemplate.parse(uriTemplate));
	}

	McpNormalizedResourceTemplateDescriptor {
		uriTemplate = McpProtocolSupport.requireNonBlank(uriTemplate,
				"Resource URI template");
		name = McpProtocolSupport.requireNonBlank(name, "Resource template name");
		descriptorFields = McpProtocolSupport.requireExtensionFields(
				descriptorFields, RESERVED_DESCRIPTOR_FIELDS);
		metadata = McpProtocolSupport.requireApplicationMetadataFields(
				metadata, Set.of());
		requireNonNull(readCachePolicy);
		requireNonNull(parsedTemplate);
		if (!uriTemplate.equals(parsedTemplate.template()))
			throw new IllegalArgumentException(
					"Parsed resource URI template does not match its descriptor.");
	}

	@NonNull
	static McpNormalizedResourceTemplateDescriptor minimal(
			@NonNull String uriTemplate) {
		return new McpNormalizedResourceTemplateDescriptor(uriTemplate, uriTemplate,
				McpJsonObject.empty(), McpJsonObject.empty(),
				McpResourceCachePolicy.privateNoCache());
	}

	@NonNull
	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>();
		values.put("uriTemplate", new McpJsonString(uriTemplate));
		values.put("name", new McpJsonString(name));
		values.putAll(descriptorFields.members());
		if (!metadata.members().isEmpty())
			values.put("_meta", metadata);
		return new McpJsonObject(values);
	}
}

/**
 * Fixed cache ownership for a resource catalog or read registration.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpResourceCachePolicy(long timeToLiveMilliseconds,
		@NonNull McpCacheScope scope) {
	McpResourceCachePolicy {
		if (timeToLiveMilliseconds < 0L)
			throw new IllegalArgumentException("Resource cache TTL must be >= 0.");
		requireNonNull(scope);
	}

	@NonNull
	static McpResourceCachePolicy privateNoCache() {
		return new McpResourceCachePolicy(0L, McpCacheScope.PRIVATE);
	}
}

/**
 * Parsed RFC 6570 Level-1 URI template. Matching and overlap detection are
 * deliberately implemented without regular expressions so application values
 * never become regex source.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLevelOneUriTemplate {
	static final int MAXIMUM_RESOURCE_URI_UTF_8_BYTES = 1_048_576;
	static final int MAXIMUM_TEMPLATE_ROUTED_RESOURCE_URI_UTF_8_BYTES = 65_535;
	static final int MAXIMUM_TEMPLATE_COUNT_PER_ENDPOINT = 256;
	static final int MAXIMUM_TEMPLATE_UTF_8_BYTES = 8_192;
	static final int MAXIMUM_VARIABLE_COUNT = 32;
	static final int MAXIMUM_VARIABLE_NAME_UTF_8_BYTES = 128;
	static final long MAXIMUM_TEMPLATE_MATCH_DYNAMIC_PROGRAMMING_CELLS = 8_388_608L;
	static final int MAXIMUM_OVERLAP_COMPARISON_STATES = 65_536;
	static final int MAXIMUM_ENDPOINT_OVERLAP_COMPARISON_STATES = 1_048_576;

	private sealed interface Part permits LiteralPart, VariablePart {
	}

	private record LiteralPart(@NonNull String value) implements Part {
		private LiteralPart {
			requireNonNull(value);
		}
	}

	private record VariablePart(@NonNull String name) implements Part {
		private VariablePart {
			requireNonNull(name);
		}
	}

	private record MatchState(int leftIndex, int rightIndex) {
	}

	static final class OverlapComparisonBudget {
		private int remainingStates;
		private boolean exhausted;

		private OverlapComparisonBudget(int maximumStates) {
			this.remainingStates = maximumStates;
		}

		private boolean chargeState() {
			if (remainingStates == 0) {
				exhausted = true;
				return false;
			}
			--remainingStates;
			return true;
		}

		private void markExhausted() {
			exhausted = true;
		}

		boolean exhausted() {
			return exhausted;
		}
	}

	@ThreadSafe
	static final class NormalizedResourceUri {
		@NonNull
		private final String value;

		private NormalizedResourceUri(@NonNull String value) {
			this.value = requireNonNull(value);
		}
	}

	@ThreadSafe
	static final class PreparedResourceUri {
		@NonNull
		private final NormalizedResourceUri normalized;
		private final int @NonNull [] tokenLengths;

		private PreparedResourceUri(@NonNull NormalizedResourceUri normalized) {
			this.normalized = requireNonNull(normalized);
			this.tokenLengths = levelOneExpansionTokenLengths(normalized.value);
		}
	}

	private record OverlapAtom(@NonNull String value, boolean wildcard,
			boolean variableConsumable) {
		private OverlapAtom {
			requireNonNull(value);
			if (wildcard && (!value.isEmpty() || variableConsumable))
				throw new IllegalArgumentException("Invalid overlap wildcard atom.");
			if (!wildcard && value.isEmpty())
				throw new IllegalArgumentException("Invalid overlap literal atom.");
		}

		@NonNull
		private static OverlapAtom wildcardAtom() {
			return new OverlapAtom("", true, false);
		}
	}

	@NonNull
	private final String template;
	@NonNull
	private final List<@NonNull Part> parts;
	@NonNull
	private final List<@NonNull OverlapAtom> overlapAtoms;
	@NonNull
	private final String leadingLiteral;
	@NonNull
	private final String trailingLiteral;
	private final int minimumWireLength;

	private McpLevelOneUriTemplate(@NonNull String template,
			@NonNull List<@NonNull Part> parts) {
		this.template = requireNonNull(template);
		this.parts = List.copyOf(requireNonNull(parts));
		this.overlapAtoms = overlapAtoms(parts);
		this.leadingLiteral = parts.get(0) instanceof LiteralPart literal
				? literal.value() : "";
		this.trailingLiteral = parts.get(parts.size() - 1)
				instanceof LiteralPart literal ? literal.value() : "";
		this.minimumWireLength = parts.stream()
				.filter(LiteralPart.class::isInstance)
				.map(LiteralPart.class::cast)
				.mapToInt(literal -> literal.value().length())
				.sum();
	}

	@NonNull
	static McpLevelOneUriTemplate parse(@NonNull String template) {
		template = McpProtocolSupport.requireNonBlank(template,
				"Resource URI template");
		requireMaximumUtf8Bytes(template, MAXIMUM_TEMPLATE_UTF_8_BYTES,
				"Resource URI template");
		List<Part> parts = new ArrayList<>();
		Set<String> variableNames = new LinkedHashSet<>();
		StringBuilder expanded = new StringBuilder(template.length());
		int literalStart = 0;
		boolean previousWasVariable = false;

		for (int index = 0; index < template.length();) {
			char character = template.charAt(index);
			if (character == '}')
				throw invalidTemplate();
			if (character != '{') {
				++index;
				continue;
			}

			if (index > literalStart) {
				String literal = normalizeLiteral(
						template.substring(literalStart, index));
				parts.add(new LiteralPart(literal));
				expanded.append(literal);
				previousWasVariable = false;
			}
			if (previousWasVariable)
				throw new IllegalArgumentException(
						"Adjacent resource URI-template variables are ambiguous.");

			int closing = template.indexOf('}', index + 1);
			if (closing < 0 || (template.indexOf('{', index + 1) >= 0
					&& template.indexOf('{', index + 1) < closing))
				throw invalidTemplate();
			String variableName = template.substring(index + 1, closing);
			requireMaximumUtf8Bytes(variableName,
					MAXIMUM_VARIABLE_NAME_UTF_8_BYTES,
					"Resource URI-template variable name");
			if (!validVariableName(variableName))
				throw new IllegalArgumentException(
						"Only RFC 6570 Level-1 variable expressions are supported.");
			if (!variableNames.add(variableName))
				throw new IllegalArgumentException(
						"Duplicate resource URI-template variable '" + variableName + "'.");
			if (variableNames.size() > MAXIMUM_VARIABLE_COUNT)
				throw new IllegalArgumentException(
						"A resource URI template may contain at most "
								+ MAXIMUM_VARIABLE_COUNT + " variables.");
			parts.add(new VariablePart(variableName));
			expanded.append('x');
			previousWasVariable = true;
			index = closing + 1;
			literalStart = index;
		}

		if (literalStart < template.length()) {
			String literal = normalizeLiteral(template.substring(literalStart));
			parts.add(new LiteralPart(literal));
			expanded.append(literal);
		}
		if (parts.stream().noneMatch(VariablePart.class::isInstance))
			throw new IllegalArgumentException(
					"A resource URI template must contain at least one variable.");
		requireMaximumUtf8Bytes(expanded.toString(),
				MAXIMUM_TEMPLATE_UTF_8_BYTES,
				"Expanded resource URI template");
		requireValidAbsoluteUri(expanded.toString(), "Expanded resource URI template");
		return new McpLevelOneUriTemplate(template, parts);
	}

	static void requireResourceTemplateCount(int count) {
		if (count > MAXIMUM_TEMPLATE_COUNT_PER_ENDPOINT)
			throw new IllegalArgumentException(
					"An MCP endpoint may contain at most "
							+ MAXIMUM_TEMPLATE_COUNT_PER_ENDPOINT
							+ " resource URI templates.");
	}

	@NonNull
	String template() {
		return template;
	}

	@NonNull
	Optional<@NonNull Map<@NonNull String, @NonNull String>> match(
			@NonNull String uri) {
		NormalizedResourceUri normalized = normalizeResourceUriForTemplateMatching(uri);
		if (!couldMatch(normalized))
			return Optional.empty();
		requireTemplateMatchDynamicProgrammingCellBudget(
				dynamicProgrammingCellCount(normalized));
		return match(new PreparedResourceUri(normalized));
	}

	boolean couldMatch(@NonNull NormalizedResourceUri normalized) {
		requireNonNull(normalized);
		String uri = normalized.value;
		return uri.length() >= minimumWireLength
				&& (leadingLiteral.isEmpty() || uri.startsWith(leadingLiteral))
				&& (trailingLiteral.isEmpty() || uri.endsWith(trailingLiteral));
	}

	long dynamicProgrammingCellCount(@NonNull NormalizedResourceUri normalized) {
		requireNonNull(normalized);
		return ((long) parts.size() + 1L) * ((long) normalized.value.length() + 1L);
	}

	@NonNull
	Optional<@NonNull Map<@NonNull String, @NonNull String>> match(
			@NonNull PreparedResourceUri prepared) {
		requireNonNull(prepared);
		String uri = prepared.normalized.value;
		if (!couldMatch(prepared.normalized))
			return Optional.empty();
		int[] tokenLengths = prepared.tokenLengths;
		BitSet[] canMatchFrom = matchReachability(uri, tokenLengths);
		if (!canMatchFrom[0].get(0))
			return Optional.empty();

		Map<String, String> captured = new LinkedHashMap<>();
		int uriIndex = 0;
		for (int partIndex = 0; partIndex < parts.size(); ++partIndex) {
			Part part = parts.get(partIndex);
			if (part instanceof LiteralPart literal) {
				if (!uri.startsWith(literal.value(), uriIndex))
					throw new IllegalStateException(
							"A resource-template match path is incomplete.");
				uriIndex += literal.value().length();
				continue;
			}

			VariablePart variable = (VariablePart) part;
			BitSet nextPart = canMatchFrom[partIndex + 1];
			int selectedEnd = nextPart.get(uriIndex) ? uriIndex : -1;
			int candidateEnd = uriIndex;
			while (candidateEnd < uri.length()
					&& tokenLengths[candidateEnd] > 0) {
				candidateEnd += tokenLengths[candidateEnd];
				if (nextPart.get(candidateEnd))
					selectedEnd = candidateEnd;
			}
			if (selectedEnd < 0)
				throw new IllegalStateException(
						"A resource-template match path is incomplete.");
			captured.put(variable.name(), strictPercentDecode(
					uri.substring(uriIndex, selectedEnd)));
			uriIndex = selectedEnd;
		}
		if (uriIndex != uri.length())
			throw new IllegalStateException(
					"A resource-template match path is incomplete.");
		return Optional.of(Collections.unmodifiableMap(captured));
	}

	@NonNull
	static NormalizedResourceUri normalizeResourceUriForTemplateMatching(
			@NonNull String uri) {
		uri = requireValidAbsoluteUri(uri, "Resource URI");
		requireMaximumUtf8Bytes(uri,
				MAXIMUM_TEMPLATE_ROUTED_RESOURCE_URI_UTF_8_BYTES,
				"Template-routed resource URI");
		return new NormalizedResourceUri(normalizePercentTripletCase(uri));
	}

	@NonNull
	static PreparedResourceUri prepareResourceUriForTemplateMatching(
			@NonNull NormalizedResourceUri normalized) {
		return new PreparedResourceUri(requireNonNull(normalized));
	}

	static void requireTemplateMatchDynamicProgrammingCellBudget(long cells) {
		if (cells > MAXIMUM_TEMPLATE_MATCH_DYNAMIC_PROGRAMMING_CELLS)
			throw new IllegalArgumentException(
					"Resource URI-template routing may evaluate at most "
							+ MAXIMUM_TEMPLATE_MATCH_DYNAMIC_PROGRAMMING_CELLS
							+ " dynamic-programming cells per request.");
	}

	boolean potentiallyOverlaps(@NonNull McpLevelOneUriTemplate other) {
		return potentiallyOverlaps(other, new OverlapComparisonBudget(
				MAXIMUM_OVERLAP_COMPARISON_STATES));
	}

	boolean potentiallyOverlaps(@NonNull McpLevelOneUriTemplate other,
			@NonNull OverlapComparisonBudget endpointBudget) {
		requireNonNull(other);
		requireNonNull(endpointBudget);
		if (!endpointBudget.chargeState())
			return true;
		ArrayDeque<MatchState> pending = new ArrayDeque<>();
		Set<MatchState> discovered = new LinkedHashSet<>();
		MatchState initial = new MatchState(0, 0);
		discovered.add(initial);
		pending.add(initial);

		while (!pending.isEmpty()) {
			MatchState state = pending.removeFirst();
			int left = state.leftIndex();
			int right = state.rightIndex();
			if (left == overlapAtoms.size()
					&& right == other.overlapAtoms.size())
				return true;
			if (left == overlapAtoms.size()) {
				if (onlyWildcardsRemain(other.overlapAtoms, right))
					return true;
				continue;
			}
			if (right == other.overlapAtoms.size()) {
				if (onlyWildcardsRemain(overlapAtoms, left))
					return true;
				continue;
			}

			OverlapAtom leftAtom = overlapAtoms.get(left);
			OverlapAtom rightAtom = other.overlapAtoms.get(right);
			boolean leftWildcard = leftAtom.wildcard();
			boolean rightWildcard = rightAtom.wildcard();
			if (leftWildcard) {
				if (!enqueueOverlapState(pending, discovered, endpointBudget,
						new MatchState(left + 1, right)))
					return true;
				if (!rightWildcard && rightAtom.variableConsumable())
					if (!enqueueOverlapState(pending, discovered, endpointBudget,
							new MatchState(left, right + 1)))
						return true;
			}
			if (rightWildcard) {
				if (!enqueueOverlapState(pending, discovered, endpointBudget,
						new MatchState(left, right + 1)))
					return true;
				if (!leftWildcard && leftAtom.variableConsumable())
					if (!enqueueOverlapState(pending, discovered, endpointBudget,
							new MatchState(left + 1, right)))
						return true;
			}
			if (!leftWildcard && !rightWildcard
					&& leftAtom.value().equals(rightAtom.value()))
				if (!enqueueOverlapState(pending, discovered, endpointBudget,
						new MatchState(left + 1, right + 1)))
					return true;
		}

		return false;
	}

	private static boolean enqueueOverlapState(
			@NonNull ArrayDeque<@NonNull MatchState> pending,
			@NonNull Set<@NonNull MatchState> discovered,
			@NonNull OverlapComparisonBudget endpointBudget,
			@NonNull MatchState state) {
		if (discovered.contains(state))
			return true;
		if (discovered.size() >= MAXIMUM_OVERLAP_COMPARISON_STATES) {
			endpointBudget.markExhausted();
			return false;
		}
		if (!endpointBudget.chargeState())
			return false;
		discovered.add(state);
		pending.add(state);
		return true;
	}

	@NonNull
	static OverlapComparisonBudget endpointOverlapComparisonBudget() {
		return new OverlapComparisonBudget(
				MAXIMUM_ENDPOINT_OVERLAP_COMPARISON_STATES);
	}

	@NonNull
	static String requireValidAbsoluteUri(@NonNull String uri,
			@NonNull String description) {
		requireNonNull(uri);
		requireNonNull(description);
		if (uri.isBlank())
			throw new IllegalArgumentException(description + " must not be blank.");
		requireMaximumUtf8Bytes(uri, MAXIMUM_RESOURCE_URI_UTF_8_BYTES,
				description);
		requireAsciiUriWireSyntax(uri, description);
		URI parsed;
		try {
			parsed = McpProtocolSupport.requireAbsoluteUri(URI.create(uri), description);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(description + " must be a valid absolute URI.",
					exception);
		}
		if (!parsed.normalize().equals(parsed))
			throw new IllegalArgumentException(description + " must be normalized.");
		return uri;
	}

	private static void requireMaximumUtf8Bytes(@NonNull String value,
			int maximumBytes, @NonNull String description) {
		requireNonNull(value);
		requireNonNull(description);
		int bytes = 0;
		for (int index = 0; index < value.length();) {
			int codePoint = value.codePointAt(index);
			bytes += codePoint <= 0x7F ? 1
					: codePoint <= 0x7FF ? 2
					: codePoint <= 0xFFFF ? 3 : 4;
			if (bytes > maximumBytes)
				throw new IllegalArgumentException(description
						+ " may contain at most " + maximumBytes
						+ " UTF-8 bytes.");
			index += Character.charCount(codePoint);
		}
	}

	@NonNull
	static String strictPercentDecode(@NonNull String value) {
		requireNonNull(value);
		StringBuilder decoded = new StringBuilder(value.length());
		for (int index = 0; index < value.length();) {
			char character = value.charAt(index);
			if (character != '%') {
				decoded.append(character);
				++index;
				continue;
			}

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			while (index < value.length() && value.charAt(index) == '%') {
				if (index + 2 >= value.length())
					throw new IllegalArgumentException("Malformed URI percent escape.");
				int high = hexadecimal(value.charAt(index + 1));
				int low = hexadecimal(value.charAt(index + 2));
				if (high < 0 || low < 0)
					throw new IllegalArgumentException("Malformed URI percent escape.");
				bytes.write((high << 4) | low);
				index += 3;
			}
			try {
				CharBuffer characters = StandardCharsets.UTF_8.newDecoder()
						.onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT)
						.decode(ByteBuffer.wrap(bytes.toByteArray()));
				decoded.append(characters);
			} catch (CharacterCodingException exception) {
				throw new IllegalArgumentException(
						"URI percent escapes must contain valid UTF-8.", exception);
			}
		}
		return decoded.toString();
	}

	@NonNull
	private static String normalizeLiteral(@NonNull String literal) {
		StringBuilder normalized = new StringBuilder(literal.length());
		for (int index = 0; index < literal.length();) {
			int codePoint = literal.codePointAt(index);
			if (codePoint == '%') {
				if (index + 2 >= literal.length()
						|| hexadecimal(literal.charAt(index + 1)) < 0
						|| hexadecimal(literal.charAt(index + 2)) < 0)
					throw invalidTemplate();
				normalized.append('%')
						.append(uppercaseHexadecimal(literal.charAt(index + 1)))
						.append(uppercaseHexadecimal(literal.charAt(index + 2)));
				index += 3;
				continue;
			}
			if (codePoint <= 0x7F) {
				if (!allowedAsciiLiteral((char) codePoint))
					throw invalidTemplate();
				normalized.append((char) codePoint);
				++index;
				continue;
			}
			if (!allowedUnicodeLiteral(codePoint))
				throw invalidTemplate();
			byte[] utf8 = new String(Character.toChars(codePoint))
					.getBytes(StandardCharsets.UTF_8);
			for (byte value : utf8) {
				int unsigned = value & 0xFF;
				normalized.append('%');
				normalized.append("0123456789ABCDEF".charAt(unsigned >>> 4));
				normalized.append("0123456789ABCDEF".charAt(unsigned & 0x0F));
			}
			index += Character.charCount(codePoint);
		}
		return normalized.toString();
	}

	private static boolean allowedAsciiLiteral(char character) {
		return character == 0x21
				|| (character >= 0x23 && character <= 0x24)
				|| character == 0x26
				|| (character >= 0x28 && character <= 0x3B)
				|| character == 0x3D
				|| (character >= 0x3F && character <= 0x5B)
				|| character == 0x5D || character == 0x5F
				|| (character >= 0x61 && character <= 0x7A)
				|| character == 0x7E;
	}

	private static boolean allowedUnicodeLiteral(int codePoint) {
		return (codePoint >= 0xA0 && codePoint <= 0xD7FF)
				|| (codePoint >= 0xE000 && codePoint <= 0xF8FF)
				|| (codePoint >= 0xF900 && codePoint <= 0xFDCF)
				|| (codePoint >= 0xFDF0 && codePoint <= 0xFFEF)
				|| (codePoint >= 0x10000 && codePoint <= 0x1FFFD)
				|| (codePoint >= 0x20000 && codePoint <= 0x2FFFD)
				|| (codePoint >= 0x30000 && codePoint <= 0x3FFFD)
				|| (codePoint >= 0x40000 && codePoint <= 0x4FFFD)
				|| (codePoint >= 0x50000 && codePoint <= 0x5FFFD)
				|| (codePoint >= 0x60000 && codePoint <= 0x6FFFD)
				|| (codePoint >= 0x70000 && codePoint <= 0x7FFFD)
				|| (codePoint >= 0x80000 && codePoint <= 0x8FFFD)
				|| (codePoint >= 0x90000 && codePoint <= 0x9FFFD)
				|| (codePoint >= 0xA0000 && codePoint <= 0xAFFFD)
				|| (codePoint >= 0xB0000 && codePoint <= 0xBFFFD)
				|| (codePoint >= 0xC0000 && codePoint <= 0xCFFFD)
				|| (codePoint >= 0xD0000 && codePoint <= 0xDFFFD)
				|| (codePoint >= 0xE1000 && codePoint <= 0xEFFFD)
				|| (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
				|| (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
	}

	private static void requireAsciiUriWireSyntax(@NonNull String uri,
			@NonNull String description) {
		for (int index = 0; index < uri.length();) {
			char character = uri.charAt(index);
			if (character > 0x7F)
				throw new IllegalArgumentException(
						description + " must use an ASCII URI wire form.");
			if (character != '%') {
				++index;
				continue;
			}
			if (index + 2 >= uri.length()
					|| hexadecimal(uri.charAt(index + 1)) < 0
					|| hexadecimal(uri.charAt(index + 2)) < 0)
				throw new IllegalArgumentException(
						description + " contains a malformed percent escape.");
			index += 3;
		}
	}

	private static boolean validVariableName(@NonNull String name) {
		if (name.isEmpty())
			return false;
		boolean segmentHasCharacter = false;
		for (int index = 0; index < name.length();) {
			char character = name.charAt(index);
			if (character == '.') {
				if (!segmentHasCharacter)
					return false;
				segmentHasCharacter = false;
				++index;
				continue;
			}
			if (asciiLetterOrDigit(character) || character == '_') {
				segmentHasCharacter = true;
				++index;
				continue;
			}
			if (character == '%' && index + 2 < name.length()
					&& hexadecimal(name.charAt(index + 1)) >= 0
					&& hexadecimal(name.charAt(index + 2)) >= 0) {
				segmentHasCharacter = true;
				index += 3;
				continue;
			}
			return false;
		}
		return segmentHasCharacter;
	}

	private static boolean asciiLetterOrDigit(char character) {
		return (character >= 'A' && character <= 'Z')
				|| (character >= 'a' && character <= 'z')
				|| (character >= '0' && character <= '9');
	}

	private static int hexadecimal(char character) {
		if (character >= '0' && character <= '9')
			return character - '0';
		if (character >= 'A' && character <= 'F')
			return character - 'A' + 10;
		if (character >= 'a' && character <= 'f')
			return character - 'a' + 10;
		return -1;
	}

	@NonNull
	private static String normalizePercentTripletCase(@NonNull String value) {
		StringBuilder normalized = null;
		for (int index = 0; index < value.length();) {
			char character = value.charAt(index);
			if (character != '%') {
				if (normalized != null)
					normalized.append(character);
				++index;
				continue;
			}

			char high = uppercaseHexadecimal(value.charAt(index + 1));
			char low = uppercaseHexadecimal(value.charAt(index + 2));
			if (normalized == null && (high != value.charAt(index + 1)
					|| low != value.charAt(index + 2))) {
				normalized = new StringBuilder(value.length());
				normalized.append(value, 0, index);
			}
			if (normalized != null)
				normalized.append('%').append(high).append(low);
			index += 3;
		}
		return normalized == null ? value : normalized.toString();
	}

	private static char uppercaseHexadecimal(char character) {
		return character >= 'a' && character <= 'f'
				? (char) (character - ('a' - 'A')) : character;
	}

	private static @NonNull List<@NonNull OverlapAtom> overlapAtoms(
			@NonNull List<@NonNull Part> parts) {
		List<OverlapAtom> atoms = new ArrayList<>();
		for (Part part : parts) {
			if (part instanceof VariablePart) {
				atoms.add(OverlapAtom.wildcardAtom());
			} else {
				String literal = ((LiteralPart) part).value();
				for (int index = 0; index < literal.length();) {
					int tokenLength = literalWireTokenLength(literal, index);
					String token = literal.substring(index, index + tokenLength);
					atoms.add(new OverlapAtom(token, false,
							levelOneExpansionTokenLength(token, 0) == token.length()));
					index += tokenLength;
				}
			}
		}
		return Collections.unmodifiableList(atoms);
	}

	private static boolean onlyWildcardsRemain(
			@NonNull List<@NonNull OverlapAtom> atoms, int index) {
		for (; index < atoms.size(); ++index) {
			if (!atoms.get(index).wildcard())
				return false;
		}
		return true;
	}

	private static int literalWireTokenLength(@NonNull String literal, int index) {
		if (literal.charAt(index) != '%')
			return 1;
		int expansionLength = levelOneExpansionTokenLength(literal, index);
		return expansionLength > 0 ? expansionLength : 3;
	}

	private BitSet @NonNull [] matchReachability(@NonNull String uri,
			int @NonNull [] tokenLengths) {
		BitSet[] rows = new BitSet[parts.size() + 1];
		rows[parts.size()] = new BitSet(uri.length() + 1);
		rows[parts.size()].set(uri.length());
		for (int partIndex = parts.size() - 1; partIndex >= 0; --partIndex) {
			Part part = parts.get(partIndex);
			if (part instanceof LiteralPart literal) {
				rows[partIndex] = literalMatchRow(
						uri, literal.value(), rows[partIndex + 1]);
				continue;
			}

			BitSet row = new BitSet(uri.length() + 1);
			BitSet next = rows[partIndex + 1];
			for (int uriIndex = uri.length(); uriIndex >= 0; --uriIndex) {
					if (next.get(uriIndex)
							|| (uriIndex < uri.length() && tokenLengths[uriIndex] > 0
							&& row.get(uriIndex + tokenLengths[uriIndex])))
					row.set(uriIndex);
			}
			rows[partIndex] = row;
		}
		return rows;
	}

	@NonNull
	private static BitSet literalMatchRow(@NonNull String uri,
			@NonNull String literal, @NonNull BitSet next) {
		BitSet row = new BitSet(uri.length() + 1);
		int[] failure = new int[literal.length()];
		for (int index = 1, matched = 0; index < literal.length(); ++index) {
			while (matched > 0 && literal.charAt(index) != literal.charAt(matched))
				matched = failure[matched - 1];
			if (literal.charAt(index) == literal.charAt(matched))
				++matched;
			failure[index] = matched;
		}

		for (int index = 0, matched = 0; index < uri.length(); ++index) {
			while (matched > 0 && uri.charAt(index) != literal.charAt(matched))
				matched = failure[matched - 1];
			if (uri.charAt(index) == literal.charAt(matched))
				++matched;
			if (matched == literal.length()) {
				int start = index + 1 - literal.length();
				if (next.get(index + 1))
					row.set(start);
				matched = failure[matched - 1];
			}
		}
		return row;
	}

	private static int @NonNull [] levelOneExpansionTokenLengths(
			@NonNull String uri) {
		int[] lengths = new int[uri.length() + 1];
		for (int index = 0; index < uri.length(); ++index)
			lengths[index] = levelOneExpansionTokenLength(uri, index);
		return lengths;
	}

	private static int levelOneExpansionTokenLength(@NonNull String uri,
			int index) {
		if (index >= uri.length())
			return 0;
		char character = uri.charAt(index);
		if (asciiLetterOrDigit(character) || character == '-'
				|| character == '.' || character == '_'
				|| character == '~')
			return 1;
		if (character != '%' || index + 2 >= uri.length())
			return 0;

		int firstByte = encodedByte(uri, index);
		int encodedByteCount;
		if (firstByte >= 0 && firstByte <= 0x7F) {
			if (isUnreserved((char) firstByte))
				return 0;
			encodedByteCount = 1;
		} else if (firstByte >= 0xC2 && firstByte <= 0xDF)
			encodedByteCount = 2;
		else if (firstByte >= 0xE0 && firstByte <= 0xEF)
			encodedByteCount = 3;
		else if (firstByte >= 0xF0 && firstByte <= 0xF4)
			encodedByteCount = 4;
		else
			return 0;

		int encodedLength = encodedByteCount * 3;
		if (index + encodedLength > uri.length())
			return 0;
		for (int offset = 0; offset < encodedLength; offset += 3) {
			if (encodedByte(uri, index + offset) < 0)
				return 0;
		}
		try {
			strictPercentDecode(uri.substring(index, index + encodedLength));
		} catch (IllegalArgumentException exception) {
			return 0;
		}
		return encodedLength;
	}

	private static boolean isUnreserved(char character) {
		return asciiLetterOrDigit(character) || character == '-'
				|| character == '.' || character == '_'
				|| character == '~';
	}

	private static int encodedByte(@NonNull String value, int percentIndex) {
		if (percentIndex + 2 >= value.length()
				|| value.charAt(percentIndex) != '%')
			return -1;
		int high = hexadecimal(value.charAt(percentIndex + 1));
		int low = hexadecimal(value.charAt(percentIndex + 2));
		return high < 0 || low < 0 ? -1 : (high << 4) | low;
	}

	@NonNull
	private static IllegalArgumentException invalidTemplate() {
		return new IllegalArgumentException("Resource URI template is malformed.");
	}
}

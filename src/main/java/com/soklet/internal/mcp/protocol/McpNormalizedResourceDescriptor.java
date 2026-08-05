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
	private static final int MAXIMUM_VARIABLE_COUNT = 32;

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

	private McpLevelOneUriTemplate(@NonNull String template,
			@NonNull List<@NonNull Part> parts) {
		this.template = requireNonNull(template);
		this.parts = List.copyOf(requireNonNull(parts));
		this.overlapAtoms = overlapAtoms(parts);
	}

	@NonNull
	static McpLevelOneUriTemplate parse(@NonNull String template) {
		template = McpProtocolSupport.requireNonBlank(template,
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
			if (closing < 0 || template.indexOf('{', index + 1) >= 0
					&& template.indexOf('{', index + 1) < closing)
				throw invalidTemplate();
			String variableName = template.substring(index + 1, closing);
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
		requireValidAbsoluteUri(expanded.toString(), "Expanded resource URI template");
		return new McpLevelOneUriTemplate(template, parts);
	}

	@NonNull
	String template() {
		return template;
	}

	@NonNull
	Optional<@NonNull Map<@NonNull String, @NonNull String>> match(
			@NonNull String uri) {
		uri = normalizePercentTripletCase(
				requireValidAbsoluteUri(uri, "Resource URI"));
		int[] tokenLengths = levelOneExpansionTokenLengths(uri);
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

	boolean potentiallyOverlaps(@NonNull McpLevelOneUriTemplate other) {
		requireNonNull(other);
		ArrayDeque<MatchState> pending = new ArrayDeque<>();
		Set<MatchState> visited = new LinkedHashSet<>();
		pending.add(new MatchState(0, 0));

		while (!pending.isEmpty()) {
			MatchState state = pending.removeFirst();
			if (!visited.add(state))
				continue;
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
				pending.add(new MatchState(left + 1, right));
				if (!rightWildcard && rightAtom.variableConsumable())
					pending.add(new MatchState(left, right + 1));
			}
			if (rightWildcard) {
				pending.add(new MatchState(left, right + 1));
				if (!leftWildcard && leftAtom.variableConsumable())
					pending.add(new MatchState(left + 1, right));
			}
			if (!leftWildcard && !rightWildcard
					&& leftAtom.value().equals(rightAtom.value()))
				pending.add(new MatchState(left + 1, right + 1));
		}

		return false;
	}

	@NonNull
	static String requireValidAbsoluteUri(@NonNull String uri,
			@NonNull String description) {
		requireNonNull(uri);
		requireNonNull(description);
		if (uri.isBlank())
			throw new IllegalArgumentException(description + " must not be blank.");
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
				|| character >= 0x23 && character <= 0x24
				|| character == 0x26
				|| character >= 0x28 && character <= 0x3B
				|| character == 0x3D
				|| character >= 0x3F && character <= 0x5B
				|| character == 0x5D || character == 0x5F
				|| character >= 0x61 && character <= 0x7A
				|| character == 0x7E;
	}

	private static boolean allowedUnicodeLiteral(int codePoint) {
		return codePoint >= 0xA0 && codePoint <= 0xD7FF
				|| codePoint >= 0xE000 && codePoint <= 0xF8FF
				|| codePoint >= 0xF900 && codePoint <= 0xFDCF
				|| codePoint >= 0xFDF0 && codePoint <= 0xFFEF
				|| codePoint >= 0x10000 && codePoint <= 0x1FFFD
				|| codePoint >= 0x20000 && codePoint <= 0x2FFFD
				|| codePoint >= 0x30000 && codePoint <= 0x3FFFD
				|| codePoint >= 0x40000 && codePoint <= 0x4FFFD
				|| codePoint >= 0x50000 && codePoint <= 0x5FFFD
				|| codePoint >= 0x60000 && codePoint <= 0x6FFFD
				|| codePoint >= 0x70000 && codePoint <= 0x7FFFD
				|| codePoint >= 0x80000 && codePoint <= 0x8FFFD
				|| codePoint >= 0x90000 && codePoint <= 0x9FFFD
				|| codePoint >= 0xA0000 && codePoint <= 0xAFFFD
				|| codePoint >= 0xB0000 && codePoint <= 0xBFFFD
				|| codePoint >= 0xC0000 && codePoint <= 0xCFFFD
				|| codePoint >= 0xD0000 && codePoint <= 0xDFFFD
				|| codePoint >= 0xE1000 && codePoint <= 0xEFFFD
				|| codePoint >= 0xF0000 && codePoint <= 0xFFFFD
				|| codePoint >= 0x100000 && codePoint <= 0x10FFFD;
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
		return character >= 'A' && character <= 'Z'
				|| character >= 'a' && character <= 'z'
				|| character >= '0' && character <= '9';
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

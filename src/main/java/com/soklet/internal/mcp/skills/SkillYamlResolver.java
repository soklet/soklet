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

package com.soklet.internal.mcp.skills;

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpJsonNull;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.soklet.internal.mcp.skills.SkillYamlException.Reason;
import static com.soklet.internal.mcp.skills.SkillYamlNode.Position;
import static java.util.Objects.requireNonNull;

/**
 * The private Skills metadata profile of YAML 1.2 core resolution. It keeps
 * exact, finite JSON values, not Java object tags or YAML 1.1 merge semantics.
 * Syntax and expansion share the caller's budget; no production limits are
 * selected here.
 */
final class SkillYamlResolver {
	private final SkillYamlBudget budget;
	private final McpJsonLimits jsonLimits;

	SkillYamlResolver(SkillYamlBudget budget, McpJsonLimits jsonLimits) {
		this.budget = requireNonNull(budget);
		this.jsonLimits = requireNonNull(jsonLimits);
	}

	McpJsonValue resolve(SkillYamlNode root) {
		return new Resolution().resolve(requireNonNull(root), 1, true);
	}

	private final class Resolution {
		private final Map<String, Anchor> anchors = new HashMap<>();
		private int jsonNodes;
		private long outputBytes;

		private McpJsonValue resolve(SkillYamlNode node, int depth, boolean jsonNode) {
			Position position = node.position();
			budget.work(1, position);

			if (node instanceof SkillYamlNode.Alias alias) {
				budget.work(alias.name().length(), position);
				Anchor anchor = anchors.get(alias.name());
				if (anchor == null)
					throw failure(Reason.UNDEFINED_ALIAS, position);
				if (anchor.value == null)
					throw failure(Reason.CYCLIC_ALIAS, position);

				// A shared immutable value still costs its fully expanded JSON size.
				// Never re-resolve its source: later anchor redefinitions must not
				// change aliases already bound inside it.
				chargeResolved(anchor.value, depth, jsonNode, position);
				return anchor.value;
			}

			chargeNode(depth, jsonNode, position);
			Anchor anchor = null;
			if (node.properties().anchor() != null) {
				budget.work(node.properties().anchor().length(), position);
				anchor = new Anchor();
				anchors.put(node.properties().anchor(), anchor);
			}

			String tag = resolvedTag(node);
			McpJsonValue result;
			if (node instanceof SkillYamlNode.Scalar scalar) {
				result = scalar(scalar, tag);
			} else if (node instanceof SkillYamlNode.Sequence sequence) {
				if (!tag.equals("seq"))
					throw failure(Reason.TYPE, position);
				chargeOutput(2, position);
				List<McpJsonValue> values = new ArrayList<>();
				for (SkillYamlNode item : sequence.items()) {
					if (!values.isEmpty())
						chargeOutput(1, position);
					values.add(resolve(item, depth + 1, true));
				}
				budget.work(values.size(), position);
				result = new McpJsonArray(values);
			} else if (node instanceof SkillYamlNode.Mapping mapping) {
				if (!tag.equals("map"))
					throw failure(Reason.TYPE, position);
				chargeOutput(2, position);
				Map<String, McpJsonValue> members = new LinkedHashMap<>();
				for (SkillYamlNode.Entry entry : mapping.entries()) {
					McpJsonValue key = resolve(entry.key(), depth + 1, false);
					if (!(key instanceof McpJsonString string))
						throw failure(Reason.TYPE, entry.key().position());
					if (members.containsKey(string.value()))
						throw failure(Reason.DUPLICATE_KEY, entry.key().position());
					chargeOutput(members.isEmpty() ? 1 : 2, position);
					members.put(string.value(), resolve(entry.value(), depth + 1, true));
				}
				budget.work(members.size(), position);
				result = new McpJsonObject(members);
			} else {
				throw failure(Reason.TYPE, position);
			}

			if (anchor != null)
				anchor.value = result;
			return result;
		}

		private String resolvedTag(SkillYamlNode node) {
			String tag = node.properties().tag();
			if (tag != null)
				budget.work(tag.length(), node.position());
			if (tag == null || tag.equals("!") || tag.equals("?")) {
				if (node instanceof SkillYamlNode.Mapping)
					return "map";
				if (node instanceof SkillYamlNode.Sequence)
					return "seq";
				SkillYamlNode.Scalar scalar = (SkillYamlNode.Scalar) node;
				if (tag != null && tag.equals("!") || scalar.style() != SkillYamlNode.Style.PLAIN)
					return "str";
				return "implicit";
			}
			String name;
			if (tag.startsWith("!!"))
				name = tag.substring(2);
			else if (tag.startsWith("!<tag:yaml.org,2002:") && tag.endsWith(">"))
				name = tag.substring("!<tag:yaml.org,2002:".length(), tag.length() - 1);
			else
				throw failure(Reason.UNSUPPORTED_TAG, node.position());
			return switch (name) {
				case "map", "seq", "str", "bool", "int", "float", "null" -> name;
				default -> throw failure(Reason.UNSUPPORTED_TAG, node.position());
			};
		}

		private McpJsonValue scalar(SkillYamlNode.Scalar scalar, String tag) {
			String value = scalar.value();
			Position position = scalar.position();
			budget.scalarLength(value.length(), position);
			budget.text(value.length(), position);
			// All scalar classifiers below are linear, including failed numeric
			// candidates. Charge before inspecting them; no regex backtracking.
			budget.work(4L * value.length(), position);
			if (tag.equals("str")) {
				chargeString(value, position);
				return new McpJsonString(value);
			}
			boolean implicit = tag.equals("implicit");
			if (implicit || tag.equals("null")) {
				if (isNull(value)) {
					chargeLiteral(4, position);
					return McpJsonNull.INSTANCE;
				}
				if (!implicit)
					throw failure(Reason.TYPE, position);
			}
			if (implicit || tag.equals("bool")) {
				if (isTrue(value) || isFalse(value)) {
					boolean result = isTrue(value);
					chargeLiteral(result ? 4 : 5, position);
					return McpJsonBoolean.fromBoolean(result);
				}
				if (!implicit)
					throw failure(Reason.TYPE, position);
			}
			if (implicit || tag.equals("int") || tag.equals("float")) {
				NumericKind kind = numericKind(value);
				if (kind == NumericKind.NONFINITE && !tag.equals("int"))
					throw failure(Reason.TYPE, position);
				boolean accepted = kind != NumericKind.NONE && kind != NumericKind.NONFINITE
						&& (!tag.equals("int") || kind != NumericKind.FLOAT)
						&& (!tag.equals("float") || kind == NumericKind.INTEGER || kind == NumericKind.FLOAT);
				if (accepted)
					return number(value, kind, position);
				if (!implicit)
					throw failure(Reason.TYPE, position);
			}
			if (!implicit)
				throw failure(Reason.TYPE, position);
			chargeString(value, position);
			return new McpJsonString(value);
		}

		private McpJsonNumber number(String value, NumericKind kind, Position position) {
			if (value.length() > jsonLimits.maximumNumberLengthInCharacters()
					|| value.length() > jsonLimits.maximumTokenLengthInCharacters())
				throw failure(Reason.NUMBER_LIMIT, position);
			checkExponent(value, position);
			// Big-number parsing/conversion can be superlinear. This conservative
			// charge and the existing small numeric-token ceiling precede allocation.
			budget.work((long) value.length() * value.length(), position);
			BigDecimal number;
			try {
				number = switch (kind) {
					case OCTAL -> new BigDecimal(new BigInteger(value.substring(2), 8));
					case HEXADECIMAL -> new BigDecimal(new BigInteger(value.substring(2), 16));
					default -> new BigDecimal(value);
				};
			} catch (NumberFormatException exception) {
				throw failure(Reason.NUMBER_LIMIT, position);
			}
			chargeNumber(number, position);
			return new McpJsonNumber(number);
		}

		private void checkExponent(String value, Position position) {
			// Hexadecimal e/E is a digit, not an exponent.
			if (value.startsWith("0x") || value.startsWith("0o"))
				return;
			int offset = Math.max(value.indexOf('e'), value.indexOf('E'));
			if (offset < 0)
				return;
			int magnitude = 0;
			for (int index = offset + 1; index < value.length(); ++index) {
				char character = value.charAt(index);
				if (character == '+' || character == '-')
					continue;
				int digit = character - '0';
				if (magnitude > jsonLimits.maximumExponentMagnitude() / 10
						|| magnitude == jsonLimits.maximumExponentMagnitude() / 10
						&& digit > jsonLimits.maximumExponentMagnitude() % 10)
					throw failure(Reason.NUMBER_LIMIT, position);
				magnitude = magnitude * 10 + digit;
			}
		}

		private void chargeResolved(McpJsonValue value, int depth, boolean jsonNode, Position position) {
			budget.work(1, position);
			chargeNode(depth, jsonNode, position);
			if (value instanceof McpJsonString string) {
				budget.scalarLength(string.value().length(), position);
				budget.text(string.value().length(), position);
				chargeString(string.value(), position);
			} else if (value instanceof McpJsonNumber number) {
				int length = (int) canonicalNumberLength(number.value());
				budget.scalarLength(length, position);
				budget.text(length, position);
				chargeNumber(number.value(), position);
			} else if (value instanceof McpJsonArray array) {
				chargeOutput(2L + Math.max(0, array.values().size() - 1), position);
				for (McpJsonValue item : array.values())
					chargeResolved(item, depth + 1, true, position);
			} else if (value instanceof McpJsonObject object) {
				chargeOutput(2L + object.members().size() + Math.max(0, object.members().size() - 1), position);
				for (Map.Entry<String, McpJsonValue> member : object.members().entrySet()) {
					budget.node(depth + 1, position);
					budget.scalarLength(member.getKey().length(), position);
					budget.text(member.getKey().length(), position);
					chargeString(member.getKey(), position);
					chargeResolved(member.getValue(), depth + 1, true, position);
				}
			} else {
				int length = value == McpJsonBoolean.FALSE ? 5 : 4;
				budget.scalarLength(length, position);
				budget.text(length, position);
				chargeLiteral(length, position);
			}
		}

		private void chargeNode(int depth, boolean jsonNode, Position position) {
			budget.node(depth, position);
			if (jsonNode) {
				if (depth > jsonLimits.maximumNestingDepth())
					throw failure(Reason.DEPTH_LIMIT, position);
				if (++jsonNodes > jsonLimits.maximumNodeCount())
					throw failure(Reason.NODE_LIMIT, position);
			}
		}

		private void chargeString(String value, Position position) {
			if (value.length() > jsonLimits.maximumStringLengthInCharacters())
				throw failure(Reason.SCALAR_LIMIT, position);
			budget.work(value.length(), position);
			long tokenCharacters = 0;
			long bytes = 2;
			for (int index = 0; index < value.length(); ++index) {
				char character = value.charAt(index);
				if (character == '"' || character == '\\' || character == '\b'
						|| character == '\f' || character == '\n' || character == '\r' || character == '\t') {
					tokenCharacters += 2;
					bytes += 2;
				} else if (character < 0x20) {
					tokenCharacters += 6;
					bytes += 6;
				} else if (Character.isHighSurrogate(character)) {
					if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index)))
						throw failure(Reason.TYPE, position);
					tokenCharacters += 2;
					bytes += 4;
				} else if (Character.isLowSurrogate(character)) {
					throw failure(Reason.TYPE, position);
				} else {
					++tokenCharacters;
					bytes += character < 0x80 ? 1 : character < 0x800 ? 2 : 3;
				}
				if (tokenCharacters > jsonLimits.maximumTokenLengthInCharacters())
					throw failure(Reason.SCALAR_LIMIT, position);
			}
			chargeOutput(bytes, position);
		}

		private void chargeNumber(BigDecimal value, Position position) {
			long adjustedExponent = (long) value.precision() - value.scale() - 1;
			long length = canonicalNumberLength(value);
			if (Math.abs(adjustedExponent) > jsonLimits.maximumExponentMagnitude()
					|| length > jsonLimits.maximumNumberLengthInCharacters()
					|| length > jsonLimits.maximumTokenLengthInCharacters())
				throw failure(Reason.NUMBER_LIMIT, position);
			chargeOutput(length, position);
		}

		private void chargeLiteral(int length, Position position) {
			if (length > jsonLimits.maximumTokenLengthInCharacters())
				throw failure(Reason.SCALAR_LIMIT, position);
			chargeOutput(length, position);
		}

		private void chargeOutput(long amount, Position position) {
			if (amount > jsonLimits.maximumOutputBytes() - outputBytes)
				throw failure(Reason.OUTPUT_LIMIT, position);
			outputBytes += amount;
		}
	}

	private static boolean isNull(String value) {
		return value.isEmpty() || value.equals("null") || value.equals("Null")
				|| value.equals("NULL") || value.equals("~");
	}

	private static boolean isTrue(String value) {
		return value.equals("true") || value.equals("True") || value.equals("TRUE");
	}

	private static boolean isFalse(String value) {
		return value.equals("false") || value.equals("False") || value.equals("FALSE");
	}

	private static NumericKind numericKind(String value) {
		if (value.startsWith("0o") && digits(value, 2, 8))
			return NumericKind.OCTAL;
		if (value.startsWith("0x") && digits(value, 2, 16))
			return NumericKind.HEXADECIMAL;
		int offset = !value.isEmpty() && (value.charAt(0) == '+' || value.charAt(0) == '-') ? 1 : 0;
		if (value.length() - offset == 4 && (value.regionMatches(offset, ".inf", 0, 4)
				|| value.regionMatches(offset, ".Inf", 0, 4) || value.regionMatches(offset, ".INF", 0, 4)
				|| offset == 0 && (value.equals(".nan") || value.equals(".NaN") || value.equals(".NAN"))))
			return NumericKind.NONFINITE;
		int start = offset;
		while (offset < value.length() && asciiDigit(value.charAt(offset)))
			++offset;
		boolean integralDigits = offset > start;
		boolean point = offset < value.length() && value.charAt(offset) == '.';
		if (point) {
			start = ++offset;
			while (offset < value.length() && asciiDigit(value.charAt(offset)))
				++offset;
			if (!integralDigits && start == offset)
				return NumericKind.NONE;
		} else if (!integralDigits) {
			return NumericKind.NONE;
		}
		boolean exponent = offset < value.length() && (value.charAt(offset) == 'e' || value.charAt(offset) == 'E');
		if (exponent) {
			++offset;
			if (offset < value.length() && (value.charAt(offset) == '+' || value.charAt(offset) == '-'))
				++offset;
			start = offset;
			while (offset < value.length() && asciiDigit(value.charAt(offset)))
				++offset;
			if (offset == start)
				return NumericKind.NONE;
		}
		return offset != value.length() ? NumericKind.NONE : point || exponent ? NumericKind.FLOAT : NumericKind.INTEGER;
	}

	private static boolean digits(String value, int offset, int radix) {
		if (offset == value.length())
			return false;
		for (int index = offset; index < value.length(); ++index) {
			char character = value.charAt(index);
			int digit = character >= '0' && character <= '9' ? character - '0'
					: character >= 'a' && character <= 'f' ? character - 'a' + 10
					: character >= 'A' && character <= 'F' ? character - 'A' + 10 : -1;
			if (digit < 0 || digit >= radix)
				return false;
		}
		return true;
	}

	private static boolean asciiDigit(char character) {
		return character >= '0' && character <= '9';
	}

	// Mirrors McpJsonCodec's canonical BigDecimal spelling without constructing it.
	private static long canonicalNumberLength(BigDecimal value) {
		int precision = value.precision();
		long adjustedExponent = (long) precision - value.scale() - 1;
		long signLength = value.signum() < 0 ? 1 : 0;
		if (value.scale() >= 0 && adjustedExponent >= -6) {
			if (value.scale() == 0)
				return signLength + precision;
			return adjustedExponent >= 0 ? signLength + precision + 1
					: signLength + precision - adjustedExponent + 1;
		}
		long magnitude = Math.abs(adjustedExponent);
		int exponentDigits = 1;
		while (magnitude >= 10) {
			magnitude /= 10;
			++exponentDigits;
		}
		return signLength + (precision == 1 ? 1 : (long) precision + 1) + 2 + exponentDigits;
	}

	private static SkillYamlException failure(Reason reason, Position position) {
		return new SkillYamlException(reason, position.line(), position.column());
	}

	private enum NumericKind { NONE, INTEGER, OCTAL, HEXADECIMAL, FLOAT, NONFINITE }

	private static final class Anchor {
		private @Nullable McpJsonValue value;
	}
}

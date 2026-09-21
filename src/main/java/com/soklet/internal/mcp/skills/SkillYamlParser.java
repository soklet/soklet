/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet.internal.mcp.skills;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static com.soklet.internal.mcp.skills.SkillYamlException.Reason.*;
import static com.soklet.internal.mcp.skills.SkillYamlNode.*;
import static java.util.Objects.requireNonNull;

/**
 * Private syntax implementation, not yet a qualified general YAML parser.
 * Node keys, tags, anchors, duplicate entries and numeric spellings survive
 * parsing; the separate resolver owns their approved JSON meaning.
 * Every scan (including lookahead) is metered. No alias is expanded here.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class SkillYamlParser {
	private final String source;
	private final SkillYamlBudget budget;
	private int index;
	private int line;
	private int column = 1;
	private boolean nextContent;
	private Map<String, String> tagDirectives = new HashMap<>();
	private boolean versionDirective;

	private SkillYamlParser(String source, SkillYamlBudget budget, int firstLine) {
		this.source = requireNonNull(source);
		this.budget = requireNonNull(budget);
		if (firstLine < 1) throw new IllegalArgumentException("Invalid YAML first line.");
		this.line = firstLine;
	}

	static SkillYamlNode parse(String source, SkillYamlBudget budget, int firstLine) {
		SkillYamlParser parser = new SkillYamlParser(source, budget, firstLine);
		List<SkillYamlNode> documents = parser.documents(true);
		return documents.isEmpty() ? parser.empty(1, Properties.EMPTY) : documents.get(0);
	}

	/** Qualification entry point; Skills frontmatter still uses the single-document entry point. */
	static List<SkillYamlNode> parseStream(String source, SkillYamlBudget budget, int firstLine) {
		return new SkillYamlParser(source, budget, firstLine).documents(false);
	}

	private List<SkillYamlNode> documents(boolean singleDocument) {
		validateInput();
		List<SkillYamlNode> documents = new ArrayList<>();
		boolean anyDocumentAllowed = true;
		while (true) {
			streamPrefix();
			if (end()) break;
			if (marker("...")) {
				take(); take(); take(); finishLine();
				anyDocumentAllowed = true;
				continue;
			}
			// Without a preceding suffix, only an explicit document can follow.
			// A BOM/comment does not substitute for either separating marker.
			if (!anyDocumentAllowed && !marker("---")) throw failure(SYNTAX);
			if (singleDocument && !documents.isEmpty()) throw failure(UNSUPPORTED_SYNTAX);
			this.budget.work(1, position());
			// Drop the old table: clearing a once-large HashMap on every later
			// small document would rescan retained capacity, not just its entries.
			this.tagDirectives = new HashMap<>();
			this.versionDirective = false;
			// Every root, including an empty explicit document, is node-charged
			// before it enters this list. The stream never resets any budget.
			documents.add(document());
			anyDocumentAllowed = false;
		}
		this.budget.work(documents.size(), position());
		return List.copyOf(documents);
	}

	private void streamPrefix() {
		while (true) {
			skipBlankLines();
			if (!byteOrderMark()) return;
			take(true); this.column = 1;
		}
	}

	private SkillYamlNode document() {
		boolean directives = false;
		while (this.column == 1 && peek() == '%') {
			directive(); directives = true;
			skipBlankLines();
		}
		if (directives && !marker("---")) throw failure(SYNTAX);
		SkillYamlNode root;
		if (marker("---")) {
			take(); take(); take();
			skipHorizontal();
			if (!lineEnd() && peek() != '#') {
				// Compact block collections are not permitted after the document
				// marker. Flow/scalar content and properties use root indentation.
				root = inline(-1, 1, Properties.EMPTY, false, false, true);
				finishLine();
			} else {
				finishLine(); skipBlankLines();
				root = documentNode();
			}
		} else root = documentNode();
		return root;
	}

	private SkillYamlNode documentNode() {
		return end() || marker("...") || marker("---") || byteOrderMark() ? empty(1, Properties.EMPTY)
				: block(this.column - 1, 1, Properties.EMPTY);
	}

	private void directive() {
		Position start = position(); take();
		String name = directiveToken();
		if (name.equals("YAML")) {
			if (this.versionDirective) throw failure(SYNTAX);
			this.versionDirective = true;
			String version = directiveArgument();
			this.budget.work(version.length(), start);
			int dot = version.indexOf('.');
			if (dot < 1 || dot == version.length() - 1) throw failure(SYNTAX);
			int major = 0;
			for (int offset = 0; offset < version.length(); ++offset) {
				if (offset == dot) continue;
				char c = version.charAt(offset);
				if (c < '0' || c > '9') throw failure(SYNTAX);
				if (offset < dot) major = Math.min(2, major * 10 + c - '0');
			}
			if (major != 1) throw failure(UNSUPPORTED_SYNTAX);
			// Version declarations never switch the approved YAML 1.2 core
			// metadata resolution policy to YAML 1.1 implicit types.
		} else if (name.equals("TAG")) {
			String handle = directiveArgument(), prefix = directiveArgument();
			validateHandle(handle, start);
			validateTagCharacters(prefix, false, start);
			if (prefix.charAt(0) != '!' && flowDelimiter(prefix.charAt(0))) throw failure(SYNTAX);
			if (this.tagDirectives.putIfAbsent(handle, prefix) != null) throw failure(SYNTAX);
		} else {
			// Reserved directives have no application-defined execution semantics.
			while (true) {
				skipHorizontal();
				if (lineEnd() || peek() == '#') break;
				directiveToken();
			}
		}
		finishLine();
	}

	private String directiveArgument() {
		if (!horizontal(peek())) throw failure(SYNTAX);
		skipHorizontal();
		if (lineEnd() || peek() == '#') throw failure(SYNTAX);
		return directiveToken();
	}

	private String directiveToken() {
		Position start = position(); StringBuilder value = builder();
		while (!lineEnd() && !horizontal(peek())) append(value, take(), start);
		if (value.isEmpty()) throw failure(SYNTAX);
		return value.toString();
	}

	private void validateHandle(String handle, Position position) {
		this.budget.work(handle.length(), position);
		if (handle.equals("!") || handle.equals("!!")) return;
		if (handle.length() < 3 || handle.charAt(0) != '!' || handle.charAt(handle.length() - 1) != '!')
			throw new SkillYamlException(SYNTAX, position.line(), position.column());
		for (int offset = 1; offset < handle.length() - 1; ++offset)
			if (!tagWordCharacter(handle.charAt(offset)))
				throw new SkillYamlException(SYNTAX, position.line(), position.column());
	}

	private String expandTag(String tag, Position position) {
		this.budget.work(2L * tag.length(), position);
		if (tag.equals("!")) return tag;
		if (tag.startsWith("!<") && tag.endsWith(">")) {
			String identity = tag.substring(2, tag.length() - 1);
			validateTagCharacters(identity, false, position);
			validateTagIdentity(identity, position);
			return tag;
		}
		int handleEnd = tag.indexOf('!', 1);
		String handle = handleEnd < 0 ? "!" : tag.substring(0, handleEnd + 1);
		String suffix = tag.substring(handle.length());
		validateHandle(handle, position);
		validateTagCharacters(suffix, true, position);
		String prefix = this.tagDirectives.get(handle);
		if (prefix == null) {
			if (handle.equals("!") || handle.equals("!!")) return tag;
			throw new SkillYamlException(SYNTAX, position.line(), position.column());
		}
		long length = (long) prefix.length() + suffix.length() + 3;
		if (length > Integer.MAX_VALUE) throw new SkillYamlException(SCALAR_LIMIT, position.line(), position.column());
		this.budget.scalarLength((int) length, position);
		this.budget.text((int) length, position);
		String identity = prefix + suffix;
		validateTagIdentity(identity, position);
		// Preserve percent escapes in identity per §5.6. Example 6.26 disagrees;
		// that oracle conflict remains explicit instead of promoting encoded tags.
		return "!<" + identity + ">";
	}

	private void validateTagCharacters(String value, boolean suffix, Position position) {
		this.budget.work(value.length(), position);
		if (value.isEmpty()) throw new SkillYamlException(SYNTAX, position.line(), position.column());
		for (int offset = 0; offset < value.length(); ++offset) {
			char c = value.charAt(offset);
			if (c == '%') {
				if (offset + 2 >= value.length() || Character.digit(value.charAt(offset + 1), 16) < 0
						|| Character.digit(value.charAt(offset + 2), 16) < 0
						|| value.charAt(offset + 1) > 127 || value.charAt(offset + 2) > 127)
					throw new SkillYamlException(SYNTAX, position.line(), position.column());
				offset += 2;
			} else if (!(tagWordCharacter(c) || "#;/?:@&=+$,_.!~*'()[]".indexOf(c) >= 0)
					|| suffix && (c == '!' || flowDelimiter(c)))
				throw new SkillYamlException(SYNTAX, position.line(), position.column());
		}
	}

	private void validateTagIdentity(String identity, Position position) {
		this.budget.work(identity.length(), position);
		if (identity.startsWith("!") && identity.length() > 1) return;
		try {
			if (!identity.equals("!") && new URI(identity).isAbsolute()) return;
		} catch (URISyntaxException ignored) { /* Fixed, redacted error below. */ }
		throw new SkillYamlException(SYNTAX, position.line(), position.column());
	}

	private static boolean tagWordCharacter(char c) {
		return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-';
	}

	private void validateInput() {
		long bytes = 0;
		int validationLine = this.line, validationColumn = 1;
		boolean afterCarriageReturn = false;
		for (int offset = 0; offset < this.source.length();) {
			Position validationPosition = new Position(validationLine, validationColumn);
			this.budget.work(1, validationPosition);
			int codePoint = this.source.codePointAt(offset);
			if (!(codePoint == 9 || codePoint == 10 || codePoint == 13 || codePoint == 0x85
					|| codePoint >= 0x20 && codePoint <= 0x7E
					|| codePoint >= 0xA0 && codePoint <= 0xD7FF
					|| codePoint >= 0xE000 && codePoint <= 0xFFFD
					|| codePoint >= 0x10000 && codePoint <= 0x10FFFF))
				throw new SkillYamlException(SYNTAX, validationLine, validationColumn);
			bytes += codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
			if (bytes > this.budget.limits().maximumInputBytes())
				throw new SkillYamlException(INPUT_LIMIT, validationLine, validationColumn);
			if (codePoint == '\r' || codePoint == '\n') {
				if (codePoint != '\n' || !afterCarriageReturn) ++validationLine;
				validationColumn = 1;
			} else if (offset != 0 || codePoint != 0xFEFF) ++validationColumn;
			afterCarriageReturn = codePoint == '\r';
			offset += Character.charCount(codePoint);
		}
	}

	private SkillYamlNode block(int indent, int depth, Properties properties) {
		if (marker("---") || marker("...") || peek() == '%') throw failure(UNSUPPORTED_SYNTAX);
		if (indicator('-')) return blockSequence(indent, depth, properties);
		if (indicator('?') || mappingColonAhead()) return blockMapping(indent, depth, properties);
		SkillYamlNode value = inline(depth == 1 ? -1 : indent - 1, depth, properties, false, false);
		finishLine(); skipBlankLines();
		return value;
	}

	private Sequence blockSequence(int indent, int depth, Properties properties) {
		Position start = position();
		this.budget.node(depth, start);
		List<SkillYamlNode> items = new ArrayList<>();
		while (!end() && this.column - 1 == indent && indicator('-')) {
			take();
			items.add(blockIndented(indent, depth + 1, false));
		}
		return new Sequence(items, properties, start);
	}

	/** The compact notation is legal after '-', '?' and an explicit ':', not an implicit ':'. */
	private SkillYamlNode blockIndented(int parentIndent, int depth, boolean indentlessSequence) {
		int separationStart = this.index;
		skipHorizontal();
		if (lineEnd() || peek() == '#')
			return nested(parentIndent, depth, Properties.EMPTY, indentlessSequence);
		if (indicator('-') || indicator('?') || mappingColonAhead()) {
			// Whitespace here becomes the compact collection's indentation.
			for (int offset = separationStart; offset < this.index; ++offset) {
				this.budget.work(1, position());
				if (at(offset) == '\t') throw failure(SYNTAX);
			}
			return block(this.column - 1, depth, Properties.EMPTY);
		}
		SkillYamlNode value = inline(parentIndent, depth, Properties.EMPTY, false, false, indentlessSequence);
		finishLine(); skipBlankLines();
		return value;
	}

	private Mapping blockMapping(int indent, int depth, Properties properties) {
		Position start = position();
		this.budget.node(depth, start);
		List<Entry> entries = new ArrayList<>();
		while (!end() && this.column - 1 == indent && !marker("---") && !marker("...") && !byteOrderMark()) {
			if (!indicator('?') && !mappingColonAhead()) break;
			SkillYamlNode key;
			boolean explicitKey = indicator('?');
			if (explicitKey) {
				take();
				key = blockIndented(indent, depth + 1, true);
				if (this.column - 1 != indent || !indicator(':')) {
					entries.add(new Entry(key, empty(depth + 1, Properties.EMPTY)));
					continue;
				}
			} else {
				Position keyStart = position();
				key = indicator(':') ? empty(depth + 1, Properties.EMPTY)
						: inline(indent, depth + 1, Properties.EMPTY, false, true);
				skipHorizontal();
				checkImplicitKey(keyStart);
			}
			skipHorizontal();
			if (!indicator(':')) throw failure(SYNTAX);
			take();
			SkillYamlNode value;
			if (explicitKey) value = blockIndented(indent, depth + 1, true);
			else {
				skipHorizontal();
				if (lineEnd() || peek() == '#') value = nested(indent, depth + 1, Properties.EMPTY, true);
				else {
					value = inline(indent, depth + 1, Properties.EMPTY, false, false, true);
					finishLine(); skipBlankLines();
				}
			}
			entries.add(new Entry(key, value));
		}
		return new Mapping(entries, properties, start);
	}

	private SkillYamlNode nested(int parentIndent, int depth, Properties properties, boolean indentlessSequence) {
		Position start = position();
		finishLine(); skipBlankLines();
		int beforeTab = spacesBeforeSeparationTab();
		if (!end() && beforeTab >= 0 && beforeTab <= parentIndent) throw failure(SYNTAX);
		if (!end() && !marker("---") && !marker("...") && !byteOrderMark()
				&& (this.column - 1 > parentIndent
				|| indentlessSequence && this.column - 1 == parentIndent && indicator('-'))) {
			if (!indicator('-') && !indicator('?') && !mappingColonAhead()) {
				// Neither a properties-only line nor an indented scalar header changes
				// the node's parent indentation. Anchored values may remain indentless sequences.
				SkillYamlNode value = inline(parentIndent, depth, properties, false, false, indentlessSequence);
				finishLine(); skipBlankLines();
				return value;
			}
			return block(this.column - 1, depth, properties);
		}
		this.budget.node(depth, start);
		return new Scalar("", Style.PLAIN, properties, start);
	}

	private SkillYamlNode inline(int parentIndent, int depth, Properties inherited, boolean flow, boolean key) {
		return inline(parentIndent, depth, inherited, flow, key, false);
	}

	private SkillYamlNode inline(int parentIndent, int depth, Properties inherited, boolean flow, boolean key,
			boolean indentlessSequence) {
		Position start = position();
		if (flow && (marker("---") || marker("..."))) throw failure(SYNTAX);
		Properties properties = properties(inherited, parentIndent, flow);
		// Flow properties may cross lines; document markers cannot become
		// scalar content merely because an anchor/tag preceded them.
		if (flow && (marker("---") || marker("..."))) throw failure(SYNTAX);
		if ((key || flow) && flowValueIndicator()
				|| flow && (peek() == ',' || peek() == ']' || peek() == '}'))
			return empty(depth, properties);
		if (lineEnd() || peek() == '#') {
			if (!flow && !key && !properties.equals(Properties.EMPTY))
				return nested(parentIndent, depth, properties, indentlessSequence);
			return empty(depth, properties);
		}
		return switch (peek()) {
			case '[' -> flowSequence(parentIndent, depth, properties);
			case '{' -> flowMapping(parentIndent, depth, properties);
			case '\'', '"' -> quoted(parentIndent, depth, properties, key);
			case '*' -> {
				if (!properties.equals(Properties.EMPTY)) throw failure(SYNTAX);
				this.budget.node(depth, start);
				take();
				yield new Alias(propertyToken(false), start);
			}
			case '|', '>' -> {
				if (flow || key) throw failure(SYNTAX);
				yield blockScalar(parentIndent, depth, properties);
			}
			case '%', '@', '`' -> throw failure(peek() == '%' ? UNSUPPORTED_SYNTAX : SYNTAX);
			default -> plain(parentIndent, depth, properties, flow, key);
		};
	}

	private Properties properties(Properties inherited, int parentIndent, boolean flow) {
		String tag = inherited.tag(), anchor = inherited.anchor();
		while (peek() == '!' || peek() == '&') {
			if (peek() == '!') {
				if (tag != null) throw failure(SYNTAX);
				Position tagStart = position();
				tag = expandTag(propertyToken(true), tagStart);
			} else {
				if (anchor != null) throw failure(SYNTAX);
				take(); anchor = propertyToken(false);
			}
			// A delimiter may end an empty property-bearing node, but properties
			// still need separation before nonempty collection content.
			if (!lineEnd() && !horizontal(peek())
					&& !(flow && (peek() == ',' || peek() == ']' || peek() == '}'))) throw failure(SYNTAX);
			if (flow) skipFlow(parentIndent);
			else skipHorizontal();
		}
		return tag == null && anchor == null ? Properties.EMPTY : new Properties(tag, anchor);
	}

	private String propertyToken(boolean tag) {
		Position start = position();
		StringBuilder value = builder();
		if (tag && peek() == '!' && at(this.index + 1) == '<') {
			append(value, take(), start); append(value, take(), start);
			while (!end() && !lineEnd() && peek() != '>') {
				if (horizontal(peek())) throw failure(SYNTAX);
				append(value, take(), start);
			}
			if (peek() != '>') throw failure(SYNTAX);
			append(value, take(), start);
			if (value.length() == 3) throw failure(SYNTAX);
		} else {
			while (!end() && !lineEnd() && !horizontal(peek()) && !flowDelimiter(peek()))
				append(value, take(), start);
		}
		if (value.isEmpty()) throw failure(SYNTAX);
		return value.toString();
	}

	private Sequence flowSequence(int parentIndent, int depth, Properties properties) {
		Position start = position(); this.budget.node(depth, start);
		take(); skipFlow(parentIndent);
		List<SkillYamlNode> items = new ArrayList<>();
		while (peek() != ']') {
			if (end() || peek() == ',') throw failure(SYNTAX);
			items.add(flowSequenceEntry(parentIndent, depth + 1));
			skipFlow(parentIndent);
			if (peek() == ']') break;
			if (peek() != ',') throw failure(SYNTAX);
			take(); skipFlow(parentIndent);
		}
		take(); return new Sequence(items, properties, start);
	}

	private SkillYamlNode flowSequenceEntry(int parentIndent, int depth) {
		Position start = position();
		boolean explicitKey = indicator('?');
		if (explicitKey) { take(); skipFlow(parentIndent); }
		boolean emptyKey = flowValueIndicator() || explicitKey && (peek() == ',' || peek() == ']');
		boolean definitePair = explicitKey || emptyKey;
		if (definitePair) this.budget.node(depth, start);
		SkillYamlNode key = emptyKey ? empty(depth + 1, Properties.EMPTY)
				: inline(parentIndent, definitePair ? depth + 1 : depth, Properties.EMPTY, true, false);
		skipFlow(parentIndent);
		if (!definitePair && peek() != ':') return key;
		if (!explicitKey) checkImplicitKey(start);
		if (!definitePair) {
			// The colon establishes a compact mapping only after reading its key.
			// Check the extra nesting BEFORE allocating the enclosing mapping.
			this.budget.node(depth, start);
			checkNestedDepth(key, depth + 1);
		}
		SkillYamlNode value = flowPairValue(parentIndent, depth + 1, ']', key);
		return new Mapping(List.of(new Entry(key, value)), Properties.EMPTY, start);
	}

	private void checkNestedDepth(SkillYamlNode node, int depth) {
		this.budget.work(1, node.position());
		this.budget.depth(depth, node.position());
		if (node instanceof Sequence sequence) {
			for (SkillYamlNode item : sequence.items()) checkNestedDepth(item, depth + 1);
		} else if (node instanceof Mapping mapping) {
			for (Entry entry : mapping.entries()) {
				checkNestedDepth(entry.key(), depth + 1);
				checkNestedDepth(entry.value(), depth + 1);
			}
		}
	}

	private boolean flowValueIndicator() {
		return peek() == ':' && (separation(at(this.index + 1)) || flowDelimiter(at(this.index + 1)));
	}

	private SkillYamlNode flowPairValue(int parentIndent, int depth, char closing, SkillYamlNode key) {
		if (peek() != ':') return empty(depth, Properties.EMPTY);
		boolean jsonLike = key instanceof Sequence || key instanceof Mapping
				|| key instanceof Scalar scalar && (scalar.style() == Style.SINGLE_QUOTED || scalar.style() == Style.DOUBLE_QUOTED);
		// YAML-style keys (notably aliases) need a separate-value indicator;
		// JSON-style quoted/collection keys also permit adjacent plain values.
		if (!jsonLike && !flowValueIndicator()) throw failure(SYNTAX);
		take(); skipFlow(parentIndent);
		return peek() == ',' || peek() == closing ? empty(depth, Properties.EMPTY)
				: inline(parentIndent, depth, Properties.EMPTY, true, false);
	}

	private Mapping flowMapping(int parentIndent, int depth, Properties properties) {
		Position start = position(); this.budget.node(depth, start);
		take(); skipFlow(parentIndent);
		List<Entry> entries = new ArrayList<>();
		while (peek() != '}') {
			if (end() || peek() == ',') throw failure(SYNTAX);
			boolean explicitKey = indicator('?');
			if (explicitKey) { take(); skipFlow(parentIndent); }
			SkillYamlNode key = flowValueIndicator() || explicitKey && (peek() == ',' || peek() == '}')
					? empty(depth + 1, Properties.EMPTY)
					: inline(parentIndent, depth + 1, Properties.EMPTY, true, false);
			// Braced flow mappings use productions 145/148, not the restricted
			// implicit-key productions used by compact sequence pairs/block keys.
			skipFlow(parentIndent);
			SkillYamlNode value = flowPairValue(parentIndent, depth + 1, '}', key);
			entries.add(new Entry(key, value));
			skipFlow(parentIndent);
			if (peek() == '}') break;
			if (peek() != ',') throw failure(SYNTAX);
			take(); skipFlow(parentIndent);
		}
		take(); return new Mapping(entries, properties, start);
	}

	private Scalar quoted(int parentIndent, int depth, Properties properties, boolean key) {
		Position start = position(); this.budget.node(depth, start);
		char quote = take();
		StringBuilder value = builder();
		int trailingAuthoredWhitespace = 0;
		while (!end()) {
			char current = peek();
			if (current == quote) {
				take();
				if (quote == '\'' && peek() == '\'') {
					take(); append(value, '\'', start); trailingAuthoredWhitespace = 0; continue;
				}
				return new Scalar(value.toString(), quote == '\'' ? Style.SINGLE_QUOTED
						: Style.DOUBLE_QUOTED, properties, start);
			}
			if (isBreak(current)) {
				if (key) throw failure(SYNTAX);
				// Escaped whitespace is content, not YAML line separation.
				value.setLength(value.length() - trailingAuthoredWhitespace);
				trailingAuthoredWhitespace = 0;
				int breaks = foldedBreaks(parentIndent);
				appendBreaks(value, breaks == 1 ? 0 : breaks - 1, breaks == 1, start);
				continue;
			}
			if (quote == '"' && current == '\\') {
				take();
				trailingAuthoredWhitespace = 0;
				if (isBreak(peek())) {
					if (key) throw failure(SYNTAX);
					int breaks = foldedBreaks(parentIndent);
					appendBreaks(value, breaks - 1, false, start);
					continue;
				}
				char escaped = take();
				int codePoint = switch (escaped) {
					case '0' -> 0; case 'a' -> 7; case 'b' -> 8; case 't', '\t' -> 9;
					case 'n' -> 10; case 'v' -> 11; case 'f' -> 12; case 'r' -> 13;
					case 'e' -> 27; case ' ', '"', '/', '\\' -> escaped;
					case 'N' -> 0x85; case '_' -> 0xA0; case 'L' -> 0x2028; case 'P' -> 0x2029;
					case 'x' -> hex(2); case 'u' -> hex(4); case 'U' -> hex(8);
					default -> throw failure(SYNTAX);
				};
				if (codePoint >= 0xD800 && codePoint <= 0xDFFF) throw failure(SYNTAX);
				if (codePoint <= 0xFFFF) append(value, (char) codePoint, start);
				else { append(value, Character.highSurrogate(codePoint), start); append(value, Character.lowSurrogate(codePoint), start); }
			} else {
				append(value, take(true), start);
				trailingAuthoredWhitespace = horizontal(current) ? trailingAuthoredWhitespace + 1 : 0;
			}
		}
		throw failure(SYNTAX);
	}

	private int hex(int digits) {
		long value = 0;
		for (int offset = 0; offset < digits; ++offset) {
			char c = take();
			int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f'
					? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
			if (digit < 0) throw failure(SYNTAX);
			value = value * 16 + digit;
		}
		if (value > 0x10FFFF) throw failure(SYNTAX);
		return (int) value;
	}

	private Scalar plain(int parentIndent, int depth, Properties properties, boolean flow, boolean key) {
		Position start = position(); this.budget.node(depth, start);
		if (flowDelimiter(peek()) || indicator('-') || indicator('?') || indicator(':')) throw failure(SYNTAX);
		if (flow && (peek() == '-' || peek() == '?' || peek() == ':')
				&& flowDelimiter(at(this.index + 1))) throw failure(SYNTAX);
		StringBuilder value = builder();
		while (!end()) {
			char current = peek();
			if (flow && flowDelimiter(current)) break;
			if (current == ':' && (separation(at(this.index + 1))
					|| flow && flowDelimiter(at(this.index + 1)))) break;
			if (current == '#' && (value.isEmpty() || horizontal(value.charAt(value.length() - 1)))) break;
			if (isBreak(current)) {
				if (key) break;
				int savedIndex = this.index, savedLine = this.line, savedColumn = this.column;
				int breaks = 0;
				do { takeBreak(); ++breaks; skipContinuationIndent(parentIndent); } while (isBreak(peek()));
				if (end() || this.column - 1 <= parentIndent || peek() == '#'
						|| marker("---") || marker("...") || !flow && (byteOrderMark() || mappingColonAhead())) {
					this.index = savedIndex; this.line = savedLine; this.column = savedColumn;
					break;
				}
				trimHorizontal(value);
				appendBreaks(value, breaks - 1, breaks == 1, start);
				continue;
			}
			append(value, take(), start);
		}
		trimHorizontal(value);
		if (value.isEmpty()) throw failure(SYNTAX);
		return new Scalar(value.toString(), Style.PLAIN, properties, start);
	}

	private Scalar blockScalar(int parentIndent, int depth, Properties properties) {
		Position start = position(); this.budget.node(depth, start);
		boolean folded = take() == '>';
		char chomp = ' ';
		int explicitIndent = 0;
		while (peek() == '+' || peek() == '-' || peek() >= '0' && peek() <= '9') {
			char c = take();
			if (c == '+' || c == '-') {
				if (chomp != ' ') throw failure(SYNTAX);
				chomp = c;
			} else {
				if (c == '0' || explicitIndent != 0) throw failure(SYNTAX);
				explicitIndent = c - '0';
			}
		}
		finishLine();
		// A document scalar has the grammatical parent indentation -1, so its
		// content can begin at column zero (including after an explicit header).
		int contentIndent = explicitIndent == 0 ? -1 : parentIndent + explicitIndent;
		int leadingSpaces = 0, pendingBreaks = 0;
		boolean anyContent = false, previousMoreIndented = false;
		StringBuilder value = builder();
		while (!end()) {
			// Document markers are forbidden content even when indentation is zero.
			if (marker("---") || marker("...") || byteOrderMark()) break;
			int spaces = 0;
			while (at(this.index + spaces) == ' ') { this.budget.work(1, position()); ++spaces; }
			char first = at(this.index + spaces);
			boolean blank = first == 0 || isBreak(first);
			if (first == '\t' && spaces < (contentIndent < 0 ? parentIndent + 1 : contentIndent))
				throw failure(SYNTAX);
			if (!blank && spaces <= parentIndent) break;
			// The first trailing comment must be less indented than the content;
			// subsequent comment lines are handled normally by skipBlankLines().
			if (first == '#' && contentIndent >= 0 && spaces < contentIndent) break;
			if (!blank && contentIndent < 0) {
				contentIndent = spaces;
				if (leadingSpaces > contentIndent) throw failure(SYNTAX);
			}
			if (!blank && spaces < contentIndent) throw failure(SYNTAX);
			if (blank && contentIndent < 0) leadingSpaces = Math.max(leadingSpaces, spaces);
			int remove = contentIndent < 0 ? spaces : Math.min(spaces, contentIndent);
			for (int count = 0; count < remove; ++count) take();
			boolean emptyLine = lineEnd();
			boolean moreIndented = !emptyLine && horizontal(peek());
			if (!emptyLine) {
				if (!anyContent) appendBreaks(value, pendingBreaks, false, start);
				else if (folded && !previousMoreIndented && !moreIndented)
					appendBreaks(value, Math.max(0, pendingBreaks - 1), pendingBreaks == 1, start);
				else appendBreaks(value, pendingBreaks, false, start);
				pendingBreaks = 0;
				while (!lineEnd()) append(value, take(), start);
				anyContent = true; previousMoreIndented = moreIndented;
			}
			if (isBreak(peek())) { takeBreak(); ++pendingBreaks; }
			else break;
		}
		if (chomp == '+') appendBreaks(value, pendingBreaks, false, start);
		else if (chomp != '-' && anyContent && pendingBreaks > 0) append(value, '\n', start);
		return new Scalar(value.toString(), folded ? Style.FOLDED : Style.LITERAL, properties, start);
	}

	private Scalar empty(int depth, Properties properties) {
		Position start = position(); this.budget.node(depth, start);
		return new Scalar("", Style.PLAIN, properties, start);
	}

	/** Finds a block mapping delimiter without treating quoted/flow colons as one. */
	private boolean mappingColonAhead() {
		int nesting = 0;
		char quote = 0;
		int contentStart = this.index;
		// Properties precede the actual key style. Brackets and quotes occurring
		// inside a block plain scalar are text, not flow/quoted key openers.
		while (at(contentStart) == '&' || at(contentStart) == '!') {
			if (at(contentStart) == '!' && at(contentStart + 1) == '<') {
				do { this.budget.work(1, position()); ++contentStart; }
				while (contentStart < this.source.length() && !isBreak(at(contentStart)) && at(contentStart) != '>');
				if (at(contentStart) == '>') ++contentStart;
			} else {
				do { this.budget.work(1, position()); ++contentStart; }
				while (contentStart < this.source.length() && !separation(at(contentStart)) && !flowDelimiter(at(contentStart)));
			}
			while (horizontal(at(contentStart))) { this.budget.work(1, position()); ++contentStart; }
		}
		// A colon belongs to an alias name until whitespace or a flow delimiter.
		// Only a subsequent, separated colon can introduce a block value.
		if (at(contentStart) == '*') {
			do { this.budget.work(1, position()); ++contentStart; }
			while (contentStart < this.source.length() && !separation(at(contentStart)) && !flowDelimiter(at(contentStart)));
		}
		for (int offset = contentStart; offset < this.source.length(); ++offset) {
			this.budget.work(1, position());
			char c = this.source.charAt(offset);
			if (isBreak(c)) return false;
			if (quote != 0) {
				if (quote == '"' && c == '\\') { ++offset; continue; }
				if (c == quote) {
					if (quote == '\'' && at(offset + 1) == '\'') ++offset;
					else quote = 0;
				}
				continue;
			}
			if ((c == '\'' || c == '"') && (offset == contentStart || nesting > 0)) quote = c;
			else if ((c == '[' || c == '{') && (offset == contentStart || nesting > 0)) ++nesting;
			else if ((c == ']' || c == '}') && nesting > 0) --nesting;
			else if (c == '#' && (offset == this.index || horizontal(at(offset - 1)))) return false;
			else if (c == ':' && nesting == 0 && separation(at(offset + 1))) return true;
		}
		return false;
	}

	private void skipBlankLines() {
		while (!end()) {
			boolean tab = false;
			while (horizontal(peek())) { tab |= peek() == '\t'; take(); }
			if (peek() == '#') comment();
			if (isBreak(peek())) { takeBreak(); continue; }
			// A tab can separate a flow/scalar node after its indentation, but
			// cannot supply any part of a block collection's indentation.
			if (tab && !end() && (indicator('-') || indicator('?') || mappingColonAhead())) throw failure(SYNTAX);
			this.nextContent = true;
			return;
		}
	}

	/** Returns the space-only indentation before a separation tab, or -1 when absent. */
	private int spacesBeforeSeparationTab() {
		int beginning = this.index;
		while (beginning > 0 && horizontal(at(beginning - 1))) {
			this.budget.work(1, position());
			--beginning;
		}
		if (beginning > 0 && !isBreak(at(beginning - 1))) return -1;
		for (int offset = beginning; offset < this.index; ++offset) {
			this.budget.work(1, position());
			if (at(offset) == '\t') return offset - beginning;
		}
		return -1;
	}

	private void skipHorizontal() { while (horizontal(peek())) take(); }
	private void skipContinuationIndent(int parentIndent) {
		int spaces = 0;
		while (peek() == ' ') { take(); ++spaces; }
		boolean tab = peek() == '\t';
		skipHorizontal();
		if (tab && spaces <= parentIndent && !lineEnd() && peek() != '#') throw failure(SYNTAX);
	}
	private void checkImplicitKey(Position start) {
		if (this.line != start.line() || this.column - start.column() > 1024) throw failure(SYNTAX);
	}
	private void skipFlow(int parentIndent) {
		boolean crossedLine = false;
		while (!end()) {
			if (horizontal(peek())) take();
			else if (isBreak(peek())) { takeBreak(); skipContinuationIndent(parentIndent); crossedLine = true; }
			else if (peek() == '#') comment();
			else break;
		}
		if (crossedLine && !end() && this.column - 1 <= parentIndent) throw failure(SYNTAX);
	}
	private int foldedBreaks(int parentIndent) {
		int count = 0;
		do { takeBreak(); ++count; skipContinuationIndent(parentIndent); } while (isBreak(peek()));
		if (!end() && (this.column - 1 <= parentIndent || marker("---") || marker("...")))
			throw failure(SYNTAX);
		return count;
	}
	private void finishLine() {
		// Nested/block-scalar readers already leave the cursor at a later line.
		if (this.column == 1 || this.nextContent) return;
		skipHorizontal();
		if (peek() == '#') comment();
		if (!lineEnd()) throw failure(SYNTAX);
		if (!end()) takeBreak();
	}
	private void comment() {
		if (this.column > 1 && !horizontal(at(this.index - 1))) throw failure(SYNTAX);
		while (!lineEnd()) take();
	}
	private boolean marker(String marker) {
		return this.column == 1 && this.source.startsWith(marker, this.index)
				&& separation(at(this.index + marker.length()));
	}
	private boolean byteOrderMark() { return this.column == 1 && peek() == '\uFEFF'; }
	private boolean indicator(char value) { return peek() == value && separation(at(this.index + 1)); }
	private static boolean horizontal(char c) { return c == ' ' || c == '\t'; }
	private static boolean isBreak(char c) { return c == '\n' || c == '\r'; }
	private static boolean separation(char c) { return c == 0 || horizontal(c) || isBreak(c); }
	private static boolean flowDelimiter(char c) { return c == ',' || c == '[' || c == ']' || c == '{' || c == '}'; }
	private boolean lineEnd() { return end() || isBreak(peek()); }
	private boolean end() { return this.index == this.source.length(); }
	private char peek() { return at(this.index); }
	private char at(int offset) { return offset >= this.source.length() ? 0 : this.source.charAt(offset); }
	private Position position() { return new Position(this.line, this.column); }
	private SkillYamlException failure(SkillYamlException.Reason reason) { return new SkillYamlException(reason, this.line, this.column); }
	private char take() { return take(false); }
	private char take(boolean allowByteOrderMark) {
		if (end()) throw failure(SYNTAX);
		// Outside stream prefixes only quoted scalar content admits a BOM.
		if (peek() == '\uFEFF' && !allowByteOrderMark) throw failure(SYNTAX);
		this.budget.work(1, position());
		this.nextContent = false;
		char c = this.source.charAt(this.index++);
		if (!Character.isLowSurrogate(c)) ++this.column;
		return c;
	}
	private void takeBreak() {
		if (!isBreak(peek())) throw failure(SYNTAX);
		char c = take();
		if (c == '\r' && peek() == '\n') take();
		++this.line; this.column = 1;
	}
	private StringBuilder builder() { return new StringBuilder(Math.min(64, this.budget.limits().maximumScalarCharacters())); }
	private void append(StringBuilder value, char c, Position start) {
		this.budget.scalarLength(value.length() + 1, start);
		this.budget.text(1, start); value.append(c);
	}
	private void appendBreaks(StringBuilder value, int breaks, boolean space, Position start) {
		if (space) append(value, ' ', start);
		for (int count = 0; count < breaks; ++count) append(value, '\n', start);
	}
	private static void trimHorizontal(StringBuilder value) {
		while (!value.isEmpty() && horizontal(value.charAt(value.length() - 1))) value.setLength(value.length() - 1);
	}
}

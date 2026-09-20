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

import java.util.ArrayList;
import java.util.List;
import static com.soklet.internal.mcp.skills.SkillYamlException.Reason.*;
import static com.soklet.internal.mcp.skills.SkillYamlNode.*;
import static java.util.Objects.requireNonNull;

/**
 * Private syntax implementation, not yet a qualified general YAML parser.
 * Node keys, tags, anchors, duplicate entries and numeric spellings survive
 * parsing; the separate resolver owns their approved JSON meaning.
 * Every scan (including lookahead) is metered. No alias is expanded here.
 */
final class SkillYamlParser {
	private final String source;
	private final SkillYamlBudget budget;
	private int index;
	private int line;
	private int column = 1;
	private boolean nextContent;

	private SkillYamlParser(String source, SkillYamlBudget budget, int firstLine) {
		this.source = requireNonNull(source);
		this.budget = requireNonNull(budget);
		if (firstLine < 1) throw new IllegalArgumentException("Invalid YAML first line.");
		this.line = firstLine;
	}

	static SkillYamlNode parse(String source, SkillYamlBudget budget, int firstLine) {
		SkillYamlParser parser = new SkillYamlParser(source, budget, firstLine);
		parser.validateInput();
		if (parser.peek() == '\uFEFF') { parser.take(); parser.column = 1; }
		parser.skipBlankLines();
		if (parser.peek() == '%') throw parser.failure(UNSUPPORTED_SYNTAX);
		if (parser.marker("---")) {
			parser.take(); parser.take(); parser.take();
			parser.finishLine();
			parser.skipBlankLines();
		}
		SkillYamlNode root = parser.end() ? parser.empty(1, Properties.EMPTY)
				: parser.block(parser.column - 1, 1, Properties.EMPTY);
		parser.skipBlankLines();
		if (parser.marker("...")) {
			parser.take(); parser.take(); parser.take();
			parser.finishLine(); parser.skipBlankLines();
		}
		if (!parser.end()) throw parser.failure(parser.marker("---") || parser.peek() == '%'
				? UNSUPPORTED_SYNTAX : SYNTAX);
		return root;
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
		SkillYamlNode value = inline(indent - 1, depth, properties, false, false);
		finishLine(); skipBlankLines();
		return value;
	}

	private Sequence blockSequence(int indent, int depth, Properties properties) {
		Position start = position();
		this.budget.node(depth, start);
		List<SkillYamlNode> items = new ArrayList<>();
		while (!end() && this.column - 1 == indent && indicator('-')) {
			take();
			skipHorizontal();
			SkillYamlNode item;
			if (lineEnd() || peek() == '#') item = nested(indent, depth + 1, Properties.EMPTY, false);
			else if (indicator('-') || indicator('?') || mappingColonAhead())
				item = block(this.column - 1, depth + 1, Properties.EMPTY);
			else {
				item = inline(indent, depth + 1, Properties.EMPTY, false, false);
				finishLine(); skipBlankLines();
			}
			items.add(item);
		}
		return new Sequence(items, properties, start);
	}

	private Mapping blockMapping(int indent, int depth, Properties properties) {
		Position start = position();
		this.budget.node(depth, start);
		List<Entry> entries = new ArrayList<>();
		while (!end() && this.column - 1 == indent && !marker("---") && !marker("...")) {
			if (!indicator('?') && !mappingColonAhead()) break;
			SkillYamlNode key;
			if (indicator('?')) {
				take(); skipHorizontal();
				if (lineEnd() || peek() == '#') key = nested(indent, depth + 1, Properties.EMPTY, false);
				else {
					key = inline(indent, depth + 1, Properties.EMPTY, false, true);
					skipHorizontal();
					if (!indicator(':')) { finishLine(); skipBlankLines(); }
				}
				if (this.column - 1 != indent || !indicator(':')) {
					entries.add(new Entry(key, empty(depth + 1, Properties.EMPTY)));
					continue;
				}
			} else {
				Position keyStart = position();
				key = peek() == ':' ? empty(depth + 1, Properties.EMPTY)
						: inline(indent, depth + 1, Properties.EMPTY, false, true);
				skipHorizontal();
				checkImplicitKey(keyStart);
			}
			skipHorizontal();
			if (!indicator(':')) throw failure(SYNTAX);
			take(); skipHorizontal();
			SkillYamlNode value;
			if (lineEnd() || peek() == '#') value = nested(indent, depth + 1, Properties.EMPTY, true);
			else {
				value = inline(indent, depth + 1, Properties.EMPTY, false, false);
				finishLine(); skipBlankLines();
			}
			entries.add(new Entry(key, value));
		}
		return new Mapping(entries, properties, start);
	}

	private SkillYamlNode nested(int parentIndent, int depth, Properties properties, boolean indentlessSequence) {
		Position start = position();
		finishLine(); skipBlankLines();
		if (!end() && !marker("---") && !marker("...")
				&& (this.column - 1 > parentIndent
				|| indentlessSequence && this.column - 1 == parentIndent && indicator('-')))
			return block(this.column - 1, depth, properties);
		this.budget.node(depth, start);
		return new Scalar("", Style.PLAIN, properties, start);
	}

	private SkillYamlNode inline(int parentIndent, int depth, Properties inherited, boolean flow, boolean key) {
		Position start = position();
		Properties properties = properties(inherited);
		if (lineEnd() || peek() == '#') {
			if (!flow && !key && !properties.equals(Properties.EMPTY))
				return nested(parentIndent, depth, properties, true);
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

	private Properties properties(Properties inherited) {
		String tag = inherited.tag(), anchor = inherited.anchor();
		while (peek() == '!' || peek() == '&') {
			if (peek() == '!') {
				if (tag != null) throw failure(SYNTAX);
				tag = propertyToken(true);
			} else {
				if (anchor != null) throw failure(SYNTAX);
				take(); anchor = propertyToken(false);
			}
			if (!lineEnd() && !horizontal(peek())) throw failure(SYNTAX);
			skipHorizontal();
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
			items.add(inline(parentIndent, depth + 1, Properties.EMPTY, true, false));
			skipFlow(parentIndent);
			if (peek() == ':') throw failure(UNSUPPORTED_SYNTAX);
			if (peek() == ']') break;
			if (peek() != ',') throw failure(SYNTAX);
			take(); skipFlow(parentIndent);
		}
		take(); return new Sequence(items, properties, start);
	}

	private Mapping flowMapping(int parentIndent, int depth, Properties properties) {
		Position start = position(); this.budget.node(depth, start);
		take(); skipFlow(parentIndent);
		List<Entry> entries = new ArrayList<>();
		while (peek() != '}') {
			if (end() || peek() == ',') throw failure(SYNTAX);
			boolean explicitKey = indicator('?');
			if (explicitKey) { take(); skipFlow(parentIndent); }
			Position keyStart = position();
			SkillYamlNode key = peek() == ':' ? empty(depth + 1, Properties.EMPTY)
					: inline(parentIndent, depth + 1, Properties.EMPTY, true, !explicitKey);
			if (!explicitKey) checkImplicitKey(keyStart);
			skipFlow(parentIndent);
			SkillYamlNode value;
			if (peek() == ':') {
				if (!explicitKey) checkImplicitKey(keyStart);
				take(); skipFlow(parentIndent);
				value = peek() == ',' || peek() == '}' ? empty(depth + 1, Properties.EMPTY)
						: inline(parentIndent, depth + 1, Properties.EMPTY, true, false);
			} else value = empty(depth + 1, Properties.EMPTY);
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
				append(value, take(), start);
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
						|| marker("---") || marker("...") || !flow && mappingColonAhead()) {
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
		int contentIndent = explicitIndent == 0 ? -1 : Math.max(0, parentIndent) + explicitIndent;
		int leadingSpaces = 0, pendingBreaks = 0;
		boolean anyContent = false, previousMoreIndented = false;
		StringBuilder value = builder();
		while (!end()) {
			int spaces = 0;
			while (at(this.index + spaces) == ' ') { this.budget.work(1, position()); ++spaces; }
			char first = at(this.index + spaces);
			boolean blank = first == 0 || isBreak(first);
			if (!blank && spaces <= Math.max(0, parentIndent)) break;
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
		for (int offset = this.index; offset < this.source.length(); ++offset) {
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
			if ((c == '\'' || c == '"') && (offset == this.index || nesting > 0)) quote = c;
			else if (c == '[' || c == '{') ++nesting;
			else if (c == ']' || c == '}') --nesting;
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
			if (tab && !end()) throw failure(SYNTAX);
			this.nextContent = true;
			return;
		}
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
	private char take() {
		if (end()) throw failure(SYNTAX);
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

/* Copyright 2026 Revetware LLC. Licensed under the Apache License, Version 2.0. */
package com.soklet.internal.mcp.skills;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Bounded syntax-event comparison for the pinned corpus, independent of metadata resolution. */
final class SkillYamlEvents {
	static final int MAX_INPUT_CHARACTERS = 4 * 1024 * 1024;
	static final int MAX_EVENTS = 1_000_000;
	static final int MAX_DEPTH = 128;
	static final int MAX_OUTPUT_CHARACTERS = 32 * 1024 * 1024;

	private SkillYamlEvents() {}

	/** Preserve node order, duplicate entries, complex keys, aliases, explicit tags, and scalar styles. */
	static List<String> fromNodes(List<SkillYamlNode> documents) {
		Accumulator events = new Accumulator();
		events.add("+STR");
		for (SkillYamlNode document : documents) {
			events.add("+DOC");
			node(document, events, 1);
			events.add("-DOC");
		}
		events.add("-STR");
		return events.result();
	}

	/**
	 * Read complete test.event streams, not invalid-input fixtures' deliberately truncated prefixes.
	 * Only collection flow/block and explicit document marker presentation flags are discarded.
	 */
	static List<String> fromCorpus(String source) {
		if (source.length() > MAX_INPUT_CHARACTERS) throw invalid("Input character bound exceeded");
		Accumulator events = new Accumulator();
		Deque<Frame> frames = new ArrayDeque<>();
		boolean started = false, ended = false;
		int offset = 0;
		while (offset < source.length()) {
			int end = source.indexOf('\n', offset);
			if (end < 0) end = source.length();
			String line = source.substring(offset, end);
			offset = end == source.length() ? end : end + 1;
			if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
			if (line.isEmpty() || ended) throw invalid("Unexpected event after stream end or empty event");
			for (int index = 0; index < line.length(); ++index)
				if (line.charAt(index) < 0x20 || line.charAt(index) == 0x7f)
					throw invalid("Unescaped event control character");
			if (!started) {
				if (!line.equals("+STR")) throw invalid("Missing stream start");
				started = true;
				events.add("+STR");
				continue;
			}
			if (line.equals("-STR")) {
				if (!frames.isEmpty()) throw invalid("Stream ended inside a document");
				ended = true;
				events.add("-STR");
			} else if (line.equals("+DOC") || line.equals("+DOC ---")) {
				if (!frames.isEmpty()) throw invalid("Nested document event");
				frames.push(new Frame("DOC"));
				events.add("+DOC");
			} else if (line.equals("-DOC") || line.equals("-DOC ...")) {
				Frame frame = close(frames, "DOC");
				if (frame.children != 1) throw invalid("Document must contain exactly one root");
				events.add("-DOC");
			} else if (line.equals("-MAP") || line.equals("-SEQ")) {
				Frame frame = close(frames, line.substring(1));
				if (frame.kind.equals("MAP") && frame.children % 2 != 0)
					throw invalid("Mapping has an unmatched key");
				events.add(line);
			} else if (line.startsWith("+MAP") || line.startsWith("+SEQ")) {
				child(frames);
				boolean mapping = line.startsWith("+MAP");
				Fields fields = new Fields(line, 4);
				fields.collectionStyle(mapping ? "{}" : "[]");
				fields.properties(false);
				fields.end();
				events.add(collection(mapping, fields.anchor, fields.tag));
				frames.push(new Frame(mapping ? "MAP" : "SEQ"));
			} else if (line.startsWith("=VAL")) {
				child(frames);
				Fields fields = new Fields(line, 4);
				fields.properties(true);
				if (fields.offset >= line.length()) throw invalid("Scalar event has no style");
				SkillYamlNode.Style style = switch (line.charAt(fields.offset++)) {
					case ':' -> SkillYamlNode.Style.PLAIN;
					case '\'' -> SkillYamlNode.Style.SINGLE_QUOTED;
					case '"' -> SkillYamlNode.Style.DOUBLE_QUOTED;
					case '|' -> SkillYamlNode.Style.LITERAL;
					case '>' -> SkillYamlNode.Style.FOLDED;
					default -> throw invalid("Unknown scalar style");
				};
				events.add(scalar(fields.anchor, fields.tag, style, decode(line.substring(fields.offset))));
			} else if (line.startsWith("=ALI *")) {
				child(frames);
				String name = line.substring(6);
				if (name.isEmpty() || name.indexOf(' ') >= 0) throw invalid("Invalid alias event");
				events.add(alias(name));
			} else throw invalid("Unknown event");
		}
		if (!started || !ended || !frames.isEmpty()) throw invalid("Incomplete event stream");
		return events.result();
	}

	/** Tag arguments to these shared encoders are identities, not YAML shorthand. */
	static String collection(boolean mapping, String anchor, String tag) {
		Line line = new Line(mapping ? "+MAP anchor=" : "+SEQ anchor=");
		line.quoted(anchor); line.append(" tag="); line.quoted(tag);
		return line.toString();
	}

	static String scalar(String anchor, String tag, SkillYamlNode.Style style, String value) {
		if (style == null || value == null) throw invalid("Missing scalar field");
		Line line = new Line("=VAL style=" + style.name() + " anchor=");
		line.quoted(anchor); line.append(" tag="); line.quoted(tag);
		line.append(" value="); line.quoted(value);
		return line.toString();
	}

	static String alias(String name) {
		if (name == null || name.isEmpty()) throw invalid("Missing alias name");
		Line line = new Line("=ALI name="); line.quoted(name);
		return line.toString();
	}

	private static void node(SkillYamlNode node, Accumulator events, int depth) {
		if (depth > MAX_DEPTH) throw invalid("Node depth bound exceeded");
		String anchor = node.properties().anchor(), tag = identity(node.properties().tag());
		if (node instanceof SkillYamlNode.Scalar value)
			events.add(scalar(anchor, tag, value.style(), value.value()));
		else if (node instanceof SkillYamlNode.Alias value) events.add(alias(value.name()));
		else if (node instanceof SkillYamlNode.Sequence value) {
			events.add(collection(false, anchor, tag));
			for (SkillYamlNode item : value.items()) node(item, events, depth + 1);
			events.add("-SEQ");
		} else if (node instanceof SkillYamlNode.Mapping value) {
			events.add(collection(true, anchor, tag));
			for (SkillYamlNode.Entry entry : value.entries()) {
				node(entry.key(), events, depth + 1);
				node(entry.value(), events, depth + 1);
			}
			events.add("-MAP");
		} else throw invalid("Unknown syntax node");
	}

	private static String identity(String tag) {
		if (tag == null) return null;
		if (tag.length() > MAX_INPUT_CHARACTERS) throw invalid("Tag character bound exceeded");
		if (tag.startsWith("!<") && tag.endsWith(">")) return tag.substring(2, tag.length() - 1);
		if (tag.startsWith("!!")) return "tag:yaml.org,2002:" + tag.substring(2);
		// In particular, do not decode percent escapes: that known corpus/spec conflict stays visible.
		return tag;
	}

	private static Frame close(Deque<Frame> frames, String kind) {
		if (frames.isEmpty() || !frames.peek().kind.equals(kind)) throw invalid("Mismatched event closure");
		return frames.pop();
	}

	private static void child(Deque<Frame> frames) {
		if (frames.isEmpty()) throw invalid("Node outside a document");
		if (frames.size() > MAX_DEPTH) throw invalid("Event depth bound exceeded");
		Frame parent = frames.peek();
		if (parent.kind.equals("DOC") && parent.children != 0) throw invalid("Multiple document roots");
		++parent.children;
	}

	/** The pinned event files use these five escape forms; unsupported forms fail visibly. */
	private static String decode(String value) {
		StringBuilder result = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);
			if (character == '\\') {
				if (++index == value.length()) throw invalid("Incomplete event escape");
				character = switch (value.charAt(index)) {
					case '\\' -> '\\'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case 'b' -> '\b';
					default -> throw invalid("Unsupported event escape");
				};
			}
			result.append(character);
		}
		return result.toString();
	}

	private static IllegalArgumentException invalid(String reason) {
		// The diagnostic identifies the adapter failure without exposing arbitrary fixture content.
		return new IllegalArgumentException("Invalid YAML verification events: " + reason + ".");
	}

	private static final class Frame {
		final String kind;
		int children;
		Frame(String kind) { this.kind = kind; }
	}

	private static final class Fields {
		final String line;
		int offset;
		String anchor, tag;
		Fields(String line, int offset) { this.line = line; this.offset = offset; }
		void collectionStyle(String style) {
			if (line.startsWith(" " + style, offset)) offset += 3;
		}
		void properties(boolean scalar) {
			while (offset < line.length()) {
				if (line.charAt(offset++) != ' ' || offset == line.length())
					throw invalid("Invalid event field separator");
				char marker = line.charAt(offset);
				if (marker == '&') {
					if (anchor != null) throw invalid("Duplicate anchor field");
					int end = line.indexOf(' ', ++offset);
					if (end < 0) end = line.length();
					anchor = line.substring(offset, end);
					if (anchor.isEmpty()) throw invalid("Empty anchor field");
					offset = end;
				} else if (marker == '<') {
					if (tag != null) throw invalid("Duplicate tag field");
					int end = line.indexOf('>', ++offset);
					if (end < 0 || end == offset) throw invalid("Invalid tag field");
					tag = line.substring(offset, end);
					if (tag.indexOf(' ') >= 0) throw invalid("Whitespace in tag field");
					offset = end + 1;
				} else if (scalar) return;
				else throw invalid("Unknown collection field");
			}
		}
		void end() { if (offset != line.length()) throw invalid("Unexpected trailing event fields"); }
	}

	private static final class Accumulator {
		final List<String> events = new ArrayList<>();
		long characters;
		void add(String event) {
			if (events.size() >= MAX_EVENTS) throw invalid("Event count bound exceeded");
			if (event.length() > MAX_OUTPUT_CHARACTERS - characters) throw invalid("Output character bound exceeded");
			characters += event.length();
			events.add(event);
		}
		List<String> result() { return List.copyOf(events); }
	}

	private static final class Line {
		final StringBuilder value = new StringBuilder();
		Line(String prefix) { append(prefix); }
		void append(String text) {
			if (text.length() > MAX_OUTPUT_CHARACTERS - value.length()) throw invalid("Event character bound exceeded");
			value.append(text);
		}
		void quoted(String text) {
			if (text == null) { append("null"); return; }
			if (text.length() > MAX_INPUT_CHARACTERS) throw invalid("Field character bound exceeded");
			append("\"");
			for (int index = 0; index < text.length(); ++index) {
				char character = text.charAt(index);
				if (character == '\\' || character == '"') append("\\" + character);
				else if (character < 0x20 || character == 0x7f || Character.isSurrogate(character)) {
					String hex = Integer.toHexString(character);
					append("\\u" + "0".repeat(4 - hex.length()) + hex);
				} else append(String.valueOf(character));
			}
			append("\"");
		}
		@Override public String toString() { return value.toString(); }
	}

	/** Fail before measuring the corpus if the normalization adapter loses significant syntax. */
	static void verify() {
		SkillYamlNode.Position position = new SkillYamlNode.Position(1, 1);
		SkillYamlNode.Properties empty = SkillYamlNode.Properties.EMPTY;
		SkillYamlNode.Scalar key = new SkillYamlNode.Scalar("key", SkillYamlNode.Style.PLAIN, empty, position);
		SkillYamlNode.Scalar one = new SkillYamlNode.Scalar("1", SkillYamlNode.Style.PLAIN, empty, position);
		SkillYamlNode.Scalar two = new SkillYamlNode.Scalar("2", SkillYamlNode.Style.DOUBLE_QUOTED, empty, position);
		SkillYamlNode.Sequence complex = new SkillYamlNode.Sequence(List.of(key), empty, position);
		SkillYamlNode.Mapping mapping = new SkillYamlNode.Mapping(List.of(new SkillYamlNode.Entry(key, one),
				new SkillYamlNode.Entry(key, two), new SkillYamlNode.Entry(complex, new SkillYamlNode.Alias("a", position))),
				new SkillYamlNode.Properties("!!map", "a"), position);
		String fixture = "+STR\n+DOC ---\n+MAP {} &a <tag:yaml.org,2002:map>\n=VAL :key\n=VAL :1\n"
				+ "=VAL :key\n=VAL \"2\n+SEQ []\n=VAL :key\n-SEQ\n=ALI *a\n-MAP\n-DOC ...\n-STR\n";
		List<String> normalized = fromCorpus(fixture);
		check(normalized.equals(fromNodes(List.of(mapping))), "syntax projection");
		check(normalized.equals(fromCorpus(fixture.replace(" ---", "").replace(" ...", "")
				.replace(" {}", "").replace(" []", ""))), "presentation normalization");
		for (String changed : List.of(fixture.replace("=VAL :1", "=VAL :2"),
				fixture.replace("=VAL \"2", "=VAL :2"), fixture.replace("*a", "*b"),
				fixture.replace("&a", "&b"), fixture.replace("2002:map", "2002:set"),
				fixture.replace("=VAL :key\n=VAL :1\n", ""),
				fixture.replace("=VAL :1\n=VAL :key\n=VAL \"2", "=VAL \"2\n=VAL :key\n=VAL :1")))
			check(!normalized.equals(fromCorpus(changed)), "significant syntax distinction");
		String escaped = "+STR\n+DOC\n=VAL &😁 <!local> |a\\\\b\\n\\r\\t\\b\n-DOC\n"
				+ "+DOC\n=VAL <!> >\n-DOC\n+DOC\n=VAL '\n-DOC\n-STR\n";
		check(fromCorpus(escaped).equals(fromNodes(List.of(
				new SkillYamlNode.Scalar("a\\b\n\r\t\b", SkillYamlNode.Style.LITERAL,
						new SkillYamlNode.Properties("!local", "😁"), position),
				new SkillYamlNode.Scalar("", SkillYamlNode.Style.FOLDED, new SkillYamlNode.Properties("!", null), position),
				new SkillYamlNode.Scalar("", SkillYamlNode.Style.SINGLE_QUOTED, empty, position)))), "escaping and stream styles");
		check(fromCorpus("+STR\n-STR\n").equals(fromNodes(List.of())), "empty stream");
		check(identity("!<!!str>").equals("!!str"), "verbatim local identity");
		check(identity("!<tag:example:tag%21>").endsWith("%21"), "percent identity preservation");
		check(!scalar(null, "tag:example:tag%21", SkillYamlNode.Style.PLAIN, "x")
				.equals(scalar(null, "tag:example:tag!", SkillYamlNode.Style.PLAIN, "x")), "percent identity distinction");
		for (String malformed : List.of("", "+STR\n", "+STR\n-DOC\n-STR\n", "+STR\n+DOC\n-DOC\n-STR\n",
				"+STR\n+DOC\n=VAL :a\n=VAL :b\n-DOC\n-STR\n", "+STR\n+DOC\n+MAP\n=VAL :a\n-MAP\n-DOC\n-STR\n",
				"+STR\n+DOC\n+MAP\n-SEQ\n-DOC\n-STR\n", "+STR\n=ALI *a\n-STR\n", "+STR\n-STR\n+DOC\n",
				"+STR\n+DOC\n=VAL &a &b :a\n-DOC\n-STR\n", "+STR\n+DOC\n=VAL <x> <y> :a\n-DOC\n-STR\n",
				"+STR\n+DOC\n=VAL :\\q\n-DOC\n-STR\n", "+STR\n+DOC\n=VAL :\\\n-DOC\n-STR\n",
				"+STR\n+DOC\n+MAP []\n-MAP\n-DOC\n-STR\n", "+STR\n+DOC\n=VAL :a\t\n-DOC\n-STR\n",
				"+STR\n+DOC\n=ALI *\n-DOC\n-STR\n", "+STR\n+DOC\n=VAL\n-DOC\n-STR\n",
				"+STR\n+DOC\n=VAL :a\n-DOC garbage\n-STR\n", "+STR\n\n-STR\n"))
			reject(() -> fromCorpus(malformed));
		reject(() -> fromCorpus(" ".repeat(MAX_INPUT_CHARACTERS + 1)));
		reject(() -> fromCorpus("+STR\n+DOC\n" + "+SEQ\n".repeat(MAX_DEPTH) + "=VAL :a\n"
				+ "-SEQ\n".repeat(MAX_DEPTH) + "-DOC\n-STR\n"));
		SkillYamlNode deep = key;
		for (int index = 0; index < MAX_DEPTH; ++index) deep = new SkillYamlNode.Sequence(List.of(deep), empty, position);
		SkillYamlNode excessive = deep;
		reject(() -> fromNodes(List.of(excessive)));
		Accumulator outputBound = new Accumulator();
		outputBound.characters = MAX_OUTPUT_CHARACTERS;
		reject(() -> outputBound.add("x"));
		try { normalized.add("unexpected"); throw new IllegalStateException("Mutable verification events."); }
		catch (UnsupportedOperationException expected) { /* Immutable projection. */ }
	}

	private static void check(boolean condition, String name) {
		if (!condition) throw new IllegalStateException("YAML event adapter self-test failed: " + name + ".");
	}

	private static void reject(Runnable operation) {
		try { operation.run(); }
		catch (IllegalArgumentException expected) { return; }
		throw new IllegalStateException("YAML event adapter accepted a malformed or over-limit self-test.");
	}
}
